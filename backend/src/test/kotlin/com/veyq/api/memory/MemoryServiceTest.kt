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
import com.veyq.api.generated.model.CreateMemoryRequest as ApiCreateMemoryRequest
import com.veyq.api.generated.model.MemoryScopeRequest
import com.veyq.api.generated.model.UpdateMemoryRequest
import com.veyq.api.generated.model.UpdateMemoryMediaMetadataBatchRequest
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
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.imageio.ImageIO

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
    @Inject lateinit var memoryJournalService: MemoryJournalService
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
        assertThat(memoryService.getMemory(trip.id).startDate).isEqualTo(LocalDate.parse("2026-05-01"))
        assertThat(memoryService.getMemory(trip.id).endDate).isEqualTo(LocalDate.parse("2026-05-03"))
        assertThat(memoryService.getMemory(day.id).startDate).isEqualTo(LocalDate.parse("2026-05-01"))
    }

    @Test
    fun `batch media metadata offset shifts captured time and marks override`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("batch-trip", "Batch Trip"))
        val first = createMedia(
            owner,
            memory(trip.id),
            "First Photo",
            capturedAt = Instant.parse("2026-01-02T10:00:00Z"),
        )
        val second = createMedia(
            owner,
            memory(trip.id),
            "Second Photo",
            capturedAt = Instant.parse("2026-01-02T11:00:00Z"),
        )

        val response = mediaService.updateMemoryMediaMetadataBatch(
            trip.id,
            UpdateMemoryMediaMetadataBatchRequest(
                mediaIds = listOf(first.publicId, second.publicId),
                capturedAtOffsetSeconds = 7200,
            ),
        )

        assertThat(response.media).extracting("id").containsExactly(first.publicId, second.publicId)
        assertThat(response.media).extracting("capturedAtOverride").containsOnly(true)
        assertThat(response.media.map { it.capturedAt?.toInstant() })
            .containsExactly(
                Instant.parse("2026-01-02T12:00:00Z"),
                Instant.parse("2026-01-02T13:00:00Z"),
            )
    }

    @Test
    fun `batch media metadata offset uses created time when captured time is missing`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("batch-fallback-trip", "Batch Fallback Trip"))
        val createdAt = Instant.parse("2026-01-02T10:00:00Z")
        val media = createMedia(owner, memory(trip.id), "Fallback Photo", createdAt = createdAt)

        val response = mediaService.updateMemoryMediaMetadataBatch(
            trip.id,
            UpdateMemoryMediaMetadataBatchRequest(
                mediaIds = listOf(media.publicId),
                capturedAtOffsetSeconds = -3600,
            ),
        )

        assertThat(response.media.single().capturedAt?.toInstant()).isEqualTo(Instant.parse("2026-01-02T09:00:00Z"))
        assertThat(response.media.single().capturedAtOverride).isTrue()
    }

    @Test
    fun `batch media metadata offset rejects empty selection`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("batch-empty-trip", "Batch Empty Trip"))

        assertThatExceptionOfType(BadRequestException::class.java)
            .isThrownBy {
                mediaService.updateMemoryMediaMetadataBatch(
                    trip.id,
                    UpdateMemoryMediaMetadataBatchRequest(mediaIds = emptyList(), capturedAtOffsetSeconds = 60),
                )
            }
    }

    @Test
    fun `batch media metadata offset rejects media outside addressed memory`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("batch-scope-trip", "Batch Scope Trip"))
        val other = memoryService.createMemory(CreateMemoryRequest("other-trip", "Other Trip"))
        val media = createMedia(owner, memory(other.id), "Other Photo")

        assertThatExceptionOfType(NotFoundException::class.java)
            .isThrownBy {
                mediaService.updateMemoryMediaMetadataBatch(
                    trip.id,
                    UpdateMemoryMediaMetadataBatchRequest(
                        mediaIds = listOf(media.publicId),
                        capturedAtOffsetSeconds = 60,
                    ),
                )
            }
    }

    @Test
    fun `memory header separates direct and descendant media counts`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("header-trip", "Header Trip"))
        val day = memoryService.createMemory(CreateMemoryRequest("day-one", "Day One", parentId = trip.id))
        val stop = memoryService.createMemory(CreateMemoryRequest("stop", "Stop", parentId = day.id))
        createMedia(owner, memory(trip.id), "Root Photo")
        createMedia(owner, memory(day.id), "Day Photo")
        createMedia(owner, memory(stop.id), "Stop Photo")

        val header = memoryService.getMemoryHeader(trip.id)

        assertThat(header.id).isEqualTo(trip.id)
        assertThat(header.displayName).isEqualTo("Header Trip")
        assertThat(header.path).isEqualTo("/header-trip")
        assertThat(header.directMediaCount).isEqualTo(1)
        assertThat(header.descendantMediaCount).isEqualTo(2)
        assertThat(header.childMemoryCount).isEqualTo(1)
        assertThat(header.scope.type).isEqualTo(com.veyq.api.generated.model.MemoryScope.Type.TENANT)
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
    fun `updates and validates memory date range`() {
        val memory = memoryService.createMemory(CreateMemoryRequest("dated", "Dated"))

        val updated = memoryService.updateMemory(
            memory.id,
            UpdateMemoryRequest(
                startDate = LocalDate.parse("2026-06-01"),
                endDate = LocalDate.parse("2026-06-07"),
            )
        )

        assertThat(updated.startDate).isEqualTo(LocalDate.parse("2026-06-01"))
        assertThat(updated.endDate).isEqualTo(LocalDate.parse("2026-06-07"))
        assertThatExceptionOfType(BadRequestException::class.java).isThrownBy {
            memoryService.updateMemory(
                memory.id,
                UpdateMemoryRequest(
                    startDate = LocalDate.parse("2026-06-08"),
                    endDate = LocalDate.parse("2026-06-07"),
                )
            )
        }
        assertThatExceptionOfType(BadRequestException::class.java).isThrownBy {
            memoryService.updateMemory(
                memory.id,
                UpdateMemoryRequest(startDate = LocalDate.parse("2026-06-08"))
            )
        }
    }

    @Test
    fun `reads empty journal and updates markdown with visible embeds`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("journal-trip", "Journal Trip"))
        val day = memoryService.createMemory(CreateMemoryRequest("day-one", "Day One", parentId = trip.id))
        val media = createMedia(owner, memory(trip.id), "Journal Photo")
        val secondMedia = createMedia(owner, memory(trip.id), "Journal Photo 2")

        val empty = memoryJournalService.getJournal(memory(trip.id))
        assertThat(empty.memoryId).isEqualTo(trip.id)
        assertThat(empty.markdown).isEmpty()

        val updated = memoryJournalService.updateJournal(
            memory(trip.id),
            """
            # Journal

            ![Photo](veyq:media/${media.publicId})
            ![Carousel](veyq:media/${media.publicId},${secondMedia.publicId})
            [Day One](veyq:memory/${day.id})
            [Map](veyq:map?lat=52.5&lng=13.4&zoom=12)
            """.trimIndent(),
            MemoryJournalService.JournalValidationContext(memory(trip.id)),
        )

        assertThat(updated.memoryId).isEqualTo(trip.id)
        assertThat(updated.markdown).contains("veyq:media/${media.publicId}")
        assertThat(updated.markdown).contains("veyq:media/${media.publicId},${secondMedia.publicId}")
        assertThat(memoryJournalService.getJournal(memory(trip.id)).markdown).isEqualTo(updated.markdown)
    }

    @Test
    fun `rejects journal media embeds outside the current memory`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("journal-private-trip", "Journal Private Trip"))
        val other = memoryService.createMemory(CreateMemoryRequest("journal-other-trip", "Journal Other Trip"))
        val otherMedia = createMedia(owner, memory(other.id), "Other Journal Photo")

        assertThatExceptionOfType(NotFoundException::class.java).isThrownBy {
            memoryJournalService.updateJournal(
                memory(trip.id),
                "![Other](veyq:media/${otherMedia.publicId})",
                MemoryJournalService.JournalValidationContext(memory(trip.id)),
            )
        }
    }

    @Test
    fun `rejects journal carousel embeds when any media is outside the current memory`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("journal-carousel-trip", "Journal Carousel Trip"))
        val other = memoryService.createMemory(CreateMemoryRequest("journal-carousel-other", "Journal Carousel Other"))
        val visibleMedia = createMedia(owner, memory(trip.id), "Visible Journal Photo")
        val otherMedia = createMedia(owner, memory(other.id), "Other Journal Photo")

        assertThatExceptionOfType(NotFoundException::class.java).isThrownBy {
            memoryJournalService.updateJournal(
                memory(trip.id),
                "![Carousel](veyq:media/${visibleMedia.publicId},${otherMedia.publicId})",
                MemoryJournalService.JournalValidationContext(memory(trip.id)),
            )
        }
    }

    @Test
    fun `rejects memory create with end date before start date`() {
        assertThatExceptionOfType(BadRequestException::class.java).isThrownBy {
            memoryService.createMemory(
                CreateMemoryRequest(
                    "invalid-dated",
                    "Invalid Dated",
                    startDate = LocalDate.parse("2026-06-08"),
                    endDate = LocalDate.parse("2026-06-07"),
                )
            )
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

        val covered = setCover(root.id, coverFile("root-cover"), "root-cover.png")
        assertThat(covered.coverMediaId).isNull()
        assertThat(covered.coverUrl).isEqualTo("/api/${tenant.name}/memories/${root.id}/cover/content")
        entityManager.clear()
        val coverResponse = memoryService.coverContent(root.id)
        assertThat(coverResponse.getHeaderString("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThatExceptionOfType(BadRequestException::class.java).isThrownBy {
            setCover(root.id, textFile("not-an-image"), "cover.txt")
        }

        memoryService.deleteMemory(root.id)

        assertThat(memoryRepo.findByTenantAndPublicId(tenant, root.id)).isNull()
        assertThat(memoryRepo.findByTenantAndPublicId(tenant, child.id)).isNull()
        assertThat(mediaRepo.findByTenantAndPublicId(tenant, rootMedia.publicId)).isNull()
        assertThat(mediaRepo.findByTenantAndPublicId(tenant, childMedia.publicId)).isNull()
        assertThat(mediaRepo.findByTenantAndPublicId(tenant, otherMedia.publicId)).isNotNull
    }

    @Test
    fun `standalone cover upload clears existing cover media reference`() {
        val root = memoryService.createMemory(CreateMemoryRequest("cover-root", "Cover Root"))
        val rootEntity = memory(root.id)
        val media = createMedia(owner, rootEntity, "Gallery Cover")
        inTransaction {
            memory(root.id).coverMedia = media
        }

        val covered = setCover(root.id, coverFile("standalone-cover"), "cover.png")

        assertThat(covered.coverMediaId).isNull()
        assertThat(covered.coverUrl).isEqualTo("/api/${tenant.name}/memories/${root.id}/cover/content")
        assertThat(memory(root.id).coverMedia).isNull()
    }

    @Test
    fun `sets cover from existing memory media with crop metadata`() {
        val root = memoryService.createMemory(CreateMemoryRequest("media-cover-root", "Media Cover Root"))
        val media = createMedia(owner, memory(root.id), "Gallery Cover")

        val covered = memoryService.setCover(
            root.id,
            uploadPath = null,
            originalFilename = null,
            mediaId = media.publicId,
            cropX = 0.0,
            cropY = 0.0,
            cropWidth = 1.0,
            cropHeight = 1.0,
        )

        assertThat(covered.coverMediaId).isEqualTo(media.publicId)
        assertThat(covered.coverUrl).contains("/memory-media/${media.publicId}/content")
        assertThat(covered.coverCrop?.x).isEqualTo(0.0)
        assertThat(covered.coverCrop?.width).isEqualTo(1.0)
    }

    @Test
    fun `rejects invalid cover crop metadata`() {
        val root = memoryService.createMemory(CreateMemoryRequest("invalid-cover-root", "Invalid Cover Root"))

        assertThatExceptionOfType(BadRequestException::class.java).isThrownBy {
            memoryService.setCover(
                root.id,
                coverFile("invalid-cover"),
                "cover.png",
                mediaId = null,
                cropX = 0.0,
                cropY = 0.0,
                cropWidth = 0.5,
                cropHeight = 1.0,
            )
        }
    }

    @Test
    fun `keeps existing standalone cover when new cover validation fails`() {
        val root = memoryService.createMemory(CreateMemoryRequest("safe-cover-root", "Safe Cover Root"))
        setCover(root.id, coverFile("safe-cover"), "cover.png")
        val existingKey = memory(root.id).coverStorageKey

        assertThatExceptionOfType(BadRequestException::class.java).isThrownBy {
            memoryService.setCover(
                root.id,
                coverFile("unsafe-cover"),
                "cover.png",
                mediaId = null,
                cropX = 0.0,
                cropY = 0.0,
                cropWidth = 0.5,
                cropHeight = 1.0,
            )
        }

        assertThat(memory(root.id).coverStorageKey).isEqualTo(existingKey)
        assertThat(memoryService.coverContent(root.id).status).isEqualTo(200)
    }

    @Test
    fun `updates crop only for existing standalone cover`() {
        val root = memoryService.createMemory(CreateMemoryRequest("crop-only-root", "Crop Only Root"))
        setCover(root.id, coverFile("crop-only-cover"), "cover.png")
        val existingKey = memory(root.id).coverStorageKey

        val updated = memoryService.setCover(
            root.id,
            uploadPath = null,
            originalFilename = null,
            mediaId = null,
            cropX = 0.0,
            cropY = 0.0,
            cropWidth = 0.5,
            cropHeight = 0.5,
        )

        assertThat(memory(root.id).coverStorageKey).isEqualTo(existingKey)
        assertThat(updated.coverCrop?.width).isEqualTo(0.5)
        assertThat(updated.coverCrop?.height).isEqualTo(0.5)
    }

    @Test
    fun `rejects crop only without existing standalone cover`() {
        val root = memoryService.createMemory(CreateMemoryRequest("missing-crop-only-root", "Missing Crop Only Root"))

        assertThatExceptionOfType(BadRequestException::class.java).isThrownBy {
            memoryService.setCover(
                root.id,
                uploadPath = null,
                originalFilename = null,
                mediaId = null,
                cropX = 0.0,
                cropY = 0.0,
                cropWidth = 1.0,
                cropHeight = 1.0,
            )
        }
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

        assertThat(setCover(ownerMemory.id, coverFile("owner-cover"), "owner.png").coverUrl)
            .isNotBlank()
        assertThat(memoryService.clearCover(ownerMemory.id).coverMediaId).isNull()
        assertThat(memoryService.getMemory(ownerMemory.id).coverUrl).isNull()
        assertThat(memoryService.getMemory(ownerMemory.id).coverMediaId).isNull()

        assertThat(setCover(groupMemory.id, coverFile("group-cover"), "group.png").coverUrl)
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
                it.permalink = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
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

    private fun CreateMemoryRequest(
        name: String,
        displayName: String,
        parentId: String? = null,
        folderPath: String? = null,
        scope: MemoryScopeRequest? = null,
        startDate: LocalDate = LocalDate.parse("2026-05-01"),
        endDate: LocalDate = LocalDate.parse("2026-05-03"),
    ): ApiCreateMemoryRequest =
        ApiCreateMemoryRequest(
            name = name,
            displayName = displayName,
            startDate = startDate,
            endDate = endDate,
            parentId = parentId,
            folderPath = folderPath,
            scope = scope,
        )

    private fun createMedia(
        owner: User,
        memory: Memory,
        title: String,
        capturedAt: Instant? = null,
        createdAt: Instant = Instant.now(),
    ): Media =
        inTransaction {
            Media().also {
                it.tenant = tenant
                it.uploaderUser = owner
                it.scopeType = memory.scopeType
                it.scopeGroup = memory.scopeGroup
                it.scopeUser = memory.scopeUser
                it.memory = memory
                it.title = title
                it.mimeType = "image/jpeg"
                it.sizeBytes = 12
                it.storageKey = "tenant-${tenant.id}/test-${title.lowercase().replace(" ", "-")}/original"
                it.originalFilename = "$title.jpg"
                it.createdAt = createdAt
                it.capturedAt = capturedAt
                it.imageWidth = 16
                it.imageHeight = 9
                mediaRepo.persist(it)
            }
        }

    private fun coverFile(prefix: String): Path {
        val path = Files.createTempFile(prefix, ".png")
        ImageIO.write(BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB), "png", path.toFile())
        return path
    }

    private fun setCover(memoryId: String, path: Path, filename: String) =
        memoryService.setCover(
            memoryId,
            path,
            filename,
            mediaId = null,
            cropX = 0.0,
            cropY = 0.0,
            cropWidth = 1.0,
            cropHeight = 1.0,
        )

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
