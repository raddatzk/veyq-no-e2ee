package com.lunva.api.library

import com.lunva.api.auth.BadRequestException
import com.lunva.api.auth.ConflictException
import com.lunva.api.auth.NotFoundException
import com.lunva.api.auth.PasswordService
import com.lunva.api.domain.AlbumMediaRepo
import com.lunva.api.domain.GroupRole
import com.lunva.api.domain.LibraryScopeType
import com.lunva.api.domain.Media
import com.lunva.api.domain.MediaRepo
import com.lunva.api.domain.ServerRole
import com.lunva.api.domain.Tenant
import com.lunva.api.domain.TenantAlbumRepo
import com.lunva.api.domain.TenantFolderRepo
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
import com.lunva.api.generated.model.LocationKind
import com.lunva.api.generated.model.AlbumMediaRequest
import com.lunva.api.generated.model.CreateLibraryItemRequest as GeneratedCreateLibraryItemRequest
import com.lunva.api.generated.model.LibraryScopeRequest
import com.lunva.api.generated.model.MoveLibraryItemRequest
import com.lunva.api.generated.model.SetAlbumCoverRequest
import com.lunva.api.generated.model.UpdateLibraryItemRequest
import com.lunva.api.library.album.AlbumService
import com.lunva.api.library.explorer.ExplorerService
import com.lunva.api.library.folder.FolderService
import com.lunva.api.library.media.MediaService
import com.lunva.api.auth.filterchain.RequestContext
import com.lunva.api.setup.ServerState
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.UserTransaction
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@QuarkusTest
class LibraryServiceTest {
    @Inject lateinit var userRepo: UserRepo
    @Inject lateinit var tenantRepo: TenantRepo
    @Inject lateinit var tenantMemberRepo: TenantMemberRepo
    @Inject lateinit var tenantGroupRepo: TenantGroupRepo
    @Inject lateinit var tenantGroupMemberRepo: TenantGroupMemberRepo
    @Inject lateinit var folderRepo: TenantFolderRepo
    @Inject lateinit var albumRepo: TenantAlbumRepo
    @Inject lateinit var albumMediaRepo: AlbumMediaRepo
    @Inject lateinit var mediaRepo: MediaRepo
    @Inject lateinit var passwordService: PasswordService
    @Inject lateinit var serverState: ServerState
    @Inject lateinit var requestContext: RequestContext
    @Inject lateinit var transaction: UserTransaction
    @Inject lateinit var explorerService: ExplorerService
    @Inject lateinit var folderService: FolderService
    @Inject lateinit var albumService: AlbumService
    @Inject lateinit var mediaService: MediaService

    private lateinit var runId: String
    private lateinit var tenant: Tenant
    private lateinit var owner: User

    private fun CreateLibraryItemRequest(
        parentPath: String,
        name: String,
        displayName: String,
        scope: LibraryScopeRequest? = null,
    ): GeneratedCreateLibraryItemRequest =
        GeneratedCreateLibraryItemRequest(
            name = name,
            displayName = displayName,
            parentPath = parentPath,
            scope = scope,
        )

    @BeforeEach
    fun setUp() {
        runId = UUID.randomUUID().toString().take(8)
        owner = createUser("owner-$runId", ServerRole.ServerOwner)
        tenant = createTenant("library-$runId", "Library $runId", owner, TenantRole.TenantOwner)
        serverState.markSetupCompleted()
        useContext(owner, tenant)
    }

    @Test
    fun `explorer resolves root folder album contexts and breadcrumbs`() {
        folderService.createFolder(CreateLibraryItemRequest("/", "urlaub", "Urlaub"))
        folderService.createFolder(CreateLibraryItemRequest("/urlaub", "klettern", "Klettern"))
        albumService.createAlbum(CreateLibraryItemRequest("/urlaub/klettern", "dolomiten", "Dolomiten"))

        val root = explorerService.explorer("/")
        assertThat(root.location.kind).isEqualTo(LocationKind.TENANT)
        assertThat(root.breadcrumbs).extracting("kind").containsExactly(LocationKind.TENANT)
        assertThat(root.folders).extracting("name").containsExactly("urlaub")
        assertThat(root.albums).isEmpty()

        val folder = explorerService.explorer("/urlaub/klettern")
        assertThat(folder.location.kind).isEqualTo(LocationKind.FOLDER)
        assertThat(folder.location.path).isEqualTo("/urlaub/klettern")
        assertThat(folder.breadcrumbs).extracting("displayName").containsExactly("Library $runId", "Urlaub", "Klettern")
        assertThat(folder.albums).extracting("name").containsExactly("dolomiten")

        val album = explorerService.explorer("/urlaub/klettern/dolomiten")
        assertThat(album.location.kind).isEqualTo(LocationKind.ALBUM)
        assertThat(album.location.path).isEqualTo("/urlaub/klettern/dolomiten")
        assertThat(album.breadcrumbs.last().path).isEqualTo(album.location.path)
        assertThat(album.folders).isEmpty()
        assertThat(album.albums).isEmpty()
    }

    @Test
    fun `create and update reject invalid names album parents and sibling collisions across folders and albums`() {
        folderService.createFolder(CreateLibraryItemRequest("/", "urlaub", "Urlaub"))
        albumService.createAlbum(CreateLibraryItemRequest("/urlaub", "klettern", "Klettern"))

        assertThatExceptionOfType(ConflictException::class.java).isThrownBy {
            folderService.createFolder(CreateLibraryItemRequest("/urlaub", "klettern", "Klettern Folder"))
        }
        assertThatExceptionOfType(BadRequestException::class.java).isThrownBy {
            folderService.createFolder(CreateLibraryItemRequest("/", "Invalid Name", "Invalid"))
        }
        assertThatExceptionOfType(BadRequestException::class.java).isThrownBy {
            albumService.createAlbum(CreateLibraryItemRequest("/urlaub/klettern", "bilder", "Bilder"))
        }
        folderService.createFolder(CreateLibraryItemRequest("/urlaub", "fotos", "Fotos"))
        assertThatExceptionOfType(ConflictException::class.java).isThrownBy {
            albumService.updateAlbum(UpdateLibraryItemRequest("/urlaub/klettern", "fotos", null))
        }
    }

    @Test
    fun `move prevents folder cycles and sibling collisions`() {
        folderService.createFolder(CreateLibraryItemRequest("/", "parent", "Parent"))
        folderService.createFolder(CreateLibraryItemRequest("/parent", "child", "Child"))
        folderService.createFolder(CreateLibraryItemRequest("/", "target", "Target"))
        albumService.createAlbum(CreateLibraryItemRequest("/target", "child", "Child Album"))

        assertThatExceptionOfType(BadRequestException::class.java).isThrownBy {
            folderService.moveFolder(MoveLibraryItemRequest("/parent", "/parent/child", null))
        }
        assertThatExceptionOfType(ConflictException::class.java).isThrownBy {
            folderService.moveFolder(MoveLibraryItemRequest("/parent/child", "/target", null))
        }

        val moved = folderService.moveFolder(MoveLibraryItemRequest("/parent/child", "/", "renamed"))
        assertThat(moved.path).isEqualTo("/renamed")
        assertThat(explorerService.explorer("/").folders).extracting("name").containsExactlyInAnyOrder("parent", "renamed", "target")
    }

    @Test
    fun `root mixes visible scopes and group marker disambiguates tenant folders`() {
        val group = createGroup("familie-$runId", owner)
        folderService.createFolder(CreateLibraryItemRequest("/", group.name, "Tenant Familie"))
        folderService.createFolder(CreateLibraryItemRequest("/", "urlaub", "Gruppenurlaub", LibraryScopeRequest("GROUP", group.name)))
        albumService.createAlbum(CreateLibraryItemRequest("/", "privat", "Privat", LibraryScopeRequest("USER")))

        val root = explorerService.explorer("/")
        assertThat(root.scopeRoots).extracting("path").contains("/~me", "/@${group.name}")
        assertThat(root.folders).extracting("path").contains("/${group.name}", "/@${group.name}/urlaub")
        assertThat(root.albums).extracting("path").contains("/~me/privat")
        assertThat(explorerService.explorer("/${group.name}").location.displayName).isEqualTo("Tenant Familie")
        assertThat(explorerService.explorer("/@${group.name}/urlaub").location.displayName).isEqualTo("Gruppenurlaub")
        assertThat(explorerService.explorer("/@${group.name}").scopeRoots).isEmpty()

        assertThatExceptionOfType(BadRequestException::class.java).isThrownBy {
            folderService.moveFolder(MoveLibraryItemRequest("/${group.name}", "/@${group.name}", null))
        }
    }

    @Test
    fun `group roots require membership and child items inherit parent scope`() {
        val author = createUser("scope-author-$runId", ServerRole.ServerMember)
        val outsider = createUser("scope-outsider-$runId", ServerRole.ServerMember)
        addTenantMember(author, TenantRole.TenantMember)
        addTenantMember(outsider, TenantRole.TenantMember)
        val group = createGroup("scope-$runId", author)

        useContext(outsider, tenant)
        assertThatExceptionOfType(NotFoundException::class.java).isThrownBy {
            folderService.createFolder(CreateLibraryItemRequest("/", "blocked", "Blocked", LibraryScopeRequest("GROUP", group.name)))
        }

        useContext(author, tenant)
        folderService.createFolder(CreateLibraryItemRequest("/", "group-root", "Group Root", LibraryScopeRequest("GROUP", group.name)))
        val child = albumService.createAlbum(
            CreateLibraryItemRequest("/@${group.name}/group-root", "child", "Child", LibraryScopeRequest("TENANT"))
        )

        assertThat(child.path).isEqualTo("/@${group.name}/group-root/child")
        assertThat(albumRepo.findByPublicId(child.id)?.scopeType).isEqualTo(LibraryScopeType.Group)
    }

    @Test
    fun `tenant members see and update tenant scoped library items`() {
        val member = createUser("member-$runId", ServerRole.ServerMember)
        addTenantMember(member, TenantRole.TenantMember)
        useContext(member, tenant)
        folderService.createFolder(CreateLibraryItemRequest("/", "shared", "Shared"))

        useContext(owner, tenant)
        val root = explorerService.explorer("/")
        assertThat(root.folders).extracting("name").contains("shared")

        val updated = folderService.updateFolder(UpdateLibraryItemRequest("/shared", null, "Updated"))
        assertThat(updated.displayName).isEqualTo("Updated")
    }

    @Test
    fun `group scoped folders are visible to group members and grant media visibility`() {
        val author = createUser("author-$runId", ServerRole.ServerMember)
        val viewer = createUser("viewer-$runId", ServerRole.ServerMember)
        addTenantMember(author, TenantRole.TenantMember)
        addTenantMember(viewer, TenantRole.TenantMember)
        val outsider = createUser("outsider-$runId", ServerRole.ServerMember)
        addTenantMember(outsider, TenantRole.TenantMember)
        val group = createGroup("shared-$runId", author)
        addGroupMember(group, viewer)

        useContext(author, tenant)
        folderService.createFolder(CreateLibraryItemRequest("/", "shared", "Shared", LibraryScopeRequest("GROUP", group.name)))
        albumService.createAlbum(CreateLibraryItemRequest("/@${group.name}/shared", "trip", "Trip"))
        val media = createMedia(author, "Summit", group)
        albumService.addMediaToAlbum(AlbumMediaRequest("/@${group.name}/shared/trip", media.publicId, false, 0))

        useContext(viewer, tenant)
        val root = explorerService.explorer("/")
        assertThat(root.scopeRoots).extracting("path").contains("/@${group.name}")
        assertThat(root.folders).extracting("name").contains("shared")
        assertThat(root.folders.single { it.name == "shared" }.path).isEqualTo("/@${group.name}/shared")

        val stream = mediaService.streamMedia("/@${group.name}/shared").toList()
        assertThat(stream).extracting("title").containsExactly("Summit")
        assertThat(stream.single().albumPath).isEqualTo("/@${group.name}/shared/trip")

        useContext(outsider, tenant)
        assertThat(explorerService.explorer("/").scopeRoots).extracting("path").doesNotContain("/@${group.name}")
        assertThat(explorerService.explorer("/").folders).extracting("name").doesNotContain("shared")
    }

    @Test
    fun `personal scoped albums are visible only to that user`() {
        val author = createUser("album-author-$runId", ServerRole.ServerMember)
        val viewer = createUser("album-viewer-$runId", ServerRole.ServerMember)
        addTenantMember(author, TenantRole.TenantMember)
        addTenantMember(viewer, TenantRole.TenantMember)

        useContext(author, tenant)
        albumService.createAlbum(CreateLibraryItemRequest("/", "private-album", "Private Album", LibraryScopeRequest("USER")))
        assertThat(explorerService.explorer("/").albums).extracting("name").contains("private-album")

        useContext(viewer, tenant)
        assertThat(explorerService.explorer("/").albums).extracting("name").doesNotContain("private-album")
    }

    @Test
    fun `album cover links hidden media by default and excludes it from regular media stream`() {
        albumService.createAlbum(CreateLibraryItemRequest("/", "photos", "Photos"))
        val visible = createMedia(owner, "Visible")
        val cover = createMedia(owner, "Cover")

        albumService.addMediaToAlbum(AlbumMediaRequest("/photos", visible.publicId, false, 1))
        val album = albumService.setAlbumCover(SetAlbumCoverRequest("/photos", cover.publicId, null))

        assertThat(album.coverMediaId).isEqualTo(cover.publicId)
        val stream = mediaService.streamMedia("/photos").toList()
        assertThat(stream).extracting("title").containsExactly("Visible")

        val coverLink = albumMediaRepo.findByAlbumAndMedia(
            albumRepo.findByPublicId(album.id) ?: error("Album not found"),
            cover,
        )
        assertThat(coverLink).isNotNull
        assertThat(coverLink!!.hidden).isTrue()
    }

    @Test
    fun `media from another scope cannot be linked or used as cover`() {
        val group = createGroup("media-scope-$runId", owner)
        albumService.createAlbum(CreateLibraryItemRequest("/", "tenant-album", "Tenant Album"))
        val groupMedia = createMedia(owner, "Group Media", group)

        assertThatExceptionOfType(NotFoundException::class.java).isThrownBy {
            albumService.addMediaToAlbum(AlbumMediaRequest("/tenant-album", groupMedia.publicId, false, 0))
        }
        assertThatExceptionOfType(NotFoundException::class.java).isThrownBy {
            albumService.setAlbumCover(SetAlbumCoverRequest("/tenant-album", groupMedia.publicId, null))
        }
    }

    @Test
    fun `delete album and folder remove structure links but preserve media rows`() {
        folderService.createFolder(CreateLibraryItemRequest("/", "folder", "Folder"))
        albumService.createAlbum(CreateLibraryItemRequest("/folder", "album", "Album"))
        val media = createMedia(owner, "Keep Me")
        albumService.addMediaToAlbum(AlbumMediaRequest("/folder/album", media.publicId, false, 0))

        albumService.deleteAlbum("/folder/album")
        assertThat(albumRepo.listByTenant(tenant)).extracting("name").doesNotContain("album")
        assertThat(mediaRepo.findByTenantAndPublicId(tenant, media.publicId)).isNotNull

        albumService.createAlbum(CreateLibraryItemRequest("/folder", "second", "Second"))
        val second = createMedia(owner, "Keep Me Too")
        albumService.addMediaToAlbum(AlbumMediaRequest("/folder/second", second.publicId, false, 0))
        folderService.deleteFolder("/folder")

        assertThat(folderRepo.listByTenant(tenant)).extracting("name").doesNotContain("folder")
        assertThat(albumRepo.listByTenant(tenant)).extracting("name").doesNotContain("second")
        assertThat(mediaRepo.findByTenantAndPublicId(tenant, second.publicId)).isNotNull
    }

    private fun useContext(user: User, tenant: Tenant) {
        requestContext.user = user
        requestContext.tenant = tenant
    }

    private fun createUser(name: String, role: ServerRole): User =
        inTransaction {
            User().also {
                it.passwordHash = passwordService.hash("password1234")
                it.displayName = name
                it.email = "$name@example.com"
                it.role = role
                it.passwordSetAt = Instant.now()
                userRepo.persist(it)
            }
        }

    private fun createTenant(name: String, displayName: String, user: User, role: TenantRole): Tenant =
        inTransaction {
            val tenant = Tenant().also {
                it.name = name
                it.displayName = displayName
                it.domain = "$name.example.test"
                tenantRepo.persist(it)
            }
            tenantRepo.flush()
            TenantMember().also {
                it.id = TenantMemberId(tenant.id, user.id)
                it.tenant = tenant
                it.user = user
                it.role = role
                tenantMemberRepo.persist(it)
            }
            tenant
        }

    private fun addTenantMember(user: User, role: TenantRole) {
        inTransaction {
            TenantMember().also {
                it.id = TenantMemberId(tenant.id, user.id)
                it.tenant = tenant
                it.user = user
                it.role = role
                tenantMemberRepo.persist(it)
            }
        }
    }

    private fun createGroup(name: String, user: User): TenantGroup =
        inTransaction {
            val group = TenantGroup().also {
                it.tenant = tenant
                it.name = name
                it.displayName = name
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

    private fun addGroupMember(group: TenantGroup, user: User) {
        inTransaction {
            TenantGroupMember().also {
                it.id = TenantGroupMemberId(group.id, user.id)
                it.group = group
                it.user = user
                it.role = GroupRole.GroupMember
                tenantGroupMemberRepo.persist(it)
            }
        }
    }

    private fun createMedia(owner: User, title: String, group: TenantGroup? = null): Media =
        inTransaction {
            Media().also {
                it.tenant = tenant
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
                it.storageKey = "tenant-${tenant.id}/test-${title.lowercase().replace(" ", "-")}/original"
                it.originalFilename = "$title.jpg"
                it.createdAt = Instant.now()
                mediaRepo.persist(it)
            }
        }

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
