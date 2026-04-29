package com.lunva.api.library.explorer

import com.lunva.api.auth.PasswordService
import com.lunva.api.domain.GroupRole
import com.lunva.api.domain.Media
import com.lunva.api.domain.MediaRepo
import com.lunva.api.domain.ServerRole
import com.lunva.api.domain.Tenant
import com.lunva.api.domain.TenantGroup
import com.lunva.api.domain.TenantGroupMember
import com.lunva.api.domain.TenantGroupMemberId
import com.lunva.api.domain.TenantGroupMemberRepo
import com.lunva.api.domain.TenantGroupRepo
import com.lunva.api.domain.TenantMember
import com.lunva.api.domain.TenantMemberId
import com.lunva.api.domain.TenantMemberRepo
import com.lunva.api.domain.TenantRepo
import com.lunva.api.domain.TenantRole
import com.lunva.api.domain.User
import com.lunva.api.domain.UserRepo
import com.lunva.api.setup.SetupFilter
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.transaction.UserTransaction
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.util.UUID

@QuarkusTest
class ExplorerResourceTest {
    @Inject lateinit var userRepo: UserRepo
    @Inject lateinit var tenantRepo: TenantRepo
    @Inject lateinit var tenantMemberRepo: TenantMemberRepo
    @Inject lateinit var tenantGroupRepo: TenantGroupRepo
    @Inject lateinit var tenantGroupMemberRepo: TenantGroupMemberRepo
    @Inject lateinit var mediaRepo: MediaRepo
    @Inject lateinit var passwordService: PasswordService
    @Inject lateinit var setupFilter: SetupFilter
    @Inject lateinit var transaction: UserTransaction

    private lateinit var runId: String
    private lateinit var ownerEmail: String
    private lateinit var ownerCookie: String
    private lateinit var tenantName: String

    @BeforeEach
    fun setUp() {
        runId = UUID.randomUUID().toString().take(8)
        ownerEmail = "owner-$runId@example.com"
        tenantName = "explorer-$runId"
        createUser("owner-$runId", "admin123", ServerRole.ServerOwner)
        ownerCookie = login(ownerEmail, "admin123")
        createTenant(ownerCookie, tenantName, "Explorer $runId")
    }

    @Test
    fun `creates folders and albums by path and rejects sibling collisions`() {
        createFolder(ownerCookie, null, "urlaub", "Urlaub")
        createAlbum(ownerCookie, "/urlaub", "klettern", "Klettern")

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .queryParam("path", "/urlaub")
            .`when`().get("/api/$tenantName/explorer")
            .then()
            .statusCode(200)
            .body("context.type", equalTo("Folder"))
            .body("context.path", equalTo("/urlaub"))
            .body("albums.name", hasItem("klettern"))

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"parentPath":"/urlaub","name":"klettern","displayName":"Klettern Folder"}""")
            .`when`().post("/api/$tenantName/folders")
            .then()
            .statusCode(409)
    }

    @Test
    fun `tenant owner does not see unshared member content`() {
        createUser("member-$runId", "member123", ServerRole.ServerMember)
        addTenantMember("member-$runId")
        val memberCookie = login("member-$runId@example.com", "member123")
        createFolder(memberCookie, null, "private", "Private")

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/$tenantName/explorer")
            .then()
            .statusCode(200)
            .body("folders.name", not(hasItem("private")))
    }

    @Test
    fun `group folder share exposes nested folder as root entry and streams descendant media`() {
        createUser("author-$runId", "member123", ServerRole.ServerMember)
        createUser("viewer-$runId", "member123", ServerRole.ServerMember)
        addTenantMember("author-$runId")
        addTenantMember("viewer-$runId")
        val authorCookie = login("author-$runId@example.com", "member123")
        val viewerCookie = login("viewer-$runId@example.com", "member123")
        createFolder(authorCookie, null, "hidden-parent", "Hidden Parent")
        createFolder(authorCookie, "/hidden-parent", "shared", "Shared")
        createAlbum(authorCookie, "/hidden-parent/shared", "trip", "Trip")
        val media = createMedia("author-$runId@example.com", "Summit")
        addMedia(authorCookie, "/hidden-parent/shared/trip", media.publicId)
        val group = createGroupWithMember("shared-$runId", "viewer-$runId@example.com")

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", authorCookie)
            .queryParam("path", "/hidden-parent/shared")
            .body("""{"groupId":${group.id}}""")
            .`when`().post("/api/$tenantName/folders/shares/groups")
            .then()
            .statusCode(200)

        given()
            .cookie("LUNVA_ACCESS_TOKEN", viewerCookie)
            .`when`().get("/api/$tenantName/explorer")
            .then()
            .statusCode(200)
            .body("folders.name", hasItem("shared"))
            .body("folders.name", not(hasItem("hidden-parent")))

        val stream = given()
            .cookie("LUNVA_ACCESS_TOKEN", viewerCookie)
            .queryParam("path", "/hidden-parent/shared")
            .`when`().get("/api/$tenantName/media")
            .then()
            .statusCode(200)
            .contentType(containsString("application/x-ndjson"))
            .extract().body().asString()

        assertThat(stream.lines()).anyMatch { it.contains("Summit") }
    }

    @Test
    fun `album cover can be hidden from regular album media stream`() {
        createAlbum(ownerCookie, null, "photos", "Photos")
        val visible = createMedia(ownerEmail, "Visible")
        val cover = createMedia(ownerEmail, "Cover")
        addMedia(ownerCookie, "/photos", visible.publicId)

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"albumPath":"/photos","mediaId":"${cover.publicId}","hidden":true}""")
            .`when`().post("/api/$tenantName/albums/cover")
            .then()
            .statusCode(200)
            .body("coverMediaId", equalTo(cover.publicId))

        val stream = given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .queryParam("path", "/photos")
            .`when`().get("/api/$tenantName/media")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertThat(stream).contains("Visible")
        assertThat(stream).doesNotContain("Cover")
    }

    @Test
    fun `move delete and preserve media rows`() {
        createFolder(ownerCookie, null, "folder", "Folder")
        createAlbum(ownerCookie, "/folder", "album", "Album")
        val media = createMedia(ownerEmail, "Keep Me")
        addMedia(ownerCookie, "/folder/album", media.publicId)

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"path":"/folder/album","newParentPath":null,"name":"moved"}""")
            .`when`().post("/api/$tenantName/albums/move")
            .then()
            .statusCode(200)
            .body("path", equalTo("/moved"))

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .queryParam("path", "/moved")
            .`when`().delete("/api/$tenantName/albums")
            .then()
            .statusCode(204)

        assertThat(inTransaction { mediaRepo.findByTenantAndPublicId(tenant(), media.publicId) }).isNotNull
    }

    @Test
    fun `uploads image into album and serves content`() {
        createAlbum(ownerCookie, null, "photos", "Photos")
        val file = File.createTempFile("lunva-upload-", ".jpg").also {
            it.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        }

        val mediaId = given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .multiPart("albumPath", "/photos")
            .multiPart("title", "Uploaded Photo")
            .multiPart("file", file, "image/jpeg")
            .`when`().post("/api/$tenantName/media")
            .then()
            .statusCode(200)
            .body("title", equalTo("Uploaded Photo"))
            .body("albumPath", equalTo("/photos"))
            .body("mimeType", equalTo("image/jpeg"))
            .extract().path<String>("id")

        val stored = inTransaction { mediaRepo.findByTenantAndPublicId(tenant(), mediaId) }
        assertThat(stored?.storageKey).isEqualTo("tenant-${tenant().id}/$mediaId/original")

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/$tenantName/media/$mediaId/content")
            .then()
            .statusCode(200)
            .contentType("image/jpeg")
            .header("Content-Disposition", containsString("inline"))

        createUser("viewer-$runId", "member123", ServerRole.ServerMember)
        addTenantMember("viewer-$runId")
        val viewerCookie = login("viewer-$runId@example.com", "member123")

        given()
            .cookie("LUNVA_ACCESS_TOKEN", viewerCookie)
            .`when`().get("/api/$tenantName/media/$mediaId/content")
            .then()
            .statusCode(404)
    }

    @Test
    fun `uploads video into album`() {
        createAlbum(ownerCookie, null, "videos", "Videos")
        val file = File.createTempFile("lunva-upload-", ".mp4").also {
            it.writeBytes(byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112))
        }

        val mediaId = given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .multiPart("albumPath", "/videos")
            .multiPart("file", file, "video/mp4")
            .`when`().post("/api/$tenantName/media")
            .then()
            .statusCode(200)
            .body("mimeType", equalTo("video/mp4"))
            .extract().path<String>("id")

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .header("Range", "bytes=0-3")
            .`when`().get("/api/$tenantName/media/$mediaId/content")
            .then()
            .statusCode(206)
            .contentType("video/mp4")
            .header("Accept-Ranges", equalTo("bytes"))
            .header("Content-Range", equalTo("bytes 0-3/8"))
            .header("Content-Length", equalTo("4"))

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .header("Range", "bytes=100-200")
            .`when`().get("/api/$tenantName/media/$mediaId/content")
            .then()
            .statusCode(416)
            .header("Content-Range", equalTo("bytes */8"))
    }

    @Test
    fun `uploads media through chunk session`() {
        createAlbum(ownerCookie, null, "chunks", "Chunks")

        val uploadId = given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"tenantName":"$tenantName","albumPath":"/chunks","filename":"chunked.mp4","mimeType":"video/mp4","sizeBytes":8}""")
            .`when`().post("/api/media/uploads")
            .then()
            .statusCode(200)
            .body("chunkSizeBytes", equalTo(4))
            .extract().path<String>("uploadId")

        given()
            .contentType("application/octet-stream")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body(byteArrayOf(0, 0, 0, 24))
            .`when`().put("/api/media/uploads/$uploadId/chunks/0")
            .then()
            .statusCode(204)

        given()
            .contentType("application/octet-stream")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body(byteArrayOf(102, 116, 121, 112))
            .`when`().put("/api/media/uploads/$uploadId/chunks/1")
            .then()
            .statusCode(204)

        val mediaId = given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .`when`().post("/api/media/uploads/$uploadId/complete")
            .then()
            .statusCode(200)
            .body("title", equalTo("chunked"))
            .body("mimeType", equalTo("video/mp4"))
            .body("sizeBytes", equalTo(8))
            .extract().path<String>("id")

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .header("Range", "bytes=4-7")
            .`when`().get("/api/$tenantName/media/$mediaId/content")
            .then()
            .statusCode(206)
            .header("Content-Range", equalTo("bytes 4-7/8"))
    }

    @Test
    fun `global upload sessions can be listed resumed and completed`() {
        createAlbum(ownerCookie, null, "resume", "Resume")

        val uploadId = given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"tenantName":"$tenantName","albumPath":"/resume","filename":"resume.mp4","mimeType":"video/mp4","sizeBytes":8}""")
            .`when`().post("/api/media/uploads")
            .then()
            .statusCode(200)
            .body("chunkSizeBytes", equalTo(4))
            .extract().path<String>("uploadId")

        given()
            .contentType("application/octet-stream")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body(byteArrayOf(102, 116, 121, 112))
            .`when`().put("/api/media/uploads/$uploadId/chunks/1")
            .then()
            .statusCode(204)

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/media/uploads")
            .then()
            .statusCode(200)
            .body("uploadId", hasItem(uploadId))
            .body("tenantName", hasItem(tenantName))

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/media/uploads/$uploadId")
            .then()
            .statusCode(200)
            .body("albumPath", equalTo("/resume"))
            .body("uploadedChunks", hasItem(1))
            .body("uploadedBytes", equalTo(4))

        given()
            .contentType("application/octet-stream")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body(byteArrayOf(0, 0, 0, 24))
            .`when`().put("/api/media/uploads/$uploadId/chunks/0")
            .then()
            .statusCode(204)

        val mediaId = given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .`when`().post("/api/media/uploads/$uploadId/complete")
            .then()
            .statusCode(200)
            .body("albumPath", equalTo("/resume"))
            .body("mimeType", equalTo("video/mp4"))
            .extract().path<String>("id")

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .header("Range", "bytes=4-7")
            .`when`().get("/api/$tenantName/media/$mediaId/content")
            .then()
            .statusCode(206)
            .header("Content-Range", equalTo("bytes 4-7/8"))
    }

    @Test
    fun `global upload sessions can be aborted and are hidden from other users`() {
        createAlbum(ownerCookie, null, "abort", "Abort")
        createUser("other-$runId", "member123", ServerRole.ServerMember)
        addTenantMember("other-$runId")
        val otherCookie = login("other-$runId@example.com", "member123")

        val uploadId = given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"tenantName":"$tenantName","albumPath":"/abort","filename":"abort.mp4","mimeType":"video/mp4","sizeBytes":8}""")
            .`when`().post("/api/media/uploads")
            .then()
            .statusCode(200)
            .extract().path<String>("uploadId")

        given()
            .contentType("application/octet-stream")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body(byteArrayOf(0, 0, 0, 24))
            .`when`().put("/api/media/uploads/$uploadId/chunks/0")
            .then()
            .statusCode(204)

        given()
            .cookie("LUNVA_ACCESS_TOKEN", otherCookie)
            .`when`().get("/api/media/uploads/$uploadId")
            .then()
            .statusCode(404)

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .`when`().delete("/api/media/uploads/$uploadId")
            .then()
            .statusCode(204)

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/media/uploads/$uploadId")
            .then()
            .statusCode(404)
    }

    @Test
    fun `rejects invalid upload targets and media types`() {
        val file = File.createTempFile("lunva-upload-", ".txt").also {
            it.writeText("plain text")
        }

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .multiPart("albumPath", "/missing")
            .multiPart("file", file, "image/jpeg")
            .`when`().post("/api/$tenantName/media")
            .then()
            .statusCode(404)

        createAlbum(ownerCookie, null, "photos", "Photos")

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .multiPart("albumPath", "/photos")
            .multiPart("file", file, "text/plain")
            .`when`().post("/api/$tenantName/media")
            .then()
            .statusCode(400)

        val empty = File.createTempFile("lunva-upload-empty-", ".jpg")
        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .multiPart("albumPath", "/photos")
            .multiPart("file", empty, "image/jpeg")
            .`when`().post("/api/$tenantName/media")
            .then()
            .statusCode(400)
    }

    private fun createTenant(cookie: String, name: String, displayName: String) {
        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", cookie)
            .body("""{"name":"$name","displayName":"$displayName"}""")
            .`when`().post("/api/tenants")
            .then()
            .statusCode(200)
    }

    private fun createFolder(cookie: String, parentPath: String?, name: String, displayName: String) {
        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", cookie)
            .body("""{"parentPath":${parentPath?.let { "\"$it\"" }},"name":"$name","displayName":"$displayName"}""")
            .`when`().post("/api/$tenantName/folders")
            .then()
            .statusCode(200)
    }

    private fun createAlbum(cookie: String, parentPath: String?, name: String, displayName: String) {
        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", cookie)
            .body("""{"parentPath":${parentPath?.let { "\"$it\"" }},"name":"$name","displayName":"$displayName"}""")
            .`when`().post("/api/$tenantName/albums")
            .then()
            .statusCode(200)
    }

    private fun addMedia(cookie: String, albumPath: String, mediaId: String) {
        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", cookie)
            .body("""{"albumPath":"$albumPath","mediaId":"$mediaId"}""")
            .`when`().post("/api/$tenantName/albums/media")
            .then()
            .statusCode(200)
    }

    private fun login(email: String, password: String): String {
        val accessCookie = given()
            .contentType("application/json")
            .body("""{"email":"$email","password":"$password"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(200)
            .extract()
            .cookie("LUNVA_ACCESS_TOKEN")

        assertThat(accessCookie)
            .describedAs("Login did not return an access cookie")
            .isNotBlank()
        return accessCookie
    }

    private fun createUser(name: String, password: String, role: ServerRole): User =
        inTransaction {
            User().also {
                it.passwordHash = passwordService.hash(password)
                it.displayName = name
                it.email = "$name@example.com"
                it.role = role
                userRepo.persist(it)
            }
        }.also {
            setupFilter.markSetupCompleted()
        }

    private fun addTenantMember(userName: String) {
        inTransaction {
            val tenant = tenant()
            val user = userRepo.findByEmail("$userName@example.com") ?: throw IllegalStateException("User not found")
            TenantMember().also {
                it.id = TenantMemberId(tenant.id, user.id)
                it.tenant = tenant
                it.user = user
                it.role = TenantRole.TenantMember
                tenantMemberRepo.persist(it)
            }
        }
    }

    private fun createGroupWithMember(groupName: String, userEmail: String): TenantGroup =
        inTransaction {
            val user = userRepo.findByEmail(userEmail) ?: throw IllegalStateException("User not found")
            val group = TenantGroup().also {
                it.tenant = tenant()
                it.name = groupName
                it.displayName = groupName
                tenantGroupRepo.persist(it)
            }
            tenantGroupRepo.flush()
            TenantGroupMember().also {
                it.id = TenantGroupMemberId(group.id, user.id)
                it.group = group
                it.user = user
                it.role = GroupRole.GroupMember
                tenantGroupMemberRepo.persist(it)
            }
            group
        }

    private fun createMedia(ownerEmail: String, title: String): Media =
        inTransaction {
            val owner = userRepo.findByEmail(ownerEmail) ?: throw IllegalStateException("Owner not found")
            Media().also {
                it.tenant = tenant()
                it.owner = owner
                it.title = title
                it.mimeType = "image/jpeg"
                it.sizeBytes = 12
                it.storageKey = "tenant-${tenant().id}/test-${title.lowercase().replace(" ", "-")}/original"
                it.originalFilename = "$title.jpg"
                it.createdAt = Instant.now()
                mediaRepo.persist(it)
            }
        }

    private fun tenant(): Tenant = tenantRepo.findByName(tenantName) ?: throw IllegalStateException("Tenant not found")

    private fun <T> inTransaction(block: () -> T): T {
        transaction.begin()
        return try {
            val result = block()
            transaction.commit()
            result
        } catch (e: Exception) {
            transaction.rollback()
            throw e
        }
    }
}
