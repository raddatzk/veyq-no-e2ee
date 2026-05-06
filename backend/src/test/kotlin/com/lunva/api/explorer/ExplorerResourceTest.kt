package com.lunva.api.library.explorer

import com.lunva.api.auth.PasswordService
import com.lunva.api.domain.GroupRole
import com.lunva.api.domain.LibraryScopeType
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
import com.lunva.api.setup.ServerState
import com.lunva.api.setup.SetupFilter
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.transaction.UserTransaction
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
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
    @Inject lateinit var serverState: ServerState
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
        createUser("owner-$runId", "admin1234567", ServerRole.ServerOwner)
        ownerCookie = login(ownerEmail, "admin1234567")
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
            .body("location.kind", equalTo("FOLDER"))
            .body("location.path", equalTo("/urlaub"))
            .body("breadcrumbs.displayName", equalTo(listOf("Explorer $runId", "Urlaub")))
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
    fun `tenant owner sees member tenant scoped content`() {
        createUser("member-$runId", "member123456", ServerRole.ServerMember)
        addTenantMember("member-$runId")
        val memberCookie = login("member-$runId@example.com", "member123456")
        createFolder(memberCookie, null, "shared", "Shared")

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/$tenantName/explorer")
            .then()
            .statusCode(200)
            .body("folders.name", hasItem("shared"))
    }

    @Test
    fun `group scoped folder is visible to group members and streams descendant media`() {
        createUser("author-$runId", "member123456", ServerRole.ServerMember)
        createUser("viewer-$runId", "member123456", ServerRole.ServerMember)
        createUser("outsider-$runId", "member123456", ServerRole.ServerMember)
        addTenantMember("author-$runId")
        addTenantMember("viewer-$runId")
        addTenantMember("outsider-$runId")
        val authorCookie = login("author-$runId@example.com", "member123456")
        val viewerCookie = login("viewer-$runId@example.com", "member123456")
        val outsiderCookie = login("outsider-$runId@example.com", "member123456")
        val group = createGroupWithMember("shared-$runId", "author-$runId@example.com")
        addGroupMember(group, "viewer-$runId@example.com")
        createFolder(authorCookie, null, "shared", "Shared", """{"type":"GROUP","groupName":"${group.name}"}""")
        createAlbum(authorCookie, "/@${group.name}/shared", "trip", "Trip")
        val media = createMedia("author-$runId@example.com", "Summit", group)
        addMedia(authorCookie, "/@${group.name}/shared/trip", media.publicId)

        given()
            .cookie("LUNVA_ACCESS_TOKEN", viewerCookie)
            .`when`().get("/api/$tenantName/explorer")
            .then()
            .statusCode(200)
            .body("scopeRoots.path", hasItem("/~me"))
            .body("scopeRoots.path", hasItem("/@${group.name}"))
            .body("folders.name", hasItem("shared"))
            .body("folders.path", hasItem("/@${group.name}/shared"))

        given()
            .cookie("LUNVA_ACCESS_TOKEN", viewerCookie)
            .queryParam("path", "/@${group.name}")
            .`when`().get("/api/$tenantName/explorer")
            .then()
            .statusCode(200)
            .body("location.kind", equalTo("GROUP"))
            .body("breadcrumbs.label", equalTo(listOf("Explorer $runId", "@${group.displayName}")))
            .body("scopeRoots.size()", equalTo(0))

        given()
            .cookie("LUNVA_ACCESS_TOKEN", outsiderCookie)
            .`when`().get("/api/$tenantName/explorer")
            .then()
            .statusCode(200)
            .body("scopeRoots.path", hasItem("/~me"))
            .body("scopeRoots.path", org.hamcrest.Matchers.not(hasItem("/@${group.name}")))
            .body("folders.path", org.hamcrest.Matchers.not(hasItem("/@${group.name}/shared")))

        given()
            .cookie("LUNVA_ACCESS_TOKEN", outsiderCookie)
            .queryParam("path", "/@${group.name}")
            .`when`().get("/api/$tenantName/explorer")
            .then()
            .statusCode(404)

        val stream = given()
            .cookie("LUNVA_ACCESS_TOKEN", viewerCookie)
            .queryParam("path", "/@${group.name}/shared")
            .`when`().get("/api/$tenantName/media")
            .then()
            .statusCode(200)
            .contentType(containsString("application/x-ndjson"))
            .extract().body().asString()

        assertThat(stream.lines()).anyMatch { it.contains("Summit") }
    }

    @Test
    fun `settings api resolves tenant group personal folder and album breadcrumbs`() {
        val group = createGroupWithMember("settings-group-$runId", ownerEmail)
        createFolder(ownerCookie, null, "tenant-folder", "Tenant Folder")
        createFolder(ownerCookie, null, "group-folder", "Group Folder", """{"type":"GROUP","groupName":"${group.name}"}""")
        createAlbum(ownerCookie, "/@${group.name}/group-folder", "group-album", "Group Album")
        createAlbum(ownerCookie, null, "private-album", "Private Album", """{"type":"USER"}""")

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .queryParam("path", "/settings")
            .`when`().get("/api/settings")
            .then()
            .statusCode(200)
            .body("location.kind", nullValue())
            .body("location.path", equalTo("/settings"))
            .body("subject.type", equalTo("SERVER"))
            .body("subject.path", nullValue())
            .body("breadcrumbs.label", equalTo(listOf("Einstellungen")))

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .queryParam("path", "/~me")
            .`when`().get("/api/settings")
            .then()
            .statusCode(200)
            .body("location.kind", nullValue())
            .body("location.path", equalTo("/~me"))
            .body("subject.type", equalTo("USER"))
            .body("subject.path", nullValue())
            .body("breadcrumbs.label", equalTo(listOf("~owner-$runId")))

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .queryParam("path", "/$tenantName/settings")
            .`when`().get("/api/settings")
            .then()
            .statusCode(200)
            .body("location.kind", nullValue())
            .body("location.path", equalTo("/$tenantName/settings"))
            .body("subject.type", equalTo("TENANT"))
            .body("subject.path", nullValue())
            .body("breadcrumbs.label", equalTo(listOf("Explorer $runId", "Einstellungen")))

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .queryParam("path", "/$tenantName/@${group.name}/settings")
            .`when`().get("/api/settings")
            .then()
            .statusCode(200)
            .body("subject.type", equalTo("GROUP"))
            .body("subject.path", nullValue())
            .body("breadcrumbs.label", equalTo(listOf("Explorer $runId", "@${group.displayName}", "Einstellungen")))

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .queryParam("path", "/$tenantName/~me/settings")
            .`when`().get("/api/settings")
            .then()
            .statusCode(200)
            .body("subject.type", equalTo("TENANT_USER"))
            .body("subject.path", nullValue())
            .body("breadcrumbs.label", equalTo(listOf("Explorer $runId", "~owner-$runId", "Einstellungen")))

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .queryParam("path", "/$tenantName/tenant-folder/settings")
            .`when`().get("/api/settings")
            .then()
            .statusCode(200)
            .body("subject.type", equalTo("FOLDER"))
            .body("subject.path", nullValue())
            .body("breadcrumbs.label", equalTo(listOf("Explorer $runId", "Tenant Folder", "Einstellungen")))

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .queryParam("path", "/$tenantName/@${group.name}/group-folder/group-album/settings")
            .`when`().get("/api/settings")
            .then()
            .statusCode(200)
            .body("subject.type", equalTo("ALBUM"))
            .body("subject.path", nullValue())
            .body("breadcrumbs.label", equalTo(listOf("Explorer $runId", "@${group.displayName}", "Group Folder", "Group Album", "Einstellungen")))
    }

    @Test
    fun `settings api rejects unsupported paths and hidden group subjects`() {
        createUser("outsider-$runId", "member123456", ServerRole.ServerMember)
        addTenantMember("outsider-$runId")
        val outsiderCookie = login("outsider-$runId@example.com", "member123456")
        val group = createGroupWithMember("hidden-settings-$runId", ownerEmail)

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .queryParam("path", "/$tenantName/@${group.name}")
            .`when`().get("/api/settings")
            .then()
            .statusCode(400)

        given()
            .cookie("LUNVA_ACCESS_TOKEN", outsiderCookie)
            .queryParam("path", "/$tenantName/@${group.name}/settings")
            .`when`().get("/api/settings")
            .then()
            .statusCode(404)

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .queryParam("path", "/settings")
            .`when`().get("/api/$tenantName/settings")
            .then()
            .statusCode(404)
    }

    @Test
    fun `settings APIs enforce server role and update current user`() {
        createUser("member-settings-$runId", "member123456", ServerRole.ServerMember)
        val memberCookie = login("member-settings-$runId@example.com", "member123456")

        given()
            .cookie("LUNVA_ACCESS_TOKEN", memberCookie)
            .`when`().get("/api/settings/server")
            .then()
            .statusCode(403)

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/settings/server")
            .then()
            .statusCode(200)
            .body("userManagementAvailable", equalTo(true))

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"displayName":"Owner Updated","email":"owner-updated-$runId@example.com"}""")
            .`when`().put("/api/users/me")
            .then()
            .statusCode(200)
            .body("displayName", equalTo("Owner Updated"))
            .body("email", equalTo("owner-$runId@example.com"))
            .body("pendingEmail", equalTo("owner-updated-$runId@example.com"))

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"displayName":"Duplicate","email":"member-settings-$runId@example.com"}""")
            .`when`().put("/api/users/me")
            .then()
            .statusCode(409)

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"currentPassword":"wrong1234567","newPassword":"changed123456"}""")
            .`when`().post("/api/users/me/password/change")
            .then()
            .statusCode(401)

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"currentPassword":"admin1234567","newPassword":"changed123456"}""")
            .`when`().post("/api/users/me/password/change")
            .then()
            .statusCode(204)

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/users/me")
            .then()
            .statusCode(200)
            .body("displayName", equalTo("Owner Updated"))
            .body("email", equalTo(ownerEmail))
            .body("pendingEmail", equalTo("owner-updated-$runId@example.com"))
    }

    @Test
    fun `settings APIs enforce tenant and group ownership`() {
        createUser("tenant-member-settings-$runId", "member123456", ServerRole.ServerMember)
        createUser("group-owner-settings-$runId", "member123456", ServerRole.ServerMember)
        createUser("group-member-settings-$runId", "member123456", ServerRole.ServerMember)
        createUser("group-outsider-settings-$runId", "member123456", ServerRole.ServerMember)
        addTenantMember("tenant-member-settings-$runId")
        addTenantMember("group-owner-settings-$runId")
        addTenantMember("group-member-settings-$runId")
        addTenantMember("group-outsider-settings-$runId")
        val tenantMemberCookie = login("tenant-member-settings-$runId@example.com", "member123456")
        val groupOwnerCookie = login("group-owner-settings-$runId@example.com", "member123456")
        val groupMemberCookie = login("group-member-settings-$runId@example.com", "member123456")
        val groupOutsiderCookie = login("group-outsider-settings-$runId@example.com", "member123456")
        val group = createGroupWithMember("secure-settings-$runId", "group-owner-settings-$runId@example.com", GroupRole.GroupOwner)
        addGroupMember(group, "group-member-settings-$runId@example.com")

        given()
            .cookie("LUNVA_ACCESS_TOKEN", tenantMemberCookie)
            .`when`().get("/api/tenants/$tenantName/settings")
            .then()
            .statusCode(200)

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", tenantMemberCookie)
            .body("""{"displayName":"Forbidden Tenant Rename"}""")
            .`when`().put("/api/tenants/$tenantName/settings")
            .then()
            .statusCode(403)

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"displayName":"Renamed Tenant"}""")
            .`when`().put("/api/tenants/$tenantName/settings")
            .then()
            .statusCode(200)
            .body("displayName", equalTo("Renamed Tenant"))

        given()
            .cookie("LUNVA_ACCESS_TOKEN", groupMemberCookie)
            .`when`().get("/api/tenants/$tenantName/${group.name}")
            .then()
            .statusCode(200)
            .body("displayName", equalTo(group.displayName))

        given()
            .cookie("LUNVA_ACCESS_TOKEN", groupOutsiderCookie)
            .`when`().get("/api/tenants/$tenantName/${group.name}")
            .then()
            .statusCode(404)

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", groupMemberCookie)
            .body("""{"displayName":"Forbidden Group Rename"}""")
            .`when`().put("/api/tenants/$tenantName/${group.name}")
            .then()
            .statusCode(403)

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", groupOwnerCookie)
            .body("""{"displayName":"Renamed Group"}""")
            .`when`().put("/api/tenants/$tenantName/${group.name}")
            .then()
            .statusCode(200)
            .body("displayName", equalTo("Renamed Group"))
    }

    @Test
    fun `settings is reserved as folder and album path name`() {
        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"parentPath":null,"name":"settings","displayName":"Settings"}""")
            .`when`().post("/api/$tenantName/folders")
            .then()
            .statusCode(400)

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"parentPath":null,"name":"settings","displayName":"Settings"}""")
            .`when`().post("/api/$tenantName/albums")
            .then()
            .statusCode(400)

        createFolder(ownerCookie, null, "rename-me", "Rename Me")
        createAlbum(ownerCookie, null, "rename-album", "Rename Album")

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"path":"/rename-me","name":"settings","displayName":"Settings"}""")
            .`when`().post("/api/$tenantName/folders/update")
            .then()
            .statusCode(400)

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"path":"/rename-album","name":"settings","displayName":"Settings"}""")
            .`when`().post("/api/$tenantName/albums/update")
            .then()
            .statusCode(400)
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
            .body("uploaderDisplayName", equalTo("owner-$runId"))
            .body("capturedAtOverride", equalTo(false))
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

        createUser("viewer-$runId", "member123456", ServerRole.ServerMember)
        addTenantMember("viewer-$runId")
        val viewerCookie = login("viewer-$runId@example.com", "member123456")

        given()
            .cookie("LUNVA_ACCESS_TOKEN", viewerCookie)
            .`when`().get("/api/$tenantName/media/$mediaId/content")
            .then()
            .statusCode(200)
    }

    @Test
    fun `visible user updates captured date metadata`() {
        createAlbum(ownerCookie, null, "metadata", "Metadata")
        val media = createMedia(ownerEmail, "Metadata")
        addMedia(ownerCookie, "/metadata", media.publicId)
        createUser("viewer-$runId", "member123456", ServerRole.ServerMember)
        addTenantMember("viewer-$runId")
        val viewerCookie = login("viewer-$runId@example.com", "member123456")

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", viewerCookie)
            .body("""{"capturedAt":"2026-02-03T04:05:06Z"}""")
            .`when`().patch("/api/$tenantName/media/${media.publicId}/metadata")
            .then()
            .statusCode(200)
            .body("capturedAt", equalTo("2026-02-03T04:05:06Z"))
            .body("capturedAtOverride", equalTo(true))
            .body("createdAt", notNullValue())
            .body("uploaderDisplayName", equalTo("owner-$runId"))

        val stored = inTransaction { mediaRepo.findByTenantAndPublicId(tenant(), media.publicId) }
        assertThat(stored?.capturedAt).isEqualTo(Instant.parse("2026-02-03T04:05:06Z"))
        assertThat(stored?.capturedAtOverride).isTrue()
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

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .header("Range", "bytes=-4")
            .`when`().get("/api/$tenantName/media/$mediaId/content")
            .then()
            .statusCode(206)
            .header("Content-Range", equalTo("bytes 4-7/8"))

        given()
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .header("Range", "invalid")
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

        val stored = inTransaction { mediaRepo.findByTenantAndPublicId(tenant(), mediaId) }
        assertThat(stored?.scopeType).isEqualTo(LibraryScopeType.Tenant)
    }

    @Test
    fun `chunk completion rejects missing chunks and size mismatches`() {
        createAlbum(ownerCookie, null, "broken-chunks", "Broken Chunks")

        val missingChunkUploadId = createUpload("/broken-chunks", "missing.mp4", 8)
        given()
            .contentType("application/octet-stream")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body(byteArrayOf(0, 0, 0, 24))
            .`when`().put("/api/media/uploads/$missingChunkUploadId/chunks/0")
            .then()
            .statusCode(204)

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .`when`().post("/api/media/uploads/$missingChunkUploadId/complete")
            .then()
            .statusCode(400)

        val sizeMismatchUploadId = createUpload("/broken-chunks", "mismatch.mp4", 8)
        given()
            .contentType("application/octet-stream")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body(byteArrayOf(0, 0, 0, 24))
            .`when`().put("/api/media/uploads/$sizeMismatchUploadId/chunks/0")
            .then()
            .statusCode(204)
        given()
            .contentType("application/octet-stream")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body(byteArrayOf(102, 116, 121))
            .`when`().put("/api/media/uploads/$sizeMismatchUploadId/chunks/1")
            .then()
            .statusCode(204)

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .`when`().post("/api/media/uploads/$sizeMismatchUploadId/complete")
            .then()
            .statusCode(400)
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
        createUser("other-$runId", "member123456", ServerRole.ServerMember)
        addTenantMember("other-$runId")
        val otherCookie = login("other-$runId@example.com", "member123456")

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
            .body("""{"name":"$name","displayName":"$displayName","domain":"$name.example.test"}""")
            .`when`().post("/api/tenants")
            .then()
            .statusCode(200)
    }

    private fun createFolder(
        cookie: String,
        parentPath: String?,
        name: String,
        displayName: String,
        scopeJson: String? = null,
    ) {
        val scope = scopeJson?.let { ""","scope":$it""" } ?: ""
        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", cookie)
            .body("""{"parentPath":${parentPath?.let { "\"$it\"" }},"name":"$name","displayName":"$displayName"$scope}""")
            .`when`().post("/api/$tenantName/folders")
            .then()
            .statusCode(200)
    }

    private fun createAlbum(
        cookie: String,
        parentPath: String?,
        name: String,
        displayName: String,
        scopeJson: String? = null,
    ) {
        val scope = scopeJson?.let { ""","scope":$it""" } ?: ""
        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", cookie)
            .body("""{"parentPath":${parentPath?.let { "\"$it\"" }},"name":"$name","displayName":"$displayName"$scope}""")
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

    private fun createUpload(albumPath: String, filename: String, sizeBytes: Long): String =
        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"tenantName":"$tenantName","albumPath":"$albumPath","filename":"$filename","mimeType":"video/mp4","sizeBytes":$sizeBytes}""")
            .`when`().post("/api/media/uploads")
            .then()
            .statusCode(200)
            .extract().path("uploadId")

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
                it.passwordSetAt = Instant.now()
                userRepo.persist(it)
            }
        }.also {
            serverState.markSetupCompleted()
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
                it.acceptedAt = java.time.Instant.now()
                tenantMemberRepo.persist(it)
            }
        }
    }

    private fun createGroupWithMember(
        groupName: String,
        userEmail: String,
        role: GroupRole = GroupRole.GroupMember,
    ): TenantGroup =
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
                it.role = role
                tenantGroupMemberRepo.persist(it)
            }
            group
        }

    private fun addGroupMember(group: TenantGroup, userEmail: String) {
        inTransaction {
            val user = userRepo.findByEmail(userEmail) ?: throw IllegalStateException("User not found")
            TenantGroupMember().also {
                it.id = TenantGroupMemberId(group.id, user.id)
                it.group = group
                it.user = user
                it.role = GroupRole.GroupMember
                tenantGroupMemberRepo.persist(it)
            }
        }
    }

    private fun createMedia(ownerEmail: String, title: String, group: TenantGroup? = null): Media =
        inTransaction {
            val owner = userRepo.findByEmail(ownerEmail) ?: throw IllegalStateException("Owner not found")
            Media().also {
                it.tenant = tenant()
                it.owner = owner
                if (group == null) {
                    it.scopeType = LibraryScopeType.Tenant
                } else {
                    it.scopeType = LibraryScopeType.Group
                    it.scopeGroup = group
                }
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
