package com.veyq.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import com.veyq.client.DerivativeSpec
import com.veyq.client.MediaDeriver
import com.veyq.client.VeyqError
import com.veyq.client.scaleToMaxEdge
import java.io.ByteArrayOutputStream

/**
 * The real Android pixel stage of the media derivatives pipeline (cr-0048, en.memory.derivatives) — the `:app`
 * impl of the `:client` [MediaDeriver] seam (ADR 0010: `:client` is pure Kotlin/JVM, so the Bitmap /
 * MediaMetadataRetriever work can only live here, injected into `VeyqClient` like `BlobTransport`).
 *
 * Image: a bounds-only decode → power-of-two `inSampleSize` downscale (cheap, avoids decoding the full bitmap)
 * → exact `createScaledBitmap` to the longest-edge target ([scaleToMaxEdge]) → JPEG `compress`. Video: the
 * first frame via `MediaMetadataRetriever.getFrameAtTime(0)`, scaled to the preview cap and JPEG-encoded — one
 * poster reused for both tiers. Parity is by **property, not bytes** (en.memory.derivatives → Quality notes):
 * the output need only be a valid JPEG at the target longest edge.
 */
class AndroidMediaDeriver : MediaDeriver {

    override fun deriveImage(data: ByteArray, maxEdge: Int, quality: Int): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw VeyqError.MediaDeriveFailed

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, decodeOpts) ?: throw VeyqError.MediaDeriveFailed
        return encodeJpeg(scaleToCap(decoded, maxEdge), quality)
    }

    override fun deriveVideoPoster(data: ByteArray, quality: Int): ByteArray {
        val retriever = MediaMetadataRetriever()
        val frame: Bitmap = try {
            retriever.setDataSource(ByteArrayMediaDataSource(data))
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: throw VeyqError.MediaDeriveFailed
        } catch (e: VeyqError) {
            throw e
        } catch (e: Exception) {
            throw VeyqError.MediaDeriveFailed
        } finally {
            retriever.release()
        }
        // The poster is capped at the preview tier; the (smaller) thumbnail tier reuses the same JPEG bytes.
        return encodeJpeg(scaleToCap(frame, DerivativeSpec.PREVIEW.maxEdge), quality)
    }

    /** Exact longest-edge downscale to [maxEdge] (never upscales) via the shared [scaleToMaxEdge] math. */
    private fun scaleToCap(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val (w, h) = scaleToMaxEdge(bitmap.width, bitmap.height, maxEdge)
        return if (w == bitmap.width && h == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw VeyqError.MediaDeriveFailed
        return out.toByteArray()
    }

    /** Largest power-of-two `inSampleSize` that keeps the decoded longest edge ≥ [maxEdge] (so the exact
     *  `createScaledBitmap` afterwards only ever downsizes). */
    private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /** Feeds the video bytes to [MediaMetadataRetriever] in-memory — no temp file, no Context. */
    private class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
        override fun getSize(): Long = data.size.toLong()
        override fun close() {}
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val count = minOf(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, count)
            return count
        }
    }
}
