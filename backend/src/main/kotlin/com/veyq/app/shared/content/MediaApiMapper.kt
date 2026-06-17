package com.veyq.app.shared.content

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.BlobRef
import com.veyq.app.generated.model.MediaDerivativeOut
import com.veyq.app.generated.model.MediaItem
import com.veyq.app.platform.error.ApiException
import jakarta.enterprise.context.ApplicationScoped
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

/**
 * Maps a gallery's worth of media to the API shape in a fixed 3 queries (media + derivatives IN + blobs IN),
 * never N+1. Shared by the member gallery (MediaHandler) and the public-share gallery (PublicShareMediaHandler),
 * which previously held byte-identical copies that could drift.
 */
@ApplicationScoped
class MediaApiMapper(
    private val blobs: BlobRepository,
    private val derivatives: MediaDerivativeRepository,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()

    fun toApiBatch(items: List<Media>): List<MediaItem> {
        if (items.isEmpty()) return emptyList()
        val derivsByMedia = derivatives.listByMediaIds(items.map { it.id }).groupBy { it.mediaId }
        val blobsById = blobs.listByIds(
            (items.map { it.originalBlobId } + derivsByMedia.values.flatten().map { it.blobId }).toSet(),
        ).associateBy { it.id }
        fun ref(blobId: String): BlobRef {
            val b = blobsById[blobId] ?: throw ApiException(500, "MISSING_BLOB", "Blob not found")
            return BlobRef(blobId = b.id, totalChunks = b.totalChunks, byteSize = b.byteSize)
        }
        return items.map { m ->
            MediaItem(
                id = m.id, scopeId = m.scopeId, nodeId = m.nodeId,
                sealedMeta = Blob(suiteId = m.suiteId, bytes = enc.encodeToString(m.sealedMeta)),
                mimeType = m.mimeType,
                original = ref(m.originalBlobId),
                derivatives = derivsByMedia[m.id].orEmpty().map { MediaDerivativeOut(kind = MediaDerivativeOut.Kind.valueOf(it.kind), blob = ref(it.blobId)) },
                highlighted = m.highlighted,
                createdAt = OffsetDateTime.ofInstant(m.createdAt, ZoneOffset.UTC),
            )
        }
    }
}
