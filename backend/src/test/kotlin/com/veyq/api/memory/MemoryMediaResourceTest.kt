package com.veyq.api.memory

import com.veyq.api.auth.PasswordService
import com.veyq.api.domain.LibraryScopeType
import com.veyq.api.domain.Media
import com.veyq.api.domain.MediaBlob
import com.veyq.api.domain.MediaRepo
import com.veyq.api.domain.Memory
import com.veyq.api.domain.MemoryRepo
import com.veyq.api.domain.ServerRole
import com.veyq.api.domain.Tenant
import com.veyq.api.domain.TenantMember
import com.veyq.api.domain.TenantMemberId
import com.veyq.api.domain.TenantMemberRepo
import com.veyq.api.domain.TenantRepo
import com.veyq.api.domain.TenantRole
import com.veyq.api.domain.User
import com.veyq.api.domain.UserRepo
import com.veyq.api.setup.ServerState
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.transaction.UserTransaction
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@QuarkusTest
class MemoryMediaResourceTest {
    @Inject lateinit var userRepo: UserRepo
    @Inject lateinit var tenantRepo: TenantRepo
    @Inject lateinit var tenantMemberRepo: TenantMemberRepo
    @Inject lateinit var memoryRepo: MemoryRepo
    @Inject lateinit var mediaRepo: MediaRepo
    @Inject lateinit var passwordService: PasswordService
    @Inject lateinit var serverState: ServerState
    @Inject lateinit var transaction: UserTransaction

    private lateinit var runId: String
    private lateinit var owner: User
    private lateinit var tenant: Tenant
    private lateinit var memory: Memory
    private lateinit var ownerAccessCookie: String

    @BeforeEach
    fun setUp() {
        runId = UUID.randomUUID().toString().take(8)
        owner = createUser("media-owner-$runId")
        tenant = createTenant("media-$runId", owner)
        memory = createMemory("trip-$runId", "Trip $runId")
        serverState.markSetupCompleted()
        ownerAccessCookie = login()
    }

    @Test
    fun `media updates endpoint accepts openapi date-time cursor`() {
        val first = createMedia("First", createdAt = Instant.parse("2026-05-31T19:54:48Z"))
        val second = createMedia("Second", createdAt = Instant.parse("2026-05-31T19:55:00Z"))

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerAccessCookie)
            .queryParam("includeChildren", true)
            .queryParam("afterCreatedAt", "2026-05-31T19:54:48Z")
            .queryParam("afterMediaId", first.publicId)
            .queryParam("timeoutSeconds", 1)
            .`when`().get("/api/${tenant.name}/memories/${memory.publicId}/media/updates")
            .then()
            .statusCode(200)
            .body("media", hasSize<Any>(1))
            .body("media[0].id", equalTo(second.publicId))
    }

    private fun login(): String =
        given()
            .contentType("application/json")
            .body("""{"email":"${owner.email}","password":"password1234"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(200)
            .extract()
            .cookie("VEYQ_ACCESS_TOKEN")

    private fun createUser(name: String): User =
        inTransaction {
            User().also {
                it.passwordHash = passwordService.hash("password1234")
                it.displayName = name
                it.permalink = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                it.email = "$name@example.com"
                it.role = ServerRole.ServerOwner
                it.passwordSetAt = Instant.now()
                userRepo.persist(it)
            }
        }

    private fun createTenant(name: String, user: User): Tenant =
        inTransaction {
            val tenant = Tenant().also {
                it.name = name
                it.displayName = name
                it.domain = "$name.example.test"
                it.activatedAt = Instant.now()
                tenantRepo.persist(it)
            }
            tenantRepo.flush()
            TenantMember().also {
                it.id = TenantMemberId(tenant.id, user.id)
                it.tenant = tenant
                it.user = user
                it.role = TenantRole.TenantOwner
                it.acceptedAt = Instant.now()
                tenantMemberRepo.persist(it)
            }
            tenant
        }

    private fun createMemory(name: String, displayName: String): Memory =
        inTransaction {
            Memory().also {
                it.tenant = tenant
                it.owner = owner
                it.scopeType = LibraryScopeType.Tenant
                it.name = name
                it.displayName = displayName
                it.startDate = LocalDate.parse("2026-05-01")
                it.endDate = LocalDate.parse("2026-05-02")
                memoryRepo.persist(it)
            }
        }

    private fun createMedia(title: String, createdAt: Instant): Media =
        inTransaction {
            val blob = MediaBlob().also {
                it.tenant = tenant
                it.contentSha256 = "${UUID.randomUUID().toString().replace("-", "")}${UUID.randomUUID().toString().replace("-", "")}"
                it.mimeType = "image/jpeg"
                it.sizeBytes = 10
                it.storageKey = "tenant-${tenant.id}/test-${UUID.randomUUID()}/original"
                it.imageWidth = 16
                it.imageHeight = 9
                it.persist()
            }
            Media().also {
                it.tenant = tenant
                it.uploaderUser = owner
                it.scopeType = LibraryScopeType.Tenant
                it.memory = memory
                it.title = title
                it.blob = blob
                it.originalFilename = "$title.jpg"
                it.createdAt = createdAt
                mediaRepo.persist(it)
            }
        }

    private fun <T> inTransaction(block: () -> T): T {
        transaction.begin()
        return try {
            val result = block()
            transaction.commit()
            result
        } catch (error: Throwable) {
            transaction.rollback()
            throw error
        }
    }
}
