package com.veyq.api.memory

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ConflictException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.common.apiPathSegment as url
import com.veyq.api.domain.*
import com.veyq.api.generated.model.CreateMemoryRequest
import com.veyq.api.generated.model.CoverCrop
import com.veyq.api.generated.model.MemoryHeaderSummary
import com.veyq.api.generated.model.MemoryParticipantSummary
import com.veyq.api.generated.model.MemoryScope
import com.veyq.api.generated.model.MemoryScopeRequest
import com.veyq.api.generated.model.MemorySummary
import com.veyq.api.generated.model.MoveMemoryRequest
import com.veyq.api.generated.model.MoveMemoryTarget
import com.veyq.api.generated.model.UpdateMemoryRequest
import com.veyq.api.library.LibraryScopeKey
import com.veyq.api.library.LibraryScopes
import com.veyq.api.library.LibraryService
import com.veyq.api.library.ResolvedMemoryLocation
import com.veyq.api.library.media.MediaConfig
import com.veyq.api.library.media.MediaKind
import com.veyq.api.library.media.MediaMetadataExtractor
import com.veyq.api.library.media.MediaService
import com.veyq.api.library.media.MediaTypeDetector
import com.veyq.api.usermgmt.user.avatarUrlForTenant
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger
import java.awt.Dimension
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.util.Comparator
import java.util.UUID
import kotlin.math.abs

@Singleton
class MemoryService @Inject constructor(
    private val memoryRepo: MemoryRepo,
    private val memoryFavoriteRepo: MemoryFavoriteRepo,
    private val memoryParticipantRepo: MemoryParticipantRepo,
    private val mediaRepo: MediaRepo,
    private val folderRepo: TenantFolderRepo,
    private val groupRepo: TenantGroupRepo,
    private val memoryGroupMemberRepo: TenantGroupMemberRepo,
    private val tenantMemberRepo: TenantMemberRepo,
    private val userRepo: UserRepo,
    private val mediaConfig: MediaConfig,
    private val mediaService: MediaService,
    private val mediaMetadataExtractor: MediaMetadataExtractor,
    private val mediaTypeDetector: MediaTypeDetector,
    requestContext: RequestContext,
) : LibraryService(folderRepo, groupRepo, memoryGroupMemberRepo, requestContext) {
    private val log: Logger = Logger.getLogger(MemoryService::class.java)

    fun listRootMemories(): List<MemorySummary> =
        memoryRepo.listRoots(tenant())
            .filter(::isMemoryVisible)
            .map(::toSummary)

    fun listMemoriesByFolderPath(folderPath: String?): List<MemorySummary> {
        val folderTarget = resolveFolderTarget(folderPath)
        val memories = memoryRepo.listByFolder(tenant(), folderTarget.parentFolder)
        return memories
            .filter(::isMemoryVisible)
            .filter { it.scope().sameAs(folderTarget.scope) }
            .map(::toSummary)
    }

    fun getMemory(publicId: String): MemorySummary =
        toSummary(resolveVisibleMemory(publicId))

    fun getMemoryHeader(publicId: String): MemoryHeaderSummary =
        toHeader(resolveVisibleMemory(publicId))

    fun getMemoryByPath(path: String?): MemorySummary =
        toSummary(resolveVisibleMemoryByPath(path))

    fun listChildren(publicId: String): List<MemorySummary> =
        memoryRepo.listChildren(tenant(), resolveVisibleMemory(publicId))
            .filter(::isMemoryVisible)
            .map(::toSummary)

    fun listParticipants(publicId: String): List<MemoryParticipantSummary> =
        visibleParticipants(resolveVisibleMemory(publicId))

    fun listParticipantCandidates(publicId: String): List<MemoryParticipantSummary> =
        participantCandidates(resolveVisibleMemory(publicId)).map { it.toParticipantSummary() }

    fun listParticipantRootMemories(profileUser: User): List<MemorySummary> =
        memoryParticipantRepo.listByTenantAndUser(tenant(), profileUser)
            .asSequence()
            .map { rootMemory(it.memory) }
            .filter(::isMemoryVisible)
            .distinctBy { it.id }
            .sortedWith(compareBy<Memory> { it.displayName.lowercase() }.thenBy { it.publicId })
            .map(::toSummary)
            .toList()

    fun listFavoriteMemories(): List<MemorySummary> =
        memoryFavoriteRepo.listByTenantAndUser(tenant(), user())
            .map { it.memory }
            .filter(::isMemoryVisible)
            .map(::toSummary)

    @Transactional
    fun favoriteMemory(publicId: String): MemorySummary {
        val memory = resolveVisibleMemory(publicId)
        if (memoryFavoriteRepo.findByTenantUserAndMemory(tenant(), user(), memory) == null) {
            memoryFavoriteRepo.persist(
                MemoryFavorite().also {
                    it.tenant = tenant()
                    it.user = user()
                    it.memory = memory
                }
            )
            memoryFavoriteRepo.flush()
        }
        return toSummary(memory)
    }

    @Transactional
    fun unfavoriteMemory(publicId: String): MemorySummary {
        val memory = resolveVisibleMemory(publicId)
        memoryFavoriteRepo.findByTenantUserAndMemory(tenant(), user(), memory)?.delete()
        return toSummary(memory)
    }

    @Transactional
    fun addParticipant(publicId: String, userId: Long): MemoryParticipantSummary {
        val memory = resolveVisibleMemory(publicId)
        val targetUser = userRepo.findById(userId) ?: throw NotFoundException("Participant not found")
        if (!isParticipantEligible(memory, targetUser)) throw NotFoundException("Participant not found")
        val participant = memoryParticipantRepo.findByMemoryAndUser(memory, targetUser) ?: MemoryParticipant().also {
            it.tenant = tenant()
            it.memory = memory
            it.user = targetUser
            it.addedBy = user()
            memoryParticipantRepo.persist(it)
            memoryParticipantRepo.flush()
        }
        return participant.user.toParticipantSummary()
    }

    @Transactional
    fun removeParticipant(publicId: String, userId: Long) {
        val memory = resolveVisibleMemory(publicId)
        val targetUser = userRepo.findById(userId) ?: return
        memoryParticipantRepo.findByMemoryAndUser(memory, targetUser)?.delete()
    }

    @Transactional
    fun createMemory(request: CreateMemoryRequest): MemorySummary {
        val parent = request.parentId?.trim()?.takeIf { it.isNotBlank() }?.let(::resolveVisibleMemory)
        val folderTarget = if (parent == null) request.folderPath
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "/" }
            ?.let(::resolveFolderTarget)
        else null
        val parentFolder = folderTarget?.parentFolder
        val scope = parent?.scope() ?: folderTarget?.scope ?: resolveRequestedRootScope(request.scope)
        val name = memoryName(request.name)
        val dateRange = requiredDateRange(request.startDate, request.endDate)
        validateNoCollision(parent, parentFolder, scope, name)
        val memory = Memory().also {
            it.tenant = tenant()
            it.parentMemory = parent
            it.parentFolder = parentFolder
            it.owner = user()
            LibraryScopes.apply(it, scope)
            it.name = name
            it.displayName = requiredDisplayName(request.displayName)
            it.startDate = dateRange.startDate
            it.endDate = dateRange.endDate
            memoryRepo.persist(it)
        }
        memoryParticipantRepo.persist(
            MemoryParticipant().also {
                it.tenant = tenant()
                it.memory = memory
                it.user = user()
                it.addedBy = user()
            }
        )
        memoryRepo.flush()
        return toSummary(memory)
    }

    @Transactional
    fun updateMemory(publicId: String, request: UpdateMemoryRequest): MemorySummary {
        val memory = resolveVisibleMemory(publicId)
        request.parentId?.trim()?.takeIf { it.isNotBlank() }?.let { parentId ->
            val parent = resolveVisibleMemory(parentId)
            if (parent.id == memory.id || isDescendantOrSame(parent, memory)) {
                throw BadRequestException("Cannot move memory into itself", "MEMORY_MOVE_INTO_SELF")
            }
            if (!parent.scope().sameAs(memory.scope())) {
                throw BadRequestException("Cannot move memory between scopes", "MEMORY_SCOPE_INVALID")
            }
            memory.parentMemory = parent
            memory.parentFolder = null
        }
        request.name?.let {
            val name = memoryName(it)
            if (name != memory.name) validateNoCollision(memory.parentMemory, memory.parentFolder, memory.scope(), name)
            memory.name = name
        }
        request.displayName?.let { memory.displayName = requiredDisplayName(it) }
        if (request.startDate != null || request.endDate != null) {
            val dateRange = requiredDateRange(
                request.startDate ?: memory.startDate,
                request.endDate ?: memory.endDate,
            )
            memory.startDate = dateRange.startDate
            memory.endDate = dateRange.endDate
        }
        return toSummary(memory)
    }

    @Transactional
    fun moveMemory(publicId: String, request: MoveMemoryRequest): MemorySummary {
        val memory = resolveVisibleMemory(publicId)
        val target = resolveMoveTarget(memory, request.target)
        val newName = request.name?.let { memoryName(it) } ?: memory.name
        val targetChanged = target.parentMemory?.id != memory.parentMemory?.id ||
            target.parentFolder?.id != memory.parentFolder?.id ||
            newName != memory.name

        if (targetChanged) {
            validateNoCollision(target.parentMemory, target.parentFolder, memory.scope(), newName)
        }

        memory.parentMemory = target.parentMemory
        memory.parentFolder = target.parentFolder
        memory.name = newName
        return toSummary(memory)
    }

    @Transactional
    fun deleteMemory(publicId: String) {
        val memory = resolveVisibleMemory(publicId)
        val memories = descendantsAndSelf(memory)
        val memoryIds = memories.map { it.id }
        val media = memories.flatMap { mediaRepo.listByMemory(it) }
        val coverDirectories = memories.mapNotNull { it.coverStorageKey?.let(::coverDirectory) }.distinct()
        memories.forEach { candidate ->
            if (candidate.coverMedia?.let { cover -> media.any { it.id == cover.id } } == true) {
                candidate.coverMedia = null
            }
        }
        memoryRepo.update("coverMedia = null where coverMedia in ?1", media)
        memoryRepo.flush()
        val blobs = media.map { it.blob }.distinctBy { it.id }
        media.forEach { it.delete() }
        mediaRepo.flush()
        blobs.forEach(mediaService::deleteBlobIfUnreferenced)
        coverDirectories.forEach(::deleteRecursively)
        memory.delete()
        mediaService.deleteUploadSessionsForMemoryTree(memory.tenant.id, memoryIds)
    }

    @Transactional
    fun setCover(
        publicId: String,
        uploadPath: Path?,
        originalFilename: String?,
        mediaId: String?,
        cropX: Double?,
        cropY: Double?,
        cropWidth: Double?,
        cropHeight: Double?,
        uploadSize: Long? = null,
        uploadContentType: String? = null,
    ): MemorySummary {
        val memory = resolveVisibleMemory(publicId)
        val normalizedMediaId = mediaId?.trim()?.takeIf { it.isNotBlank() }
        val hasUpload = uploadPath != null
        val hasMedia = normalizedMediaId != null
        if (hasUpload && hasMedia) {
            throw BadRequestException("Exactly one cover source is allowed", "MEMORY_COVER_SOURCE_INVALID")
        }
        if (!hasUpload && !hasMedia && memory.coverStorageKey == null) {
            throw BadRequestException("Cover source is required", "MEMORY_COVER_SOURCE_REQUIRED")
        }

        val oldCoverStorageKey = memory.coverStorageKey
        if (uploadPath != null) {
            val size = uploadSize ?: Files.size(uploadPath)
            if (size <= 0) throw BadRequestException("file must not be empty", "MEDIA_UPLOAD_EMPTY")
            val mediaType = mediaTypeDetector.detect(uploadPath)
                ?: throw BadRequestException("Only JPEG, PNG and GIF covers are supported", "MEMORY_COVER_TYPE_UNSUPPORTED")
            if (mediaType.kind != MediaKind.Image) throw BadRequestException("Only JPEG, PNG and GIF covers are supported", "MEMORY_COVER_TYPE_UNSUPPORTED")
            val crop = requiredCoverCrop(cropX, cropY, cropWidth, cropHeight, imageDimensions(uploadPath, mediaType.mimeType))
            val storageKey = "tenant-${tenant().id}/memory-covers/${memory.publicId}-${UUID.randomUUID()}/cover-original"
            writeFile(uploadPath, storageKey)
            memory.coverStorageKey = storageKey
            memory.coverMimeType = mediaType.mimeType
            memory.coverOriginalFilename = originalFilename?.trim()?.takeIf { it.isNotBlank() } ?: "cover"
            memory.coverMedia = null
            memory.coverCropX = crop.x
            memory.coverCropY = crop.y
            memory.coverCropWidth = crop.width
            memory.coverCropHeight = crop.height
            memoryRepo.flush()
            deleteOldExplicitCover(oldCoverStorageKey, storageKey)
        } else if (normalizedMediaId != null) {
            val media = mediaRepo.findByTenantAndPublicId(tenant(), normalizedMediaId)
                ?: throw NotFoundException("Media not found")
            if (media.memory?.id != memory.id) throw NotFoundException("Media not found")
            if (!media.blob.mimeType.startsWith("image/")) throw BadRequestException("Only image covers are supported", "MEMORY_COVER_TYPE_UNSUPPORTED")
            val crop = requiredCoverCrop(cropX, cropY, cropWidth, cropHeight, media.imageDimensions())
            memory.coverStorageKey = null
            memory.coverMimeType = null
            memory.coverOriginalFilename = null
            memory.coverMedia = media
            memory.coverCropX = crop.x
            memory.coverCropY = crop.y
            memory.coverCropWidth = crop.width
            memory.coverCropHeight = crop.height
            memoryRepo.flush()
            deleteOldExplicitCover(oldCoverStorageKey, null)
        } else {
            val storageKey = oldCoverStorageKey ?: throw BadRequestException("Cover source is required", "MEMORY_COVER_SOURCE_REQUIRED")
            val mimeType = memory.coverMimeType ?: throw BadRequestException("Cover source dimensions could not be read", "MEMORY_COVER_DIMENSIONS_UNREADABLE")
            val crop = requiredCoverCrop(cropX, cropY, cropWidth, cropHeight, imageDimensions(storagePath(storageKey), mimeType))
            memory.coverCropX = crop.x
            memory.coverCropY = crop.y
            memory.coverCropWidth = crop.width
            memory.coverCropHeight = crop.height
        }
        return toSummary(memory)
    }

    fun coverContent(publicId: String): Response {
        val memory = resolveVisibleMemory(publicId)
        val storageKey = memory.coverStorageKey ?: throw NotFoundException("Cover not found")
        val path = storagePath(storageKey)
        if (!Files.isRegularFile(path)) throw NotFoundException("Cover not found")
        return Response.ok(path.toFile(), memory.coverMimeType ?: "application/octet-stream")
            .header("Cache-Control", "private, max-age=31536000, immutable")
            .header("Content-Length", Files.size(path))
            .header("X-Content-Type-Options", "nosniff")
            .build()
    }

    @Transactional
    fun clearCover(publicId: String): MemorySummary {
        val memory = resolveVisibleMemory(publicId)
        memory.coverStorageKey?.let { deleteRecursively(coverDirectory(it)) }
        memory.coverStorageKey = null
        memory.coverMimeType = null
        memory.coverOriginalFilename = null
        memory.coverMedia = null
        memory.coverCropX = null
        memory.coverCropY = null
        memory.coverCropWidth = null
        memory.coverCropHeight = null
        return toSummary(memory)
    }

    fun resolveVisibleMemory(publicId: String): Memory {
        val memory = memoryRepo.findByTenantAndPublicId(tenant(), publicId)
            ?: throw NotFoundException("Memory not found")
        if (!isMemoryVisible(memory)) throw NotFoundException("Memory not found")
        return memory
    }

    fun isMemoryVisible(memory: Memory): Boolean =
        memory.tenant.id == tenant().id && isScopeVisible(memory.scope())

    private fun validateNoCollision(parentMemory: Memory?, parentFolder: TenantFolder?, scope: LibraryScopeKey, name: String) {
        val memoryCollision = (parentMemory?.let { memoryRepo.listChildren(tenant(), it) }
            ?: memoryRepo.listByFolder(tenant(), parentFolder))
            .any { it.name == name && it.scope().sameAs(scope) }
        val folderCollision = parentMemory == null && folderRepo.listChildren(tenant(), parentFolder)
            .any { it.name == name && it.hasScope(scope) }
        if (memoryCollision || folderCollision) throw ConflictException("Path name already exists", "MEMORY_NAME_CONFLICT")
    }

    private fun memoryName(value: String?): String =
        normalizedName(value, requiredCode = "MEMORY_NAME_REQUIRED", invalidCode = "MEMORY_NAME_INVALID")

    private fun resolveRequestedRootScope(scope: MemoryScopeRequest?): LibraryScopeKey =
        when (scope?.type?.trim()?.uppercase() ?: "TENANT") {
            "TENANT" -> LibraryScopeKey.Tenant
            "GROUP" -> {
                val groupName = scope?.groupName?.trim()?.takeIf { it.isNotBlank() }
                    ?: throw BadRequestException("groupName is required", "MEMORY_SCOPE_INVALID")
                LibraryScopeKey.Group(resolveGroup(groupName))
            }
            "USER" -> LibraryScopeKey.User(user())
            else -> throw BadRequestException("Invalid scope type", "MEMORY_SCOPE_INVALID")
        }.also {
            if (!isScopeVisible(it)) throw NotFoundException("Scope not found")
        }

    private fun resolveGroup(name: String): TenantGroup =
        groupRepo.findByTenantAndName(tenant(), name) ?: throw NotFoundException("Group not found")

    private fun requiredDisplayName(value: String?): String =
        value?.trim()?.takeIf { it.isNotBlank() } ?: throw BadRequestException("displayName is required", "MEMORY_DISPLAY_NAME_REQUIRED")

    private fun toSummary(memory: Memory): MemorySummary =
        MemorySummary(
            id = memory.publicId,
            parentId = memory.parentMemory?.publicId,
            name = memory.name,
            displayName = memory.displayName,
            path = memory.path(),
            coverMediaId = memory.coverMedia?.publicId,
            coverUrl = memory.coverUrl(),
            coverCrop = memory.coverCrop(),
            startDate = memory.startDate,
            endDate = memory.endDate,
            childCount = memoryRepo.listChildren(tenant(), memory).count { isMemoryVisible(it) },
            mediaCount = mediaRepo.listByMemory(memory).size,
            favorite = isFavorite(memory),
            scope = memory.scope().toApiScope(),
        )

    private fun toHeader(memory: Memory): MemoryHeaderSummary {
        val directMediaCount = mediaRepo.listByMemory(memory).size
        val visibleDescendants = descendantsAndSelf(memory).drop(1).filter(::isMemoryVisible)
        return MemoryHeaderSummary(
            id = memory.publicId,
            displayName = memory.displayName,
            path = memory.path(),
            coverUrl = memory.coverUrl(),
            coverCrop = memory.coverCrop(),
            startDate = memory.startDate,
            endDate = memory.endDate,
            directMediaCount = directMediaCount,
            descendantMediaCount = visibleDescendants.sumOf { mediaRepo.listByMemory(it).size },
            childMemoryCount = memoryRepo.listChildren(tenant(), memory).count { isMemoryVisible(it) },
            favorite = isFavorite(memory),
            scope = memory.scope().toApiScope(),
            participants = visibleParticipants(memory),
        )
    }

    private fun visibleParticipants(memory: Memory): List<MemoryParticipantSummary> =
        memoryParticipantRepo.listByMemory(memory)
            .map { it.user }
            .filter { isParticipantEligible(memory, it) }
            .sortedWith(compareBy<User> { it.displayName.lowercase() }.thenBy { it.permalink })
            .map { it.toParticipantSummary() }

    private fun participantCandidates(memory: Memory): List<User> =
        when (val scope = memory.scope()) {
            LibraryScopeKey.Tenant -> tenantMemberRepo.listByTenant(tenant())
                .filter { it.status == TenantMemberStatus.Active && it.user.disabledAt == null && it.user.deletedAt == null }
                .map { it.user }
            is LibraryScopeKey.Group -> scope.group.members
                .map { it.user }
                .filter(::isActiveTenantUser)
            is LibraryScopeKey.User -> listOf(scope.user)
                .filter(::isActiveTenantUser)
        }
            .distinctBy { it.id }
            .sortedWith(compareBy<User> { it.displayName.lowercase() }.thenBy { it.permalink })

    private fun isParticipantEligible(memory: Memory, participant: User): Boolean =
        when (val scope = memory.scope()) {
            LibraryScopeKey.Tenant -> isActiveTenantUser(participant)
            is LibraryScopeKey.Group ->
                isActiveTenantUser(participant) &&
                    memoryGroupMemberRepo.findByGroupAndUser(scope.group, participant) != null
            is LibraryScopeKey.User -> scope.user.id == participant.id &&
                isActiveTenantUser(participant)
        }

    private fun isActiveTenantUser(user: User): Boolean =
        user.disabledAt == null &&
            user.deletedAt == null &&
            !user.systemUser &&
            tenantMemberRepo.findByTenantAndUser(tenant(), user)?.status == TenantMemberStatus.Active

    private fun User.toParticipantSummary(): MemoryParticipantSummary =
        MemoryParticipantSummary(
            userId = id,
            displayName = displayName,
            permalink = permalink,
            initials = initials(displayName),
            avatarUrl = avatarUrlForTenant(tenant().name),
        )

    private fun initials(displayName: String): String =
        displayName
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .take(4)
            .ifBlank { "?" }

    private fun isFavorite(memory: Memory): Boolean =
        memoryFavoriteRepo.findByTenantUserAndMemory(tenant(), user(), memory) != null

    private fun requiredDateRange(startDate: LocalDate?, endDate: LocalDate?): MemoryDateRange {
        val start = startDate ?: throw BadRequestException("startDate is required", "MEMORY_DATE_RANGE_REQUIRED")
        val end = endDate ?: throw BadRequestException("endDate is required", "MEMORY_DATE_RANGE_REQUIRED")
        if (end.isBefore(start)) throw BadRequestException("endDate must not be before startDate", "MEMORY_DATE_RANGE_INVALID")
        return MemoryDateRange(start, end)
    }

    private fun requiredCoverCrop(
        x: Double?,
        y: Double?,
        width: Double?,
        height: Double?,
        source: Dimension,
    ): MemoryCoverCrop {
        val crop = MemoryCoverCrop(
            x ?: throw BadRequestException("cropX is required", "MEMORY_COVER_CROP_REQUIRED"),
            y ?: throw BadRequestException("cropY is required", "MEMORY_COVER_CROP_REQUIRED"),
            width ?: throw BadRequestException("cropWidth is required", "MEMORY_COVER_CROP_REQUIRED"),
            height ?: throw BadRequestException("cropHeight is required", "MEMORY_COVER_CROP_REQUIRED"),
        )
        val values = listOf(crop.x, crop.y, crop.width, crop.height)
        if (values.any { !it.isFinite() || it < 0.0 || it > 1.0 }) {
            throw BadRequestException("Cover crop values must be between 0 and 1", "MEMORY_COVER_CROP_INVALID")
        }
        if (crop.width <= 0.0 || crop.height <= 0.0) {
            throw BadRequestException("Cover crop dimensions must be greater than 0", "MEMORY_COVER_CROP_INVALID")
        }
        if (crop.x + crop.width > 1.000001 || crop.y + crop.height > 1.000001) {
            throw BadRequestException("Cover crop must fit inside the source image", "MEMORY_COVER_CROP_INVALID")
        }
        val cropAspectRatio = (crop.width * source.width.toDouble()) / (crop.height * source.height.toDouble())
        if (abs(cropAspectRatio - COVER_ASPECT_RATIO) > COVER_ASPECT_TOLERANCE) {
            throw BadRequestException("Cover crop must be 16:9", "MEMORY_COVER_CROP_INVALID")
        }
        return crop
    }

    private fun Memory.coverUrl(): String? =
        coverStorageKey?.let { "/api/${url(tenant().name)}/memories/${url(publicId)}/cover/content" }
            ?: coverMedia?.let { media -> media.contentUrl() }

    private fun Memory.coverCrop(): CoverCrop? {
        val x = coverCropX ?: return null
        val y = coverCropY ?: return null
        val width = coverCropWidth ?: return null
        val height = coverCropHeight ?: return null
        return CoverCrop(x, y, width, height)
    }

    private fun Memory.path(): String =
        (parentMemory?.path()?.trimEnd('/')
            ?: parentFolder?.path()?.trimEnd('/')
            ?: LibraryScopes.pathPrefix(scope()).trimEnd('/')) + "/" + name

    private fun LibraryScopeKey.toApiScope(): MemoryScope =
        when (this) {
            LibraryScopeKey.Tenant -> MemoryScope(MemoryScope.Type.TENANT, null)
            is LibraryScopeKey.Group -> MemoryScope(MemoryScope.Type.GROUP, group.name)
            is LibraryScopeKey.User -> MemoryScope(MemoryScope.Type.USER, null)
        }

    private fun descendantsAndSelf(root: Memory): List<Memory> =
        listOf(root) + memoryRepo.list("tenant", tenant())
            .filter { candidate -> generateSequence(candidate.parentMemory) { it.parentMemory }.any { it.id == root.id } }

    private fun rootMemory(memory: Memory): Memory =
        generateSequence(memory) { it.parentMemory }.last()

    private fun isDescendantOrSame(candidate: Memory, ancestor: Memory): Boolean =
        generateSequence(candidate) { it.parentMemory }.any { it.id == ancestor.id }

    private data class MemoryFolderTarget(
        val parentFolder: TenantFolder?,
        val scope: LibraryScopeKey,
    )

    private data class MemoryMoveTarget(
        val parentMemory: Memory?,
        val parentFolder: TenantFolder?,
    )

    private data class MemoryDateRange(
        val startDate: LocalDate,
        val endDate: LocalDate,
    )

    private fun resolveMoveTarget(memory: Memory, target: MoveMemoryTarget): MemoryMoveTarget =
        when (target.type) {
            MoveMemoryTarget.Type.MEMORY -> {
                val parentId = target.parentMemoryId?.trim()?.takeIf { it.isNotBlank() }
                    ?: throw BadRequestException("parentMemoryId is required", "MEMORY_MOVE_TARGET_REQUIRED")
                val parent = resolveVisibleMemory(parentId)
                if (parent.id == memory.id || isDescendantOrSame(parent, memory)) {
                    throw BadRequestException("Cannot move memory into itself", "MEMORY_MOVE_INTO_SELF")
                }
                if (!parent.scope().sameAs(memory.scope())) {
                    throw BadRequestException("Cannot move memory between spaces", "MEMORY_SCOPE_INVALID")
                }
                MemoryMoveTarget(parent, null)
            }
            MoveMemoryTarget.Type.FOLDER -> {
                val folderPath = target.folderPath?.trim()?.takeIf { it.isNotBlank() }
                    ?: throw BadRequestException("folderPath is required", "MEMORY_MOVE_TARGET_REQUIRED")
                val context = resolveVisibleContext(folderPath)
                val folder = (context as? ResolvedMemoryLocation.Folder)?.folder
                    ?: throw BadRequestException("folderPath must point to a folder", "MEMORY_MOVE_TARGET_INVALID")
                if (!folder.scope().sameAs(memory.scope())) {
                    throw BadRequestException("Cannot move memory between spaces", "MEMORY_SCOPE_INVALID")
                }
                MemoryMoveTarget(null, folder)
            }
            MoveMemoryTarget.Type.SCOPE_ROOT -> {
                val scope = target.folderPath?.trim()?.takeIf { it.isNotBlank() }?.let { rootPath ->
                    when (val context = resolveVisibleContext(rootPath)) {
                        ResolvedMemoryLocation.Tenant -> LibraryScopeKey.Tenant
                        is ResolvedMemoryLocation.Group -> context.scope
                        is ResolvedMemoryLocation.User -> context.scope
                        is ResolvedMemoryLocation.Folder -> throw BadRequestException(
                            "folderPath must point to a scope root",
                            "MEMORY_MOVE_TARGET_INVALID",
                        )
                    }
                } ?: memory.scope()
                if (!scope.sameAs(memory.scope())) {
                    throw BadRequestException("Cannot move memory between spaces", "MEMORY_SCOPE_INVALID")
                }
                MemoryMoveTarget(null, null)
            }
        }

    private fun resolveFolderTarget(path: String?): MemoryFolderTarget =
        when (val context = resolveVisibleContext(path)) {
            ResolvedMemoryLocation.Tenant -> MemoryFolderTarget(null, LibraryScopeKey.Tenant)
            is ResolvedMemoryLocation.Group -> MemoryFolderTarget(null, context.scope)
            is ResolvedMemoryLocation.User -> MemoryFolderTarget(null, context.scope)
            is ResolvedMemoryLocation.Folder -> MemoryFolderTarget(context.folder, context.folder.scope())
        }

    private fun resolveVisibleMemoryByPath(path: String?): Memory {
        val segments = pathSegments(path)
        if (segments.isEmpty()) throw NotFoundException("Memory not found")

        segments.indices.reversed().forEach { memoryIndex ->
            val folderPath = segments.take(memoryIndex).toPathOrNull()
            val folderTarget = try {
                resolveFolderTarget(folderPath)
            } catch (_: NotFoundException) {
                return@forEach
            }
            var memory = memoryRepo.listByFolder(tenant(), folderTarget.parentFolder)
                .firstOrNull { it.name == segments[memoryIndex] && it.scope().sameAs(folderTarget.scope) }
                ?: return@forEach
            for (childName in segments.drop(memoryIndex + 1)) {
                memory = memoryRepo.listChildren(tenant(), memory).firstOrNull { it.name == childName }
                    ?: return@forEach
            }
            if (isMemoryVisible(memory)) return memory
        }

        throw NotFoundException("Memory not found")
    }

    private fun pathSegments(path: String?): List<String> =
        path?.trim()?.trim('/')?.split('/')?.filter { it.isNotBlank() } ?: emptyList()

    private fun List<String>.toPathOrNull(): String? =
        if (isEmpty()) null else "/" + joinToString("/")

    private fun Media.contentUrl(): String =
        "/api/${url(tenant().name)}/memory-media/${url(publicId)}/content"

    private fun Media.imageDimensions(): Dimension =
        blob.imageWidth?.let { width ->
            blob.imageHeight?.let { height -> Dimension(width, height) }
        } ?: imageDimensions(storagePath(blob.storageKey), blob.mimeType)

    private fun imageDimensions(path: Path, mimeType: String): Dimension {
        val metadata = mediaMetadataExtractor.extract(path, mimeType)
        val width = metadata.imageWidth
        val height = metadata.imageHeight
        if (width == null || height == null || width <= 0 || height <= 0) {
            throw BadRequestException("Cover source dimensions could not be read", "MEMORY_COVER_DIMENSIONS_UNREADABLE")
        }
        return Dimension(width, height)
    }

    private fun coverDirectory(storageKey: String): Path {
        val coverPath = storagePath(storageKey)
        return coverPath.parent ?: coverPath
    }

    private fun writeFile(source: Path, storageKey: String) {
        val target = storagePath(storageKey)
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, "cover-", ".tmp")
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            throw e
        }
    }

    private fun deleteOldExplicitCover(oldStorageKey: String?, newStorageKey: String?) {
        if (oldStorageKey == null || oldStorageKey == newStorageKey) return
        try {
            deleteRecursively(coverDirectory(oldStorageKey))
        } catch (e: Exception) {
            log.warn("Could not delete old memory cover $oldStorageKey", e)
        }
    }

    private fun storagePath(storageKey: String): Path {
        val root = mediaConfig.storageDir.toAbsolutePath().normalize()
        val path = root.resolve(storageKey).normalize()
        if (!path.startsWith(root)) throw BadRequestException("Invalid media storage key", "MEDIA_STORAGE_KEY_INVALID")
        return path
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private data class MemoryCoverCrop(
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
    )

    private companion object {
        private const val COVER_ASPECT_RATIO = 16.0 / 9.0
        private const val COVER_ASPECT_TOLERANCE = 0.01
    }
}
