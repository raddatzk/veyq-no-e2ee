package com.veyq.api.library.media

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.domain.ArchiveMediaKeyset
import com.veyq.api.domain.ArchiveMediaRow
import com.veyq.api.domain.ArchiveTrackKeyset
import com.veyq.api.domain.ArchiveTrackRow
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.domain.Media
import com.veyq.api.domain.MediaBlob
import com.veyq.api.domain.MediaBlobRepo
import com.veyq.api.domain.MediaRepo
import com.veyq.api.domain.Memory
import com.veyq.api.domain.MemoryShareIdentity
import com.veyq.api.domain.MemoryShareIdentityRepo
import com.veyq.api.domain.MemoryRepo
import com.veyq.api.domain.MemoryTrackRepo
import com.veyq.api.domain.TenantFolderRepo
import com.veyq.api.domain.TenantGroupMemberRepo
import com.veyq.api.domain.TenantGroupRepo
import com.veyq.api.domain.User
import com.veyq.api.domain.UserRepo
import com.veyq.api.generated.model.CreateMemoryMediaUploadRequest
import com.veyq.api.generated.model.MediaUploader
import com.veyq.api.generated.model.MemoryMediaUploadSession
import com.veyq.api.generated.model.MemoryMediaBatchResponse
import com.veyq.api.generated.model.MemoryMediaSummary
import com.veyq.api.generated.model.PendingMemoryMediaUploadSession
import com.veyq.api.generated.model.PublicShareMediaSummary
import com.veyq.api.generated.model.UpdateMemoryMediaMetadataRequest
import com.veyq.api.generated.model.UpdateMemoryMediaMetadataBatchRequest
import com.veyq.api.library.LibraryScopeKey
import com.veyq.api.library.LibraryService
import com.veyq.api.library.LibraryScopes
import com.veyq.api.library.track.MemoryTrackService
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.StreamingOutput
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.multipart.FileUpload
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Comparator
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

@Singleton
class MediaService @Inject constructor(
    private val mediaRepo: MediaRepo,
    private val trackRepo: MemoryTrackRepo,
    private val mediaBlobRepo: MediaBlobRepo,
    private val memoryRepo: MemoryRepo,
    private val userRepo: UserRepo,
    private val shareIdentityRepo: MemoryShareIdentityRepo,
    private val mediaConfig: MediaConfig,
    private val mediaTypeDetector: MediaTypeDetector,
    private val mediaMetadataExtractor: MediaMetadataExtractor,
    private val trackService: MemoryTrackService,
    folderRepo: TenantFolderRepo,
    groupRepo: TenantGroupRepo,
    private val mediaGroupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext,
    private val self: Instance<MediaService>,
) : LibraryService(folderRepo, groupRepo, mediaGroupMemberRepo, requestContext) {
    private val log: Logger = Logger.getLogger(MediaService::class.java)

    private data class UploadSession(
        val uploadId: String,
        val tenantId: Long,
        val memoryId: Long,
        val memoryPublicId: String,
        val filename: String,
        val mimeType: String,
        val sizeBytes: Long,
        val title: String?,
        val uploaderUserId: Long?,
        val shareIdentityId: Long?,
        val shareId: String?,
        val createdAt: Instant,
        val updatedAt: Instant,
    )

    data class PreparedMemoryUpload(
        val tenantId: Long,
        val memoryId: Long,
        val originalFilename: String?,
        val title: String?,
        val uploaderUserId: Long?,
        val shareIdentityId: Long?,
        val sizeBytes: Long,
        val mimeType: String,
        val contentSha256: String,
        val storageKey: String,
        val metadata: ExtractedMediaMetadata,
    )

    fun listMemoryMedia(memoryId: String, includeChildren: Boolean): List<MemoryMediaSummary> {
        val memory = resolveVisibleMemory(memoryId)
        val memories = if (includeChildren) descendantsAndSelf(memory) else listOf(memory)
        return memories
            .filter(::isMemoryVisible)
            .flatMap { mediaRepo.listByMemory(it) }
            .distinctBy { it.id }
            .sortedWith(compareBy<Media> { it.capturedAt ?: it.createdAt }.thenBy { it.createdAt })
            .map { it.toMemorySummary() }
    }

    fun downloadMemoryMedia(memoryId: String, includeChildren: Boolean, locale: String?): Response {
        val memory = resolveVisibleMemory(memoryId)
        val memories = if (includeChildren) descendantsAndSelf(memory).filter(::isMemoryVisible) else listOf(memory)
        return archiveResponse(
            memoryDirectories = archiveMemoryDirectories(memory, memories),
            archiveName = archiveFilename(memory, includeChildren, locale),
            locale = locale,
        )
    }

    fun archiveResponse(memoryDirectories: Map<Long, String>, archiveName: String, locale: String?): Response {
        val memoryIds = memoryDirectories.keys
        val output = StreamingOutput { stream ->
            try {
                ZipOutputStream(stream).use { zip ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    val usedNames = mutableMapOf<String, Int>()
                    var after: ArchiveMediaKeyset? = null
                    while (true) {
                        val rows = mediaRepo.listArchiveRowsByMemoryIds(memoryIds, after, mediaConfig.archiveBatchSize)
                        if (rows.isEmpty()) break

                        rows.forEach { row ->
                            val path = storagePath(row.storageKey)
                            if (!Files.isRegularFile(path)) throw NotFoundException("Media file not found")
                            val zipEntry = ZipEntry(archiveEntryName(memoryDirectories, row, locale, usedNames))
                            zipEntry.time = row.sortTime.toEpochMilli()
                            zip.putNextEntry(zipEntry)
                            Files.newInputStream(path).use { input ->
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    zip.write(buffer, 0, read)
                                }
                            }
                            zip.closeEntry()
                        }
                        val last = rows.last()
                        after = ArchiveMediaKeyset(last.sortTime, last.createdAt, last.id)
                    }
                    var trackAfter: ArchiveTrackKeyset? = null
                    while (true) {
                        val rows = trackRepo.listArchiveRowsByMemoryIds(memoryIds, trackAfter, mediaConfig.archiveBatchSize)
                        if (rows.isEmpty()) break

                        rows.forEach { row ->
                            val path = storagePath(row.storageKey)
                            if (!Files.isRegularFile(path)) throw NotFoundException("Track file not found")
                            val zipEntry = ZipEntry(trackArchiveEntryName(memoryDirectories, row, locale, usedNames))
                            zipEntry.time = row.sortTime.toEpochMilli()
                            zip.putNextEntry(zipEntry)
                            Files.newInputStream(path).use { input ->
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    zip.write(buffer, 0, read)
                                }
                            }
                            zip.closeEntry()
                        }
                        val last = rows.last()
                        trackAfter = ArchiveTrackKeyset(last.sortTime, last.createdAt, last.id)
                    }
                }
            } catch (e: IOException) {
                if (isClientAbort(e)) {
                    log.debug("Media archive stream ended because the client closed the connection")
                    return@StreamingOutput
                }
                throw e
            }
        }

        return Response.ok(output, "application/zip")
            .header("Content-Disposition", "attachment; filename=\"${safeHeaderFilename(archiveName)}\"")
            .header("X-Content-Type-Options", "nosniff")
            .build()
    }

    @Transactional
    fun uploadMemoryMedia(memoryId: String, file: FileUpload?, title: String?): MemoryMediaSummary {
        val memory = resolveVisibleMemory(memoryId)
        val upload = file ?: throw BadRequestException("file is required", "MEDIA_UPLOAD_REQUIRED")
        return uploadMemoryMediaInternal(
            memory = memory,
            uploadPath = upload.uploadedFile(),
            originalFilename = upload.fileName(),
            title = title,
            uploaderUser = user(),
            shareIdentity = null,
        ).toMemorySummary()
    }

    @Transactional
    fun uploadPublicShareMedia(
        shareId: String,
        memory: Memory,
        uploadPath: Path,
        originalFilename: String?,
        title: String?,
        uploaderUser: User?,
        shareIdentity: MemoryShareIdentity?,
    ): PublicShareMediaSummary =
        uploadMemoryMediaInternal(
            memory = memory,
            uploadPath = uploadPath,
            originalFilename = originalFilename,
            title = title,
            uploaderUser = uploaderUser,
            shareIdentity = shareIdentity,
        ).toPublicShareSummary(shareId)

    fun createMemoryMediaUpload(memoryId: String, request: CreateMemoryMediaUploadRequest): MemoryMediaUploadSession {
        val memory = resolveVisibleMemory(memoryId)
        return createUploadSession(
            memory = memory,
            request = request,
            uploaderUser = user(),
            shareIdentity = null,
            shareId = null,
        )
    }

    fun createPublicShareMediaUpload(
        shareId: String,
        memory: Memory,
        request: CreateMemoryMediaUploadRequest,
        uploaderUser: User?,
        shareIdentity: MemoryShareIdentity?,
    ): MemoryMediaUploadSession =
        createUploadSession(memory, request, uploaderUser, shareIdentity, shareId)

    fun memoryMediaUploadStatus(memoryId: String, uploadId: String): PendingMemoryMediaUploadSession {
        val memory = resolveVisibleMemory(memoryId)
        val session = loadUploadSession(uploadId)
        requireUploadSessionAccess(session, memory, user(), null, null)
        return session.toPendingSummary()
    }

    fun publicShareMediaUploadStatus(
        shareId: String,
        memory: Memory,
        uploadId: String,
        uploaderUser: User?,
        shareIdentity: MemoryShareIdentity?,
    ): PendingMemoryMediaUploadSession {
        val session = loadUploadSession(uploadId)
        requireUploadSessionAccess(session, memory, uploaderUser, shareIdentity, shareId)
        return session.toPendingSummary()
    }

    fun uploadMemoryMediaChunk(memoryId: String, uploadId: String, chunkIndex: Int, chunk: Path) {
        val memory = resolveVisibleMemory(memoryId)
        val session = loadUploadSession(uploadId)
        requireUploadSessionAccess(session, memory, user(), null, null)
        uploadChunk(session, chunkIndex, chunk)
    }

    fun uploadPublicShareMediaChunk(
        shareId: String,
        memory: Memory,
        uploadId: String,
        chunkIndex: Int,
        chunk: Path,
        uploaderUser: User?,
        shareIdentity: MemoryShareIdentity?,
    ) {
        val session = loadUploadSession(uploadId)
        requireUploadSessionAccess(session, memory, uploaderUser, shareIdentity, shareId)
        uploadChunk(session, chunkIndex, chunk)
    }

    fun completeMemoryMediaUpload(memoryId: String, uploadId: String): MemoryMediaSummary {
        val memory = resolveVisibleMemory(memoryId)
        val session = loadUploadSession(uploadId)
        val uploaderUser = user()
        requireUploadSessionAccess(session, memory, uploaderUser, null, null)
        val temp = assembleUploadSession(session)
        var completed = false
        try {
            val prepared = prepareMemoryUpload(
                tenantId = memory.tenant.id,
                memoryId = memory.id,
                uploadPath = temp,
                originalFilename = session.filename,
                title = session.title,
                uploaderUserId = uploaderUser.id,
                shareIdentityId = null,
            )
            val summary = self.get().persistPreparedMemoryUpload(prepared).toMemorySummary()
            completed = true
            return summary
        } finally {
            Files.deleteIfExists(temp)
            if (completed) deleteUploadSession(uploadId, "complete")
        }
    }

    fun completePublicShareMediaUpload(
        shareId: String,
        memory: Memory,
        uploadId: String,
        uploaderUser: User?,
        shareIdentity: MemoryShareIdentity?,
    ): PublicShareMediaSummary {
        val session = loadUploadSession(uploadId)
        requireUploadSessionAccess(session, memory, uploaderUser, shareIdentity, shareId)
        val temp = assembleUploadSession(session)
        var completed = false
        try {
            val prepared = prepareMemoryUpload(
                tenantId = memory.tenant.id,
                memoryId = memory.id,
                uploadPath = temp,
                originalFilename = session.filename,
                title = session.title,
                uploaderUserId = uploaderUser?.id,
                shareIdentityId = shareIdentity?.id,
            )
            val summary = self.get().persistPreparedMemoryUpload(prepared).toPublicShareSummary(shareId)
            completed = true
            return summary
        } finally {
            Files.deleteIfExists(temp)
            if (completed) deleteUploadSession(uploadId, "complete")
        }
    }

    fun abortMemoryMediaUpload(memoryId: String, uploadId: String) {
        val memory = resolveVisibleMemory(memoryId)
        val session = loadUploadSession(uploadId)
        requireUploadSessionAccess(session, memory, user(), null, null)
        deleteUploadSession(uploadId, "abort")
    }

    fun abortPublicShareMediaUpload(
        shareId: String,
        memory: Memory,
        uploadId: String,
        uploaderUser: User?,
        shareIdentity: MemoryShareIdentity?,
    ) {
        val session = loadUploadSession(uploadId)
        requireUploadSessionAccess(session, memory, uploaderUser, shareIdentity, shareId)
        deleteUploadSession(uploadId, "abort")
    }

    fun deleteUploadSessionsForMemoryTree(tenantId: Long, memoryIds: Collection<Long>) {
        val memoryIdSet = memoryIds.toSet()
        if (memoryIdSet.isEmpty()) return
        deleteUploadSessions("memory tree") { session ->
            session.tenantId == tenantId && session.memoryId in memoryIdSet
        }
    }

    fun deleteUploadSessionsForTenant(tenantId: Long) {
        deleteUploadSessions("tenant $tenantId") { session -> session.tenantId == tenantId }
    }

    @Scheduled(cron = "{veyq.media.upload-session-cleanup-cron}")
    fun cleanupUploadSessions() {
        val expiresBefore = Instant.now().minusSeconds(mediaConfig.uploadSessionTtlHours * 60 * 60)
        uploadSessions()
            .filter { it.updatedAt.isBefore(expiresBefore) }
            .forEach { session ->
                deleteUploadSession(session.uploadId, "ttl cleanup")
            }
    }

    private fun deleteUploadSessions(reason: String, predicate: (UploadSession) -> Boolean) {
        uploadSessions()
            .filter(predicate)
            .forEach { session ->
                deleteUploadSession(session.uploadId, reason)
            }
    }

    private fun deleteUploadSession(uploadId: String, reason: String) {
        try {
            deleteRecursively(uploadDirectory(uploadId))
        } catch (e: Exception) {
            log.warn("Failed to delete upload session $uploadId for $reason", e)
        }
    }

    private fun uploadMemoryMediaInternal(
        memory: Memory,
        uploadPath: Path,
        originalFilename: String?,
        title: String?,
        uploaderUser: User?,
        shareIdentity: MemoryShareIdentity?,
    ): Media {
        if ((uploaderUser == null) == (shareIdentity == null)) {
            throw BadRequestException("Exactly one uploader source is required")
        }

        val size = Files.size(uploadPath)
        if (size <= 0) throw BadRequestException("file must not be empty", "MEDIA_UPLOAD_EMPTY")
        val mediaType = mediaTypeDetector.detect(uploadPath)
            ?: throw BadRequestException("Only JPEG, PNG, GIF, MP4 and QuickTime uploads are supported", "MEDIA_UPLOAD_TYPE_UNSUPPORTED")
        val contentSha256 = sha256(uploadPath)
        val blob = mediaBlobRepo.findByTenantAndSha256(memory.tenant, contentSha256)
            ?: createMediaBlob(memory, uploadPath, mediaType.mimeType, size, contentSha256)

        val media = Media().also {
            it.tenant = memory.tenant
            applyScope(it, LibraryScopes.scope(memory))
            it.memory = memory
            it.title = title?.trim()?.takeIf { value -> value.isNotBlank() }
                ?: originalFilename?.substringBeforeLast('.')?.takeIf { value -> value.isNotBlank() }
                ?: "Upload"
            it.blob = blob
            it.originalFilename = originalFilename
            it.uploaderUser = uploaderUser
            it.shareIdentity = shareIdentity
            it.capturedAt = blob.capturedAt
            it.capturedAtOverride = false
            mediaRepo.persist(it)
        }

        return media
    }

    private fun prepareMemoryUpload(
        tenantId: Long,
        memoryId: Long,
        uploadPath: Path,
        originalFilename: String?,
        title: String?,
        uploaderUserId: Long?,
        shareIdentityId: Long?,
    ): PreparedMemoryUpload {
        if ((uploaderUserId == null) == (shareIdentityId == null)) {
            throw BadRequestException("Exactly one uploader source is required")
        }

        val size = Files.size(uploadPath)
        if (size <= 0) throw BadRequestException("file must not be empty", "MEDIA_UPLOAD_EMPTY")
        val mediaType = mediaTypeDetector.detect(uploadPath)
            ?: throw BadRequestException("Only JPEG, PNG, GIF, MP4 and QuickTime uploads are supported", "MEDIA_UPLOAD_TYPE_UNSUPPORTED")
        val contentSha256 = sha256(uploadPath)
        val storageKey = storageKeyForBlob(tenantId, contentSha256)
        if (!Files.isRegularFile(storagePath(storageKey))) {
            writeMediaFile(uploadPath, storageKey)
        }
        val metadata = mediaMetadataExtractor.extract(storagePath(storageKey), mediaType.mimeType)

        return PreparedMemoryUpload(
            tenantId = tenantId,
            memoryId = memoryId,
            originalFilename = originalFilename,
            title = title,
            uploaderUserId = uploaderUserId,
            shareIdentityId = shareIdentityId,
            sizeBytes = size,
            mimeType = mediaType.mimeType,
            contentSha256 = contentSha256,
            storageKey = storageKey,
            metadata = metadata,
        )
    }

    @Transactional
    fun persistPreparedMemoryUpload(prepared: PreparedMemoryUpload): Media {
        val memory = memoryRepo.findById(prepared.memoryId) ?: throw NotFoundException("Memory not found")
        if (memory.tenant.id != prepared.tenantId) throw NotFoundException("Memory not found")
        val uploaderUser = prepared.uploaderUserId?.let { userRepo.findById(it) ?: throw NotFoundException("User not found") }
        val shareIdentity = prepared.shareIdentityId?.let { shareIdentityRepo.findById(it) ?: throw NotFoundException("Share identity not found") }
        if ((uploaderUser == null) == (shareIdentity == null)) {
            throw BadRequestException("Exactly one uploader source is required")
        }

        val blob = mediaBlobRepo.findByTenantAndSha256(memory.tenant, prepared.contentSha256)
            ?: createPreparedMediaBlob(memory, prepared)

        return Media().also {
            it.tenant = memory.tenant
            applyScope(it, LibraryScopes.scope(memory))
            it.memory = memory
            it.title = prepared.title?.trim()?.takeIf { value -> value.isNotBlank() }
                ?: prepared.originalFilename?.substringBeforeLast('.')?.takeIf { value -> value.isNotBlank() }
                ?: "Upload"
            it.blob = blob
            it.originalFilename = prepared.originalFilename
            it.uploaderUser = uploaderUser
            it.shareIdentity = shareIdentity
            it.capturedAt = blob.capturedAt
            it.capturedAtOverride = false
            mediaRepo.persist(it)
        }
    }

    private fun createPreparedMediaBlob(memory: Memory, prepared: PreparedMemoryUpload): MediaBlob =
        MediaBlob().also {
            it.tenant = memory.tenant
            it.contentSha256 = prepared.contentSha256
            it.storageKey = prepared.storageKey
            it.mimeType = prepared.mimeType
            it.sizeBytes = prepared.sizeBytes
            mediaMetadataExtractor.apply(it, prepared.metadata)
            mediaBlobRepo.persist(it)
            mediaBlobRepo.flush()
        }

    private fun createMediaBlob(
        memory: Memory,
        uploadPath: Path,
        mimeType: String,
        size: Long,
        contentSha256: String,
    ): MediaBlob {
        val storageKey = storageKeyForBlob(memory.tenant.id, contentSha256)
        writeMediaFile(uploadPath, storageKey)
        return MediaBlob().also {
            it.tenant = memory.tenant
            it.contentSha256 = contentSha256
            it.storageKey = storageKey
            it.mimeType = mimeType
            it.sizeBytes = size
            mediaMetadataExtractor.apply(it, mediaMetadataExtractor.extract(storagePath(storageKey), mimeType))
            mediaBlobRepo.persist(it)
            mediaBlobRepo.flush()
            generatePreviewIfSupported(it)
        }
    }

    private fun storageKeyForBlob(tenantId: Long, contentSha256: String): String =
        "tenant-$tenantId/blobs/$contentSha256/original"

    private fun createUploadSession(
        memory: Memory,
        request: CreateMemoryMediaUploadRequest,
        uploaderUser: User?,
        shareIdentity: MemoryShareIdentity?,
        shareId: String?,
    ): MemoryMediaUploadSession {
        if ((uploaderUser == null) == (shareIdentity == null)) {
            throw BadRequestException("Exactly one uploader source is required")
        }
        val filename = request.filename.trim().takeIf { it.isNotBlank() }
            ?: throw BadRequestException("filename is required", "MEDIA_UPLOAD_FILENAME_REQUIRED")
        val mimeType = request.mimeType.trim().takeIf { it.isNotBlank() }
            ?: throw BadRequestException("mimeType is required", "MEDIA_UPLOAD_TYPE_REQUIRED")
        if (request.sizeBytes <= 0) throw BadRequestException("file must not be empty", "MEDIA_UPLOAD_EMPTY")

        val now = Instant.now()
        val session = UploadSession(
            uploadId = UUID.randomUUID().toString(),
            tenantId = memory.tenant.id,
            memoryId = memory.id,
            memoryPublicId = memory.publicId,
            filename = filename,
            mimeType = mimeType,
            sizeBytes = request.sizeBytes,
            title = request.title?.trim()?.takeIf { it.isNotBlank() },
            uploaderUserId = uploaderUser?.id,
            shareIdentityId = shareIdentity?.id,
            shareId = shareId,
            createdAt = now,
            updatedAt = now,
        )
        Files.createDirectories(uploadDirectory(session.uploadId).resolve("chunks"))
        saveUploadSession(session)
        return MemoryMediaUploadSession(uploadId = session.uploadId, chunkSizeBytes = mediaConfig.chunkSizeBytes)
    }

    private fun uploadChunk(session: UploadSession, chunkIndex: Int, source: Path) {
        if (chunkIndex < 0) throw BadRequestException("chunkIndex must not be negative", "MEDIA_UPLOAD_CHUNK_INVALID")
        val chunkSize = Files.size(source)
        if (chunkSize <= 0) throw BadRequestException("chunk must not be empty", "MEDIA_UPLOAD_CHUNK_EMPTY")
        val chunkCount = uploadChunkCount(session)
        if (chunkIndex >= chunkCount) throw BadRequestException("chunkIndex is outside upload range", "MEDIA_UPLOAD_CHUNK_INVALID")
        val maxChunkSize = if (chunkIndex == chunkCount - 1) {
            session.sizeBytes - (chunkIndex.toLong() * mediaConfig.chunkSizeBytes)
        } else {
            mediaConfig.chunkSizeBytes
        }
        if (chunkSize > maxChunkSize) throw BadRequestException("chunk is too large", "MEDIA_UPLOAD_CHUNK_TOO_LARGE")

        val chunks = uploadDirectory(session.uploadId).resolve("chunks")
        Files.createDirectories(chunks)
        val target = chunks.resolve("$chunkIndex.part")
        val temp = Files.createTempFile(chunks, "$chunkIndex-", ".tmp")
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            updateUploadSessionTimestamp(session.uploadId)
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            throw e
        }
    }

    private fun assembleUploadSession(session: UploadSession): Path {
        val chunkCount = uploadChunkCount(session)
        val temp = Files.createTempFile("veyq-memory-upload-assemble-", ".tmp")
        try {
            Files.newOutputStream(temp).use { output ->
                for (index in 0 until chunkCount) {
                    val chunk = uploadDirectory(session.uploadId).resolve("chunks").resolve("$index.part")
                    if (!Files.isRegularFile(chunk)) {
                        throw BadRequestException("Missing upload chunk $index", "MEDIA_UPLOAD_CHUNK_MISSING")
                    }
                    Files.newInputStream(chunk).use { input ->
                        while (true) {
                            val read = input.readNBytes(DEFAULT_BUFFER_SIZE)
                            if (read.isEmpty()) break
                            output.write(read)
                        }
                    }
                }
            }
            if (Files.size(temp) != session.sizeBytes) {
                throw BadRequestException("Upload size mismatch", "MEDIA_UPLOAD_SIZE_MISMATCH")
            }
            return temp
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            throw e
        }
    }

    private fun saveUploadSession(session: UploadSession) {
        val properties = Properties().also {
            it["uploadId"] = session.uploadId
            it["tenantId"] = session.tenantId.toString()
            it["memoryId"] = session.memoryId.toString()
            it["memoryPublicId"] = session.memoryPublicId
            it["filename"] = session.filename
            it["mimeType"] = session.mimeType
            it["sizeBytes"] = session.sizeBytes.toString()
            it["createdAt"] = session.createdAt.toString()
            it["updatedAt"] = session.updatedAt.toString()
            session.title?.let { title -> it["title"] = title }
            session.uploaderUserId?.let { userId -> it["uploaderUserId"] = userId.toString() }
            session.shareIdentityId?.let { identityId -> it["shareIdentityId"] = identityId.toString() }
            session.shareId?.let { shareId -> it["shareId"] = shareId }
        }
        Files.createDirectories(uploadDirectory(session.uploadId))
        Files.newOutputStream(uploadDirectory(session.uploadId).resolve("session.properties")).use { output ->
            properties.store(output, null)
        }
    }

    private fun updateUploadSessionTimestamp(uploadId: String) {
        val path = uploadDirectory(uploadId).resolve("session.properties")
        if (!Files.isRegularFile(path)) throw NotFoundException("Upload not found")
        val properties = Properties()
        Files.newInputStream(path).use { input -> properties.load(input) }
        properties["updatedAt"] = Instant.now().toString()
        Files.newOutputStream(path).use { output -> properties.store(output, null) }
    }

    private fun loadUploadSession(uploadId: String): UploadSession {
        if (!uploadId.matches(Regex("^[0-9a-fA-F-]{36}$"))) throw NotFoundException("Upload not found")
        val path = uploadDirectory(uploadId).resolve("session.properties")
        if (!Files.isRegularFile(path)) throw NotFoundException("Upload not found")
        return try {
            loadUploadSession(path)
        } catch (e: Exception) {
            log.warn("Ignoring invalid upload session $path: ${e.message}")
            throw NotFoundException("Upload not found")
        }
    }

    private fun loadUploadSession(path: Path): UploadSession {
        val properties = Properties()
        Files.newInputStream(path).use { input -> properties.load(input) }
        val updatedAt = properties.getProperty("updatedAt")?.let(Instant::parse)
            ?: Files.getLastModifiedTime(path).toInstant()
        val createdAt = properties.getProperty("createdAt")?.let(Instant::parse) ?: updatedAt
        return UploadSession(
            uploadId = properties.getProperty("uploadId"),
            tenantId = properties.getProperty("tenantId").toLong(),
            memoryId = properties.getProperty("memoryId").toLong(),
            memoryPublicId = properties.getProperty("memoryPublicId"),
            filename = properties.getProperty("filename"),
            mimeType = properties.getProperty("mimeType"),
            sizeBytes = properties.getProperty("sizeBytes").toLong(),
            title = properties.getProperty("title"),
            uploaderUserId = properties.getProperty("uploaderUserId")?.toLong(),
            shareIdentityId = properties.getProperty("shareIdentityId")?.toLong(),
            shareId = properties.getProperty("shareId"),
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun uploadSessions(): List<UploadSession> {
        val root = storagePath("_uploads")
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .map { it.resolve("session.properties") }
                .filter { Files.isRegularFile(it) }
                .toList()
                .mapNotNull { path ->
                    try {
                        loadUploadSession(path)
                    } catch (e: Exception) {
                        log.warn("Ignoring invalid upload session $path: ${e.message}")
                        null
                    }
                }
        }
    }

    private fun requireUploadSessionAccess(
        session: UploadSession,
        memory: Memory,
        uploaderUser: User?,
        shareIdentity: MemoryShareIdentity?,
        shareId: String?,
    ) {
        if (session.tenantId != memory.tenant.id || session.memoryId != memory.id) {
            throw NotFoundException("Upload not found")
        }
        if (session.shareId != shareId) throw NotFoundException("Upload not found")
        if (session.uploaderUserId != uploaderUser?.id) throw NotFoundException("Upload not found")
        if (session.shareIdentityId != shareIdentity?.id) throw NotFoundException("Upload not found")
    }

    private fun UploadSession.toPendingSummary(): PendingMemoryMediaUploadSession {
        val uploadedChunks = uploadedChunks(uploadId)
        val uploadedBytes = uploadedChunks.sumOf { index ->
            Files.size(uploadDirectory(uploadId).resolve("chunks").resolve("$index.part"))
        }.coerceAtMost(sizeBytes)
        return PendingMemoryMediaUploadSession(
            uploadId = uploadId,
            memoryId = memoryPublicId,
            filename = filename,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            title = title,
            chunkSizeBytes = mediaConfig.chunkSizeBytes,
            uploadedChunks = uploadedChunks,
            uploadedBytes = uploadedBytes,
            createdAt = createdAt.toApiOffsetDateTime(),
            updatedAt = updatedAt.toApiOffsetDateTime(),
        )
    }

    private fun uploadedChunks(uploadId: String): List<Int> {
        val chunks = uploadDirectory(uploadId).resolve("chunks")
        if (!Files.isDirectory(chunks)) return emptyList()
        return Files.list(chunks).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .toList()
                .mapNotNull { path ->
                    val name = path.fileName.toString()
                    if (name.endsWith(".part")) name.removeSuffix(".part").toIntOrNull() else null
                }
                .sorted()
        }
    }

    private fun uploadDirectory(uploadId: String): Path =
        storagePath("_uploads/$uploadId")

    private fun uploadChunkCount(session: UploadSession): Int =
        ((session.sizeBytes + mediaConfig.chunkSizeBytes - 1) / mediaConfig.chunkSizeBytes).toInt()

    @Transactional
    fun updateMemoryMediaMetadata(memoryId: String, publicId: String, request: UpdateMemoryMediaMetadataRequest): MemoryMediaSummary {
        val memory = resolveVisibleMemory(memoryId)
        val media = mediaRepo.findByTenantAndPublicId(tenant(), publicId)
            ?: throw NotFoundException("Media not found")
        if (media.memory?.id != memory.id) throw NotFoundException("Media not found")

        media.capturedAt = request.capturedAt?.toInstant()
        media.capturedAtOverride = true
        return media.toMemorySummary()
    }

    @Transactional
    fun updateMemoryMediaMetadataBatch(
        memoryId: String,
        request: UpdateMemoryMediaMetadataBatchRequest,
    ): MemoryMediaBatchResponse {
        val memory = resolveVisibleMemory(memoryId)
        val mediaIds = request.mediaIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (mediaIds.isEmpty()) throw BadRequestException("mediaIds must not be empty", "MEDIA_BATCH_EMPTY")

        val updated = mediaIds.map { publicId ->
            val media = mediaRepo.findByTenantAndPublicId(tenant(), publicId)
                ?: throw NotFoundException("Media not found")
            if (media.memory?.id != memory.id) throw NotFoundException("Media not found")

            media.capturedAt = (media.capturedAt ?: media.createdAt).plusSeconds(request.capturedAtOffsetSeconds)
            media.capturedAtOverride = true
            media
        }
        return MemoryMediaBatchResponse(media = updated.map { it.toMemorySummary() })
    }

    fun mediaContent(publicId: String, rangeHeader: String?): Response {
        val media = resolveAccessibleMedia(publicId)
        return streamOriginal(media, rangeHeader)
    }

    fun streamOriginal(media: Media, rangeHeader: String?): Response {
        val path = storagePath(media.blob.storageKey)
        if (!Files.isRegularFile(path)) throw NotFoundException("Media file not found")
        val filename = downloadFilename(
            media.originalFilename?.takeIf { it.isNotBlank() } ?: media.publicId,
            media.blob.mimeType,
        )
        val size = Files.size(path)
        val range = MediaRanges.parse(rangeHeader, size)

        if (rangeHeader != null && range == null) {
            return Response.status(Response.Status.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header("Accept-Ranges", "bytes")
                .header("Content-Range", "bytes */$size")
                .build()
        }

        if (range != null) {
            return Response.status(Response.Status.PARTIAL_CONTENT)
                .type(media.blob.mimeType)
                .entity(streamRange(path, range))
                .header("Accept-Ranges", "bytes")
                .header("Content-Length", range.length)
                .header("Content-Range", "bytes ${range.start}-${range.end}/$size")
                .header("Content-Disposition", "inline; filename=\"${safeHeaderFilename(filename)}\"")
                .header("X-Content-Type-Options", "nosniff")
                .build()
        }

        return Response.ok(path.toFile(), media.blob.mimeType)
            .header("Accept-Ranges", "bytes")
            .header("Content-Length", media.blob.sizeBytes)
            .header("Content-Disposition", "inline; filename=\"${safeHeaderFilename(filename)}\"")
            .header("X-Content-Type-Options", "nosniff")
            .build()
    }

    fun mediaPreview(publicId: String): Response {
        val media = resolveAccessibleMedia(publicId)
        return streamPreview(media)
    }

    fun streamPreview(media: Media): Response {
        if (!canGeneratePreview(media.blob)) throw NotFoundException("Media not found")

        val previewPath = previewPath(media.blob)
        if (!Files.isRegularFile(previewPath)) {
            if (!generatePreview(media.blob, throwOnFailure = false)) throw NotFoundException("Media preview not found")
        }

        return Response.ok(previewPath.toFile(), "image/jpeg")
            .header("Cache-Control", "private, max-age=31536000, immutable")
            .header("Content-Length", Files.size(previewPath))
            .header("Content-Disposition", "inline; filename=\"${safeHeaderFilename(media.publicId)}-preview.jpg\"")
            .header("X-Content-Type-Options", "nosniff")
            .build()
    }

    fun publicShareSummary(media: Media, shareId: String): PublicShareMediaSummary =
        media.toPublicShareSummary(shareId)

    fun deleteBlobIfUnreferenced(blob: MediaBlob) {
        if (mediaRepo.count("blob", blob) > 0) return
        val directory = blobDirectory(blob)
        blob.delete()
        mediaBlobRepo.flush()
        deleteRecursively(directory)
    }

    private fun resolveAccessibleMedia(publicId: String): Media {
        val media = mediaRepo.findByTenantAndPublicId(tenant(), publicId)
            ?: throw NotFoundException("Media not found")
        if (media.memory?.let(::isMemoryVisible) != true) throw NotFoundException("Media not found")
        return media
    }

    private fun resolveVisibleMemory(publicId: String): Memory {
        val memory = memoryRepo.findByTenantAndPublicId(tenant(), publicId)
            ?: throw NotFoundException("Memory not found")
        if (!isMemoryVisible(memory)) throw NotFoundException("Memory not found")
        return memory
    }

    private fun isMemoryVisible(memory: Memory): Boolean =
        memory.tenant.id == tenant().id && when (val scope = memory.scope()) {
            LibraryScopeKey.Tenant -> true
            is LibraryScopeKey.Group -> scope.group.tenant.id == tenant().id && mediaGroupMemberRepo.findByGroupAndUser(scope.group, user()) != null
            is LibraryScopeKey.User -> scope.user.id == user().id
        }

    private fun descendantsAndSelf(root: Memory): List<Memory> =
        listOf(root) + memoryRepo.list("tenant", tenant())
            .filter { candidate -> generateSequence(candidate.parentMemory) { it.parentMemory }.any { it.id == root.id } }

    fun archiveMemoryDirectories(rootMemory: Memory, memories: List<Memory>): Map<Long, String> =
        memories.associate { memory -> memory.id to memoryPath(rootMemory, memory).joinToString("/") }

    private fun archiveEntryName(
        memoryDirectories: Map<Long, String>,
        media: ArchiveMediaRow,
        locale: String?,
        usedNames: MutableMap<String, Int>,
    ): String {
        val directory = memoryDirectories[media.memoryId] ?: throw NotFoundException("Media not found")
        val baseFilename = mediaFilename(media, locale)
        return uniqueArchiveName("$directory/$baseFilename", usedNames)
    }

    private fun trackArchiveEntryName(
        memoryDirectories: Map<Long, String>,
        track: ArchiveTrackRow,
        locale: String?,
        usedNames: MutableMap<String, Int>,
    ): String {
        val directory = memoryDirectories[track.memoryId] ?: throw NotFoundException("Track not found")
        return uniqueArchiveName("$directory/tracks/${trackService.archiveFilename(track, locale)}", usedNames)
    }

    private fun memoryPath(rootMemory: Memory, memory: Memory): List<String> {
        val path = generateSequence(memory) { it.parentMemory }
            .takeWhile { it.id != rootMemory.parentMemory?.id }
            .toList()
            .asReversed()
        return path.map { safeZipSegment(it.displayName) }
    }

    private fun mediaFilename(media: ArchiveMediaRow, locale: String?): String {
        val fallback = fallbackMediaFilename(media, locale)
        val name = media.originalFilename?.takeIf { it.isNotBlank() }?.let { safeZipSegment(it, fallback) }
            ?: media.title.takeIf { it.isNotBlank() }?.let { safeZipSegment(it, fallback) }
            ?: fallback
        return "${ARCHIVE_TIMESTAMP_FORMAT.format(media.sortTime)} ${downloadFilename(name, media.mimeType)}"
    }

    private fun fallbackMediaFilename(media: ArchiveMediaRow, locale: String?): String =
        when (normalizedLocale(locale)) {
            "en" -> downloadFilename("file-${media.publicId}", media.mimeType)
            else -> downloadFilename("datei-${media.publicId}", media.mimeType)
        }

    private fun downloadFilename(name: String, mimeType: String): String {
        val filename = name.trim().takeIf { it.isNotBlank() } ?: "file"
        if (filename.substringAfterLast('/').contains('.')) return filename
        val extension = extensionForMimeType(mimeType) ?: return filename
        return "$filename.$extension"
    }

    private fun extensionForMimeType(mimeType: String): String? =
        when (mimeType.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            else -> null
        }

    private fun uniqueArchiveName(candidate: String, usedNames: MutableMap<String, Int>): String {
        val key = archiveNameKey(candidate)
        val count = usedNames.getOrDefault(key, 0)
        usedNames[key] = count + 1
        if (count == 0) return candidate

        val slashIndex = candidate.lastIndexOf('/')
        val directory = candidate.substring(0, slashIndex + 1)
        val filename = candidate.substring(slashIndex + 1)
        val dotIndex = filename.lastIndexOf('.').takeIf { it > 0 }
        val stem = dotIndex?.let { filename.substring(0, it) } ?: filename
        val extension = dotIndex?.let { filename.substring(it) } ?: ""
        val deduplicated = "$directory$stem-${count + 1}$extension"
        return uniqueArchiveName(deduplicated, usedNames)
    }

    private fun archiveNameKey(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFC).lowercase()

    private fun archiveFilename(memory: Memory, includeChildren: Boolean, locale: String?): String {
        val name = safeZipSegment(memory.displayName, archiveFallbackName(locale))
        val suffix = when (normalizedLocale(locale)) {
            "en" -> if (includeChildren) "-with-chapters" else ""
            else -> if (includeChildren) "-mit-kapiteln" else ""
        }
        return "$name$suffix.zip"
    }

    private fun archiveFallbackName(locale: String?): String =
        when (normalizedLocale(locale)) {
            "en" -> "memory"
            else -> "erinnerung"
        }

    fun normalizedLocale(locale: String?): String =
        locale?.trim()?.lowercase()?.substringBefore('-')?.takeIf { it == "en" } ?: "de"

    private fun safeZipSegment(value: String, fallback: String = "untitled"): String =
        value
            .replace("\\", "-")
            .replace("/", "-")
            .replace(Regex("[\\x00-\\x1F\\x7F:*?\"<>|]+"), "-")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .takeIf { it.isNotBlank() }
            ?: fallback

    private fun writeMediaFile(source: Path, storageKey: String) {
        val target = storagePath(storageKey)
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, "upload-", ".tmp")
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            throw e
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun generatePreviewIfSupported(blob: MediaBlob) {
        if (!canGeneratePreview(blob)) return
        generatePreview(blob, throwOnFailure = false)
    }

    private fun generatePreview(blob: MediaBlob, throwOnFailure: Boolean): Boolean {
        val originalPath = storagePath(blob.storageKey)
        if (!Files.isRegularFile(originalPath)) return false
        return when {
            blob.mimeType.startsWith("image/") -> generateImagePreview(blob, originalPath, throwOnFailure)
            blob.mimeType.startsWith("video/") -> generateVideoPreview(blob, originalPath, throwOnFailure)
            else -> false
        }
    }

    private fun generateImagePreview(blob: MediaBlob, originalPath: Path, throwOnFailure: Boolean): Boolean {
        val previewPath = previewPath(blob)
        Files.createDirectories(previewPath.parent)
        val temp = Files.createTempFile(previewPath.parent, "preview-", ".jpg")
        try {
            val source = ImageIO.read(originalPath.toFile()) ?: return false
            val scale = minOf(1.0, PREVIEW_MAX_EDGE.toDouble() / maxOf(source.width, source.height).toDouble())
            val targetWidth = maxOf(1, (source.width * scale).toInt())
            val targetHeight = maxOf(1, (source.height * scale).toInt())
            val preview = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
            val graphics = preview.createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, targetWidth, targetHeight)
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null)
            } finally {
                graphics.dispose()
            }
            if (!ImageIO.write(preview, "jpg", temp.toFile())) return false
            Files.move(temp, previewPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            blob.previewStorageKey = previewStorageKey(blob)
            return true
        } catch (e: Exception) {
            if (throwOnFailure) throw e
            log.warn("Failed to generate media preview for blob ${blob.id}: ${e.message}")
            return false
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun generateVideoPreview(blob: MediaBlob, originalPath: Path, throwOnFailure: Boolean): Boolean {
        val previewPath = previewPath(blob)
        Files.createDirectories(previewPath.parent)
        val temp = Files.createTempFile(previewPath.parent, "preview-", ".jpg")
        val logFile = Files.createTempFile(previewPath.parent, "preview-", ".log")
        try {
            val process = ProcessBuilder(
                mediaConfig.ffmpegPath,
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-ss",
                "00:00:01",
                "-i",
                originalPath.toString(),
                "-frames:v",
                "1",
                "-vf",
                "scale=720:720:force_original_aspect_ratio=decrease",
                "-q:v",
                "3",
                temp.toString(),
            )
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()))
                .start()

            val completed = process.waitFor(mediaConfig.previewTimeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                throw IOException("ffmpeg timed out after ${mediaConfig.previewTimeoutSeconds}s")
            }
            if (process.exitValue() != 0) {
                val message = Files.readString(logFile).trim().take(500)
                throw IOException("ffmpeg exited with ${process.exitValue()}: $message")
            }
            if (!Files.isRegularFile(temp) || Files.size(temp) <= 0) return false
            Files.move(temp, previewPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            blob.previewStorageKey = previewStorageKey(blob)
            return true
        } catch (e: Exception) {
            if (throwOnFailure) throw e
            log.warn("Failed to generate media preview for blob ${blob.id}: ${e.message}")
            return false
        } finally {
            Files.deleteIfExists(temp)
            Files.deleteIfExists(logFile)
        }
    }

    private fun storagePath(storageKey: String): Path {
        val root = mediaConfig.storageDir.toAbsolutePath().normalize()
        val path = root.resolve(storageKey).normalize()
        if (!path.startsWith(root)) throw BadRequestException("Invalid media storage key", "MEDIA_STORAGE_KEY_INVALID")
        return path
    }

    private fun blobDirectory(blob: MediaBlob): Path {
        val originalPath = storagePath(blob.storageKey)
        return originalPath.parent ?: storagePath("tenant-${blob.tenant.id}/blobs/${blob.contentSha256}")
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun streamRange(path: Path, range: ContentRange): StreamingOutput =
        StreamingOutput { output ->
            try {
                Files.newInputStream(path).use { input ->
                    skipFully(input, range.start)
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var remaining = range.length
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            } catch (e: IOException) {
                if (isClientAbort(e)) {
                    log.debug("Media range stream ended because the client closed the connection")
                    return@StreamingOutput
                }
                throw e
            }
        }

    private fun isClientAbort(exception: IOException): Boolean =
        generateSequence(exception as Throwable?) { it.cause }
            .mapNotNull { it.message }
            .any {
                it.contains("Failed to write", ignoreCase = true) ||
                    it.contains("Broken pipe", ignoreCase = true) ||
                    it.contains("Connection reset", ignoreCase = true) ||
                    it.contains("Stream is closed", ignoreCase = true)
            }

    private fun skipFully(input: java.io.InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0 && input.read() == -1) break
            remaining -= if (skipped > 0) skipped else 1
        }
    }

    private fun Media.toMemorySummary(): MemoryMediaSummary =
        MemoryMediaSummary(
            id = publicId,
            memoryId = memory?.publicId ?: "",
            title = title,
            uploader = uploader(),
            createdAt = createdAt.toApiOffsetDateTime(),
            capturedAt = capturedAt?.toApiOffsetDateTime(),
            capturedAtOverride = capturedAtOverride,
            mimeType = blob.mimeType,
            sizeBytes = blob.sizeBytes,
            contentUrl = contentUrl(),
            previewUrl = previewUrl(),
            cameraMake = blob.cameraMake,
            cameraModel = blob.cameraModel,
            lensModel = blob.lensModel,
            imageWidth = blob.imageWidth,
            imageHeight = blob.imageHeight,
            gpsLatitude = blob.gpsLatitude,
            gpsLongitude = blob.gpsLongitude,
        )

    private fun Media.toPublicShareSummary(shareId: String): PublicShareMediaSummary =
        PublicShareMediaSummary(
            id = publicId,
            memoryId = memory?.publicId ?: "",
            title = title,
            uploader = uploader(),
            createdAt = createdAt.toApiOffsetDateTime(),
            capturedAt = capturedAt?.toApiOffsetDateTime(),
            capturedAtOverride = capturedAtOverride,
            mimeType = blob.mimeType,
            sizeBytes = blob.sizeBytes,
            contentUrl = "/api/public-shares/${url(shareId)}/media/${url(publicId)}/content",
            previewUrl = if (canGeneratePreview(blob)) "/api/public-shares/${url(shareId)}/media/${url(publicId)}/preview" else null,
        )

    private fun Media.uploader(): MediaUploader {
        uploaderUser?.let { user ->
            return MediaUploader(
                type = MediaUploader.Type.USER,
                displayName = user.displayName,
                permalink = user.permalink,
                userId = user.id,
            )
        }
        shareIdentity?.let { identity ->
            return MediaUploader(
                type = MediaUploader.Type.ANONYMOUS,
                displayName = identity.displayName,
                permalink = null,
                userId = null,
            )
        }
        return MediaUploader(
            type = MediaUploader.Type.ANONYMOUS,
            displayName = "Unbekannt",
            permalink = null,
            userId = null,
        )
    }

    private fun Media.contentUrl(): String =
        "/api/${url(tenant().name)}/memory-media/${url(publicId)}/content"

    private fun Media.previewUrl(): String? =
        if (canGeneratePreview(blob)) "/api/${url(tenant().name)}/memory-media/${url(publicId)}/preview" else null

    private fun canGeneratePreview(blob: MediaBlob): Boolean =
        blob.mimeType.startsWith("image/") || blob.mimeType.startsWith("video/")

    private fun previewPath(blob: MediaBlob): Path =
        storagePath(blob.previewStorageKey ?: previewStorageKey(blob))

    private fun previewStorageKey(blob: MediaBlob): String =
        "tenant-${blob.tenant.id}/blobs/${blob.contentSha256}/preview.jpg"

    private fun url(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun safeHeaderFilename(value: String): String =
        value.replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_")

    private fun Instant.toApiOffsetDateTime(): OffsetDateTime =
        OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
    companion object {
        private const val PREVIEW_MAX_EDGE = 720
        private val ARCHIVE_TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss").withZone(ZoneOffset.UTC)
    }
}
