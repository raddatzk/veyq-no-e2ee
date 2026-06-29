package com.veyq.client

// en.memory.derivatives (android, cr-0048) — the pixel-stage seam of the media derivatives pipeline, the
// Android analog of web `media.ts`'s `MediaDeriver` and Apple's `MediaDeriver`. On add-media the client
// downscales an image to a **thumbnail** + **preview** JPEG (a video: one **first-frame poster** reused for
// both), seals each as its own `K_blob` blob, and uploads original + derivatives in one batch — so a later
// gallery fetches grid=thumbnail / lightbox=preview without ever pulling the full original.
//
// **Module split (ADR 0010).** `:client` is a pure Kotlin/JVM data layer with **no Android SDK**, so it cannot
// touch `android.graphics.Bitmap` / `MediaMetadataRetriever`. The deriver therefore mirrors `BlobTransport`:
// this **interface lives in `:client`** (bytes → JPEG bytes, no Android types) with a no-op default, and the
// real `AndroidMediaDeriver` impl lives in `:app` (Bitmap downscale + first-frame poster), injected into
// `VeyqClient` at construction. `:client` owns the role/batch assembly + tier selection; `:app` only the pixels.
//
// Parity here is by **property, not bytes** (en.memory.derivatives → Quality notes): the sealing stays
// vector-gated/byte-identical across surfaces, but this lossy decode → downscale → JPEG re-encode is
// platform-native and byte-divergent — a derivative need only be a valid JPEG at the target longest edge.

/** A pinned derivative tier spec — longest-edge cap + JPEG quality (0..100, Android's `Bitmap.compress` unit).
 *  Pinned from the legacy E2EE client (en.memory.derivatives → *The three tiers*). */
data class DerivativeSpec(val maxEdge: Int, val quality: Int) {
    companion object {
        /** Gallery **grid** tier: JPEG, longest edge 400 px, quality 0.80. */
        val THUMBNAIL = DerivativeSpec(maxEdge = 400, quality = 80)
        /** **Lightbox** tier: JPEG, longest edge 2048 px, quality 0.85. */
        val PREVIEW = DerivativeSpec(maxEdge = 2048, quality = 85)
    }
}

/** The platform-native pixel stage behind an interface (no Android types): the real one (`:app`) uses Bitmap +
 *  MediaMetadataRetriever; tests inject a stub. A derivative need only be a valid JPEG at the target longest
 *  edge — parity is by **property**, not bytes (en.memory.derivatives → Quality notes). */
interface MediaDeriver {
    /** Downscale image bytes to a JPEG with longest edge ≤ [maxEdge] at [quality] (0..100). Never upscales. */
    fun deriveImage(data: ByteArray, maxEdge: Int, quality: Int): ByteArray
    /** Decode a video's first frame to a JPEG poster (reused for both the thumbnail and the preview tier). */
    fun deriveVideoPoster(data: ByteArray, quality: Int): ByteArray
}

/** The `:client` default — no pixel stage available (pure JVM, no Android SDK). Adding an image/video media
 *  needs a real deriver injected (`:app` supplies `AndroidMediaDeriver`); otherwise generation fails cleanly
 *  and only original-only / non-derivable types work. */
object NoOpMediaDeriver : MediaDeriver {
    override fun deriveImage(data: ByteArray, maxEdge: Int, quality: Int): ByteArray = throw VeyqError.MediaDeriveFailed
    override fun deriveVideoPoster(data: ByteArray, quality: Int): ByteArray = throw VeyqError.MediaDeriveFailed
}

/** Longest-edge target math (pure): scale [width]×[height] so the longest edge is ≤ [maxEdge], preserving the
 *  aspect ratio and **never upscaling**; each side is at least 1 px. The cross-surface contract the native
 *  downscalers satisfy — here used by `:app`'s `AndroidMediaDeriver` and directly unit-tested in `:client`. */
fun scaleToMaxEdge(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= maxEdge || longest == 0) return width to height
    val scale = maxEdge.toDouble() / longest
    return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
}
