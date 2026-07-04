package com.veyq.app.features.tasks

import com.veyq.app.features.admin.tenant.MembershipEntity
import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.features.account.login.SessionService
import com.veyq.app.features.spaces.scope.ScopeEntity
import com.veyq.app.shared.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for the operator-blind background-task queue (ADR 0021, cr-0076): the atomic claim/lease
 * (first-wins), lazy reclaim of an expired lease, dead-letter on exhausted attempts, and the first consumer —
 * grant-delivery originated on a **seeded grant-less membership**, claimed, and completed idempotently by
 * posting a wrap (the server writes the opaque ciphertext onto the target membership, never a key). Seeds use
 * UUID-unique ids so the JVM-lived H2 mem DB (shared across test classes) can't bleed state.
 */
@QuarkusTest
class TasksSliceTest {

    @Inject
    lateinit var sessions: SessionService

    private fun bearer(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    // --- grant-delivery: the first, trivially-blind consumer -------------------------------------------------

    @Test
    fun grantDeliveryOriginatedOnGrantlessMembershipThenClaimedAndCompletedIdempotently() {
        val scopeId = seedTenantScope()
        val worker = seedUser()
        seedGrantedMembership(worker, scopeId, "owner-grant")
        val target = seedUser(pubkey = "dGFyZ2V0LXB1YmtleQ==") // "target-pubkey"
        val targetMembership = seedGrantlessMembership(target, scopeId)

        // The worker pulls its scopes with the grant-delivery tag → the server originates + leases the job,
        // resolving the delivery target (userId + identity pubkey — operational metadata, never a key).
        val jobId = given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"capabilityTags":["${CapabilityTags.GRANT_DELIVERY}"]}""")
            .`when`().post("/api/tasks/claim")
            .then().statusCode(200)
            .body("capabilityTag", equalTo(CapabilityTags.GRANT_DELIVERY))
            .body("scopeId", equalTo(scopeId))
            .body("resourceRef", equalTo(targetMembership))
            .body("grantDelivery.targetUserId", equalTo(target))
            .body("grantDelivery.targetMembershipId", equalTo(targetMembership))
            .body("grantDelivery.identityPublicKey", equalTo("dGFyZ2V0LXB1YmtleQ=="))
            .body("jobId", notNullValue())
            .extract().path<String>("jobId")

        // The worker wraps K_SC to the target and completes: the backend writes the opaque grant onto the
        // target membership (the first wrap to a foreign identity) and marks the job done.
        val wrap = "d3JhcC10by1mb3JlaWdu" // "wrap-to-foreign"
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"sealedResult":"$wrap"}""")
            .`when`().post("/api/tasks/$jobId/complete")
            .then().statusCode(204)

        assertEquals(wrap, grantOf(targetMembership), "the grant was delivered onto the target membership")

        // Idempotent + first-online-wins: nothing more is claimable (job DONE, member now granted → no
        // re-origination), and re-completing the same job is a success no-op that does not overwrite the grant.
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"capabilityTags":["${CapabilityTags.GRANT_DELIVERY}"]}""")
            .`when`().post("/api/tasks/claim").then().statusCode(204)

        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"sealedResult":"c29tZXRoaW5nLWVsc2U="}""")
            .`when`().post("/api/tasks/$jobId/complete").then().statusCode(204)
        assertEquals(wrap, grantOf(targetMembership), "a second completion must not overwrite the delivered grant")
    }

    @Test
    fun grantDeliveryCompletionWithoutAWrapIs400() {
        val scopeId = seedTenantScope()
        val worker = seedUser()
        seedGrantedMembership(worker, scopeId, "owner-grant")
        seedGrantlessMembership(seedUser(), scopeId)

        val jobId = claimGrantDelivery(worker)
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/tasks/$jobId/complete")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
    }

    // --- the generic mechanism: claim / reclaim / dead-letter / heartbeat / fail / enqueue -------------------

    @Test
    fun claimIsAtomicFirstWins() {
        val scopeId = seedTenantScope()
        val worker = seedUser()
        seedGrantedMembership(worker, scopeId, "owner-grant")
        val tag = "deadbeef"
        seedJob(scopeId, tag, JobEntity.PENDING, attempts = 0)

        claim(worker, tag).then().statusCode(200)     // first claim leases it
        claim(worker, tag).then().statusCode(204)      // now leased → nothing claimable
    }

    @Test
    fun expiredLeaseIsLazilyReclaimed() {
        val scopeId = seedTenantScope()
        val worker = seedUser()
        seedGrantedMembership(worker, scopeId, "owner-grant")
        val tag = "deadbeef"
        // A worker crashed holding this job; its lease is already in the past → another worker reclaims it.
        val jobId = seedJob(scopeId, tag, JobEntity.LEASED, attempts = 1, leaseExpiresAt = Instant.now().minusSeconds(60))

        claim(worker, tag).then().statusCode(200).body("jobId", equalTo(jobId)).body("attempts", equalTo(2))
    }

    @Test
    fun exhaustedExpiredJobIsDeadLetteredNotClaimed() {
        val scopeId = seedTenantScope()
        val worker = seedUser()
        seedGrantedMembership(worker, scopeId, "owner-grant")
        val tag = "deadbeef"
        val jobId = seedJob(
            scopeId, tag, JobEntity.LEASED, attempts = 5, maxAttempts = 5,
            leaseExpiresAt = Instant.now().minusSeconds(60),
        )

        claim(worker, tag).then().statusCode(204) // exhausted → swept, not handed out
        assertEquals(JobEntity.FAILED, statusOf(jobId), "an exhausted expired job is dead-lettered")
    }

    @Test
    fun failBelowMaxRequeuesThenAtMaxDeadLetters() {
        val scopeId = seedTenantScope()
        val worker = seedUser()
        seedGrantedMembership(worker, scopeId, "owner-grant")
        val tag = "deadbeef"
        val jobId = seedJob(scopeId, tag, JobEntity.PENDING, attempts = 0, maxAttempts = 2)

        claim(worker, tag).then().statusCode(200) // attempts → 1
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/tasks/$jobId/fail")
            .then().statusCode(200).body("status", equalTo("pending")).body("attempts", equalTo(1))

        claim(worker, tag).then().statusCode(200) // requeued, claimed again → attempts → 2 (== max)
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/tasks/$jobId/fail")
            .then().statusCode(200).body("status", equalTo("failed")).body("attempts", equalTo(2))
    }

    @Test
    fun heartbeatExtendsTheLeaseForTheHolderOnly() {
        val scopeId = seedTenantScope()
        val worker = seedUser()
        seedGrantedMembership(worker, scopeId, "owner-grant")
        val tag = "deadbeef"
        val jobId = seedJob(scopeId, tag, JobEntity.PENDING, attempts = 0)
        claim(worker, tag).then().statusCode(200)

        given().header("Authorization", bearer(worker))
            .`when`().post("/api/tasks/$jobId/heartbeat")
            .then().statusCode(200).body("leaseExpiresAt", notNullValue())

        // A non-holder key-holder in the same scope cannot extend a lease they do not own → 409.
        val other = seedUser()
        seedGrantedMembership(other, scopeId, "other-grant")
        given().header("Authorization", bearer(other))
            .`when`().post("/api/tasks/$jobId/heartbeat")
            .then().statusCode(409).body("code", equalTo("not_lease_holder"))
    }

    @Test
    fun enqueueRequiresScopeMembership() {
        val scopeId = seedTenantScope()
        val member = seedUser()
        seedGrantedMembership(member, scopeId, "member-grant")

        given().header("Authorization", bearer(member)).contentType(ContentType.JSON)
            .body("""{"scopeId":"$scopeId","capabilityTag":"9f3a1c04","sealedBody":"AQ=="}""")
            .`when`().post("/api/tasks/enqueue")
            .then().statusCode(201).body("jobId", notNullValue())

        // An outsider with no grant in the scope cannot enqueue there.
        val outsider = seedUser()
        given().header("Authorization", bearer(outsider)).contentType(ContentType.JSON)
            .body("""{"scopeId":"$scopeId","capabilityTag":"9f3a1c04","sealedBody":"AQ=="}""")
            .`when`().post("/api/tasks/enqueue")
            .then().statusCode(403).body("code", equalTo("not_scope_member"))
    }

    @Test
    fun authedButNoGrantClaimIs204() {
        // A user with no granted membership anywhere holds no scope key → nothing is ever claimable for them.
        val nobody = seedUser()
        claim(nobody, CapabilityTags.GRANT_DELIVERY).then().statusCode(204)
    }

    @Test
    fun claimWithEmptyTagsIs400() {
        val worker = seedUser()
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"capabilityTags":[]}""")
            .`when`().post("/api/tasks/claim")
            .then().statusCode(400)
    }

    @Test
    fun actingOnAnUnknownJobIs404() {
        val worker = seedUser()
        val ghost = UUID.randomUUID().toString()
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON).body("""{"sealedResult":"AQ=="}""")
            .`when`().post("/api/tasks/$ghost/complete").then().statusCode(404).body("code", equalTo("job_not_found"))
        given().header("Authorization", bearer(worker))
            .`when`().post("/api/tasks/$ghost/heartbeat").then().statusCode(404).body("code", equalTo("job_not_found"))
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/tasks/$ghost/fail").then().statusCode(404).body("code", equalTo("job_not_found"))
    }

    @Test
    fun claimWithoutAuthIs401() {
        given().contentType(ContentType.JSON).body("""{"capabilityTags":["deadbeef"]}""")
            .`when`().post("/api/tasks/claim")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun grantLessWorkerCannotClaim() {
        // Eligibility is holding the scope key: a grant-less member is a *target*, not a worker.
        val scopeId = seedTenantScope()
        val pending = seedUser()
        seedGrantlessMembership(pending, scopeId)
        claim(pending, CapabilityTags.GRANT_DELIVERY).then().statusCode(204)
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private fun claim(userId: String, tag: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"capabilityTags":["$tag"]}""")
            .`when`().post("/api/tasks/claim")

    private fun claimGrantDelivery(userId: String): String =
        claim(userId, CapabilityTags.GRANT_DELIVERY).then().statusCode(200).extract().path("jobId")

    private fun seedTenantScope(): String {
        val tenantId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Fam"; domain = "t-$tenantId.example.com"
                ownerEmail = "o-$tenantId@example.com"; status = TenantEntity.ACTIVE; createdAt = Instant.now()
            }.persist()
            ScopeEntity().apply {
                id = tenantId; this.tenantId = tenantId; kind = ScopeEntity.TENANT; createdAt = Instant.now()
            }.persist()
        }
        return tenantId
    }

    private fun seedUser(pubkey: String = "aWRlbnRpdHktcHVibGljLWtleQ=="): String {
        val userId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            VeyqUser().apply {
                id = userId; email = "u-$userId@example.com"; displayName = "Tasks Test"
                serverRole = "MEMBER"; suiteId = "v1"; identityPublicKey = pubkey
                wrappedIdentityPrivateKey = "aWRlbnRpdHktcHJpdg=="; authPublicKey = "YXV0aC1wdWJsaWMta2V5"
                createdAt = Instant.now()
            }.persist()
        }
        return userId
    }

    private fun seedGrantedMembership(userId: String, scopeId: String, grant: String) =
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; tenantId = scopeId
                this.scopeId = scopeId; role = MembershipEntity.OWNER
                wrappedGrant = java.util.Base64.getEncoder().encodeToString(grant.toByteArray())
                createdAt = Instant.now()
            }.persist()
        }

    /** A pending member: a role, no grant — the state the delivery job reconciles. Returns the membership id. */
    private fun seedGrantlessMembership(userId: String, scopeId: String): String {
        val membershipId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = membershipId; this.userId = userId; tenantId = scopeId
                this.scopeId = scopeId; role = MembershipEntity.MEMBER; wrappedGrant = null
                createdAt = Instant.now()
            }.persist()
        }
        return membershipId
    }

    private fun seedJob(
        scopeId: String, tag: String, status: String, attempts: Int, maxAttempts: Int = 5,
        leaseExpiresAt: Instant? = null,
    ): String {
        val jobId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            JobEntity().apply {
                id = jobId; this.scopeId = scopeId; capabilityTag = tag; this.status = status
                this.attempts = attempts; this.maxAttempts = maxAttempts
                this.leaseExpiresAt = leaseExpiresAt; leaseHolder = if (status == JobEntity.LEASED) "crashed" else null
                createdAt = Instant.now()
            }.persist()
        }
        return jobId
    }

    private fun grantOf(membershipId: String): String? =
        QuarkusTransaction.requiringNew().call { MembershipEntity.findById(membershipId)?.wrappedGrant }

    private fun statusOf(jobId: String): String =
        QuarkusTransaction.requiringNew().call { JobEntity.findById(jobId)!!.status }
}
