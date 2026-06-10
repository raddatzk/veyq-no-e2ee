package com.veyq.api.library.media

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ConflictException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.common.toApiOffsetDateTime
import com.veyq.api.domain.*
import com.veyq.api.generated.model.*
import com.veyq.api.library.LibraryScopeKey
import com.veyq.api.library.LibraryScopes
import com.veyq.api.library.LibraryService
import com.veyq.api.library.track.MemoryTrackService
import com.veyq.api.memory.MemoryJournalService
import com.veyq.api.memory.MemoryJournalService.JournalReferenceKind
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.StreamingOutput
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.multipart.FileUpload
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

@Singleton
class MediaService @Inject constructor(
    private val mediaRepo: MediaRepo,
    private val trackRepo: MemoryTrackRepo,
    private val mediaBlobRepo: MediaBlobRepo,
    private val bucketRepo: MemoryMediaBucketRepo,
    private val memoryRepo: MemoryRepo,
    private val userRepo: UserRepo,
    private val shareIdentityRepo: MemoryShareIdentityRepo,
    private val mediaConfig: MediaConfig,
    private val mediaStorageService: MediaStorageService,
    private val mediaTypeDetector: MediaTypeDetector,
    private val mediaMetadataExtractor: MediaMetadataExtractor,
    private val mediaResponseMapper: MediaResponseMapper,
    private val trackService: MemoryTrackService,
    private val memoryJournalService: MemoryJournalService,
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
        val bucketId: Long?,
        val bucketPublicId: String?,
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
        val bucketId: Long?,
        val uploaderUserId: Long?,
        val shareIdentityId: Long?,
        val sizeBytes: Long,
        val mimeType: String,
        val contentSha256: String,
        val storageKey: String,
        val metadata: ExtractedMediaMetadata,
    )

    data class MemoryMediaUpdates(
        val media: List<MemoryMediaSummary>,
        val currentMediaIds: List<String>?,
    )

    @Transactional
    fun listMemoryMedia(memoryId: String, includeChildren: Boolean): List<MemoryMediaSummary> {
        val memory = resolveVisibleMemory(memoryId)
        val memories = if (includeChildren) descendantsAndSelf(memory) else listOf(memory)
        val visibleMemories = memories.filter(::isMemoryVisible)
        ensureBucketsForMemories(memories)
        return mediaRepo.listByMemoriesOrdered(visibleMemories)
            .distinctBy { it.id }
            .map { mediaResponseMapper.toMemorySummary(it, tenant().name) }
    }

    fun waitForMemoryMediaUpdates(
        memoryId: String,
        includeChildren: Boolean,
        afterUpdatedAt: Instant?,
        afterMediaId: String?,
        knownMediaSetHash: String?,
        timeoutSeconds: Long,
    ): MemoryMediaUpdates {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.coerceIn(1, 30))
        // Evaluate the (unbounded) media-set hash only once per request. Deletion detection is a
        // snapshot reconciliation, so re-running the full-set scan on every 750ms poll iteration is
        // unnecessary and would let a single long-poll request trigger dozens of full-set scans.
        // Additions are still detected on every iteration via the LIVE_MEDIA_LIMIT-capped query.
        val firstPass = self.get().listMemoryMediaUpdates(
            memoryId,
            includeChildren,
            afterUpdatedAt,
            afterMediaId,
            knownMediaSetHash,
        )
        if (firstPass.media.isNotEmpty() || firstPass.currentMediaIds != null) return firstPass
        while (System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(750)
            val updates = self.get().listMemoryMediaUpdates(
                memoryId,
                includeChildren,
                afterUpdatedAt,
                afterMediaId,
                null,
            )
            if (updates.media.isNotEmpty()) return updates
        }
        return firstPass
    }

    @Transactional
    fun listMemoryMediaUpdates(
        memoryId: String,
        includeChildren: Boolean,
        afterUpdatedAt: Instant?,
        afterMediaId: String?,
        knownMediaSetHash: String?,
    ): MemoryMediaUpdates {
        val memory = resolveVisibleMemory(memoryId)
        val memories = if (includeChildren) descendantsAndSelf(memory).filter(::isMemoryVisible) else listOf(memory)
        ensureBucketsForMemories(memories)
        val media = mediaRepo.listByMemoriesUpdatedAfter(memories, afterUpdatedAt, afterMediaId, LIVE_MEDIA_LIMIT)
            .distinctBy { it.id }
            .map { mediaResponseMapper.toMemorySummary(it, tenant().name) }
        return MemoryMediaUpdates(
            media = media,
            currentMediaIds = currentMediaIdsIfChanged(memories, knownMediaSetHash),
        )
    }

    @Transactional
    fun listMemoryMediaBuckets(memoryId: String, includeChildren: Boolean = false): List<MemoryMediaBucketSummary> {
        val memory = resolveVisibleMemory(memoryId)
        val memories = if (includeChildren) descendantsAndSelf(memory).filter(::isMemoryVisible) else listOf(memory)
        val currentUser = user()
        ensureBucketsForMemories(memories)
        syncPrimaryBucketDisplayNameIfPresent(memory, currentUser)
        val buckets = bucketRepo.listByMemories(memories)
        return bucketSummaries(memories, visibleBuckets(memory, memories, buckets, currentUser, includeChildren))
    }

    @Transactional
    fun createMemoryMediaBucket(memoryId: String, request: CreateMemoryMediaBucketRequest): MemoryMediaBucketSummary {
        val memory = resolveVisibleMemory(memoryId)
        val parent = resolveCreateBucketParent(memory, request.parentBucketId)
        if (!parent.primaryBucket) {
            throw BadRequestException("Subbuckets must be created below a primary bucket", "MEDIA_BUCKET_PARENT_INVALID")
        }
        val name = bucketName(request.displayName)
        if (bucketRepo.nameExists(memory, parent, name)) throw ConflictException("Bucket name already exists")
        val bucket = MemoryMediaBucket().also {
            it.tenant = memory.tenant
            it.memory = memory
            it.parentBucket = parent
            it.name = name
            it.displayName = requiredBucketDisplayName(request.displayName)
            it.primaryBucket = false
            it.uploaderUser = parent.uploaderUser
            it.shareIdentity = parent.shareIdentity
            bucketRepo.persist(it)
        }
        bucketRepo.flush()
        return mediaResponseMapper.toBucketSummary(bucket, mediaCountsByBucket(listOf(memory)))
    }

    private fun resolveCreateBucketParent(memory: Memory, parentBucketId: String): MemoryMediaBucket =
        if (parentBucketId == SELF_PRIMARY_BUCKET_PLACEHOLDER_ID) {
            ensurePrimaryBucket(memory, user(), null)
        } else {
            resolveBucketInMemory(memory, parentBucketId)
        }

    @Transactional
    fun updateMemoryMediaBucket(memoryId: String, bucketId: String, request: UpdateMemoryMediaBucketRequest): MemoryMediaBucketSummary {
        val memory = resolveVisibleMemory(memoryId)
        val bucket = resolveBucketInMemory(memory, bucketId)
        if (bucket.primaryBucket) throw BadRequestException("Primary buckets cannot be renamed", "MEDIA_BUCKET_PRIMARY_IMMUTABLE")
        val name = bucketName(request.displayName)
        if (name != bucket.name && bucketRepo.nameExists(memory, bucket.parentBucket, name)) {
            throw ConflictException("Bucket name already exists")
        }
        bucket.name = name
        bucket.displayName = requiredBucketDisplayName(request.displayName)
        return mediaResponseMapper.toBucketSummary(bucket, mediaCountsByBucket(listOf(memory)))
    }

    @Transactional
    fun deleteMemoryMediaBucket(memoryId: String, bucketId: String) {
        val memory = resolveVisibleMemory(memoryId)
        val bucket = resolveBucketInMemory(memory, bucketId)
        deleteResolvedMemoryMediaBucket(bucket)
    }

    fun deleteResolvedMemoryMediaBucket(bucket: MemoryMediaBucket) {
        if (bucket.primaryBucket && bucket.shareIdentity == null) {
            deleteBucketContents(bucket)
        } else {
            deleteBucketTree(bucket)
        }
    }

    @Transactional
    fun moveMemoryMedia(memoryId: String, request: MoveMemoryMediaRequest): MemoryMediaBatchResponse {
        val memory = resolveVisibleMemory(memoryId)
        val target = resolveBucketInMemory(memory, request.targetBucketId)
        val mediaIds = normalizedMediaIds(request.mediaIds)
        val moved = mediaIds.map { mediaId ->
            val media = resolveMediaInMemory(memory, mediaId)
            media.bucket = target
            media
        }
        val tenantName = tenant().name
        return MemoryMediaBatchResponse(
            media = moved.map { mediaResponseMapper.toMemorySummary(it, tenantName) },
        )
    }

    @Transactional
    fun deleteMemoryMediaBatch(memoryId: String, request: DeleteMemoryMediaBatchRequest) {
        val memory = resolveVisibleMemory(memoryId)
        val media = normalizedMediaIds(request.mediaIds)
            .map { resolveMediaInMemory(memory, it) }
        media.forEach { requireNotReferencedByJournal(it) }
        media.forEach(::deleteMedia)
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
            targetBucket = null,
            uploaderUser = user(),
            shareIdentity = null,
        ).let { mediaResponseMapper.toMemorySummary(it, tenant().name) }
    }

    @Transactional
    fun uploadPublicShareMedia(
        shareId: String,
        memory: Memory,
        uploadPath: Path,
        originalFilename: String?,
        title: String?,
        targetBucket: MemoryMediaBucket?,
        uploaderUser: User?,
        shareIdentity: MemoryShareIdentity?,
    ): PublicShareMediaSummary =
        uploadMemoryMediaInternal(
            memory = memory,
            uploadPath = uploadPath,
            originalFilename = originalFilename,
            title = title,
            targetBucket = targetBucket,
            uploaderUser = uploaderUser,
            shareIdentity = shareIdentity,
        ).let { mediaResponseMapper.toPublicShareSummary(it, shareId) }

    fun createMemoryMediaUpload(memoryId: String, request: CreateMemoryMediaUploadRequest): MemoryMediaUploadSession {
        val memory = resolveVisibleMemory(memoryId)
        val targetBucket = request.bucketId?.let { resolveBucketInMemory(memory, it) }
        return createUploadSession(
            memory = memory,
            request = request,
            targetBucket = targetBucket,
            uploaderUser = user(),
            shareIdentity = null,
            shareId = null,
        )
    }

    fun createPublicShareMediaUpload(
        shareId: String,
        memory: Memory,
        request: CreateMemoryMediaUploadRequest,
        targetBucket: MemoryMediaBucket?,
        uploaderUser: User?,
        shareIdentity: MemoryShareIdentity?,
    ): MemoryMediaUploadSession =
        createUploadSession(memory, request, targetBucket, uploaderUser, shareIdentity, shareId)

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
                bucketId = session.bucketId,
                uploaderUserId = uploaderUser.id,
                shareIdentityId = null,
            )
            val summary = self.get().persistPreparedMemoryUpload(prepared)
                .let { mediaResponseMapper.toMemorySummary(it, tenant().name) }
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
                bucketId = session.bucketId,
                uploaderUserId = uploaderUser?.id,
                shareIdentityId = shareIdentity?.id,
            )
            val summary = self.get().persistPreparedMemoryUpload(prepared)
                .let { mediaResponseMapper.toPublicShareSummary(it, shareId) }
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
        targetBucket: MemoryMediaBucket?,
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
        val bucket = targetBucket ?: ensurePrimaryBucket(memory, uploaderUser, shareIdentity)

        val media = Media().also {
            it.tenant = memory.tenant
            applyScope(it, LibraryScopes.scope(memory))
            it.memory = memory
            it.bucket = bucket
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
        bucketId: Long?,
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
            bucketId = bucketId,
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
        val bucket = prepared.bucketId?.let { bucketRepo.findById(it) ?: throw NotFoundException("Bucket not found") }
            ?: ensurePrimaryBucket(memory, uploaderUser, shareIdentity)
        if (bucket.memory.id != memory.id) throw NotFoundException("Bucket not found")

        return Media().also {
            it.tenant = memory.tenant
            applyScope(it, LibraryScopes.scope(memory))
            it.memory = memory
            it.bucket = bucket
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
            generatePreviewIfSupported(it)
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
        targetBucket: MemoryMediaBucket?,
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
            bucketId = targetBucket?.id,
            bucketPublicId = targetBucket?.publicId,
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
            session.bucketId?.let { bucketId -> it["bucketId"] = bucketId.toString() }
            session.bucketPublicId?.let { bucketId -> it["bucketPublicId"] = bucketId }
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
            bucketId = properties.getProperty("bucketId")?.toLong(),
            bucketPublicId = properties.getProperty("bucketPublicId"),
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
            bucketId = bucketPublicId,
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

    fun resolveBucketForPublicShareUpload(memory: Memory, bucketId: String?): MemoryMediaBucket? =
        bucketId?.let { resolveBucketInMemory(memory, it) }

    fun listPublicShareBuckets(
        memory: Memory,
        visibleMedia: List<Media>,
        canManageBucket: (MemoryMediaBucket) -> Boolean = { true },
    ): List<MemoryMediaBucketSummary> =
        listPublicShareBuckets(listOf(memory), visibleMedia, canManageBucket)

    fun listPublicShareBuckets(
        memories: List<Memory>,
        visibleMedia: List<Media>,
        canManageBucket: (MemoryMediaBucket) -> Boolean = { true },
    ): List<MemoryMediaBucketSummary> {
        memories.forEach { memory ->
            ensureBucketsForMedia(memory, visibleMedia.filter { it.memory?.id == memory.id })
        }
        val visibleBucketIds = visibleMedia
            .mapNotNull { it.bucket }
            .flatMap { bucket -> generateSequence(bucket) { it.parentBucket }.toList() }
            .map { it.id }
            .toSet()
        val buckets = bucketRepo.listByMemories(memories).filter { bucket ->
            bucket.id in visibleBucketIds
        }
        return bucketSummaries(memories, buckets, canManageBucket)
    }

    @Transactional
    fun updateMemoryMediaCapturedAt(memoryId: String, publicId: String, request: UpdateMemoryMediaCapturedAtRequest): MemoryMediaSummary {
        val memory = resolveVisibleMemory(memoryId)
        val media = resolveMediaInMemory(memory, publicId)

        media.capturedAt = request.capturedAt?.toInstant()
        media.capturedAtOverride = true

        return mediaResponseMapper.toMemorySummary(media, tenant().name)
    }

    @Transactional
    fun updateMemoryMediaHighlight(memoryId: String, publicId: String, request: UpdateMemoryMediaHighlightRequest): MemoryMediaSummary {
        val memory = resolveVisibleMemory(memoryId)
        val media = resolveMediaInMemory(memory, publicId)

        request.highlight?.let {
            media.highlight = it
        }

        return mediaResponseMapper.toMemorySummary(media, tenant().name)
    }

    @Transactional
    fun updateMemoryMediaMetadataBatch(
        memoryId: String,
        request: UpdateMemoryMediaMetadataBatchRequest,
    ): MemoryMediaBatchResponse {
        val memory = resolveVisibleMemory(memoryId)
        val mediaIds = normalizedMediaIds(request.mediaIds)

        val updated = mediaIds.map { publicId ->
            val media = resolveMediaInMemory(memory, publicId)

            media.capturedAt = (media.capturedAt ?: media.createdAt).plusSeconds(request.capturedAtOffsetSeconds)
            media.capturedAtOverride = true
            media
        }
        val tenantName = tenant().name
        return MemoryMediaBatchResponse(
            media = updated.map { mediaResponseMapper.toMemorySummary(it, tenantName) },
        )
    }

    @Transactional
    fun deleteMemoryMedia(memoryId: String, mediaId: String) {
        val memory = resolveVisibleMemory(memoryId)
        val media = resolveMediaInMemory(memory, mediaId)
        requireNotReferencedByJournal(media)
        deleteMedia(media)
    }

    @Transactional
    fun deleteAllMemoryMedia(memoryId: String) {
        val memory = resolveVisibleMemory(memoryId)
        val media = mediaRepo.listByMemory(memory)
        media.forEach { requireNotReferencedByJournal(it) }
        media.forEach(::deleteMedia)
    }

    fun deletePublicMedia(media: Media) {
        requireNotReferencedByJournal(media)
        deleteMedia(media)
    }

    fun movePublicMedia(media: Media, targetBucket: MemoryMediaBucket): Media {
        if (media.memory?.id != targetBucket.memory.id) throw NotFoundException("Bucket not found")
        media.bucket = targetBucket
        return media
    }

    private fun resolveMediaInMemory(memory: Memory, publicId: String): Media {
        val media = mediaRepo.findByTenantAndPublicId(memory.tenant, publicId)
            ?: throw NotFoundException("Media not found")
        if (media.memory?.id != memory.id) throw NotFoundException("Media not found")
        return media
    }

    private fun resolveBucketInMemory(memory: Memory, publicId: String): MemoryMediaBucket {
        val bucket = bucketRepo.findByTenantAndPublicId(memory.tenant, publicId)
            ?: throw NotFoundException("Bucket not found")
        if (bucket.memory.id != memory.id) throw NotFoundException("Bucket not found")
        return bucket
    }

    private fun ensureBucketsForMemories(memories: Collection<Memory>) {
        if (memories.isEmpty()) return
        val mediaByMemoryId = mediaRepo.listByMemoriesOrdered(memories.toList()).groupBy { it.memory?.id }
        memories.forEach { memory -> ensureBucketsForMedia(memory, mediaByMemoryId[memory.id] ?: emptyList()) }
    }

    private fun ensureBucketsForMedia(memory: Memory, media: List<Media>) {
        media.filter { it.bucket == null }.forEach { item ->
            item.bucket = ensurePrimaryBucket(memory, item.uploaderUser, item.shareIdentity)
        }
    }

    private fun ensurePrimaryBucket(
        memory: Memory,
        uploaderUser: User?,
        shareIdentity: MemoryShareIdentity?,
    ): MemoryMediaBucket {
        if ((uploaderUser == null) == (shareIdentity == null)) {
            throw BadRequestException("Exactly one uploader source is required")
        }
        return when {
            uploaderUser != null -> bucketRepo.findPrimaryByUser(memory, uploaderUser)
                ?.also { syncPrimaryBucketDisplayName(it, uploaderUser.displayName) }
                ?: createPrimaryBucket(memory, uploaderUser, null)

            shareIdentity != null -> bucketRepo.findPrimaryByShareIdentity(memory, shareIdentity)
                ?: createPrimaryBucket(memory, null, shareIdentity)

            else -> throw BadRequestException("Exactly one uploader source is required")
        }
    }

    private fun syncPrimaryBucketDisplayName(bucket: MemoryMediaBucket, displayName: String) {
        if (bucket.displayName != displayName) {
            bucket.displayName = displayName
        }
    }

    private fun syncPrimaryBucketDisplayNameIfPresent(memory: Memory, user: User) {
        bucketRepo.findPrimaryByUser(memory, user)?.also { syncPrimaryBucketDisplayName(it, user.displayName) }
    }

    private fun createPrimaryBucket(
        memory: Memory,
        uploaderUser: User?,
        shareIdentity: MemoryShareIdentity?,
    ): MemoryMediaBucket {
        val displayName = uploaderUser?.displayName ?: shareIdentity?.displayName ?: "Unknown"
        val ownerKey = uploaderUser?.id?.let { "user-$it" } ?: shareIdentity?.id?.let { "share-$it" } ?: "unknown"
        val baseName = "primary-$ownerKey"
        val name = uniqueBucketName(memory, null, baseName)
        return MemoryMediaBucket().also {
            it.tenant = memory.tenant
            it.memory = memory
            it.parentBucket = null
            it.name = name
            it.displayName = displayName
            it.primaryBucket = true
            it.uploaderUser = uploaderUser
            it.shareIdentity = shareIdentity
            bucketRepo.persist(it)
            bucketRepo.flush()
        }
    }

    private fun bucketSummaries(
        memories: Collection<Memory>,
        buckets: List<MemoryMediaBucket>,
        canManageBucket: (MemoryMediaBucket) -> Boolean = { true },
    ): List<MemoryMediaBucketSummary> {
        val memoryIds = memories.map { it.id }.toSet()
        val bucketSet = buckets.toMutableSet()
        buckets.forEach { bucket ->
            generateSequence(bucket.parentBucket) { it.parentBucket }
                .filter { it.memory.id in memoryIds }
                .forEach(bucketSet::add)
        }
        val counts = mediaCountsByBucket(memories)
        return bucketSet.sortedWith(compareBy<MemoryMediaBucket> { it.parentBucket?.id ?: 0L }.thenBy { it.displayName })
            .map { mediaResponseMapper.toBucketSummary(it, counts, canManageBucket(it)) }
    }

    private fun visibleBuckets(
        memory: Memory,
        memories: Collection<Memory>,
        buckets: List<MemoryMediaBucket>,
        currentUser: User,
        includeChildren: Boolean,
    ): List<MemoryMediaBucket> {
        if (!includeChildren || memories.size <= 1) return buckets

        val currentPrimary = buckets.find {
            it.memory.id == memory.id &&
                    it.primaryBucket &&
                    it.uploaderUser?.id == currentUser.id &&
                    it.shareIdentity == null
        } ?: return buckets
        val hasSameUserDescendantPrimary = buckets.any {
            it.id != currentPrimary.id &&
                    it.primaryBucket &&
                    it.uploaderUser?.id == currentUser.id &&
                    it.shareIdentity == null
        }
        if (!hasSameUserDescendantPrimary) return buckets

        val mediaCounts = mediaCountsByBucket(memories)
        val currentPrimaryHasMedia = (mediaCounts[currentPrimary.id] ?: 0) > 0
        val currentPrimaryHasChildren = buckets.any { it.parentBucket?.id == currentPrimary.id }
        if (currentPrimaryHasMedia || currentPrimaryHasChildren) return buckets

        return buckets.filterNot { it.id == currentPrimary.id }
    }

    private fun mediaCountsByBucket(memories: Collection<Memory>): Map<Long, Int> =
        mediaRepo.countByBucketIds(bucketRepo.listByMemories(memories).map { it.id })

    private fun deleteBucketTree(bucket: MemoryMediaBucket) {
        deleteBucketContents(bucket)
        bucket.delete()
        bucketRepo.flush()
    }

    private fun deleteBucketContents(bucket: MemoryMediaBucket) {
        bucketRepo.listChildren(bucket).forEach(::deleteBucketTree)
        mediaRepo.list("bucket", bucket).forEach(::deleteMedia)
        bucketRepo.flush()
    }

    private fun normalizedMediaIds(mediaIds: List<String>): List<String> =
        mediaIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            .also { if (it.isEmpty()) throw BadRequestException("mediaIds must not be empty", "MEDIA_BATCH_EMPTY") }

    private fun requiredBucketDisplayName(value: String?): String =
        value?.trim()?.takeIf { it.isNotBlank() && it.length <= 120 }
            ?: throw BadRequestException("displayName is required", "MEDIA_BUCKET_NAME_REQUIRED")

    private fun bucketName(value: String?): String {
        val base = requiredBucketDisplayName(value)
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(120)
            .trim('-')
        return base.takeIf { it.isNotBlank() } ?: throw BadRequestException("Invalid bucket name", "MEDIA_BUCKET_NAME_INVALID")
    }

    private fun uniqueBucketName(memory: Memory, parent: MemoryMediaBucket?, baseName: String): String {
        var candidate = baseName.take(120).trim('-').ifBlank { "bucket" }
        var suffix = 2
        while (bucketRepo.nameExists(memory, parent, candidate)) {
            val tail = "-$suffix"
            candidate = "${baseName.take(120 - tail.length).trim('-')}$tail"
            suffix += 1
        }
        return candidate
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
                .header("Cache-Control", MEDIA_CACHE_CONTROL)
                .header("Content-Length", range.length)
                .header("Content-Range", "bytes ${range.start}-${range.end}/$size")
                .header("Content-Disposition", "inline; filename=\"${safeHeaderFilename(filename)}\"")
                .header("X-Content-Type-Options", "nosniff")
                .build()
        }

        return Response.ok(path.toFile(), media.blob.mimeType)
            .header("Accept-Ranges", "bytes")
            .header("Cache-Control", MEDIA_CACHE_CONTROL)
            .header("Content-Length", media.blob.sizeBytes)
            .header("Content-Disposition", "inline; filename=\"${safeHeaderFilename(filename)}\"")
            .header("X-Content-Type-Options", "nosniff")
            .build()
    }

    fun mediaPreview(publicId: String): Response {
        val media = resolveAccessibleMedia(publicId)
        return streamPreview(media)
    }

    fun mediaThumbnail(publicId: String): Response {
        val media = resolveAccessibleMedia(publicId)
        return streamThumbnail(media)
    }

    fun mediaPoster(publicId: String): Response {
        val media = resolveAccessibleMedia(publicId)
        return streamPoster(media)
    }

    fun streamThumbnail(media: Media): Response {
        return streamDerivative(
            media = media,
            path = thumbnailPath(media.blob),
            filenameSuffix = "thumbnail",
            description = "thumbnail",
            generate = { generateThumbnail(it, throwOnFailure = false) },
        )
    }

    fun streamPreview(media: Media): Response {
        return streamDerivative(
            media = media,
            path = previewPath(media.blob),
            filenameSuffix = "preview",
            description = "preview",
            generate = { generatePreview(it, throwOnFailure = false) },
        )
    }

    fun streamPoster(media: Media): Response {
        return streamDerivative(
            media = media,
            path = posterPath(media.blob),
            filenameSuffix = "poster",
            description = "poster",
            generate = { generatePoster(it, throwOnFailure = false) },
        )
    }

    private fun streamDerivative(
        media: Media,
        path: Path,
        filenameSuffix: String,
        description: String,
        generate: (MediaBlob) -> Boolean,
    ): Response {
        if (!canGeneratePreview(media.blob)) throw NotFoundException("Media not found")

        if (!Files.isRegularFile(path)) {
            if (!generate(media.blob)) throw NotFoundException("Media $description not found")
        }

        return Response.ok(path.toFile(), "image/jpeg")
            .header("Cache-Control", MEDIA_CACHE_CONTROL)
            .header("Content-Length", Files.size(path))
            .header("Content-Disposition", "inline; filename=\"${safeHeaderFilename(media.publicId)}-$filenameSuffix.jpg\"")
            .header("X-Content-Type-Options", "nosniff")
            .build()
    }

    fun publicShareSummary(media: Media, shareId: String): PublicShareMediaSummary =
        mediaResponseMapper.toPublicShareSummary(media, shareId)

    fun deleteBlobIfUnreferenced(blob: MediaBlob) {
        if (mediaRepo.count("blob", blob) > 0) return
        val directory = blobDirectory(blob)
        blob.delete()
        mediaBlobRepo.flush()
        deleteRecursively(directory)
    }

    private fun deleteMedia(media: Media) {
        memoryRepo.list("coverMedia = ?1", media).forEach { it.coverMedia = null }
        val blob = media.blob
        media.delete()
        mediaRepo.flush()
        deleteBlobIfUnreferenced(blob)
    }

    private fun requireNotReferencedByJournal(media: Media) {
        val memory = media.memory ?: return
        memoryJournalService.requireNoJournalReference(memory, JournalReferenceKind.Media, media.publicId)
    }

    fun mediaSetHash(mediaIds: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        mediaIds.sorted().forEach { mediaId ->
            digest.update(mediaId.length.toString().toByteArray(Charsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(mediaId.toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun currentMediaIdsIfChanged(memories: List<Memory>, knownMediaSetHash: String?): List<String>? {
        if (knownMediaSetHash.isNullOrBlank()) return null
        val currentMediaIds = mediaRepo.listPublicIdsByMemories(memories).distinct().sorted()
        return if (mediaSetHash(currentMediaIds) == knownMediaSetHash) null else currentMediaIds
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
        memoryRepo.listDescendantsAndSelf(tenant(), root)

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
        mediaStorageService.writeAtomic(source, storageKey, "upload-")
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
        generateThumbnail(blob, throwOnFailure = false)
        generatePreview(blob, throwOnFailure = false)
        generatePoster(blob, throwOnFailure = false)
    }

    private fun generateThumbnail(blob: MediaBlob, throwOnFailure: Boolean): Boolean =
        generateDerivative(
            blob = blob,
            targetPath = thumbnailPath(blob),
            maxEdge = THUMBNAIL_MAX_EDGE,
            logLabel = "thumbnail",
            throwOnFailure = throwOnFailure,
            assignStorageKey = { thumbnailStorageKey = thumbnailStorageKey(this) },
        )

    private fun generatePreview(blob: MediaBlob, throwOnFailure: Boolean): Boolean {
        return generateDerivative(
            blob = blob,
            targetPath = previewPath(blob),
            maxEdge = PREVIEW_MAX_EDGE,
            logLabel = "preview",
            throwOnFailure = throwOnFailure,
            assignStorageKey = { previewStorageKey = previewStorageKey(this) },
        )
    }

    private fun generatePoster(blob: MediaBlob, throwOnFailure: Boolean): Boolean {
        if (!canGeneratePoster(blob)) return false
        return generateDerivative(
            blob = blob,
            targetPath = posterPath(blob),
            maxEdge = null,
            logLabel = "poster",
            throwOnFailure = throwOnFailure,
            assignStorageKey = { posterStorageKey = posterStorageKey(this) },
        )
    }

    private fun generateDerivative(
        blob: MediaBlob,
        targetPath: Path,
        maxEdge: Int?,
        logLabel: String,
        throwOnFailure: Boolean,
        assignStorageKey: MediaBlob.() -> Unit,
    ): Boolean {
        val originalPath = storagePath(blob.storageKey)
        if (!Files.isRegularFile(originalPath)) return false
        return when {
            blob.mimeType.startsWith("image/") && maxEdge != null -> generateImageDerivative(blob, originalPath, targetPath, maxEdge, logLabel, throwOnFailure, assignStorageKey)
            blob.mimeType.startsWith("video/") -> generateVideoDerivative(blob, originalPath, targetPath, maxEdge, logLabel, throwOnFailure, assignStorageKey)
            else -> false
        }
    }

    private fun generateImageDerivative(
        blob: MediaBlob,
        originalPath: Path,
        targetPath: Path,
        maxEdge: Int,
        logLabel: String,
        throwOnFailure: Boolean,
        assignStorageKey: MediaBlob.() -> Unit,
    ): Boolean {
        Files.createDirectories(targetPath.parent)
        val temp = Files.createTempFile(targetPath.parent, "$logLabel-", ".jpg")
        try {
            val source = ImageIO.read(originalPath.toFile()) ?: return false
            val orientedSource = MediaImageOrientation.apply(
                source,
                mediaMetadataExtractor.extractOrientation(originalPath)
            )
            val scale = minOf(1.0, maxEdge.toDouble() / maxOf(orientedSource.width, orientedSource.height).toDouble())
            val targetWidth = maxOf(1, (orientedSource.width * scale).toInt())
            val targetHeight = maxOf(1, (orientedSource.height * scale).toInt())
            val preview = MediaImageOrientation.resize(orientedSource, targetWidth, targetHeight)
            if (!ImageIO.write(preview, "jpg", temp.toFile())) return false
            Files.move(temp, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            blob.assignStorageKey()
            return true
        } catch (e: Exception) {
            if (throwOnFailure) throw e
            log.warn("Failed to generate media $logLabel for blob ${blob.id}: ${e.message}")
            return false
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun generateVideoDerivative(
        blob: MediaBlob,
        originalPath: Path,
        targetPath: Path,
        maxEdge: Int?,
        logLabel: String,
        throwOnFailure: Boolean,
        assignStorageKey: MediaBlob.() -> Unit,
    ): Boolean {
        Files.createDirectories(targetPath.parent)
        val temp = Files.createTempFile(targetPath.parent, "$logLabel-", ".jpg")
        val logFile = Files.createTempFile(targetPath.parent, "$logLabel-", ".log")
        try {
            val command = mutableListOf(
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
            )
            if (maxEdge != null) {
                command.add("-vf")
                command.add("scale=$maxEdge:$maxEdge:force_original_aspect_ratio=decrease")
            }
            command.add("-q:v")
            command.add(if (maxEdge == null) "2" else "3")
            command.add(temp.toString())

            val process = ProcessBuilder(command)
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
            Files.move(temp, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            blob.assignStorageKey()
            return true
        } catch (e: Exception) {
            if (throwOnFailure) throw e
            log.warn("Failed to generate media $logLabel for blob ${blob.id}: ${e.message}")
            return false
        } finally {
            Files.deleteIfExists(temp)
            Files.deleteIfExists(logFile)
        }
    }

    private fun storagePath(storageKey: String): Path {
        return mediaStorageService.path(storageKey)
    }

    private fun blobDirectory(blob: MediaBlob): Path {
        val originalPath = storagePath(blob.storageKey)
        return originalPath.parent ?: storagePath("tenant-${blob.tenant.id}/blobs/${blob.contentSha256}")
    }

    private fun deleteRecursively(path: Path) {
        mediaStorageService.deleteRecursively(path)
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

    private fun hasThumbnail(blob: MediaBlob): Boolean =
        blob.thumbnailStorageKey != null

    private fun canGeneratePreview(blob: MediaBlob): Boolean =
        blob.mimeType.startsWith("image/") || blob.mimeType.startsWith("video/")

    private fun canGeneratePoster(blob: MediaBlob): Boolean =
        blob.mimeType.startsWith("video/")

    private fun thumbnailPath(blob: MediaBlob): Path =
        storagePath(blob.thumbnailStorageKey ?: thumbnailStorageKey(blob))

    private fun thumbnailStorageKey(blob: MediaBlob): String =
        "tenant-${blob.tenant.id}/blobs/${blob.contentSha256}/thumbnail.jpg"

    private fun previewPath(blob: MediaBlob): Path =
        storagePath(blob.previewStorageKey ?: previewStorageKey(blob))

    private fun previewStorageKey(blob: MediaBlob): String =
        "tenant-${blob.tenant.id}/blobs/${blob.contentSha256}/preview.jpg"

    private fun posterPath(blob: MediaBlob): Path =
        storagePath(blob.posterStorageKey ?: posterStorageKey(blob))

    private fun posterStorageKey(blob: MediaBlob): String =
        "tenant-${blob.tenant.id}/blobs/${blob.contentSha256}/poster.jpg"

    private fun safeHeaderFilename(value: String): String =
        value.replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_")

    companion object {
        const val SELF_PRIMARY_BUCKET_PLACEHOLDER_ID = "__veyq_self_primary_bucket__"
        private const val THUMBNAIL_MAX_EDGE = 360
        private const val PREVIEW_MAX_EDGE = 1600
        private const val LIVE_MEDIA_LIMIT = 100
        private const val MEDIA_CACHE_CONTROL = "private, max-age=31536000, immutable"
        private val ARCHIVE_TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss").withZone(ZoneOffset.UTC)
    }
}
