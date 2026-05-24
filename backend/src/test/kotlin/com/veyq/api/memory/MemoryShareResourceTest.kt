package com.veyq.api.memory

import com.veyq.api.auth.PasswordService
import com.veyq.api.domain.*
import com.veyq.api.security.RateLimitService
import com.veyq.api.setup.ServerState
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.transaction.UserTransaction
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.imageio.ImageIO

@QuarkusTest
class MemoryShareResourceTest {
    @Inject lateinit var userRepo: UserRepo
    @Inject lateinit var tenantRepo: TenantRepo
    @Inject lateinit var tenantMemberRepo: TenantMemberRepo
    @Inject lateinit var memoryRepo: MemoryRepo
    @Inject lateinit var mediaRepo: MediaRepo
    @Inject lateinit var passwordService: PasswordService
    @Inject lateinit var rateLimitService: RateLimitService
    @Inject lateinit var serverState: ServerState
    @Inject lateinit var transaction: UserTransaction

    private lateinit var runId: String
    private lateinit var owner: User
    private lateinit var tenant: Tenant
    private lateinit var memory: Memory
    private lateinit var ownerAccessCookie: String

    @BeforeEach
    fun setUp() {
        rateLimitService.clearAll()
        runId = UUID.randomUUID().toString().take(8)
        owner = createUser("share-owner-$runId")
        tenant = createTenant("share-$runId", owner)
        memory = createMemory("trip-$runId", "Trip $runId")
        serverState.markSetupCompleted()
        ownerAccessCookie = login()
    }

    @Test
    fun `public share requires password and opens access token cookie`() {
        val shareId = createShare("VIEWER")

        given()
            .`when`().get("/api/public-shares/$shareId")
            .then()
            .statusCode(200)
            .body("authenticated", equalTo(false))
            .body("passwordRequired", equalTo(true))
            .body("rootMemory", nullValue())

        given()
            .`when`().get("/api/public-shares/$shareId/tree")
            .then()
            .statusCode(401)

        given()
            .contentType("application/json")
            .body("""{"password":"wrong-password","displayName":"Alex"}""")
            .`when`().post("/api/public-shares/$shareId/login")
            .then()
            .statusCode(401)

        val accessCookie = given()
            .contentType("application/json")
            .body("""{"password":"share-password123","displayName":"Alex"}""")
            .`when`().post("/api/public-shares/$shareId/login")
            .then()
            .statusCode(200)
            .body("authenticated", equalTo(true))
            .extract()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN")

        assertThat(accessCookie).isNotBlank()

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
            .`when`().get("/api/public-shares/$shareId/tree")
            .then()
            .statusCode(200)
            .body("root.id", equalTo(memory.publicId))
    }

    @Test
    fun `share login is rate limited and success clears failures`() {
        val shareId = createShare("VIEWER")

        repeat(2) {
            given()
                .contentType("application/json")
                .body("""{"password":"wrong-password","displayName":"Alex"}""")
                .`when`().post("/api/public-shares/$shareId/login")
                .then()
                .statusCode(401)
        }

        given()
            .contentType("application/json")
            .body("""{"password":"share-password123","displayName":"Alex"}""")
            .`when`().post("/api/public-shares/$shareId/login")
            .then()
            .statusCode(200)

        repeat(5) {
            given()
                .contentType("application/json")
                .body("""{"password":"wrong-password","displayName":"Alex"}""")
                .`when`().post("/api/public-shares/$shareId/login")
                .then()
                .statusCode(401)
        }

        given()
            .contentType("application/json")
            .body("""{"password":"wrong-password","displayName":"Alex"}""")
            .`when`().post("/api/public-shares/$shareId/login")
            .then()
            .statusCode(429)
    }

    @Test
    fun `share login rate limit ignores spoofed forwarded for by default`() {
        val shareId = createShare("VIEWER")

        repeat(5) { attempt ->
            given()
                .contentType("application/json")
                .header("X-Forwarded-For", "203.0.113.$attempt")
                .body("""{"password":"wrong-password","displayName":"Alex"}""")
                .`when`().post("/api/public-shares/$shareId/login")
                .then()
                .statusCode(401)
        }

        given()
            .contentType("application/json")
            .header("X-Forwarded-For", "203.0.113.99")
            .body("""{"password":"wrong-password","displayName":"Alex"}""")
            .`when`().post("/api/public-shares/$shareId/login")
            .then()
            .statusCode(429)
            .header("Retry-After", notNullValue())
            .body("code", equalTo("RATE_LIMITED"))
    }

    @Test
    fun `public share upload returns stable code for empty files`() {
        val shareId = createShare("UPLOADER")
        val accessCookie = loginShare(shareId)
        val empty = Files.createTempFile("veyq-empty-upload-", ".png")
        try {
            given()
                .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
                .multiPart("file", empty.toFile(), "image/png")
                .multiPart("title", "Empty")
                .`when`().post("/api/public-shares/$shareId/media")
                .then()
                .statusCode(400)
                .body("code", equalTo("MEDIA_UPLOAD_EMPTY"))
        } finally {
            Files.deleteIfExists(empty)
        }
    }

    @Test
    fun `public share cover returns stable code for invalid crop`() {
        val media = createMedia("Cover")
        val shareId = createShare("MANAGER")
        val accessCookie = loginShare(shareId)

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
            .multiPart("mediaId", media.publicId)
            .multiPart("cropX", "0")
            .multiPart("cropY", "0")
            .multiPart("cropWidth", "2")
            .multiPart("cropHeight", "1")
            .`when`().put("/api/public-shares/$shareId/memories/${memory.publicId}/cover")
            .then()
            .statusCode(400)
            .body("code", equalTo("MEMORY_COVER_CROP_INVALID"))
    }

    @Test
    fun `uploader share sees only own uploaded media`() {
        createMedia("Existing")
        val shareId = createShare("UPLOADER")
        val response = given()
            .contentType("application/json")
            .body("""{"password":"share-password123","displayName":"Alex"}""")
            .`when`().post("/api/public-shares/$shareId/login")
            .then()
            .statusCode(200)
            .body("canUpload", equalTo(true))
            .body("rootMemory", nullValue())
            .extract()
        val accessCookie = response.cookie("VEYQ_SHARE_ACCESS_TOKEN")
        val refreshCookie = response.cookie("VEYQ_SHARE_REFRESH_TOKEN")

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
            .`when`().get("/api/public-shares/$shareId/media")
            .then()
            .statusCode(200)
            .body("", hasSize<Any>(0))

        val image = imageFile()
        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
            .multiPart("file", image.toFile(), "image/png")
            .multiPart("title", "Upload")
            .`when`().post("/api/public-shares/$shareId/media")
            .then()
            .statusCode(200)
            .body("uploader.displayName", equalTo("Alex"))
            .body("uploader.type", equalTo("ANONYMOUS"))

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
            .`when`().get("/api/public-shares/$shareId/media")
            .then()
            .statusCode(200)
            .body("", hasSize<Any>(1))
            .body("[0].title", equalTo("Upload"))

        val refreshedAccessCookie = given()
            .cookie("VEYQ_SHARE_REFRESH_TOKEN", refreshCookie)
            .`when`().post("/api/public-shares/$shareId/refresh")
            .then()
            .statusCode(200)
            .body("canUpload", equalTo(true))
            .body("rootMemory", nullValue())
            .extract()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN")

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", refreshedAccessCookie)
            .`when`().get("/api/public-shares/$shareId/media")
            .then()
            .statusCode(200)
            .body("", hasSize<Any>(1))
    }

    @Test
    fun `share refresh token reuse revokes rotated token chain`() {
        val shareId = createShare("UPLOADER")
        val login = given()
            .contentType("application/json")
            .body("""{"password":"share-password123","displayName":"Alex"}""")
            .`when`().post("/api/public-shares/$shareId/login")
            .then()
            .statusCode(200)
            .extract()
        val oldRefreshCookie = login.cookie("VEYQ_SHARE_REFRESH_TOKEN")
        assertThat(oldRefreshCookie).isNotBlank()

        val refresh = given()
            .cookie("VEYQ_SHARE_REFRESH_TOKEN", oldRefreshCookie)
            .`when`().post("/api/public-shares/$shareId/refresh")
            .then()
            .statusCode(200)
            .extract()
        val newAccessCookie = refresh.cookie("VEYQ_SHARE_ACCESS_TOKEN")
        val newRefreshCookie = refresh.cookie("VEYQ_SHARE_REFRESH_TOKEN")
        assertThat(newAccessCookie).isNotBlank()
        assertThat(newRefreshCookie).isNotBlank()
        assertThat(newRefreshCookie).isNotEqualTo(oldRefreshCookie)

        given()
            .cookie("VEYQ_SHARE_REFRESH_TOKEN", oldRefreshCookie)
            .`when`().post("/api/public-shares/$shareId/refresh")
            .then()
            .statusCode(401)

        given()
            .cookie("VEYQ_SHARE_REFRESH_TOKEN", newRefreshCookie)
            .`when`().post("/api/public-shares/$shareId/refresh")
            .then()
            .statusCode(401)

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", newAccessCookie)
            .`when`().get("/api/public-shares/$shareId/media")
            .then()
            .statusCode(401)
    }

    @Test
    fun `managed share can be named and created without password`() {
        val shareId = given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerAccessCookie)
            .body("""{"name":"Family review","role":"VIEWER"}""")
            .`when`().post("/api/${tenant.name}/memories/${memory.publicId}/shares")
            .then()
            .statusCode(200)
            .body("name", equalTo("Family review"))
            .body("hasPassword", equalTo(false))
            .extract()
            .path<String>("id")

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerAccessCookie)
            .`when`().get("/api/${tenant.name}/memories/${memory.publicId}/shares")
            .then()
            .statusCode(200)
            .body("[0].id", equalTo(shareId))
            .body("[0].name", equalTo("Family review"))
            .body("[0].hasPassword", equalTo(false))
    }

    @Test
    fun `passwordless viewer reads memory without login`() {
        val child = createMemory("public-day-$runId", "Public Day $runId", memory)
        val shareId = createShare("VIEWER", password = null)

        given()
            .`when`().get("/api/public-shares/$shareId")
            .then()
            .statusCode(200)
            .body("authenticated", equalTo(false))
            .body("passwordRequired", equalTo(false))
            .body("displayNameRequired", equalTo(false))
            .body("canViewGallery", equalTo(true))

        given()
            .queryParam("path", "/${child.name}")
            .`when`().get("/api/public-shares/$shareId/memories/by-path")
            .then()
            .statusCode(200)
            .body("id", equalTo(child.publicId))
    }

    @Test
    fun `passwordless contributor requires identity login before write`() {
        val shareId = createShare("CONTRIBUTOR", password = null)

        given()
            .`when`().get("/api/public-shares/$shareId")
            .then()
            .statusCode(200)
            .body("passwordRequired", equalTo(false))
            .body("displayNameRequired", equalTo(true))
            .body("canEdit", equalTo(false))

        given()
            .contentType("application/json")
            .body("""{"name":"blocked","displayName":"Blocked","startDate":"2026-05-03","endDate":"2026-05-03"}""")
            .`when`().post("/api/public-shares/$shareId/memories/${memory.publicId}/children")
            .then()
            .statusCode(401)

        val accessCookie = given()
            .contentType("application/json")
            .body("""{"displayName":"Alex"}""")
            .`when`().post("/api/public-shares/$shareId/login")
            .then()
            .statusCode(200)
            .body("authenticated", equalTo(true))
            .body("passwordRequired", equalTo(false))
            .body("displayNameRequired", equalTo(false))
            .body("canEdit", equalTo(true))
            .extract()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN")

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
            .contentType("application/json")
            .body("""{"name":"allowed","displayName":"Allowed","startDate":"2026-05-03","endDate":"2026-05-03"}""")
            .`when`().post("/api/public-shares/$shareId/memories/${memory.publicId}/children")
            .then()
            .statusCode(200)
            .body("path", equalTo("/allowed"))
    }

    @Test
    fun `password update can leave set remove and revoke sessions`() {
        val shareId = createShare("CONTRIBUTOR", name = "Original")
        val accessCookie = loginShare(shareId)

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
            .contentType("application/json")
            .body("""{"name":"before-remove","displayName":"Before Remove","startDate":"2026-05-03","endDate":"2026-05-03"}""")
            .`when`().post("/api/public-shares/$shareId/memories/${memory.publicId}/children")
            .then()
            .statusCode(200)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerAccessCookie)
            .body("""{"name":"Renamed"}""")
            .`when`().put("/api/${tenant.name}/memory-shares/$shareId")
            .then()
            .statusCode(200)
            .body("name", equalTo("Renamed"))
            .body("hasPassword", equalTo(true))

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerAccessCookie)
            .body("""{"role":"MANAGER"}""")
            .`when`().put("/api/${tenant.name}/memory-shares/$shareId")
            .then()
            .statusCode(200)
            .body("name", equalTo("Renamed"))

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerAccessCookie)
            .body("""{"name":""}""")
            .`when`().put("/api/${tenant.name}/memory-shares/$shareId")
            .then()
            .statusCode(200)
            .body("name", nullValue())

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerAccessCookie)
            .body("""{"name":"Renamed Again"}""")
            .`when`().put("/api/${tenant.name}/memory-shares/$shareId")
            .then()
            .statusCode(200)
            .body("name", equalTo("Renamed Again"))

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerAccessCookie)
            .body("""{"name":null}""")
            .`when`().put("/api/${tenant.name}/memory-shares/$shareId")
            .then()
            .statusCode(200)
            .body("name", nullValue())

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
            .contentType("application/json")
            .body("""{"name":"after-name","displayName":"After Name","startDate":"2026-05-03","endDate":"2026-05-03"}""")
            .`when`().post("/api/public-shares/$shareId/memories/${memory.publicId}/children")
            .then()
            .statusCode(200)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerAccessCookie)
            .body("""{"password":""}""")
            .`when`().put("/api/${tenant.name}/memory-shares/$shareId")
            .then()
            .statusCode(200)
            .body("hasPassword", equalTo(false))

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
            .contentType("application/json")
            .body("""{"name":"after-remove","displayName":"After Remove","startDate":"2026-05-03","endDate":"2026-05-03"}""")
            .`when`().post("/api/public-shares/$shareId/memories/${memory.publicId}/children")
            .then()
            .statusCode(401)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerAccessCookie)
            .body("""{"password":"new-share-password123"}""")
            .`when`().put("/api/${tenant.name}/memory-shares/$shareId")
            .then()
            .statusCode(200)
            .body("hasPassword", equalTo(true))

        val secondAccessCookie = given()
            .contentType("application/json")
            .body("""{"password":"new-share-password123","displayName":"Before Null Remove"}""")
            .`when`().post("/api/public-shares/$shareId/login")
            .then()
            .statusCode(200)
            .extract()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN")

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerAccessCookie)
            .body("""{"password":null}""")
            .`when`().put("/api/${tenant.name}/memory-shares/$shareId")
            .then()
            .statusCode(200)
            .body("hasPassword", equalTo(false))

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", secondAccessCookie)
            .contentType("application/json")
            .body("""{"name":"after-null-remove","displayName":"After Null Remove","startDate":"2026-05-03","endDate":"2026-05-03"}""")
            .`when`().post("/api/public-shares/$shareId/memories/${memory.publicId}/children")
            .then()
            .statusCode(401)
    }

    @Test
    fun `viewer reads memory view but cannot write or delete`() {
        val child = createMemory("day-$runId", "Day $runId", memory)
        val shareId = createShare("VIEWER")
        val accessCookie = loginShare(shareId)

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
            .queryParam("path", "/")
            .`when`().get("/api/public-shares/$shareId/memories/by-path")
            .then()
            .statusCode(200)
            .body("id", equalTo(memory.publicId))
            .body("path", equalTo("/"))

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
            .`when`().get("/api/public-shares/$shareId/memories/${memory.publicId}/children")
            .then()
            .statusCode(200)
            .body("", hasSize<Any>(1))
            .body("[0].id", equalTo(child.publicId))
            .body("[0].path", equalTo("/${child.name}"))

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
            .contentType("application/json")
            .body("""{"displayName":"Updated"}""")
            .`when`().put("/api/public-shares/$shareId/memories/${memory.publicId}")
            .then()
            .statusCode(403)

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
            .`when`().delete("/api/public-shares/$shareId/memories/${child.publicId}")
            .then()
            .statusCode(403)
    }

    @Test
    fun `contributor writes inside subtree but cannot delete`() {
        val shareId = createShare("CONTRIBUTOR")
        val accessCookie = loginShare(shareId)

        val childId = given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
            .contentType("application/json")
            .body(
                """
                {"name":"day-2","displayName":"Day 2","startDate":"2026-05-03","endDate":"2026-05-03"}
                """.trimIndent()
            )
            .`when`().post("/api/public-shares/$shareId/memories/${memory.publicId}/children")
            .then()
            .statusCode(200)
            .body("path", equalTo("/day-2"))
            .extract()
            .path<String>("id")

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
            .contentType("application/json")
            .body("""{"displayName":"Day Two"}""")
            .`when`().put("/api/public-shares/$shareId/memories/$childId")
            .then()
            .statusCode(200)
            .body("displayName", equalTo("Day Two"))

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
            .`when`().delete("/api/public-shares/$shareId/memories/$childId")
            .then()
            .statusCode(403)
    }

    @Test
    fun `manager deletes submemories but not share root`() {
        val child = createMemory("manager-day-$runId", "Manager Day $runId", memory)
        val shareId = createShare("MANAGER")
        val accessCookie = loginShare(shareId)

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
            .`when`().delete("/api/public-shares/$shareId/memories/${memory.publicId}")
            .then()
            .statusCode(403)

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
            .`when`().delete("/api/public-shares/$shareId/memories/${child.publicId}")
            .then()
            .statusCode(204)
    }

    @Test
    fun `uploader cannot load full memory view`() {
        val shareId = createShare("UPLOADER")
        val accessCookie = loginShare(shareId)

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
            .queryParam("path", "/")
            .`when`().get("/api/public-shares/$shareId/memories/by-path")
            .then()
            .statusCode(403)
    }

    @Test
    fun `public share memory header exposes subtree counts without scope`() {
        val child = createMemory("header-day-$runId", "Header Day $runId", memory)
        createMedia("Root Header Photo", memory)
        createMedia("Child Header Photo", child)
        val shareId = createShare("VIEWER")
        val accessCookie = loginShare(shareId)

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
            .`when`().get("/api/public-shares/$shareId/memories/${memory.publicId}/header")
            .then()
            .statusCode(200)
            .body("id", equalTo(memory.publicId))
            .body("displayName", equalTo(memory.displayName))
            .body("path", equalTo("/"))
            .body("directMediaCount", equalTo(1))
            .body("descendantMediaCount", equalTo(1))
            .body("childMemoryCount", equalTo(1))
            .body("scope", nullValue())
    }

    @Test
    fun `uploader cannot load public share memory header`() {
        val shareId = createShare("UPLOADER")
        val accessCookie = loginShare(shareId)

        given()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN", accessCookie)
            .`when`().get("/api/public-shares/$shareId/memories/${memory.publicId}/header")
            .then()
            .statusCode(403)
    }

    private fun createShare(role: String, password: String? = "share-password123", name: String? = null): String {
        val fields = mutableListOf("\"role\":\"$role\"")
        name?.let { fields += "\"name\":\"$it\"" }
        password?.let { fields += "\"password\":\"$it\"" }
        return given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerAccessCookie)
            .body("{${fields.joinToString(",")}}")
            .`when`().post("/api/${tenant.name}/memories/${memory.publicId}/shares")
            .then()
            .statusCode(200)
            .extract()
            .path("id")
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

    private fun loginShare(shareId: String): String =
        given()
            .contentType("application/json")
            .body("""{"password":"share-password123","displayName":"Alex"}""")
            .`when`().post("/api/public-shares/$shareId/login")
            .then()
            .statusCode(200)
            .extract()
            .cookie("VEYQ_SHARE_ACCESS_TOKEN")

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

    private fun createMemory(name: String, displayName: String, parent: Memory? = null): Memory =
        inTransaction {
            Memory().also {
                it.tenant = tenant
                it.owner = owner
                it.scopeType = LibraryScopeType.Tenant
                it.parentMemory = parent
                it.name = name
                it.displayName = displayName
                it.startDate = LocalDate.parse("2026-05-01")
                it.endDate = LocalDate.parse("2026-05-02")
                memoryRepo.persist(it)
            }
        }

    private fun createMedia(title: String, targetMemory: Memory = memory): Media =
        inTransaction {
            Media().also {
                it.tenant = tenant
                it.uploaderUser = owner
                it.scopeType = LibraryScopeType.Tenant
                it.memory = targetMemory
                it.title = title
                it.mimeType = "image/jpeg"
                it.sizeBytes = 10
                it.storageKey = "tenant-${tenant.id}/test-${title.lowercase()}/original"
                it.originalFilename = "$title.jpg"
                mediaRepo.persist(it)
            }
        }

    private fun imageFile() =
        Files.createTempFile("veyq-share-upload-", ".png").also {
            ImageIO.write(BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB), "png", it.toFile())
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
