package com.veyq.api.library.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

class MediaTypeDetectorTest {
    private val detector = MediaTypeDetector()

    @Test
    fun `detects allowed image and video formats from file bytes`() {
        assertThat(detector.detect(tempFile(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))))
            .isEqualTo(DetectedMediaType("image/jpeg", MediaKind.Image))
        assertThat(detector.detect(tempFile(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))))
            .isEqualTo(DetectedMediaType("image/png", MediaKind.Image))
        assertThat(detector.detect(tempFile("GIF89a".toByteArray())))
            .isEqualTo(DetectedMediaType("image/gif", MediaKind.Image))
        assertThat(detector.detect(tempFile(ftyp("isom"))))
            .isEqualTo(DetectedMediaType("video/mp4", MediaKind.Video))
        assertThat(detector.detect(tempFile(ftyp("qt  "))))
            .isEqualTo(DetectedMediaType("video/quicktime", MediaKind.Video))
    }

    @Test
    fun `rejects svg text and unknown bytes`() {
        assertThat(detector.detect(tempFile("""<svg xmlns="http://www.w3.org/2000/svg"></svg>""".toByteArray())))
            .isNull()
        assertThat(detector.detect(tempFile("not media".toByteArray())))
            .isNull()
    }

    private fun ftyp(brand: String): ByteArray =
        byteArrayOf(0, 0, 0, 20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) +
            brand.toByteArray(Charsets.ISO_8859_1)

    private fun tempFile(bytes: ByteArray) =
        Files.createTempFile("veyq-media-type-", ".bin").also { Files.write(it, bytes) }
}
