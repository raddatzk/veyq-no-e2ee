package com.veyq.api.memory

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ConflictException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.auth.PasswordService
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.domain.GroupRole
import com.veyq.api.domain.LibraryScopeType
import com.veyq.api.domain.Media
import com.veyq.api.domain.MediaRepo
import com.veyq.api.domain.Memory
import com.veyq.api.domain.MemoryRepo
import com.veyq.api.domain.ServerRole
import com.veyq.api.domain.Tenant
import com.veyq.api.domain.TenantGroup
import com.veyq.api.domain.TenantGroupMember
import com.veyq.api.domain.TenantGroupMemberId
import com.veyq.api.domain.TenantGroupMemberRepo
import com.veyq.api.domain.TenantGroupRepo
import com.veyq.api.domain.TenantMember
import com.veyq.api.domain.TenantMemberId
import com.veyq.api.domain.TenantMemberRepo
import com.veyq.api.domain.TenantRepo
import com.veyq.api.domain.TenantRole
import com.veyq.api.domain.User
import com.veyq.api.domain.UserRepo
import com.veyq.api.generated.model.CreateFolderRequest
import com.veyq.api.generated.model.CreateMemoryRequest
import com.veyq.api.generated.model.MemoryScopeRequest
import com.veyq.api.generated.model.UpdateMemoryRequest
import com.veyq.api.library.folder.FolderService
import com.veyq.api.library.media.MediaService
import com.veyq.api.setup.ServerState
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.UserTransaction
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import java.util.UUID

@QuarkusTest
class MemoryServiceTest {
    @Inject lateinit var userRepo: UserRepo
    @Inject lateinit var tenantRepo: TenantRepo
    @Inject lateinit var tenantMemberRepo: TenantMemberRepo
    @Inject lateinit var tenantGroupRepo: TenantGroupRepo
    @Inject lateinit var tenantGroupMemberRepo: TenantGroupMemberRepo
    @Inject lateinit var memoryRepo: MemoryRepo
    @Inject lateinit var mediaRepo: MediaRepo
    @Inject lateinit var passwordService: PasswordService
    @Inject lateinit var serverState: ServerState
    @Inject lateinit var requestContext: RequestContext
    @Inject lateinit var transaction: UserTransaction
    @Inject lateinit var entityManager: EntityManager
    @Inject lateinit var memoryService: MemoryService
    @Inject lateinit var mediaService: MediaService
    @Inject lateinit var folderService: FolderService

    private lateinit var runId: String
    private lateinit var tenant: Tenant
    private lateinit var owner: User

    @BeforeEach
    fun setUp() {
        runId = UUID.randomUUID().toString().take(8)
        owner = createUser("memory-owner-$runId", ServerRole.ServerOwner)
        tenant = createTenant("memory-$runId", "Memory $runId", owner, TenantRole.TenantOwner)
        serverState.markSetupCompleted()
        useContext(owner, tenant)
    }

    @Test
    fun `creates root and child memories and lists descendant media by taken time`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("trip", "Trip"))
        val day = memoryService.createMemory(CreateMemoryRequest("day-one", "Day One", parentId = trip.id))
        val tripEntity = memory(trip.id)
        val dayEntity = memory(day.id)
        val rootMedia = createMedia(owner, tripEntity, "Root Photo", capturedAt = Instant.parse("2026-01-02T10:00:00Z"))
        val childMedia = createMedia(owner, dayEntity, "Child Photo", capturedAt = Instant.parse("2026-01-01T10:00:00Z"))

        assertThat(memoryService.listRootMemories()).extracting("path").containsExactly("/trip")
        assertThat(memoryService.listChildren(trip.id)).extracting("path").containsExactly("/trip/day-one")
        assertThat(mediaService.listMemoryMedia(trip.id, includeChildren = false)).extracting("id")
            .containsExactly(rootMedia.publicId)
        assertThat(mediaService.listMemoryMedia(trip.id, includeChildren = true)).extracting("id")
            .containsExactly(childMedia.publicId, rootMedia.publicId)
    }

    @Test
    fun `creates and resolves memories by folder path`() {
        folderService.createFolder(CreateFolderRequest("travel", "Travel"))
        folderService.createFolder(CreateFolderRequest("italy", "Italy", parentPath = "/travel"))

        val trip = memoryService.createMemory(CreateMemoryRequest("dolomites", "Dolomites", folderPath = "/travel/italy"))
        val day = memoryService.createMemory(CreateMemoryRequest("day-one", "Day One", parentId = trip.id))

        assertThat(memoryService.listRootMemories()).extracting("id").doesNotContain(trip.id)
        assertThat(memoryService.listMemoriesByFolderPath("/travel/italy")).extracting("path")
            .containsExactly("/travel/italy/dolomites")
        assertThat(memoryService.getMemoryByPath("/travel/italy/dolomites").id).isEqualTo(trip.id)
        assertThat(memoryService.getMemoryByPath("/travel/italy/dolomites/day-one").id).isEqualTo(day.id)
    }

    @Test
    fun `folder paths preserve group and personal root scopes`() {
        val group = createGroup("folder-group-$runId", owner)
        val groupMemory = memoryService.createMemory(
            CreateMemoryRequest("shared-trip", "Shared Trip", folderPath = "/@${group.name}")
        )
        val personalMemory = memoryService.createMemory(
            CreateMemoryRequest("private-trip", "Private Trip", folderPath = "/~me")
        )

        assertThat(groupMemory.path).isEqualTo("/@${group.name}/shared-trip")
        assertThat(personalMemory.path).isEqualTo("/~me/private-trip")
        assertThat(memoryService.listMemoriesByFolderPath("/")).extracting("id")
            .doesNotContain(groupMemory.id, personalMemory.id)
        assertThat(memoryService.listMemoriesByFolderPath("/@${group.name}")).extracting("id")
            .containsExactly(groupMemory.id)
        assertThat(memoryService.listMemoriesByFolderPath("/~me")).extracting("id")
            .containsExactly(personalMemory.id)
    }

    @Test
    fun `group memories require membership and children inherit parent scope`() {
        val author = createUser("memory-author-$runId", ServerRole.ServerMember)
        val viewer = createUser("memory-viewer-$runId", ServerRole.ServerMember)
        val outsider = createUser("memory-outsider-$runId", ServerRole.ServerMember)
        addTenantMember(author, TenantRole.TenantMember)
        addTenantMember(viewer, TenantRole.TenantMember)
        addTenantMember(outsider, TenantRole.TenantMember)
        val group = createGroup("memory-group-$runId", author)
        addGroupMember(group, viewer)

        useContext(outsider, tenant)
        assertThatExceptionOfType(NotFoundException::class.java).isThrownBy {
            memoryService.createMemory(
                CreateMemoryRequest("blocked", "Blocked", scope = MemoryScopeRequest("GROUP", group.name))
            )
        }

        useContext(author, tenant)
        val shared = memoryService.createMemory(
            CreateMemoryRequest("shared-trip", "Shared Trip", scope = MemoryScopeRequest("GROUP", group.name))
        )
        val child = memoryService.createMemory(
            CreateMemoryRequest("shared-day", "Shared Day", parentId = shared.id, scope = MemoryScopeRequest("TENANT"))
        )

        assertThat(child.path).isEqualTo("/@${group.name}/shared-trip/shared-day")
        assertThat(memory(child.id).scopeType).isEqualTo(LibraryScopeType.Group)

        useContext(viewer, tenant)
        assertThat(memoryService.listRootMemories()).extracting("id").contains(shared.id)

        useContext(outsider, tenant)
        assertThat(memoryService.listRootMemories()).extracting("id").doesNotContain(shared.id)
        assertThatExceptionOfType(NotFoundException::class.java).isThrownBy {
            memoryService.getMemory(shared.id)
        }
    }

    @Test
    fun `prevents sibling collisions cycles and cross-scope moves`() {
        val parent = memoryService.createMemory(CreateMemoryRequest("parent", "Parent"))
        val child = memoryService.createMemory(CreateMemoryRequest("child", "Child", parentId = parent.id))
        memoryService.createMemory(CreateMemoryRequest("sibling", "Sibling", parentId = parent.id))
        val group = createGroup("move-group-$runId", owner)
        val groupMemory = memoryService.createMemory(
            CreateMemoryRequest("group-root", "Group Root", scope = MemoryScopeRequest("GROUP", group.name))
        )

        assertThatExceptionOfType(ConflictException::class.java).isThrownBy {
            memoryService.createMemory(CreateMemoryRequest("child", "Duplicate", parentId = parent.id))
        }
        assertThatExceptionOfType(BadRequestException::class.java).isThrownBy {
            memoryService.updateMemory(parent.id, UpdateMemoryRequest(parentId = child.id))
        }
        assertThatExceptionOfType(BadRequestException::class.java).isThrownBy {
            memoryService.updateMemory(child.id, UpdateMemoryRequest(parentId = groupMemory.id))
        }
    }

    @Test
    fun `uploads standalone covers and deletes descendant memories and media`() {
        val root = memoryService.createMemory(CreateMemoryRequest("root", "Root"))
        val child = memoryService.createMemory(CreateMemoryRequest("child", "Child", parentId = root.id))
        val other = memoryService.createMemory(CreateMemoryRequest("other", "Other"))
        val rootMedia = createMedia(owner, memory(root.id), "Root Cover")
        val childMedia = createMedia(owner, memory(child.id), "Child Media")
        val otherMedia = createMedia(owner, memory(other.id), "Other Media")

        val covered = memoryService.setCover(root.id, coverFile("root-cover"), "root-cover.png")
        assertThat(covered.coverMediaId).isNull()
        assertThat(covered.coverUrl).isEqualTo("/api/${tenant.name}/memories/${root.id}/cover/content")
        entityManager.clear()
        val coverResponse = memoryService.coverContent(root.id)
        assertThat(coverResponse.getHeaderString("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThatExceptionOfType(BadRequestException::class.java).isThrownBy {
            memoryService.setCover(root.id, textFile("not-an-image"), "cover.txt")
        }

        memoryService.deleteMemory(root.id)

        assertThat(memoryRepo.findByTenantAndPublicId(tenant, root.id)).isNull()
        assertThat(memoryRepo.findByTenantAndPublicId(tenant, child.id)).isNull()
        assertThat(mediaRepo.findByTenantAndPublicId(tenant, rootMedia.publicId)).isNull()
        assertThat(mediaRepo.findByTenantAndPublicId(tenant, childMedia.publicId)).isNull()
        assertThat(mediaRepo.findByTenantAndPublicId(tenant, otherMedia.publicId)).isNotNull
    }

    @Test
    fun `clears memory cover only for visible memories`() {
        val ownerMemory = memoryService.createMemory(CreateMemoryRequest("owner-root", "Owner Root"))
        val outsider = createUser("cover-outsider-$runId", ServerRole.ServerMember)
        addTenantMember(outsider, TenantRole.TenantMember)
        val group = createGroup("cover-group-$runId", owner)
        val groupMemory = memoryService.createMemory(
            CreateMemoryRequest("group-root", "Group Root", scope = MemoryScopeRequest("GROUP", group.name))
        )

        assertThat(memoryService.setCover(ownerMemory.id, coverFile("owner-cover"), "owner.png").coverUrl)
            .isNotBlank()
        assertThat(memoryService.clearCover(ownerMemory.id).coverMediaId).isNull()
        assertThat(memoryService.getMemory(ownerMemory.id).coverUrl).isNull()
        assertThat(memoryService.getMemory(ownerMemory.id).coverMediaId).isNull()

        assertThat(memoryService.setCover(groupMemory.id, coverFile("group-cover"), "group.png").coverUrl)
            .isNotBlank()
        useContext(outsider, tenant)
        assertThatExceptionOfType(NotFoundException::class.java).isThrownBy {
            memoryService.clearCover(groupMemory.id)
        }
    }

    private fun useContext(user: User, tenant: Tenant) {
        requestContext.user = user
        requestContext.tenant = tenant
    }

    private fun memory(publicId: String): Memory =
        memoryRepo.findByTenantAndPublicId(tenant, publicId) ?: error("Memory $publicId not found")

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
                it.acceptedAt = Instant.now()
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
                it.acceptedAt = Instant.now()
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

    private fun createMedia(
        owner: User,
        memory: Memory,
        title: String,
        capturedAt: Instant? = null,
    ): Media =
        inTransaction {
            Media().also {
                it.tenant = tenant
                it.owner = owner
                it.scopeType = memory.scopeType
                it.scopeGroup = memory.scopeGroup
                it.scopeUser = memory.scopeUser
                it.memory = memory
                it.title = title
                it.mimeType = "image/jpeg"
                it.sizeBytes = 12
                it.storageKey = "tenant-${tenant.id}/test-${title.lowercase().replace(" ", "-")}/original"
                it.originalFilename = "$title.jpg"
                it.createdAt = Instant.now()
                it.capturedAt = capturedAt
                mediaRepo.persist(it)
            }
        }

    private fun coverFile(prefix: String): Path {
        val path = Files.createTempFile(prefix, ".png")
        Files.write(
            path,
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
            )
        )
        return path
    }

    private fun textFile(prefix: String): Path {
        val path = Files.createTempFile(prefix, ".txt")
        Files.writeString(path, "not an image")
        return path
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
