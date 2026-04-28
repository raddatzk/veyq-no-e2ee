package com.lunva.api.library.media

import com.lunva.api.auth.BadRequestException
import com.lunva.api.auth.NotFoundException
import com.lunva.api.domain.*
import com.lunva.api.library.LibraryService
import com.lunva.api.library.explorer.ExplorerContext
import com.lunva.api.security.RequestContext
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.multipart.FileUpload
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Singleton
class MediaService @Inject constructor(
    private val albumMediaRepo: AlbumMediaRepo,
    private val albumRepo: TenantAlbumRepo,
    private val mediaRepo: MediaRepo,
    private val mediaConfig: MediaConfig,
    folderRepo: TenantFolderRepo,
    folderUserShareRepo: TenantFolderUserShareRepo,
    folderGroupShareRepo: TenantFolderGroupShareRepo,
    albumUserShareRepo: TenantAlbumUserShareRepo,
    albumGroupShareRepo: TenantAlbumGroupShareRepo,
    groupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext
) : LibraryService(folderRepo, albumRepo, folderUserShareRepo, folderGroupShareRepo, albumUserShareRepo, albumGroupShareRepo, groupMemberRepo, requestContext) {

    fun streamMedia(path: String?): Sequence<StreamMediaSummary> {
        val context = resolveVisibleContext(path)
        val albums = when (context) {
            ExplorerContext.Root -> visibleAlbums()
            is ExplorerContext.Folder -> visibleAlbums().filter { it.parentFolder != null && isDescendantOrSame(it.parentFolder!!, context.folder) }
            is ExplorerContext.Album -> listOf(context.album)
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
        if (size > mediaConfig.maxUploadBytes) throw BadRequestException("file is too large")
        val mimeType = upload.contentType() ?: Files.probeContentType(uploadPath) ?: ""
        if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) {
            throw BadRequestException("Only image and video uploads are supported")
        }

        val media = Media().also {
            it.tenant = tenant()
            it.owner = user()
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

    fun mediaContent(publicId: String): Response {
        val media = mediaRepo.findByTenantAndPublicId(tenant(), publicId)
            ?: throw NotFoundException("Media not found")
        if (!isMediaAccessible(media)) throw NotFoundException("Media not found")

        val path = storagePath(media.storageKey)
        if (!Files.isRegularFile(path)) throw NotFoundException("Media file not found")
        val filename = media.originalFilename?.takeIf { it.isNotBlank() } ?: "${media.publicId}.bin"
        return Response.ok(path.toFile(), media.mimeType)
            .header("Content-Length", media.sizeBytes)
            .header("Content-Disposition", "inline; filename=\"${safeHeaderFilename(filename)}\"")
            .build()
    }

    private fun visibleAlbums(): List<TenantAlbum> =
        albumRepo.listByTenant(tenant()).filter { isAlbumVisible(it) }

    private fun isDescendantOrSame(candidate: TenantFolder, ancestor: TenantFolder): Boolean =
        generateSequence(candidate) { it.parentFolder }.any { it.id == ancestor.id }

    private fun isMediaVisible(media: Media, album: TenantAlbum): Boolean =
        media.owner.id == user().id ||
                isAlbumVisible(album) ||
                mediaRepo.listAccessible(tenant(), user()).any { it.id == media.id }

    private fun isMediaAccessible(media: Media): Boolean =
        media.owner.id == user().id ||
                mediaRepo.listAccessible(tenant(), user()).any { it.id == media.id } ||
                albumMediaRepo.list("media", media).any { isAlbumVisible(it.album) }

    private fun resolveAlbum(path: String?): TenantAlbum =
        (resolveVisibleContext(path) as? ExplorerContext.Album)?.album ?: throw NotFoundException("Album not found")

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

    private fun storagePath(storageKey: String): Path {
        val root = mediaConfig.storageDir.toAbsolutePath().normalize()
        val path = root.resolve(storageKey).normalize()
        if (!path.startsWith(root)) throw BadRequestException("Invalid media storage key")
        return path
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
