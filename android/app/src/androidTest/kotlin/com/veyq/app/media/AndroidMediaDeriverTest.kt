package com.veyq.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.veyq.client.DerivativeSpec
import com.veyq.client.VeyqError
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * cr-0048 — on-emulator property test of the REAL [AndroidMediaDeriver] (Bitmap downscale +
 * MediaMetadataRetriever first-frame poster). The pure-JVM `:client:test` injects a stub deriver, so the real
 * pixel stage can only run with the Android runtime — this is the Android analog of Apple's hermetic
 * `NativeMediaDeriverTests`, run as an instrumented test (it needs `android.graphics`). Parity is by
 * **property** (decodable JPEG, longest edge ≤ the tier cap, aspect preserved), not byte vector
 * (en.memory.derivatives → Quality notes). The transport (seal→presigned PUT/GET) is already proven by
 * `:client:test` + the backend e2e; this proves only the derivation. Derived tiers are written back via
 * [PlatformTestStorageRegistry] as the visual proof.
 */
@RunWith(AndroidJUnit4::class)
class AndroidMediaDeriverTest {

    private val deriver = AndroidMediaDeriver()

    @Test fun imageDownscaleCapsLongestEdgeAtEachTierPreservingAspect() {
        val png = makePng(1600, 900)

        val thumbnail = deriver.deriveImage(png, DerivativeSpec.THUMBNAIL.maxEdge, DerivativeSpec.THUMBNAIL.quality)
        val (tw, th) = jpegDimensions(thumbnail)   // also asserts it decodes as JPEG
        assertEquals("thumbnail longest edge hits the 400 cap", 400, maxOf(tw, th))
        assertEquals("aspect ratio preserved", 1600.0 / 900.0, tw.toDouble() / th, 0.02)
        save("image-thumbnail-${tw}x$th.jpg", thumbnail)

        val preview = deriver.deriveImage(png, DerivativeSpec.PREVIEW.maxEdge, DerivativeSpec.PREVIEW.quality)
        val (pw, ph) = jpegDimensions(preview)
        assertEquals("source within the 2048 cap is never upscaled (width)", 1600, pw)
        assertEquals("source within the 2048 cap is never upscaled (height)", 900, ph)
        save("image-preview-${pw}x$ph.jpg", preview)
    }

    @Test fun videoPosterIsADecodableJpegWithinThePreviewCap() {
        val mp4 = InstrumentationRegistry.getInstrumentation().context.assets.open("sample.mp4").use { it.readBytes() }
        val poster = deriver.deriveVideoPoster(mp4, DerivativeSpec.PREVIEW.quality)
        val (w, h) = jpegDimensions(poster)        // decodes as JPEG = first frame extracted
        assertTrue("poster longest edge ≤ 2048", maxOf(w, h) <= DerivativeSpec.PREVIEW.maxEdge)
        assertEquals("poster keeps the video aspect ratio", 320.0 / 240.0, w.toDouble() / h, 0.05)
        save("video-poster-${w}x$h.jpg", poster)
    }

    @Test fun undecodableImageBytesFailCleanly() {
        try {
            deriver.deriveImage(byteArrayOf(0x00, 0x01, 0x02, 0x03), 400, 80)
            throw AssertionError("expected MediaDeriveFailed on garbage bytes")
        } catch (e: VeyqError) {
            assertTrue("best-effort generation fails cleanly", e === VeyqError.MediaDeriveFailed)
        }
    }

    // --- fixtures / helpers ---

    private fun makePng(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                paint.color = Color.rgb(255 * x / width, 255 * y / height, 128)
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + 8).toFloat(), (y + 8).toFloat(), paint)
                x += 8
            }
            y += 8
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun jpegDimensions(data: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        assertEquals("derivative is a JPEG", "image/jpeg", opts.outMimeType)
        assertTrue("derivative decodes", opts.outWidth > 0 && opts.outHeight > 0)
        return opts.outWidth to opts.outHeight
    }

    private fun save(name: String, bytes: ByteArray) {
        PlatformTestStorageRegistry.getInstance().openOutputFile(name).use { it.write(bytes) }
    }
}
