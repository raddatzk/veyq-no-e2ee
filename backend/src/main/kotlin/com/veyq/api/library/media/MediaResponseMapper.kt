package com.veyq.api.library.media

import com.veyq.api.common.apiPathSegment as url
import com.veyq.api.common.toApiOffsetDateTime
import com.veyq.api.domain.Media
import com.veyq.api.domain.MediaBlob
import com.veyq.api.domain.MemoryMediaBucket
import com.veyq.api.generated.model.MediaUploader
import com.veyq.api.generated.model.MemoryMediaBucketSummary
import com.veyq.api.generated.model.MemoryMediaSummary
import com.veyq.api.generated.model.PublicShareMediaSummary
import jakarta.inject.Singleton

@Singleton
class MediaResponseMapper {
    fun toMemorySummary(media: Media, tenantName: String): MemoryMediaSummary =
        MemoryMediaSummary(
            id = media.publicId,
            memoryId = media.memory?.publicId ?: "",
            bucketId = media.bucket?.publicId,
            title = media.title,
            uploader = uploader(media),
            createdAt = media.createdAt.toApiOffsetDateTime(),
            updatedAt = media.updatedAt.toApiOffsetDateTime(),
            capturedAt = media.capturedAt?.toApiOffsetDateTime(),
            capturedAtOverride = media.capturedAtOverride,
            mimeType = media.blob.mimeType,
            sizeBytes = media.blob.sizeBytes,
            contentUrl = contentUrl(media, tenantName),
            highlight = media.highlight,
            thumbnailUrl = thumbnailUrl(media, tenantName),
            previewUrl = previewUrl(media, tenantName),
            posterUrl = posterUrl(media, tenantName),
            cameraMake = media.blob.cameraMake,
            cameraModel = media.blob.cameraModel,
            lensModel = media.blob.lensModel,
            imageWidth = media.blob.imageWidth,
            imageHeight = media.blob.imageHeight,
            gpsLatitude = media.blob.gpsLatitude,
            gpsLongitude = media.blob.gpsLongitude,
        )

    fun toPublicShareSummary(media: Media, shareId: String): PublicShareMediaSummary =
        PublicShareMediaSummary(
            id = media.publicId,
            memoryId = media.memory?.publicId ?: "",
            bucketId = media.bucket?.publicId,
            title = media.title,
            uploader = uploader(media),
            createdAt = media.createdAt.toApiOffsetDateTime(),
            updatedAt = media.updatedAt.toApiOffsetDateTime(),
            capturedAt = media.capturedAt?.toApiOffsetDateTime(),
            capturedAtOverride = media.capturedAtOverride,
            mimeType = media.blob.mimeType,
            sizeBytes = media.blob.sizeBytes,
            contentUrl = publicShareMediaUrl(shareId, media.publicId, "content"),
            highlight = media.highlight,
            thumbnailUrl = if (hasThumbnail(media.blob)) publicShareMediaUrl(shareId, media.publicId, "thumbnail") else null,
            previewUrl = if (canGeneratePreview(media.blob)) publicShareMediaUrl(shareId, media.publicId, "preview") else null,
            posterUrl = if (canGeneratePoster(media.blob)) publicShareMediaUrl(shareId, media.publicId, "poster") else null,
            gpsLatitude = media.blob.gpsLatitude,
            gpsLongitude = media.blob.gpsLongitude,
        )

    fun toBucketSummary(bucket: MemoryMediaBucket, counts: Map<Long, Int>, canManage: Boolean = true): MemoryMediaBucketSummary =
        MemoryMediaBucketSummary(
            id = bucket.publicId,
            memoryId = bucket.memory.publicId,
            parentBucketId = bucket.parentBucket?.publicId,
            displayName = bucket.displayName,
            primary = bucket.primaryBucket,
            uploader = bucketUploader(bucket),
            canManage = canManage,
            mediaCount = counts[bucket.id] ?: 0,
            createdAt = bucket.createdAt.toApiOffsetDateTime(),
            updatedAt = bucket.updatedAt.toApiOffsetDateTime(),
        )

    private fun uploader(media: Media): MediaUploader {
        media.uploaderUser?.let { user ->
            return MediaUploader(
                type = MediaUploader.Type.USER,
                displayName = user.displayName,
                permalink = user.permalink,
                userId = user.id,
            )
        }
        media.shareIdentity?.let { identity ->
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

    private fun bucketUploader(bucket: MemoryMediaBucket): MediaUploader =
        when {
            bucket.uploaderUser != null -> MediaUploader(
                type = MediaUploader.Type.USER,
                displayName = bucket.uploaderUser!!.displayName,
                userId = bucket.uploaderUser!!.id,
            )
            bucket.shareIdentity != null -> MediaUploader(
                type = MediaUploader.Type.ANONYMOUS,
                displayName = bucket.shareIdentity!!.displayName,
                userId = null,
            )
            else -> MediaUploader(type = MediaUploader.Type.ANONYMOUS, displayName = "Unknown", userId = null)
        }

    private fun contentUrl(media: Media, tenantName: String): String =
        mediaUrl(media, tenantName, "content")

    private fun thumbnailUrl(media: Media, tenantName: String): String? =
        if (hasThumbnail(media.blob)) mediaUrl(media, tenantName, "thumbnail") else null

    private fun previewUrl(media: Media, tenantName: String): String? =
        if (canGeneratePreview(media.blob)) mediaUrl(media, tenantName, "preview") else null

    private fun posterUrl(media: Media, tenantName: String): String? =
        if (canGeneratePoster(media.blob)) mediaUrl(media, tenantName, "poster") else null

    private fun mediaUrl(media: Media, tenantName: String, suffix: String): String =
        "/api/${url(tenantName)}/memory-media/${url(media.publicId)}/$suffix"

    private fun publicShareMediaUrl(shareId: String, mediaId: String, suffix: String): String =
        "/api/public-shares/${url(shareId)}/media/${url(mediaId)}/$suffix"

    private fun hasThumbnail(blob: MediaBlob): Boolean =
        blob.thumbnailStorageKey != null

    private fun canGeneratePreview(blob: MediaBlob): Boolean =
        blob.mimeType.startsWith("image/") || blob.mimeType.startsWith("video/")

    private fun canGeneratePoster(blob: MediaBlob): Boolean =
        blob.mimeType.startsWith("video/")
}
