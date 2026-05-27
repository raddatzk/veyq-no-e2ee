package com.veyq.api.library.media

import com.veyq.api.domain.MediaBlob
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.time.Instant
import javax.imageio.ImageIO

class MediaMetadataExtractorTest {
    @Test
    fun `extracts image dimensions from image metadata`() {
        val image = Files.createTempFile("metadata-dimensions", ".png")
        ImageIO.write(BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB), "png", image.toFile())

        val metadata = MediaMetadataExtractor().extract(image, "image/png")

        assertThat(metadata.imageWidth).isEqualTo(16)
        assertThat(metadata.imageHeight).isEqualTo(9)
    }

    @Test
    fun `extracts visible image dimensions from exif orientation`() {
        val image = Files.createTempFile("metadata-oriented-dimensions", ".jpg")
        Files.write(image, orientedJpeg(16, 9, 6))

        val metadata = MediaMetadataExtractor().extract(image, "image/jpeg")

        assertThat(metadata.imageWidth).isEqualTo(9)
        assertThat(metadata.imageHeight).isEqualTo(16)
    }

    @Test
    fun `rotates image pixels for preview generation`() {
        val source = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB)
        source.setRGB(0, 0, 0xff0000)
        source.setRGB(2, 1, 0x0000ff)

        val oriented = MediaImageOrientation.apply(source, 6)

        assertThat(oriented.width).isEqualTo(2)
        assertThat(oriented.height).isEqualTo(3)
        assertThat(oriented.getRGB(1, 0) and 0xffffff).isEqualTo(0xff0000)
        assertThat(oriented.getRGB(0, 2) and 0xffffff).isEqualTo(0x0000ff)
    }

    @Test
    fun `applies extracted metadata to media blob`() {
        val blob = MediaBlob()
        val capturedAt = Instant.parse("2026-01-02T03:04:05Z")

        MediaMetadataExtractor().apply(
            blob,
            ExtractedMediaMetadata(
                capturedAt = capturedAt,
                cameraMake = "Canon",
                cameraModel = "R6",
                lensModel = "RF 35mm",
                imageWidth = 6000,
                imageHeight = 4000,
                gpsLatitude = 52.52,
                gpsLongitude = 13.405,
            )
        )

        assertThat(blob.capturedAt).isEqualTo(capturedAt)
        assertThat(blob.cameraMake).isEqualTo("Canon")
        assertThat(blob.cameraModel).isEqualTo("R6")
        assertThat(blob.lensModel).isEqualTo("RF 35mm")
        assertThat(blob.imageWidth).isEqualTo(6000)
        assertThat(blob.imageHeight).isEqualTo(4000)
        assertThat(blob.gpsLatitude).isEqualTo(52.52)
        assertThat(blob.gpsLongitude).isEqualTo(13.405)
    }

    private fun orientedJpeg(width: Int, height: Int, orientation: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val jpeg = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", jpeg)
        val bytes = jpeg.toByteArray()

        val exifPayload = byteArrayOf(
            0x45, 0x78, 0x69, 0x66, 0x00, 0x00,
            0x49, 0x49, 0x2a, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
            orientation.toByte(), 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val segmentLength = exifPayload.size + 2

        return ByteArrayOutputStream().use { output ->
            output.write(bytes, 0, 2)
            output.write(0xff)
            output.write(0xe1)
            output.write((segmentLength ushr 8) and 0xff)
            output.write(segmentLength and 0xff)
            output.write(exifPayload)
            output.write(bytes, 2, bytes.size - 2)
            output.toByteArray()
        }
    }
}
