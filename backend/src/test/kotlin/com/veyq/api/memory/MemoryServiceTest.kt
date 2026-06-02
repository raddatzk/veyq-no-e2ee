package com.veyq.api.memory

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ConflictException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.auth.PasswordService
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.domain.GroupRole
import com.veyq.api.domain.LibraryScopeType
import com.veyq.api.domain.Media
import com.veyq.api.domain.MediaBlob
import com.veyq.api.domain.MediaRepo
import com.veyq.api.domain.Memory
import com.veyq.api.domain.MemoryRepo
import com.veyq.api.domain.MemoryTrack
import com.veyq.api.domain.MemoryTrackActivityType
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
import com.veyq.api.generated.model.CreateMemoryMediaBucketRequest
import com.veyq.api.generated.model.CreateMemoryMediaUploadRequest
import com.veyq.api.generated.model.CreateMemoryPlaylistRequest
import com.veyq.api.generated.model.CreateMemoryRequest as ApiCreateMemoryRequest
import com.veyq.api.generated.model.DeleteMemoryMediaBatchRequest
import com.veyq.api.generated.model.MemoryScopeRequest
import com.veyq.api.generated.model.MoveMemoryMediaRequest
import com.veyq.api.generated.model.SearchResultSummary
import com.veyq.api.generated.model.UpdateMemoryRequest
import com.veyq.api.generated.model.UpdateMemoryMediaCapturedAtRequest
import com.veyq.api.generated.model.UpdateMemoryMediaMetadataBatchRequest
import com.veyq.api.generated.model.UpdateMemoryPlaylistRequest
import com.veyq.api.library.playlist.MemoryPlaylistService
import com.veyq.api.library.LibraryDeletionService
import com.veyq.api.library.folder.FolderService
import com.veyq.api.library.media.MediaConfig
import com.veyq.api.library.media.MediaService
import com.veyq.api.library.media.MediaService.Companion.SELF_PRIMARY_BUCKET_PLACEHOLDER_ID
import com.veyq.api.setup.ServerState
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.UserTransaction
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.MultivaluedMap
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.jboss.resteasy.reactive.multipart.FileUpload
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.imageio.ImageIO
import jakarta.ws.rs.core.StreamingOutput
import java.util.zip.ZipInputStream

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
    @Inject lateinit var memoryNavigationService: MemoryNavigationService
    @Inject lateinit var memoryJournalService: MemoryJournalService
    @Inject lateinit var searchService: SearchService
    @Inject lateinit var memoryPlaylistService: MemoryPlaylistService
    @Inject lateinit var mediaService: MediaService
    @Inject lateinit var mediaConfig: MediaConfig
    @Inject lateinit var folderService: FolderService
    @Inject lateinit var libraryDeletionService: LibraryDeletionService

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
    fun `creates updates and deletes memory playlist with single provider link`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("playlist-trip", "Playlist Trip"))
        val playlist = memoryPlaylistService.createMemoryPlaylist(
            trip.id,
            CreateMemoryPlaylistRequest(
                title = "Roadtrip Soundtrack",
                note = "Songs from the van",
                url = "https://www.youtube.com/playlist?list=PL1234567890abcdef",
            ),
        )

        assertThat(playlist.title).isEqualTo("Roadtrip Soundtrack")
        assertThat(playlist.provider.name).isEqualTo("YOUTUBE")
        assertThat(playlist.url).isEqualTo("https://www.youtube.com/playlist?list=PL1234567890abcdef")
        assertThat(playlist.externalId).isEqualTo("PL1234567890abcdef")

        val metadataOnly = memoryPlaylistService.updateMemoryPlaylist(
            trip.id,
            playlist.id,
            UpdateMemoryPlaylistRequest(title = "Roadtrip Mix", note = "Updated", featured = true),
        )

        assertThat(metadataOnly.provider.name).isEqualTo("YOUTUBE")
        assertThat(metadataOnly.url).isEqualTo("https://www.youtube.com/playlist?list=PL1234567890abcdef")
        assertThat(metadataOnly.featured).isTrue()

        val relinked = memoryPlaylistService.updateMemoryPlaylist(
            trip.id,
            playlist.id,
            UpdateMemoryPlaylistRequest(
                title = "Roadtrip Mix",
                note = "Updated",
                url = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M",
            ),
        )

        assertThat(relinked.provider.name).isEqualTo("SPOTIFY")
        assertThat(relinked.externalId).isEqualTo("37i9dQZF1DXcBWIGoYBM5M")

        memoryPlaylistService.deleteMemoryPlaylist(trip.id, playlist.id)

        assertThatExceptionOfType(NotFoundException::class.java)
            .isThrownBy { memoryPlaylistService.updateMemoryPlaylist(trip.id, playlist.id, UpdateMemoryPlaylistRequest(title = "Gone")) }
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
        assertThat(memoryService.listParticipants(trip.id).map { it.userId }).containsExactly(owner.id)
        assertThat(memoryService.listParticipants(day.id).map { it.userId }).containsExactly(owner.id)
        assertThat(mediaService.listMemoryMedia(trip.id, includeChildren = false)).extracting("id")
            .containsExactly(rootMedia.publicId)
        assertThat(mediaService.listMemoryMedia(trip.id, includeChildren = true)).extracting("id")
            .containsExactly(childMedia.publicId, rootMedia.publicId)
        assertThat(mediaService.listMemoryMediaBuckets(trip.id, includeChildren = false).map { it.memoryId to it.mediaCount })
            .containsExactly(trip.id to 1)
        assertThat(mediaService.listMemoryMediaBuckets(trip.id, includeChildren = true).map { it.memoryId to it.mediaCount })
            .containsExactlyInAnyOrder(trip.id to 1, day.id to 1)
        assertThat(memoryService.getMemory(trip.id).startDate).isEqualTo(LocalDate.parse("2026-05-01"))
        assertThat(memoryService.getMemory(trip.id).endDate).isEqualTo(LocalDate.parse("2026-05-03"))
        assertThat(memoryService.getMemory(day.id).startDate).isEqualTo(LocalDate.parse("2026-05-01"))
    }

    @Test
    fun `lists updated memory media after updated cursor`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("cursor-trip", "Cursor Trip"))
        val day = memoryService.createMemory(CreateMemoryRequest("cursor-day", "Cursor Day", parentId = trip.id))
        val other = memoryService.createMemory(CreateMemoryRequest("cursor-other", "Cursor Other"))
        val first = createMedia(
            owner,
            memory(trip.id),
            "First",
            createdAt = Instant.parse("2026-01-02T10:00:00Z"),
        )
        val second = createMedia(
            owner,
            memory(trip.id),
            "Second",
            createdAt = Instant.parse("2026-01-02T10:01:00Z"),
        )
        val child = createMedia(
            owner,
            memory(day.id),
            "Child",
            createdAt = Instant.parse("2026-01-02T10:02:00Z"),
        )
        createMedia(
            owner,
            memory(other.id),
            "Other",
            createdAt = Instant.parse("2026-01-02T10:03:00Z"),
        )
        val firstCursor = mediaService.listMemoryMedia(trip.id, true).first { it.id == first.publicId }

        assertThat(
            mediaService.listUpdatedMemoryMedia(
                memoryId = trip.id,
                includeChildren = true,
                afterUpdatedAt = firstCursor.updatedAt.toInstant(),
                afterMediaId = first.publicId,
            )
        ).extracting("id").containsExactly(second.publicId, child.publicId)
    }

    @Test
    fun `favorites are private to the current tenant user and appear in navigation`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("favorite-trip", "Favorite Trip"))
        val day = memoryService.createMemory(CreateMemoryRequest("favorite-day", "Favorite Day", parentId = trip.id))
        val otherUser = createUser("favorite-other-$runId", ServerRole.ServerMember)
        addTenantMember(otherUser, TenantRole.TenantMember)

        assertThat(memoryService.getMemory(trip.id).favorite).isFalse()
        assertThat(memoryService.getMemoryHeader(trip.id).favorite).isFalse()

        assertThat(memoryService.favoriteMemory(trip.id).favorite).isTrue()
        Thread.sleep(5)
        assertThat(memoryService.favoriteMemory(day.id).favorite).isTrue()

        val navigation = memoryNavigationService.navigation("/")
        assertThat(navigation.favoriteMemories).extracting("id").containsExactly(day.id, trip.id)
        assertThat(memoryService.getMemory(trip.id).favorite).isTrue()
        assertThat(memoryService.getMemoryHeader(trip.id).favorite).isTrue()

        useContext(otherUser, tenant)
        assertThat(memoryService.getMemory(trip.id).favorite).isFalse()
        assertThat(memoryNavigationService.navigation("/").favoriteMemories).isEmpty()

        assertThat(memoryService.favoriteMemory(trip.id).favorite).isTrue()
        assertThat(memoryNavigationService.navigation("/").favoriteMemories).extracting("id").containsExactly(trip.id)

        assertThat(memoryService.unfavoriteMemory(trip.id).favorite).isFalse()
        assertThat(memoryService.unfavoriteMemory(trip.id).favorite).isFalse()
        assertThat(memoryNavigationService.navigation("/").favoriteMemories).isEmpty()

        useContext(owner, tenant)
        assertThat(memoryService.getMemory(trip.id).favorite).isTrue()
        assertThat(memoryNavigationService.navigation("/").favoriteMemories).extracting("id").containsExactly(day.id, trip.id)
    }

    @Test
    fun `favorites reject memories outside the current visible tenant scope`() {
        val group = createGroup("favorite-private-group-$runId", owner)
        val groupMemory = memoryService.createMemory(
            CreateMemoryRequest("favorite-group", "Favorite Group", scope = MemoryScopeRequest("GROUP", group.name))
        )
        val outsider = createUser("favorite-outsider-$runId", ServerRole.ServerMember)
        addTenantMember(outsider, TenantRole.TenantMember)

        useContext(outsider, tenant)
        assertThatExceptionOfType(NotFoundException::class.java).isThrownBy {
            memoryService.favoriteMemory(groupMemory.id)
        }

        useContext(owner, tenant)
        val otherTenantOwner = createUser("favorite-other-tenant-$runId", ServerRole.ServerMember)
        val otherTenant = createTenant("favorite-other-$runId", "Other Favorite $runId", otherTenantOwner, TenantRole.TenantOwner)
        useContext(otherTenantOwner, otherTenant)
        assertThatExceptionOfType(NotFoundException::class.java).isThrownBy {
            memoryService.favoriteMemory(groupMemory.id)
        }
    }

    @Test
    fun `adds lists and removes tenant memory participants`() {
        val participant = createUser("participant-$runId", ServerRole.ServerMember)
        addTenantMember(participant, TenantRole.TenantMember)
        inTransaction {
            userRepo.findById(participant.id)!!.also {
                it.avatarStorageKey = "users/${participant.id}/avatar.png"
                it.avatarContentType = "image/png"
                it.avatarUpdatedAt = Instant.parse("2026-05-27T10:00:00Z")
            }
        }
        val trip = memoryService.createMemory(CreateMemoryRequest("participant-trip", "Participant Trip"))

        val added = memoryService.addParticipant(trip.id, participant.id)

        assertThat(added.displayName).isEqualTo(participant.displayName)
        assertThat(memoryService.listParticipants(trip.id).map { it.userId }).containsExactly(owner.id, participant.id)
        assertThat(memoryService.listParticipants(trip.id).single { it.userId == participant.id }.avatarUrl)
            .isEqualTo("/api/${tenant.name}/profiles/${participant.permalink}/avatar?v=2026-05-27T10%3A00%3A00Z")
        assertThat(memoryService.getMemoryHeader(trip.id).participants.map { it.userId }).containsExactly(owner.id, participant.id)

        memoryService.removeParticipant(trip.id, participant.id)

        assertThat(memoryService.listParticipants(trip.id).map { it.userId }).containsExactly(owner.id)
    }

    @Test
    fun `participant eligibility follows group and personal memory scope`() {
        val groupMember = createUser("group-participant-$runId", ServerRole.ServerMember)
        val outsider = createUser("group-outsider-$runId", ServerRole.ServerMember)
        addTenantMember(groupMember, TenantRole.TenantMember)
        addTenantMember(outsider, TenantRole.TenantMember)
        val group = createGroup("participants-$runId", owner)
        addGroupMember(group, groupMember)
        val groupMemory = memoryService.createMemory(
            CreateMemoryRequest("group-memory", "Group Memory", scope = MemoryScopeRequest("GROUP", group.name))
        )

        assertThat(memoryService.listParticipantCandidates(groupMemory.id).map { it.userId })
            .contains(owner.id, groupMember.id)
            .doesNotContain(outsider.id)
        assertThatExceptionOfType(NotFoundException::class.java).isThrownBy {
            memoryService.addParticipant(groupMemory.id, outsider.id)
        }

        val personalMemory = memoryService.createMemory(
            CreateMemoryRequest("personal-memory", "Personal Memory", scope = MemoryScopeRequest("USER", null))
        )
        assertThat(memoryService.listParticipantCandidates(personalMemory.id).map { it.userId }).containsExactly(owner.id)
        assertThatExceptionOfType(NotFoundException::class.java).isThrownBy {
            memoryService.addParticipant(personalMemory.id, groupMember.id)
        }
    }

    @Test
    fun `participant profile memories return visible roots only`() {
        val participant = createUser("profile-participant-$runId", ServerRole.ServerMember)
        addTenantMember(participant, TenantRole.TenantMember)
        val trip = memoryService.createMemory(CreateMemoryRequest("profile-trip", "Profile Trip"))
        val day = memoryService.createMemory(CreateMemoryRequest("profile-day", "Profile Day", parentId = trip.id))
        memoryService.addParticipant(day.id, participant.id)

        assertThat(memoryService.listParticipantRootMemories(participant).map { it.id }).containsExactly(trip.id)
    }

    @Test
    fun `downloads original media as zip with optional descendants and unique filenames`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("download-trip", "Download Trip"))
        val day = memoryService.createMemory(CreateMemoryRequest("day-one", "Day One", parentId = trip.id))
        val otherUploader = createUser("memory-uploader-$runId", ServerRole.ServerMember)
        val rootCaptured = createMedia(
            owner,
            memory(trip.id),
            "Captured Photo",
            capturedAt = Instant.parse("2026-01-01T09:00:00Z"),
            createdAt = Instant.parse("2026-01-05T10:00:00Z"),
        )
        val rootMedia = createMedia(
            owner,
            memory(trip.id),
            "Same Name",
            createdAt = Instant.parse("2026-01-02T10:00:00Z"),
            cameraMake = "Canon",
            cameraModel = "R6",
        )
        val rootDuplicate = createMedia(
            otherUploader,
            memory(trip.id),
            "Same Name",
            createdAt = Instant.parse("2026-01-02T10:00:00Z"),
            cameraMake = "Nikon",
            cameraModel = "Z6",
        )
        val rootCaseDuplicate = createMedia(
            owner,
            memory(trip.id),
            "same name",
            createdAt = Instant.parse("2026-01-02T10:00:00Z"),
            cameraMake = "Sony",
            cameraModel = "A7",
        )
        val rootExistingSuffix = createMedia(owner, memory(trip.id), "Same Name-2", createdAt = Instant.parse("2026-01-02T10:00:00Z"))
        val rootWithoutExtension = createMedia(
            owner,
            memory(trip.id),
            "Browser Upload",
            createdAt = Instant.parse("2026-01-02T10:30:00Z"),
            originalFilename = "Browser Upload",
        )
        val rootFallback = createMedia(
            owner,
            memory(trip.id),
            " ",
            createdAt = Instant.parse("2026-01-02T11:00:00Z"),
            originalFilename = null,
        )
        val childMedia = createMedia(owner, memory(day.id), "Same Name", createdAt = Instant.parse("2026-01-03T10:00:00Z"))
        writeStoredMedia(rootCaptured, "root-captured-original")
        writeStoredMedia(rootMedia, "root-original")
        writeStoredMedia(rootDuplicate, "root-duplicate-original")
        writeStoredMedia(rootCaseDuplicate, "root-case-duplicate-original")
        writeStoredMedia(rootExistingSuffix, "root-existing-suffix-original")
        writeStoredMedia(rootWithoutExtension, "root-without-extension-original")
        writeStoredMedia(rootFallback, "root-fallback-original")
        writeStoredMedia(childMedia, "child-original")

        val originalResponse = mediaService.streamOriginal(rootWithoutExtension, rangeHeader = null)
        assertThat(originalResponse.getHeaderString("Content-Disposition")).contains("Browser Upload.jpg")
        assertThat(originalResponse.getHeaderString("Cache-Control")).isEqualTo("private, max-age=31536000, immutable")
        val rangeResponse = mediaService.streamOriginal(rootWithoutExtension, rangeHeader = "bytes=0-3")
        assertThat(rangeResponse.getHeaderString("Cache-Control")).isEqualTo("private, max-age=31536000, immutable")

        val directResponse = mediaService.downloadMemoryMedia(trip.id, includeChildren = false, locale = "de")
        assertThat(directResponse.getHeaderString("Content-Disposition")).contains("Download Trip.zip")
        val directEntries = zipEntries(directResponse.entity)
        assertThat(directEntries).containsExactly(
            "Download Trip/2026-01-01 09-00-00 Captured Photo.jpg" to "root-captured-original",
            "Download Trip/2026-01-02 10-00-00 Same Name.jpg" to "root-original",
            "Download Trip/2026-01-02 10-00-00 Same Name-2.jpg" to "root-duplicate-original",
            "Download Trip/2026-01-02 10-00-00 same name-3.jpg" to "root-case-duplicate-original",
            "Download Trip/2026-01-02 10-00-00 Same Name-2-2.jpg" to "root-existing-suffix-original",
            "Download Trip/2026-01-02 10-30-00 Browser Upload.jpg" to "root-without-extension-original",
            "Download Trip/2026-01-02 11-00-00 datei-${rootFallback.publicId}.jpg" to "root-fallback-original",
        )

        val subtreeResponse = mediaService.downloadMemoryMedia(trip.id, includeChildren = true, locale = "en")
        assertThat(subtreeResponse.getHeaderString("Content-Disposition")).contains("Download Trip-with-chapters.zip")
        val subtreeEntries = zipEntries(subtreeResponse.entity)
        assertThat(subtreeEntries).containsExactly(
            "Download Trip/2026-01-01 09-00-00 Captured Photo.jpg" to "root-captured-original",
            "Download Trip/2026-01-02 10-00-00 Same Name.jpg" to "root-original",
            "Download Trip/2026-01-02 10-00-00 Same Name-2.jpg" to "root-duplicate-original",
            "Download Trip/2026-01-02 10-00-00 same name-3.jpg" to "root-case-duplicate-original",
            "Download Trip/2026-01-02 10-00-00 Same Name-2-2.jpg" to "root-existing-suffix-original",
            "Download Trip/2026-01-02 10-30-00 Browser Upload.jpg" to "root-without-extension-original",
            "Download Trip/2026-01-02 11-00-00 file-${rootFallback.publicId}.jpg" to "root-fallback-original",
            "Download Trip/Day One/2026-01-03 10-00-00 Same Name.jpg" to "child-original",
        )
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
    fun `legacy media listing creates primary bucket for uploader and assigns media`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("bucket-legacy-trip", "Bucket Legacy Trip"))
        val media = createMedia(owner, memory(trip.id), "Legacy Photo")

        val buckets = mediaService.listMemoryMediaBuckets(trip.id)
        val listedMedia = mediaService.listMemoryMedia(trip.id, includeChildren = false)

        assertThat(buckets).hasSize(1)
        assertThat(buckets.single().primary).isTrue()
        assertThat(buckets.single().displayName).isEqualTo(owner.displayName)
        assertThat(buckets.single().mediaCount).isEqualTo(1)
        assertThat(listedMedia.single().id).isEqualTo(media.publicId)
        assertThat(listedMedia.single().bucketId).isEqualTo(buckets.single().id)
    }

    @Test
    fun `bucket listing does not create empty primary bucket for current user before first upload`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("bucket-empty-trip", "Bucket Empty Trip"))

        val buckets = mediaService.listMemoryMediaBuckets(trip.id)

        assertThat(buckets).isEmpty()
        assertThat(mediaService.listMemoryMediaBuckets(trip.id)).isEmpty()
    }

    @Test
    fun `bucket listing does not expose another users empty primary bucket after read`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("bucket-other-empty-trip", "Bucket Other Empty Trip"))
        val otherUser = createUser("bucket-other-$runId", ServerRole.ServerMember)
        addTenantMember(otherUser, TenantRole.TenantMember)

        assertThat(mediaService.listMemoryMediaBuckets(trip.id)).isEmpty()
        useContext(otherUser, tenant)

        assertThat(mediaService.listMemoryMediaBuckets(trip.id)).isEmpty()
    }

    @Test
    fun `bucket listing updates primary user bucket display name after profile rename`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("bucket-rename-trip", "Bucket Rename Trip"))
        createMedia(owner, memory(trip.id), "Bucket Rename Photo")
        val original = mediaService.listMemoryMediaBuckets(trip.id).single()

        inTransaction {
            owner = userRepo.findById(owner.id) ?: error("Owner not found")
            owner.displayName = "Renamed Owner $runId"
        }
        useContext(owner, tenant)

        val buckets = mediaService.listMemoryMediaBuckets(trip.id)

        assertThat(buckets).hasSize(1)
        assertThat(buckets.single().id).isEqualTo(original.id)
        assertThat(buckets.single().displayName).isEqualTo(owner.displayName)
        assertThat(buckets.single().uploader.displayName).isEqualTo(owner.displayName)
    }

    @Test
    fun `subbucket creation with self primary placeholder creates primary bucket lazily`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("bucket-placeholder-trip", "Bucket Placeholder Trip"))

        val subbucket = mediaService.createMemoryMediaBucket(
            trip.id,
            CreateMemoryMediaBucketRequest(parentBucketId = SELF_PRIMARY_BUCKET_PLACEHOLDER_ID, displayName = "Phone imports"),
        )

        val buckets = mediaService.listMemoryMediaBuckets(trip.id)
        val primary = buckets.single { it.primary }
        assertThat(subbucket.primary).isFalse()
        assertThat(subbucket.displayName).isEqualTo("Phone imports")
        assertThat(subbucket.parentBucketId).isEqualTo(primary.id)
        assertThat(primary.displayName).isEqualTo(owner.displayName)
        assertThat(buckets.map { it.displayName }).containsExactlyInAnyOrder(owner.displayName, "Phone imports")
    }

    @Test
    fun `bucket listing with children hides redundant empty current memory primary bucket`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("bucket-tree-trip", "Bucket Tree Trip"))
        val day = memoryService.createMemory(CreateMemoryRequest("bucket-tree-day", "Bucket Tree Day", parentId = trip.id))
        createMedia(owner, memory(day.id), "Child Photo")

        val buckets = mediaService.listMemoryMediaBuckets(trip.id, includeChildren = true)

        assertThat(buckets).hasSize(1)
        assertThat(buckets.single().memoryId).isEqualTo(day.id)
        assertThat(buckets.single().displayName).isEqualTo(owner.displayName)
        assertThat(buckets.single().mediaCount).isEqualTo(1)
    }

    @Test
    fun `moves selected media into subbucket and deletes selected media`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("bucket-move-trip", "Bucket Move Trip"))
        val first = createMedia(owner, memory(trip.id), "First Photo")
        val second = createMedia(owner, memory(trip.id), "Second Photo")
        val primary = mediaService.listMemoryMediaBuckets(trip.id).single { it.primary }
        val subbucket = mediaService.createMemoryMediaBucket(
            trip.id,
            CreateMemoryMediaBucketRequest(parentBucketId = primary.id, displayName = "Phone imports"),
        )

        val moved = mediaService.moveMemoryMedia(
            trip.id,
            MoveMemoryMediaRequest(mediaIds = listOf(first.publicId), targetBucketId = subbucket.id),
        )

        assertThat(moved.media.single().bucketId).isEqualTo(subbucket.id)
        assertThat(mediaService.listMemoryMediaBuckets(trip.id).map { it.displayName to it.mediaCount })
            .contains(
                owner.displayName to 1,
                "Phone imports" to 1,
            )

        mediaService.deleteMemoryMediaBatch(trip.id, DeleteMemoryMediaBatchRequest(mediaIds = listOf(second.publicId)))

        assertThat(mediaRepo.findByTenantAndPublicId(tenant, first.publicId)).isNotNull
        assertThat(mediaRepo.findByTenantAndPublicId(tenant, second.publicId)).isNull()
    }

    @Test
    fun `deleting subbucket deletes contained media and keeps primary bucket`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("bucket-delete-trip", "Bucket Delete Trip"))
        val media = createMedia(owner, memory(trip.id), "Subbucket Photo")
        val primary = mediaService.listMemoryMediaBuckets(trip.id).single { it.primary }
        val subbucket = mediaService.createMemoryMediaBucket(
            trip.id,
            CreateMemoryMediaBucketRequest(parentBucketId = primary.id, displayName = "Drone"),
        )
        mediaService.moveMemoryMedia(trip.id, MoveMemoryMediaRequest(listOf(media.publicId), subbucket.id))

        mediaService.deleteMemoryMediaBucket(trip.id, subbucket.id)

        assertThat(mediaRepo.findByTenantAndPublicId(tenant, media.publicId)).isNull()
        assertThat(mediaService.listMemoryMediaBuckets(trip.id)).extracting("id").containsExactly(primary.id)
    }

    @Test
    fun `deleting primary bucket deletes media and subbuckets but keeps primary bucket`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("bucket-delete-primary-trip", "Bucket Delete Primary Trip"))
        val primaryMedia = createMedia(owner, memory(trip.id), "Primary Photo")
        val subbucketMedia = createMedia(owner, memory(trip.id), "Subbucket Photo")
        val primary = mediaService.listMemoryMediaBuckets(trip.id).single { it.primary }
        val subbucket = mediaService.createMemoryMediaBucket(
            trip.id,
            CreateMemoryMediaBucketRequest(parentBucketId = primary.id, displayName = "Phone"),
        )
        mediaService.moveMemoryMedia(trip.id, MoveMemoryMediaRequest(listOf(subbucketMedia.publicId), subbucket.id))

        mediaService.deleteMemoryMediaBucket(trip.id, primary.id)

        assertThat(mediaRepo.findByTenantAndPublicId(tenant, primaryMedia.publicId)).isNull()
        assertThat(mediaRepo.findByTenantAndPublicId(tenant, subbucketMedia.publicId)).isNull()
        val remaining = mediaService.listMemoryMediaBuckets(trip.id).single()
        assertThat(remaining.id).isEqualTo(primary.id)
        assertThat(remaining.primary).isTrue()
        assertThat(remaining.mediaCount).isEqualTo(0)
    }

    @Test
    fun `rejects nested media buckets below subbuckets`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("bucket-nested-trip", "Bucket Nested Trip"))
        createMedia(owner, memory(trip.id), "Primary Photo")
        val primary = mediaService.listMemoryMediaBuckets(trip.id).single { it.primary }
        val subbucket = mediaService.createMemoryMediaBucket(
            trip.id,
            CreateMemoryMediaBucketRequest(parentBucketId = primary.id, displayName = "Phone"),
        )

        assertThatExceptionOfType(BadRequestException::class.java)
            .isThrownBy {
                mediaService.createMemoryMediaBucket(
                    trip.id,
                    CreateMemoryMediaBucketRequest(parentBucketId = subbucket.id, displayName = "Nested"),
                )
            }
    }

    @Test
    fun `duplicate uploads in same tenant reuse blob but keep separate media assets`() {
        val firstMemory = memoryService.createMemory(CreateMemoryRequest("dedupe-one", "Dedupe One"))
        val secondMemory = memoryService.createMemory(CreateMemoryRequest("dedupe-two", "Dedupe Two"))
        val upload = imageFile("dedupe-same-tenant")

        val first = mediaService.uploadMemoryMedia(firstMemory.id, TestFileUpload(upload, "same.png"), "First")
        val second = mediaService.uploadMemoryMedia(secondMemory.id, TestFileUpload(upload, "same.png"), "Second")

        assertThat(first.thumbnailUrl).endsWith("/thumbnail")
        assertThat(first.previewUrl).endsWith("/preview")
        assertThat(first.contentUrl).endsWith("/content")
        assertThat(first.thumbnailUrl).isNotEqualTo(first.previewUrl)
        assertThat(first.previewUrl).isNotEqualTo(first.contentUrl)
        val firstMedia = mediaRepo.findByTenantAndPublicId(tenant, first.id)!!
        val secondMedia = mediaRepo.findByTenantAndPublicId(tenant, second.id)!!
        assertThat(firstMedia.id).isNotEqualTo(secondMedia.id)
        assertThat(firstMedia.blob.id).isEqualTo(secondMedia.blob.id)
        assertThat(blobCount(tenant)).isEqualTo(1)
    }

    @Test
    fun `video summaries expose poster url and poster response is cacheable`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("video-poster-trip", "Video Poster Trip"))
        val media = createMedia(
            owner = owner,
            memory = memory(trip.id),
            title = "Clip",
            originalFilename = "clip.mp4",
            mimeType = "video/mp4",
        )
        val posterStorageKey = "tenant-${tenant.id}/test-${UUID.randomUUID()}/poster.jpg"
        inTransaction {
            media.blob.posterStorageKey = posterStorageKey
        }
        val posterPath = mediaConfig.storageDir.resolve(posterStorageKey)
        Files.createDirectories(posterPath.parent)
        Files.writeString(posterPath, "poster")

        val summary = mediaService.listMemoryMedia(trip.id, includeChildren = false).single()
        assertThat(summary.posterUrl).endsWith("/poster")

        val response = mediaService.streamPoster(media)
        assertThat(response.getHeaderString("Cache-Control")).isEqualTo("private, max-age=31536000, immutable")
        assertThat(response.getHeaderString("Content-Disposition")).contains("poster.jpg")
    }

    @Test
    fun `duplicate uploads across tenants do not share blobs`() {
        val firstMemory = memoryService.createMemory(CreateMemoryRequest("tenant-dedupe-one", "Tenant Dedupe One"))
        val otherOwner = createUser("other-tenant-owner-$runId", ServerRole.ServerOwner)
        val otherTenant = createTenant("other-memory-$runId", "Other Memory $runId", otherOwner, TenantRole.TenantOwner)
        val upload = imageFile("dedupe-cross-tenant")
        val first = mediaService.uploadMemoryMedia(firstMemory.id, TestFileUpload(upload, "same.png"), "First")

        useContext(otherOwner, otherTenant)
        val secondMemory = memoryService.createMemory(CreateMemoryRequest("tenant-dedupe-two", "Tenant Dedupe Two"))
        val second = mediaService.uploadMemoryMedia(secondMemory.id, TestFileUpload(upload, "same.png"), "Second")

        val firstMedia = mediaRepo.findByTenantAndPublicId(tenant, first.id)!!
        val secondMedia = mediaRepo.findByTenantAndPublicId(otherTenant, second.id)!!
        assertThat(firstMedia.blob.id).isNotEqualTo(secondMedia.blob.id)
    }

    @Test
    fun `deleting duplicate media keeps blob until final reference is gone`() {
        val firstMemory = memoryService.createMemory(CreateMemoryRequest("delete-dedupe-one", "Delete Dedupe One"))
        val secondMemory = memoryService.createMemory(CreateMemoryRequest("delete-dedupe-two", "Delete Dedupe Two"))
        val upload = imageFile("delete-dedupe")
        val first = mediaService.uploadMemoryMedia(firstMemory.id, TestFileUpload(upload, "same.png"), "First")
        val second = mediaService.uploadMemoryMedia(secondMemory.id, TestFileUpload(upload, "same.png"), "Second")
        val blob = mediaRepo.findByTenantAndPublicId(tenant, first.id)!!.blob
        val originalPath = mediaConfig.storageDir.resolve(blob.storageKey)
        val thumbnailPath = mediaConfig.storageDir.resolve(blob.thumbnailStorageKey!!)
        val previewPath = mediaConfig.storageDir.resolve(blob.previewStorageKey!!)

        memoryService.deleteMemory(firstMemory.id)

        assertThat(mediaRepo.findByTenantAndPublicId(tenant, first.id)).isNull()
        assertThat(mediaRepo.findByTenantAndPublicId(tenant, second.id)).isNotNull
        assertThat(Files.exists(originalPath)).isTrue()
        assertThat(Files.exists(thumbnailPath)).isTrue()
        assertThat(Files.exists(previewPath)).isTrue()

        memoryService.deleteMemory(secondMemory.id)

        assertThat(Files.exists(originalPath)).isFalse()
        assertThat(Files.exists(thumbnailPath)).isFalse()
        assertThat(Files.exists(previewPath)).isFalse()
    }

    @Test
    fun `deletes one direct memory media item and clears cover references`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("delete-media-trip", "Delete Media Trip"))
        val media = createMedia(owner, memory(trip.id), "Cover Photo")
        memoryService.setCover(
            trip.id,
            uploadPath = null,
            originalFilename = null,
            mediaId = media.publicId,
            cropX = 0.0,
            cropY = 0.0,
            cropWidth = 1.0,
            cropHeight = 1.0,
        )

        mediaService.deleteMemoryMedia(trip.id, media.publicId)

        assertThat(mediaRepo.findByTenantAndPublicId(tenant, media.publicId)).isNull()
        assertThat(memoryService.getMemory(trip.id).coverMediaId).isNull()
    }

    @Test
    fun `deletes all direct memory media without deleting child memory media`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("delete-all-media-trip", "Delete All Media Trip"))
        val child = memoryService.createMemory(CreateMemoryRequest("delete-all-media-child", "Delete All Media Child", parentId = trip.id))
        val first = createMedia(owner, memory(trip.id), "First")
        val second = createMedia(owner, memory(trip.id), "Second")
        val childMedia = createMedia(owner, memory(child.id), "Child")

        mediaService.deleteAllMemoryMedia(trip.id)

        assertThat(mediaRepo.findByTenantAndPublicId(tenant, first.publicId)).isNull()
        assertThat(mediaRepo.findByTenantAndPublicId(tenant, second.publicId)).isNull()
        assertThat(mediaRepo.findByTenantAndPublicId(tenant, childMedia.publicId)).isNotNull
    }

    @Test
    fun `memory chunk upload resumes existing chunks and completes media`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("chunk-trip", "Chunk Trip"))
        val image = Files.readAllBytes(imageFile("chunk-upload"))
        val session = mediaService.createMemoryMediaUpload(
            trip.id,
            CreateMemoryMediaUploadRequest(filename = "chunk.png", mimeType = "image/png", sizeBytes = image.size.toLong(), title = "Chunk")
        )

        mediaService.uploadMemoryMediaChunk(trip.id, session.uploadId, 0, tempUploadChunk(image.copyOfRange(0, 4)))
        val pending = mediaService.memoryMediaUploadStatus(trip.id, session.uploadId)
        assertThat(pending.uploadedChunks).containsExactly(0)
        assertThat(pending.uploadedBytes).isEqualTo(4)

        for (offset in 4 until image.size step 4) {
            val chunkIndex = offset / 4
            mediaService.uploadMemoryMediaChunk(
                trip.id,
                session.uploadId,
                chunkIndex,
                tempUploadChunk(image.copyOfRange(offset, (offset + 4).coerceAtMost(image.size)))
            )
        }
        val media = mediaService.completeMemoryMediaUpload(trip.id, session.uploadId)

        assertThat(media.title).isEqualTo("Chunk")
        assertThat(media.sizeBytes).isEqualTo(image.size.toLong())
        val buckets = mediaService.listMemoryMediaBuckets(trip.id)
        assertThat(buckets).hasSize(1)
        assertThat(buckets.single().primary).isTrue()
        assertThat(media.bucketId).isEqualTo(buckets.single().id)
        assertThat(media.thumbnailUrl).endsWith("/thumbnail")
        assertThat(media.previewUrl).endsWith("/preview")
        val storedMedia = mediaRepo.findByTenantAndPublicId(tenant, media.id)
        assertThat(storedMedia).isNotNull
        assertThat(Files.exists(mediaConfig.storageDir.resolve(storedMedia!!.blob.thumbnailStorageKey!!))).isTrue()
        assertThat(Files.exists(mediaConfig.storageDir.resolve(storedMedia.blob.previewStorageKey!!))).isTrue()
        assertThatExceptionOfType(NotFoundException::class.java).isThrownBy {
            mediaService.memoryMediaUploadStatus(trip.id, session.uploadId)
        }
    }

    @Test
    fun `memory chunk upload rejects out of range chunk index`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("chunk-range-trip", "Chunk Range Trip"))
        val session = mediaService.createMemoryMediaUpload(
            trip.id,
            CreateMemoryMediaUploadRequest(filename = "range.txt", mimeType = "text/plain", sizeBytes = 5, title = "Range")
        )

        assertThatExceptionOfType(BadRequestException::class.java)
            .isThrownBy {
                mediaService.uploadMemoryMediaChunk(trip.id, session.uploadId, 2, tempUploadChunk("x"))
            }
            .withMessageContaining("chunkIndex is outside upload range")
    }

    @Test
    fun `failed memory chunk complete keeps upload session`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("chunk-missing-trip", "Chunk Missing Trip"))
        val session = mediaService.createMemoryMediaUpload(
            trip.id,
            CreateMemoryMediaUploadRequest(filename = "missing.txt", mimeType = "text/plain", sizeBytes = 8, title = "Missing")
        )

        mediaService.uploadMemoryMediaChunk(trip.id, session.uploadId, 0, tempUploadChunk("abcd"))

        assertThatExceptionOfType(BadRequestException::class.java)
            .isThrownBy {
                mediaService.completeMemoryMediaUpload(trip.id, session.uploadId)
            }
            .withMessageContaining("Missing upload chunk")

        assertThat(mediaService.memoryMediaUploadStatus(trip.id, session.uploadId).uploadedChunks).containsExactly(0)
    }

    @Test
    fun `memory chunk upload abort deletes session and chunks`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("chunk-abort-trip", "Chunk Abort Trip"))
        val session = mediaService.createMemoryMediaUpload(
            trip.id,
            CreateMemoryMediaUploadRequest(filename = "abort.txt", mimeType = "text/plain", sizeBytes = 4, title = "Abort")
        )

        mediaService.uploadMemoryMediaChunk(trip.id, session.uploadId, 0, tempUploadChunk("abcd"))
        mediaService.abortMemoryMediaUpload(trip.id, session.uploadId)

        assertThatExceptionOfType(NotFoundException::class.java).isThrownBy {
            mediaService.memoryMediaUploadStatus(trip.id, session.uploadId)
        }
        assertThatExceptionOfType(NotFoundException::class.java).isThrownBy {
            mediaService.completeMemoryMediaUpload(trip.id, session.uploadId)
        }
    }

    @Test
    fun `deleting memory removes pending upload sessions for memory tree`() {
        val root = memoryService.createMemory(CreateMemoryRequest("chunk-cleanup-root", "Chunk Cleanup Root"))
        val child = memoryService.createMemory(CreateMemoryRequest("chunk-cleanup-child", "Chunk Cleanup Child", parentId = root.id))
        val corruptUploadId = writeCorruptUploadSession()
        val rootSession = mediaService.createMemoryMediaUpload(
            root.id,
            CreateMemoryMediaUploadRequest(filename = "root.txt", mimeType = "text/plain", sizeBytes = 4, title = "Root")
        )
        val childSession = mediaService.createMemoryMediaUpload(
            child.id,
            CreateMemoryMediaUploadRequest(filename = "child.txt", mimeType = "text/plain", sizeBytes = 4, title = "Child")
        )
        mediaService.uploadMemoryMediaChunk(root.id, rootSession.uploadId, 0, tempUploadChunk("root"))
        mediaService.uploadMemoryMediaChunk(child.id, childSession.uploadId, 0, tempUploadChunk("part"))
        val rootUploadDirectory = uploadSessionDirectory(rootSession.uploadId)
        val childUploadDirectory = uploadSessionDirectory(childSession.uploadId)

        memoryService.deleteMemory(root.id)

        assertThat(Files.exists(rootUploadDirectory)).isFalse()
        assertThat(Files.exists(childUploadDirectory)).isFalse()
        assertThat(Files.exists(uploadSessionDirectory(corruptUploadId))).isTrue()
        assertThatExceptionOfType(NotFoundException::class.java).isThrownBy {
            mediaService.memoryMediaUploadStatus(root.id, rootSession.uploadId)
        }
        deleteUploadSessionDirectory(corruptUploadId)
    }

    @Test
    fun `invalid upload session status returns not found`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("invalid-upload-session-trip", "Invalid Upload Session Trip"))
        val uploadId = writeCorruptUploadSession()

        assertThatExceptionOfType(NotFoundException::class.java).isThrownBy {
            mediaService.memoryMediaUploadStatus(trip.id, uploadId)
        }
        deleteUploadSessionDirectory(uploadId)
    }

    @Test
    fun `deleting tenant removes only pending upload sessions for that tenant`() {
        val targetMemory = memoryService.createMemory(CreateMemoryRequest("tenant-cleanup-target", "Tenant Cleanup Target"))
        val targetSession = mediaService.createMemoryMediaUpload(
            targetMemory.id,
            CreateMemoryMediaUploadRequest(filename = "target.txt", mimeType = "text/plain", sizeBytes = 4, title = "Target")
        )
        mediaService.uploadMemoryMediaChunk(targetMemory.id, targetSession.uploadId, 0, tempUploadChunk("part"))
        val targetUploadDirectory = uploadSessionDirectory(targetSession.uploadId)

        val otherOwner = createUser("tenant-cleanup-other-$runId", ServerRole.ServerMember)
        val otherTenant = createTenant("tenant-cleanup-other-$runId", "Tenant Cleanup Other $runId", otherOwner, TenantRole.TenantOwner)
        useContext(otherOwner, otherTenant)
        val otherMemory = memoryService.createMemory(CreateMemoryRequest("tenant-cleanup-other", "Tenant Cleanup Other"))
        val otherSession = mediaService.createMemoryMediaUpload(
            otherMemory.id,
            CreateMemoryMediaUploadRequest(filename = "other.txt", mimeType = "text/plain", sizeBytes = 4, title = "Other")
        )
        mediaService.uploadMemoryMediaChunk(otherMemory.id, otherSession.uploadId, 0, tempUploadChunk("part"))
        val otherUploadDirectory = uploadSessionDirectory(otherSession.uploadId)

        inTransaction {
            libraryDeletionService.deleteTenant(tenant)
        }

        assertThat(Files.exists(targetUploadDirectory)).isFalse()
        assertThat(Files.exists(otherUploadDirectory)).isTrue()
        assertThat(mediaService.memoryMediaUploadStatus(otherMemory.id, otherSession.uploadId).uploadedChunks).containsExactly(0)
    }

    @Test
    fun `captured at override stays media specific and does not mutate blob metadata`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("blob-captured-at", "Blob Captured At"))
        val media = createMedia(
            owner,
            memory(trip.id),
            "Captured",
            capturedAt = Instant.parse("2026-01-01T10:00:00Z"),
        )

        mediaService.updateMemoryMediaCapturedAt(
            trip.id,
            media.publicId,
            UpdateMemoryMediaCapturedAtRequest(
                capturedAt = java.time.OffsetDateTime.parse("2026-01-02T10:00:00Z"),
            ),
        )

        val updated = mediaRepo.findByTenantAndPublicId(tenant, media.publicId)!!
        assertThat(updated.capturedAt).isEqualTo(Instant.parse("2026-01-02T10:00:00Z"))
        assertThat(updated.blob.capturedAt).isEqualTo(Instant.parse("2026-01-01T10:00:00Z"))
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
        val playlist = memoryPlaylistService.createMemoryPlaylist(
            trip.id,
            CreateMemoryPlaylistRequest(
                title = "Roadtrip Mix",
                url = "https://www.youtube.com/playlist?list=PL1234567890abcdef",
            ),
        )
        val track = inTransaction {
            MemoryTrack().also {
                it.tenant = tenant
                it.memory = memoryRepo.findByTenantAndPublicId(tenant, trip.id)!!
                it.scopeType = LibraryScopeType.Tenant
                it.title = "Summit Route"
                it.activityType = MemoryTrackActivityType.HIKING
                it.originalFilename = "summit.gpx"
                it.storageKey = "test/$runId/summit.gpx"
                it.geometryJson = """{"type":"LineString","coordinates":[]}"""
                entityManager.persist(it)
                entityManager.flush()
            }
        }

        val empty = memoryJournalService.getJournal(memory(trip.id))
        assertThat(empty.memoryId).isEqualTo(trip.id)
        assertThat(empty.markdown).isEmpty()

        val updated = memoryJournalService.updateJournal(
            memory(trip.id),
            """
            # Journal

            ::media:${media.publicId} Photo
            ::media:${media.publicId},${secondMedia.publicId} Carousel
            ::chapter:${day.id} Day One
            ::playlist:${playlist.id} Roadtrip
            ::track:${track.publicId} Route
            """.trimIndent(),
            MemoryJournalService.JournalValidationContext(memory(trip.id)),
        )

        assertThat(updated.memoryId).isEqualTo(trip.id)
        assertThat(updated.markdown).contains("::media:${media.publicId}")
        assertThat(updated.markdown).contains("::media:${media.publicId},${secondMedia.publicId}")
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
                "::media:${otherMedia.publicId} Other",
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
                "::media:${visibleMedia.publicId},${otherMedia.publicId} Carousel",
                MemoryJournalService.JournalValidationContext(memory(trip.id)),
            )
        }
    }

    @Test
    fun `does not validate legacy veyq markdown link embeds`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("journal-legacy-trip", "Journal Legacy Trip"))
        val other = memoryService.createMemory(CreateMemoryRequest("journal-legacy-other", "Journal Legacy Other"))
        val otherMedia = createMedia(owner, memory(other.id), "Other Journal Photo")

        val updated = memoryJournalService.updateJournal(
            memory(trip.id),
            "![Other](veyq:media:${otherMedia.publicId})",
            MemoryJournalService.JournalValidationContext(memory(trip.id)),
        )

        assertThat(updated.markdown).contains("veyq:media:${otherMedia.publicId}")
    }

    @Test
    fun `creates and validates an internal journal live session`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("journal-live-trip", "Journal Live Trip"))
        memoryJournalService.updateJournal(
            memory(trip.id),
            "# Live journal",
            MemoryJournalService.JournalValidationContext(memory(trip.id)),
        )

        val session = memoryJournalService.createLiveSession(memory(trip.id))
        val document = memoryJournalService.validateLiveSession(session.token, session.documentId)

        assertThat(session.memoryId).isEqualTo(trip.id)
        assertThat(session.canEdit).isTrue()
        assertThat(document.documentId).isEqualTo(session.documentId)
        assertThat(document.markdown).isEqualTo("# Live journal")
        assertThat(document.displayName).isEqualTo(owner.displayName)

        val updated = memoryJournalService.persistLiveSnapshot(
            session.token,
            session.documentId,
            "# Live journal\n\nSynced.",
        )
        assertThat(updated.markdown).contains("Synced.")
    }

    @Test
    fun `rejects invalid journal live snapshot without replacing current markdown`() {
        val trip = memoryService.createMemory(CreateMemoryRequest("journal-live-invalid", "Journal Live Invalid"))
        memoryJournalService.updateJournal(
            memory(trip.id),
            "Original",
            MemoryJournalService.JournalValidationContext(memory(trip.id)),
        )
        val session = memoryJournalService.createLiveSession(memory(trip.id))

        assertThatExceptionOfType(BadRequestException::class.java).isThrownBy {
            memoryJournalService.persistLiveSnapshot(
                session.token,
                session.documentId,
                "::unsupported:value Invalid",
            )
        }
        assertThat(memoryJournalService.getJournal(memory(trip.id)).markdown).isEqualTo("Original")
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
    fun `search finds visible memories folders and journal snippets with ranked results`() {
        val folder = folderService.createFolder(CreateFolderRequest("beach-archive", "Beach Archive"))
        val exact = memoryService.createMemory(CreateMemoryRequest("beach", "Beach"))
        val journalOnly = memoryService.createMemory(CreateMemoryRequest("journal-day", "Journal Day"))
        memoryJournalService.updateJournal(
            memory(journalOnly.id),
            "We found the quiet beach picnic spot after sunset.",
            MemoryJournalService.JournalValidationContext(memory(journalOnly.id)),
        )

        val results = searchService.search("beach", 10)

        assertThat(results.map { it.id }).containsSubsequence(exact.id, folder.id, journalOnly.id)
        assertThat(results.first { it.id == exact.id }.matchTypes).contains(SearchResultSummary.MatchTypes.TITLE)
        assertThat(results.first { it.id == folder.id }.kind).isEqualTo(SearchResultSummary.Kind.FOLDER)
        assertThat(results.first { it.id == journalOnly.id }.matchTypes).contains(SearchResultSummary.MatchTypes.JOURNAL)
        assertThat(results.first { it.id == journalOnly.id }.journalSnippet).contains("quiet beach picnic")
    }

    @Test
    fun `search only returns memories and folders in visible scopes`() {
        val outsider = createUser("search-outsider-$runId", ServerRole.ServerMember)
        addTenantMember(outsider, TenantRole.TenantMember)
        val group = createGroup("search-private-$runId", owner)
        val groupFolder = folderService.createFolder(
            CreateFolderRequest(
                name = "secret-search-folder",
                displayName = "Secret Search Folder",
                scope = MemoryScopeRequest("GROUP", group.name),
            )
        )
        val groupMemory = memoryService.createMemory(
            CreateMemoryRequest(
                "secret-search-memory",
                "Secret Search Memory",
                scope = MemoryScopeRequest("GROUP", group.name),
            )
        )
        val personalMemory = memoryService.createMemory(
            CreateMemoryRequest(
                "secret-search-personal",
                "Secret Search Personal",
                scope = MemoryScopeRequest("USER", null),
            )
        )

        assertThat(searchService.search("secret-search", 10).map { it.id })
            .contains(groupFolder.id, groupMemory.id, personalMemory.id)

        useContext(outsider, tenant)
        assertThat(searchService.search("secret-search", 10)).isEmpty()
    }

    @Test
    fun `search limits result count`() {
        memoryService.createMemory(CreateMemoryRequest("limit-search-one", "Limit Search One"))
        memoryService.createMemory(CreateMemoryRequest("limit-search-two", "Limit Search Two"))
        memoryService.createMemory(CreateMemoryRequest("limit-search-three", "Limit Search Three"))

        assertThat(searchService.search("limit-search", 2)).hasSize(2)
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
                it.activatedAt = Instant.now()
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
        originalFilename: String? = "$title.jpg",
        cameraMake: String? = null,
        cameraModel: String? = null,
        mimeType: String = "image/jpeg",
    ): Media =
        inTransaction {
            val blob = MediaBlob().also {
                it.tenant = tenant
                it.contentSha256 = "${UUID.randomUUID().toString().replace("-", "")}${UUID.randomUUID().toString().replace("-", "")}"
                it.mimeType = mimeType
                it.sizeBytes = 12
                it.storageKey = "tenant-${tenant.id}/test-${UUID.randomUUID()}/original"
                it.capturedAt = capturedAt
                it.imageWidth = 16
                it.imageHeight = 9
                it.cameraMake = cameraMake
                it.cameraModel = cameraModel
                it.persist()
            }
            Media().also {
                it.tenant = tenant
                it.uploaderUser = owner
                it.scopeType = memory.scopeType
                it.scopeGroup = memory.scopeGroup
                it.scopeUser = memory.scopeUser
                it.memory = memory
                it.title = title
                it.blob = blob
                it.originalFilename = originalFilename
                it.createdAt = createdAt
                it.capturedAt = capturedAt
                mediaRepo.persist(it)
            }
        }

    private fun coverFile(prefix: String): Path {
        val path = Files.createTempFile(prefix, ".png")
        ImageIO.write(BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB), "png", path.toFile())
        return path
    }

    private fun imageFile(prefix: String): Path {
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

    private fun tempUploadChunk(content: String): Path {
        val path = Files.createTempFile("veyq-upload-chunk-", ".part")
        Files.writeString(path, content)
        return path
    }

    private fun tempUploadChunk(content: ByteArray): Path {
        val path = Files.createTempFile("veyq-upload-chunk-", ".part")
        Files.write(path, content)
        return path
    }

    private fun uploadSessionDirectory(uploadId: String): Path =
        mediaConfig.storageDir.resolve("_uploads").resolve(uploadId)

    private fun writeCorruptUploadSession(): String {
        val uploadId = UUID.randomUUID().toString()
        val directory = uploadSessionDirectory(uploadId)
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("session.properties"), "not-a-valid-session=true")
        return uploadId
    }

    private fun deleteUploadSessionDirectory(uploadId: String) {
        uploadSessionDirectory(uploadId).toFile().deleteRecursively()
    }

    private fun writeStoredMedia(media: Media, content: String) {
        val path = mediaConfig.storageDir.resolve(media.blob.storageKey)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    private fun blobCount(tenant: Tenant): Long =
        entityManager
            .createQuery("select count(b) from MediaBlob b where b.tenant = :tenant", Long::class.javaObjectType)
            .setParameter("tenant", tenant)
            .singleResult
            .toLong()

    private fun zipEntries(entity: Any): List<Pair<String, String>> {
        val output = ByteArrayOutputStream()
        (entity as StreamingOutput).write(output)
        val entries = mutableListOf<Pair<String, String>>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += entry.name to zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        return entries
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

    private class TestFileUpload(
        private val path: Path,
        private val filename: String,
    ) : FileUpload {
        override fun name(): String = "file"
        override fun filePath(): Path = path
        override fun fileName(): String = filename
        override fun size(): Long = Files.size(path)
        override fun contentType(): String? = null
        override fun charSet(): String? = null
        override fun getHeaders(): MultivaluedMap<String, String> = MultivaluedHashMap()
    }
}
