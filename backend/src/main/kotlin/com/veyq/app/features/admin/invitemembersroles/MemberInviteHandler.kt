package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.generated.model.IssueMemberInviteRequest
import com.veyq.app.generated.model.OwnerInvite
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.InviteKind
import com.veyq.app.shared.tenancy.InviteStatus
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.TenantInvite
import com.veyq.app.shared.tenancy.TenantInviteRepository
import com.veyq.app.shared.tenancy.TenantRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * uc.admin.invite-members-roles (temp-wrap invite) — a key-holding Tenant Owner issues an invite for a
 * brand-new user. The owner has already wrapped the tenant scope private key to a one-time temp public
 * key (client-side); the server stores only the temp public key + that ciphertext. The temp private key
 * travels in the invite URL fragment and never reaches the server, so the operator stays blind.
 */
@ApplicationScoped
class MemberInviteHandler(
    private val tenants: TenantRepository,
    private val memberships: MembershipRepository,
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val invites: TenantInviteRepository,
    private val sessions: SessionService,
    private val suites: CryptoSuiteRegistry,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val rng = SecureRandom()
    private val inviteTtl: Duration = Duration.ofDays(7)

    @Transactional
    fun issueMemberInvite(auth: String?, tenantId: String, req: IssueMemberInviteRequest): OwnerInvite {
        val callerId = sessions.resolve(auth) ?: throw unauthorized()
        tenants.findById(tenantId) ?: throw notFound("Tenant")
        val scope = scopes.findByTenantId(tenantId) ?: throw notFound("Scope")
        requireSuite(req.tempPublicKey.suiteId)
        requireSuite(req.tempWrappedScopePrivateKey.suiteId)

        // only an active Owner who actually holds the scope can hand it on (you can only grant what you read)
        val callerMembership = memberships.find(tenantId, callerId)
        if (callerMembership == null || callerMembership.role != "Owner" || callerMembership.status != "ACTIVE") {
            throw ApiException(403, "FORBIDDEN", "Tenant Owner role required")
        }
        grants.find(scope.id, callerId) ?: throw ApiException(403, "NOT_A_KEY_HOLDER", "You do not hold this scope")

        val secret = ByteArray(32).also { rng.nextBytes(it) }
        val now = Instant.now()
        val expires = now.plus(inviteTtl)
        val invite = TenantInvite().apply {
            id = UUID.randomUUID().toString(); this.tenantId = tenantId; role = req.role.value
            secretHash = sha256(secret); status = InviteStatus.PENDING.name; kind = InviteKind.MEMBER.name
            scopeId = scope.id
            tempPublicKey = dec.decode(req.tempPublicKey.kemPublicKey)
            tempWrappedScopePrivateKey = dec.decode(req.tempWrappedScopePrivateKey.bytes)
            tempWrapSuiteId = req.tempWrappedScopePrivateKey.suiteId
            createdBy = callerId; createdAt = now; expiresAt = expires
        }
        invites.persist(invite)
        return OwnerInvite(
            inviteId = invite.id,
            bootstrapSecret = enc.encodeToString(secret),
            expiresAt = OffsetDateTime.ofInstant(expires, ZoneOffset.UTC),
        )
    }

    private fun requireSuite(suiteId: String) {
        if (!suites.isKnown(suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite: $suiteId")
    }

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    private fun unauthorized() = ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
    private fun notFound(what: String) = ApiException(404, "NOT_FOUND", "$what not found")
}
