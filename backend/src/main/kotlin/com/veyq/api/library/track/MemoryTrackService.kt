package com.veyq.api.library.track

import com.fasterxml.jackson.databind.ObjectMapper
import com.soundicly.jnanoidenhanced.jnanoid.NanoIdUtils
import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.domain.Memory
import com.veyq.api.domain.MemoryRepo
import com.veyq.api.domain.MemoryTrack
import com.veyq.api.domain.MemoryTrackRepo
import com.veyq.api.domain.TenantFolderRepo
import com.veyq.api.domain.TenantGroupMemberRepo
import com.veyq.api.domain.TenantGroupRepo
import com.veyq.api.generated.model.MemoryTrackBounds
import com.veyq.api.generated.model.MemoryTrackGeometry
import com.veyq.api.generated.model.MemoryTrackSummary
import com.veyq.api.generated.model.UpdateMemoryTrackRequest
import com.veyq.api.common.apiPathSegment as url
import com.veyq.api.common.toApiOffsetDateTime
import com.veyq.api.library.LibraryScopeKey
import com.veyq.api.library.LibraryScopes
import com.veyq.api.library.LibraryService
import com.veyq.api.library.media.MediaConfig
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import jakarta.ws.rs.core.Response
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.util.Comparator

@Singleton
class MemoryTrackService(
    private val trackRepo: MemoryTrackRepo,
    private val memoryRepo: MemoryRepo,
    private val mediaConfig: MediaConfig,
    private val gpxTrackParser: GpxTrackParser,
    private val objectMapper: ObjectMapper,
    folderRepo: TenantFolderRepo,
    groupRepo: TenantGroupRepo,
    private val trackGroupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext,
) : LibraryService(folderRepo, groupRepo, trackGroupMemberRepo, requestContext) {
    fun listMemoryTracks(memoryId: String): List<MemoryTrackSummary> {
        val memory = resolveVisibleMemory(memoryId)
        return trackRepo.listByMemory(memory).map { it.toSummary() }
    }

    @Transactional
    fun uploadMemoryTrack(
        memoryId: String,
        uploadPath: Path,
        originalFilename: String?,
        title: String?,
        activityType: com.veyq.api.generated.model.MemoryTrackActivityType?,
    ): MemoryTrackSummary {
        val memory = resolveVisibleMemory(memoryId)
        return uploadTrack(memory, uploadPath, originalFilename, title, activityType).toSummary()
    }

    @Transactional
    fun uploadPublicShareMemoryTrack(
        shareId: String,
        memory: Memory,
        uploadPath: Path,
        originalFilename: String?,
        title: String?,
        activityType: com.veyq.api.generated.model.MemoryTrackActivityType?,
    ): MemoryTrackSummary =
        uploadTrack(memory, uploadPath, originalFilename, title, activityType).toSummary(publicShareId = shareId)

    @Transactional
    fun updateMemoryTrack(memoryId: String, trackId: String, request: UpdateMemoryTrackRequest): MemoryTrackSummary {
        val memory = resolveVisibleMemory(memoryId)
        val track = resolveTrack(memory, trackId)
        track.title = requiredTitle(request.title)
        track.activityType = request.activityType.toDomain()
        return track.toSummary()
    }

    @Transactional
    fun updatePublicShareMemoryTrack(
        shareId: String,
        memory: Memory,
        trackId: String,
        request: UpdateMemoryTrackRequest,
    ): MemoryTrackSummary {
        val track = resolveTrack(memory, trackId)
        track.title = requiredTitle(request.title)
        track.activityType = request.activityType.toDomain()
        return track.toSummary(publicShareId = shareId)
    }

    @Transactional
    fun deleteMemoryTrack(memoryId: String, trackId: String) {
        val memory = resolveVisibleMemory(memoryId)
        deleteTrack(resolveTrack(memory, trackId))
    }

    @Transactional
    fun deletePublicShareMemoryTrack(memory: Memory, trackId: String) {
        deleteTrack(resolveTrack(memory, trackId))
    }

    fun memoryTrackContent(memoryId: String, trackId: String): Response {
        val memory = resolveVisibleMemory(memoryId)
        return trackContent(resolveTrack(memory, trackId))
    }

    fun publicShareMemoryTrackContent(memory: Memory, trackId: String): Response =
        trackContent(resolveTrack(memory, trackId))

    fun listPublicShareMemoryTracks(shareId: String, memory: Memory): List<MemoryTrackSummary> =
        trackRepo.listByMemory(memory).map { it.toSummary(publicShareId = shareId) }

    fun existsInMemory(memory: Memory, trackId: String): Boolean =
        trackRepo.findByTenantAndPublicId(memory.tenant, trackId)?.memory?.id == memory.id

    fun existsInMemoryTree(root: Memory, trackId: String): Boolean {
        val track = trackRepo.findByTenantAndPublicId(root.tenant, trackId) ?: return false
        return isDescendantOrSelf(track.memory, root)
    }

    private fun uploadTrack(
        memory: Memory,
        uploadPath: Path,
        originalFilename: String?,
        title: String?,
        activityType: com.veyq.api.generated.model.MemoryTrackActivityType?,
    ): MemoryTrack {
        val size = Files.size(uploadPath)
        if (size <= 0) throw BadRequestException("file must not be empty", "MEMORY_TRACK_UPLOAD_EMPTY")
        val filename = originalFilename?.trim()?.takeIf { it.isNotBlank() } ?: "track.gpx"
        if (!filename.endsWith(".gpx", ignoreCase = true)) {
            throw BadRequestException("Only GPX tracks are supported", "MEMORY_TRACK_UPLOAD_TYPE_UNSUPPORTED")
        }

        val parsed = gpxTrackParser.parse(uploadPath)
        val publicId = NanoIdUtils.randomNanoId(16)
        val track = MemoryTrack().also {
            it.publicId = publicId
            it.tenant = memory.tenant
            it.memory = memory
            applyScope(it, LibraryScopes.scope(memory))
            it.title = requiredTitle(title ?: filename.substringBeforeLast('.'))
            it.activityType = (activityType ?: throw BadRequestException("activityType is required", "MEMORY_TRACK_ACTIVITY_TYPE_REQUIRED")).toDomain()
            it.originalFilename = filename
            it.sizeBytes = size
            it.distanceMeters = parsed.distanceMeters
            it.ascentMeters = parsed.ascentMeters
            it.descentMeters = parsed.descentMeters
            it.pointCount = parsed.pointCount
            it.startedAt = parsed.startedAt
            it.endedAt = parsed.endedAt
            it.minLatitude = parsed.minLatitude
            it.minLongitude = parsed.minLongitude
            it.maxLatitude = parsed.maxLatitude
            it.maxLongitude = parsed.maxLongitude
            it.geometryJson = parsed.geometryJson
            it.storageKey = "tenant-${memory.tenant.id}/tracks/$publicId/original.gpx"
            trackRepo.persist(it)
            trackRepo.flush()
        }
        writeTrackFile(uploadPath, track.storageKey)
        return track
    }

    private fun resolveVisibleMemory(publicId: String): Memory {
        val memory = memoryRepo.findByTenantAndPublicId(tenant(), publicId)
            ?: throw NotFoundException("Memory not found")
        if (!isMemoryVisible(memory)) throw NotFoundException("Memory not found")
        return memory
    }

    private fun resolveTrack(memory: Memory, trackId: String): MemoryTrack {
        val track = trackRepo.findByTenantAndPublicId(memory.tenant, trackId)
            ?: throw NotFoundException("Track not found")
        if (track.memory.id != memory.id) throw NotFoundException("Track not found")
        return track
    }

    private fun deleteTrack(track: MemoryTrack) {
        val directory = trackDirectory(track)
        track.delete()
        trackRepo.flush()
        deleteRecursively(directory)
    }

    private fun trackContent(track: MemoryTrack): Response {
        val path = storagePath(track.storageKey)
        if (!Files.isRegularFile(path)) throw NotFoundException("Track not found")
        return Response.ok(path.toFile(), "application/gpx+xml")
            .header("Content-Length", Files.size(path))
            .header("Content-Disposition", "inline; filename=\"${safeHeaderFilename(track.originalFilename)}\"")
            .header("X-Content-Type-Options", "nosniff")
            .build()
    }

    private fun applyScope(track: MemoryTrack, scope: LibraryScopeKey) {
        track.scopeType = when (scope) {
            LibraryScopeKey.Tenant -> com.veyq.api.domain.LibraryScopeType.Tenant
            is LibraryScopeKey.Group -> com.veyq.api.domain.LibraryScopeType.Group
            is LibraryScopeKey.User -> com.veyq.api.domain.LibraryScopeType.User
        }
        track.scopeGroup = (scope as? LibraryScopeKey.Group)?.group
        track.scopeUser = (scope as? LibraryScopeKey.User)?.user
    }

    private fun isMemoryVisible(memory: Memory): Boolean =
        memory.tenant.id == tenant().id && when (val scope = memory.scope()) {
            LibraryScopeKey.Tenant -> true
            is LibraryScopeKey.Group -> scope.group.tenant.id == tenant().id && trackGroupMemberRepo.findByGroupAndUser(scope.group, user()) != null
            is LibraryScopeKey.User -> scope.user.id == user().id
        }

    private fun isDescendantOrSelf(memory: Memory, root: Memory): Boolean =
        generateSequence(memory) { it.parentMemory }.any { it.id == root.id }

    private fun requiredTitle(value: String?): String {
        val title = value?.trim()?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("title is required", "MEMORY_TRACK_TITLE_REQUIRED")
        if (title.length > 160) throw BadRequestException("title is too long", "MEMORY_TRACK_TITLE_TOO_LONG")
        return title
    }

    private fun writeTrackFile(source: Path, storageKey: String) {
        val target = storagePath(storageKey)
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, "track-", ".tmp")
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            throw e
        }
    }

    private fun storagePath(storageKey: String): Path {
        val root = mediaConfig.storageDir.toAbsolutePath().normalize()
        val path = root.resolve(storageKey).normalize()
        if (!path.startsWith(root)) throw BadRequestException("Invalid track storage key", "MEMORY_TRACK_STORAGE_KEY_INVALID")
        return path
    }

    private fun trackDirectory(track: MemoryTrack): Path {
        val originalPath = storagePath(track.storageKey)
        return originalPath.parent ?: storagePath("tenant-${track.tenant.id}/tracks/${track.publicId}")
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun MemoryTrack.toSummary(publicShareId: String? = null): MemoryTrackSummary =
        MemoryTrackSummary(
            id = publicId,
            memoryId = memory.publicId,
            title = title,
            activityType = activityType.toApi(),
            originalFilename = originalFilename,
            distanceMeters = distanceMeters,
            ascentMeters = ascentMeters,
            descentMeters = descentMeters,
            pointCount = pointCount,
            bounds = MemoryTrackBounds(
                minLatitude = minLatitude,
                minLongitude = minLongitude,
                maxLatitude = maxLatitude,
                maxLongitude = maxLongitude,
            ),
            geometry = objectMapper.readValue(geometryJson, MemoryTrackGeometry::class.java),
            contentUrl = if (publicShareId == null) {
                "/api/${url(tenant.name)}/memories/${url(memory.publicId)}/tracks/${url(publicId)}/content"
            } else {
                "/api/public-shares/${url(publicShareId)}/memories/${url(memory.publicId)}/tracks/${url(publicId)}/content"
            },
            createdAt = createdAt.toApiOffsetDateTime(),
            updatedAt = updatedAt.toApiOffsetDateTime(),
            startedAt = startedAt?.toApiOffsetDateTime(),
            endedAt = endedAt?.toApiOffsetDateTime(),
        )

    private fun com.veyq.api.generated.model.MemoryTrackActivityType.toDomain(): com.veyq.api.domain.MemoryTrackActivityType =
        com.veyq.api.domain.MemoryTrackActivityType.valueOf(name)

    private fun com.veyq.api.domain.MemoryTrackActivityType.toApi(): com.veyq.api.generated.model.MemoryTrackActivityType =
        com.veyq.api.generated.model.MemoryTrackActivityType.valueOf(name)

    private fun safeHeaderFilename(value: String): String =
        value.replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_")

    private fun String.safeZipSegment(fallback: String = "track"): String =
        replace("\\", "-")
            .replace("/", "-")
            .replace(Regex("[\\x00-\\x1F\\x7F:*?\"<>|]+"), "-")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .takeIf { it.isNotBlank() }
            ?: fallback

    fun archiveFilename(track: com.veyq.api.domain.ArchiveTrackRow, locale: String?): String {
        val fallback = when (locale?.trim()?.lowercase()?.substringBefore('-')) {
            "en" -> "track-${track.publicId}.gpx"
            else -> "track-${track.publicId}.gpx"
        }
        val name = track.originalFilename.takeIf { it.isNotBlank() }?.safeZipSegment(fallback)
            ?: track.title.takeIf { it.isNotBlank() }?.safeZipSegment(fallback)
            ?: fallback
        return "${ARCHIVE_TIMESTAMP_FORMAT.format(track.sortTime)} ${ensureGpxExtension(name)}"
    }

    private fun ensureGpxExtension(name: String): String =
        if (name.substringAfterLast('/').contains('.')) name else "$name.gpx"

    companion object {
        private val ARCHIVE_TIMESTAMP_FORMAT = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH-mm-ss")
            .withZone(ZoneOffset.UTC)
    }
}
