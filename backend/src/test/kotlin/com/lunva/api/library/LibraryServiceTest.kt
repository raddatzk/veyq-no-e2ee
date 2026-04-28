package com.lunva.api.library

import com.lunva.api.auth.BadRequestException
import com.lunva.api.auth.ConflictException
import com.lunva.api.auth.PasswordService
import com.lunva.api.domain.AlbumMediaRepo
import com.lunva.api.domain.GroupRole
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
import com.lunva.api.library.album.AlbumService
import com.lunva.api.library.album.AlbumSharesService
import com.lunva.api.library.explorer.ExplorerBreadcrumbType
import com.lunva.api.library.explorer.ExplorerService
import com.lunva.api.library.folder.FolderService
import com.lunva.api.library.folder.FolderSharesService
import com.lunva.api.library.media.MediaService
import com.lunva.api.security.RequestContext
import com.lunva.api.setup.SetupFilter
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
    @Inject lateinit var setupFilter: SetupFilter
    @Inject lateinit var requestContext: RequestContext
    @Inject lateinit var transaction: UserTransaction
    @Inject lateinit var explorerService: ExplorerService
    @Inject lateinit var folderService: FolderService
    @Inject lateinit var albumService: AlbumService
    @Inject lateinit var folderSharesService: FolderSharesService
    @Inject lateinit var albumSharesService: AlbumSharesService
    @Inject lateinit var mediaService: MediaService

    private lateinit var runId: String
    private lateinit var tenant: Tenant
    private lateinit var owner: User

    @BeforeEach
    fun setUp() {
        runId = UUID.randomUUID().toString().take(8)
        owner = createUser("owner-$runId", ServerRole.ServerOwner)
        tenant = createTenant("library-$runId", "Library $runId", owner, TenantRole.TenantOwner)
        setupFilter.markSetupCompleted()
        useContext(owner, tenant)
    }

    @Test
    fun `explorer resolves root folder album contexts and breadcrumbs`() {
        folderService.createFolder(CreateLibraryItemRequest("/", "urlaub", "Urlaub"))
        folderService.createFolder(CreateLibraryItemRequest("/urlaub", "klettern", "Klettern"))
        albumService.createAlbum(CreateLibraryItemRequest("/urlaub/klettern", "dolomiten", "Dolomiten"))

        val root = explorerService.explorer("/")
        assertThat(root.context.type).isEqualTo(ExplorerBreadcrumbType.Tenant)
        assertThat(root.folders).extracting("name").containsExactly("urlaub")
        assertThat(root.albums).isEmpty()

        val folder = explorerService.explorer("/urlaub/klettern")
        assertThat(folder.context.type).isEqualTo(ExplorerBreadcrumbType.Folder)
        assertThat(folder.context.path).isEqualTo("/urlaub/klettern")
        assertThat(folder.breadcrumbs).extracting("name").containsExactly("urlaub", "klettern")
        assertThat(folder.albums).extracting("name").containsExactly("dolomiten")

        val album = explorerService.explorer("/urlaub/klettern/dolomiten")
        assertThat(album.context.type).isEqualTo(ExplorerBreadcrumbType.Album)
        assertThat(album.context.path).isEqualTo("/urlaub/klettern/dolomiten")
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
    fun `tenant owner only sees owned or shared library items`() {
        val member = createUser("member-$runId", ServerRole.ServerMember)
        addTenantMember(member, TenantRole.TenantMember)
        useContext(member, tenant)
        folderService.createFolder(CreateLibraryItemRequest("/", "private", "Private"))

        useContext(owner, tenant)
        val beforeShare = explorerService.explorer("/")
        assertThat(beforeShare.folders).extracting("name").doesNotContain("private")

        useContext(member, tenant)
        folderSharesService.shareFolderWithUser("/private", ShareUserRequest(owner.id))

        useContext(owner, tenant)
        val afterShare = explorerService.explorer("/")
        assertThat(afterShare.folders).extracting("name").contains("private")
    }

    @Test
    fun `folder group share exposes nested folder as root entry and grants recursive media visibility`() {
        val author = createUser("author-$runId", ServerRole.ServerMember)
        val viewer = createUser("viewer-$runId", ServerRole.ServerMember)
        addTenantMember(author, TenantRole.TenantMember)
        addTenantMember(viewer, TenantRole.TenantMember)

        useContext(author, tenant)
        folderService.createFolder(CreateLibraryItemRequest("/", "hidden-parent", "Hidden Parent"))
        folderService.createFolder(CreateLibraryItemRequest("/hidden-parent", "shared", "Shared"))
        albumService.createAlbum(CreateLibraryItemRequest("/hidden-parent/shared", "trip", "Trip"))
        val media = createMedia(author, "Summit")
        albumService.addMediaToAlbum(AlbumMediaRequest("/hidden-parent/shared/trip", media.publicId, false, 0))

        val group = createGroup("shared-$runId", viewer)
        folderSharesService.shareFolderWithGroup("/hidden-parent/shared", ShareGroupRequest(group.id))

        useContext(viewer, tenant)
        val root = explorerService.explorer("/")
        assertThat(root.folders).extracting("name").contains("shared")
        assertThat(root.folders).extracting("name").doesNotContain("hidden-parent")

        val stream = mediaService.streamMedia("/hidden-parent/shared").toList()
        assertThat(stream).extracting("title").containsExactly("Summit")
        assertThat(stream.single().albumPath).isEqualTo("/hidden-parent/shared/trip")
    }

    @Test
    fun `album shares grant direct visibility without exposing parent folder`() {
        val author = createUser("album-author-$runId", ServerRole.ServerMember)
        val viewer = createUser("album-viewer-$runId", ServerRole.ServerMember)
        addTenantMember(author, TenantRole.TenantMember)
        addTenantMember(viewer, TenantRole.TenantMember)

        useContext(author, tenant)
        folderService.createFolder(CreateLibraryItemRequest("/", "private-parent", "Private Parent"))
        albumService.createAlbum(CreateLibraryItemRequest("/private-parent", "shared-album", "Shared Album"))
        albumSharesService.shareAlbumWithUser("/private-parent/shared-album", ShareUserRequest(viewer.id))

        useContext(viewer, tenant)
        val root = explorerService.explorer("/")
        assertThat(root.albums).extracting("name").contains("shared-album")
        assertThat(root.folders).extracting("name").doesNotContain("private-parent")
        assertThat(explorerService.explorer("/private-parent/shared-album").context.displayName).isEqualTo("Shared Album")
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
                it.passwordHash = passwordService.hash("password123")
                it.displayName = name
                it.email = "$name@example.com"
                it.role = role
                userRepo.persist(it)
            }
        }

    private fun createTenant(name: String, displayName: String, user: User, role: TenantRole): Tenant =
        inTransaction {
            val tenant = Tenant().also {
                it.name = name
                it.displayName = displayName
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

    private fun createMedia(owner: User, title: String): Media =
        inTransaction {
            Media().also {
                it.tenant = tenant
                it.owner = owner
                it.title = title
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
