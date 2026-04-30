package com.lunva.api.library.media

import com.lunva.api.auth.BadRequestException
import com.lunva.api.auth.ForbiddenException
import com.lunva.api.auth.NotFoundException
import com.lunva.api.domain.*
import com.lunva.api.library.LibraryService
import com.lunva.api.library.explorer.ResolvedExplorerLocation
import com.lunva.api.auth.filterchain.RequestContext
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.StreamingOutput
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.multipart.FileUpload
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Comparator
import java.util.Properties
import java.util.UUID

@Singleton
class MediaService @Inject constructor(
    private val albumMediaRepo: AlbumMediaRepo,
    private val albumRepo: TenantAlbumRepo,
    private val mediaRepo: MediaRepo,
    private val mediaConfig: MediaConfig,
    private val tenantRepo: TenantRepo,
    private val tenantMemberRepo: TenantMemberRepo,
    folderRepo: TenantFolderRepo,
    groupRepo: TenantGroupRepo,
    groupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext
) : LibraryService(folderRepo, albumRepo, groupRepo, groupMemberRepo, requestContext) {
    private val log: Logger = Logger.getLogger(MediaService::class.java)

    fun streamMedia(path: String?): Sequence<StreamMediaSummary> {
        val context = resolveVisibleContext(path)
        val albums = when (context) {
            ResolvedExplorerLocation.Tenant -> visibleAlbums()
            is ResolvedExplorerLocation.Group -> visibleAlbums().filter { it.hasScope(context.scope) }
            is ResolvedExplorerLocation.User -> visibleAlbums().filter { it.hasScope(context.scope) }
            is ResolvedExplorerLocation.Folder -> visibleAlbums().filter { it.parentFolder != null && isDescendantOrSame(it.parentFolder!!, context.scope) }
            is ResolvedExplorerLocation.Album -> listOf(context.scope)
        }

        return albums.asSequence()
            .flatMap { album ->
                albumMediaRepo.listVisibleByAlbum(album).asSequence()
                    .filter { isMediaVisible(it.media, album) }
                    .map { it.media.toStreamSummary(album) }
            }
            .distinctBy { it.id }
    }

    @Transactional
    fun uploadMedia(file: FileUpload?, albumPath: String?, title: String?): UploadMediaSummary {
        val album = resolveAlbum(albumPath)
        requireVisible(album)
        val upload = file ?: throw BadRequestException("file is required")
        val uploadPath = upload.uploadedFile()
        val size = Files.size(uploadPath)
        if (size <= 0) throw BadRequestException("file must not be empty")
        val mimeType = upload.contentType() ?: Files.probeContentType(uploadPath) ?: ""
        if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) {
            throw BadRequestException("Only image and video uploads are supported")
        }

        val media = Media().also {
            it.tenant = tenant()
            it.owner = user()
            applyScope(it, album.scope())
            it.title = title?.trim()?.takeIf { value -> value.isNotBlank() }
                ?: upload.fileName()?.substringBeforeLast('.')?.takeIf { value -> value.isNotBlank() }
                        ?: "Upload"
            it.mimeType = mimeType
            it.sizeBytes = size
            it.storageKey = ""
            it.originalFilename = upload.fileName()
            mediaRepo.persist(it)
        }
        mediaRepo.flush()

        val storageKey = "tenant-${tenant().id}/${media.publicId}/original"
        writeMediaFile(uploadPath, storageKey)
        media.storageKey = storageKey
        linkMedia(album, media)

        return media.toUploadSummary(album)
    }

    fun createUpload(request: CreateMediaUploadRequest): MediaUploadSessionSummary {
        val targetTenant = resolveUploadTenant(request.tenantName)
        requestContext.tenant = targetTenant
        val album = resolveAlbum(request.albumPath)
        requireVisible(album)
        val filename = request.filename?.trim()?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("filename is required")
        val mimeType = request.mimeType?.trim()?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("mimeType is required")
        if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) {
            throw BadRequestException("Only image and video uploads are supported")
        }
        val sizeBytes = request.sizeBytes ?: throw BadRequestException("sizeBytes is required")
        if (sizeBytes <= 0) throw BadRequestException("file must not be empty")
        val uploadId = UUID.randomUUID().toString()
        val now = Instant.now()
        val session = UploadSession(
            uploadId = uploadId,
            tenantId = targetTenant.id,
            tenantName = targetTenant.name,
            ownerUserId = user().id,
            albumPath = album.path(),
            filename = filename,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            title = request.title?.trim()?.takeIf { it.isNotBlank() },
            createdAt = now,
            updatedAt = now,
        )
        val directory = uploadDirectory(uploadId)
        Files.createDirectories(directory.resolve("chunks"))
        saveUploadSession(session)
        return MediaUploadSessionSummary(uploadId, mediaConfig.chunkSizeBytes)
    }

    fun uploadChunk(uploadId: String, chunkIndex: Int, chunk: InputStream) {
        if (chunkIndex < 0) throw BadRequestException("chunkIndex must not be negative")
        val session = loadUploadSession(uploadId)
        requireCurrentUploadOwner(session)
        val chunks = uploadDirectory(uploadId).resolve("chunks")
        Files.createDirectories(chunks)
        val target = chunks.resolve("$chunkIndex.part")
        val temp = Files.createTempFile(chunks, "$chunkIndex-", ".tmp")
        try {
            chunk.use { input -> Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING) }
            val chunkSize = Files.size(temp)
            if (chunkSize <= 0) throw BadRequestException("chunk must not be empty")
            if (chunkSize > mediaConfig.chunkSizeBytes) throw BadRequestException("chunk is too large")
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            updateUploadSessionTimestamp(session.uploadId)
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            throw e
        }
    }

    fun listUploads(): List<PendingMediaUploadSummary> =
        uploadSessions()
            .filter { it.ownerUserId == user().id }
            .sortedByDescending { it.updatedAt }
            .map { it.toPendingSummary() }

    fun uploadStatus(uploadId: String): PendingMediaUploadSummary {
        val session = loadUploadSession(uploadId)
        requireCurrentUploadOwner(session)
        return session.toPendingSummary()
    }

    fun abortUpload(uploadId: String) {
        val session = loadUploadSession(uploadId)
        requireCurrentUploadOwner(session)
        deleteRecursively(uploadDirectory(uploadId))
    }

    @Transactional
    fun completeUpload(uploadId: String): UploadMediaSummary {
        val session = loadUploadSession(uploadId)
        requireCurrentUploadOwner(session)
        requestContext.tenant = loadUploadTenant(session)
        val album = resolveAlbum(session.albumPath)
        requireVisible(album)
        val chunkCount = ((session.sizeBytes + mediaConfig.chunkSizeBytes - 1) / mediaConfig.chunkSizeBytes).toInt()
        val media = Media().also {
            it.tenant = tenant()
            it.owner = user()
            applyScope(it, album.scope())
            it.title = session.title ?: session.filename.substringBeforeLast('.').takeIf { value -> value.isNotBlank() } ?: "Upload"
            it.mimeType = session.mimeType
            it.sizeBytes = session.sizeBytes
            it.storageKey = ""
            it.originalFilename = session.filename
            mediaRepo.persist(it)
        }
        mediaRepo.flush()

        val storageKey = "tenant-${tenant().id}/${media.publicId}/original"
        assembleChunks(uploadId, chunkCount, session.sizeBytes, storageKey)
        media.storageKey = storageKey
        linkMedia(album, media)
        deleteRecursively(uploadDirectory(uploadId))

        return media.toUploadSummary(album)
    }

    fun mediaContent(publicId: String, rangeHeader: String?): Response {
        val media = mediaRepo.findByTenantAndPublicId(tenant(), publicId)
            ?: throw NotFoundException("Media not found")
        if (!isMediaAccessible(media)) throw NotFoundException("Media not found")

        val path = storagePath(media.storageKey)
        if (!Files.isRegularFile(path)) throw NotFoundException("Media file not found")
        val filename = media.originalFilename?.takeIf { it.isNotBlank() } ?: "${media.publicId}.bin"
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
                .type(media.mimeType)
                .entity(streamRange(path, range))
                .header("Accept-Ranges", "bytes")
                .header("Content-Length", range.length)
                .header("Content-Range", "bytes ${range.start}-${range.end}/$size")
                .header("Content-Disposition", "inline; filename=\"${safeHeaderFilename(filename)}\"")
                .build()
        }

        return Response.ok(path.toFile(), media.mimeType)
            .header("Accept-Ranges", "bytes")
            .header("Content-Length", media.sizeBytes)
            .header("Content-Disposition", "inline; filename=\"${safeHeaderFilename(filename)}\"")
            .build()
    }

    private fun visibleAlbums(): List<TenantAlbum> =
        albumRepo.listByTenant(tenant()).filter { isAlbumVisible(it) }

    private fun isDescendantOrSame(candidate: TenantFolder, ancestor: TenantFolder): Boolean =
        generateSequence(candidate) { it.parentFolder }.any { it.id == ancestor.id }

    private fun isMediaVisible(media: Media, album: TenantAlbum): Boolean =
        isAlbumVisible(album) && media.scope().sameAs(album.scope())

    private fun isMediaAccessible(media: Media): Boolean =
        albumMediaRepo.list("media", media).any { isMediaVisible(media, it.album) }

    private fun resolveAlbum(path: String?): TenantAlbum =
        (resolveVisibleContext(path) as? ResolvedExplorerLocation.Album)?.scope ?: throw NotFoundException("Album not found")

    private fun linkMedia(album: TenantAlbum, media: Media) {
        val link = albumMediaRepo.findByAlbumAndMedia(album, media)
        if (link == null) {
            albumMediaRepo.persist(AlbumMedia().also {
                it.id = AlbumMediaId(album.id, media.id)
                it.album = album
                it.media = media
                it.hidden = false
                it.position = 0
            })
        } else {
            link.hidden = false
        }
    }

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

    private fun assembleChunks(uploadId: String, chunkCount: Int, expectedSize: Long, storageKey: String) {
        val target = storagePath(storageKey)
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, "upload-", ".tmp")
        try {
            Files.newOutputStream(temp).use { output ->
                for (index in 0 until chunkCount) {
                    val chunk = uploadDirectory(uploadId).resolve("chunks").resolve("$index.part")
                    if (!Files.isRegularFile(chunk)) throw BadRequestException("Missing upload chunk $index")
                    Files.newInputStream(chunk).use { input -> copy(input, output) }
                }
            }
            if (Files.size(temp) != expectedSize) throw BadRequestException("Upload size mismatch")
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            throw e
        }
    }

    private fun saveUploadSession(session: UploadSession) {
        val properties = MediaUploadSessions.toProperties(session)
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
        if (!MediaUploadSessions.hasValidUploadId(uploadId)) throw NotFoundException("Upload not found")
        val path = uploadDirectory(uploadId).resolve("session.properties")
        if (!Files.isRegularFile(path)) throw NotFoundException("Upload not found")
        return loadUploadSession(path)
    }

    private fun loadUploadSession(path: Path): UploadSession {
        val properties = Properties()
        Files.newInputStream(path).use { input -> properties.load(input) }
        val updatedAt = properties.getProperty("updatedAt")?.let { Instant.parse(it) }
            ?: Files.getLastModifiedTime(path).toInstant()
        val createdAt = properties.getProperty("createdAt")?.let { Instant.parse(it) }
            ?: updatedAt
        return try {
            MediaUploadSessions.fromProperties(
                properties = properties,
                updatedAtFallback = updatedAt,
                createdAtFallback = createdAt,
                tenantNameFallback = { tenantId -> tenantRepo.findById(tenantId)?.name },
            )
        } catch (e: Exception) {
            throw NotFoundException("Upload not found")
        }
    }

    private fun requireCurrentUploadOwner(session: UploadSession) {
        if (session.ownerUserId != user().id) throw NotFoundException("Upload not found")
    }

    private fun resolveUploadTenant(tenantName: String?): Tenant {
        val targetTenant = tenantName?.trim()?.takeIf { it.isNotBlank() }
            ?.let { tenantRepo.findByName(it) ?: throw NotFoundException("Tenant not found") }
            ?: requestContext.tenant
            ?: throw BadRequestException("tenantName is required")

        if (tenantMemberRepo.findByTenantAndUser(targetTenant, user()) == null) {
            throw ForbiddenException("User is not member of this tenant")
        }
        return targetTenant
    }

    private fun loadUploadTenant(session: UploadSession): Tenant =
        tenantRepo.findById(session.tenantId) ?: throw NotFoundException("Tenant not found")

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
                        log.warn("Ignoring invalid upload session $path", e)
                        null
                    }
                }
        }
    }

    private fun UploadSession.toPendingSummary(): PendingMediaUploadSummary {
        val tenant = loadUploadTenant(this)
        val uploadedChunks = uploadedChunks(uploadId)
        val uploadedBytes = uploadedChunks.sumOf { index ->
            Files.size(uploadDirectory(uploadId).resolve("chunks").resolve("$index.part"))
        }
        return PendingMediaUploadSummary(
            uploadId = uploadId,
            tenantName = tenant.name,
            tenantDisplayName = tenant.displayName,
            albumPath = albumPath,
            filename = filename,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            title = title,
            chunkSizeBytes = mediaConfig.chunkSizeBytes,
            uploadedChunks = uploadedChunks,
            uploadedBytes = uploadedBytes.coerceAtMost(sizeBytes),
            createdAt = createdAt,
            updatedAt = updatedAt,
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
                    if (!name.endsWith(".part")) null else name.removeSuffix(".part").toIntOrNull()
                }
                .sorted()
        }
    }

    private fun uploadDirectory(uploadId: String): Path =
        storagePath("_uploads/$uploadId")

    private fun storagePath(storageKey: String): Path {
        val root = mediaConfig.storageDir.toAbsolutePath().normalize()
        val path = root.resolve(storageKey).normalize()
        if (!path.startsWith(root)) throw BadRequestException("Invalid media storage key")
        return path
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
        }
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

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0 && input.read() == -1) break
            remaining -= if (skipped > 0) skipped else 1
        }
    }

    private fun Media.toStreamSummary(album: TenantAlbum?): StreamMediaSummary =
        StreamMediaSummary(publicId, title, album?.path(), owner.id, createdAt, mimeType, sizeBytes, contentUrl())

    private fun Media.toUploadSummary(album: TenantAlbum): UploadMediaSummary =
        UploadMediaSummary(publicId, title, album.path(), owner.id, createdAt, mimeType, sizeBytes, contentUrl())

    private fun Media.contentUrl(): String =
        "/api/${url(tenant().name)}/media/${url(publicId)}/content"

    private fun url(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun safeHeaderFilename(value: String): String =
        value.replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_")
}
