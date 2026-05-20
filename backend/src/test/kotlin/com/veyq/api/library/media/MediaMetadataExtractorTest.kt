package com.veyq.api.library.media

import com.veyq.api.domain.Media
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class MediaMetadataExtractorTest {
    @Test
    fun `applies extracted metadata to media`() {
        val media = Media()
        val capturedAt = Instant.parse("2026-01-02T03:04:05Z")

        MediaMetadataExtractor().apply(
            media,
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

        assertThat(media.capturedAt).isEqualTo(capturedAt)
        assertThat(media.capturedAtOverride).isFalse()
        assertThat(media.cameraMake).isEqualTo("Canon")
        assertThat(media.cameraModel).isEqualTo("R6")
        assertThat(media.lensModel).isEqualTo("RF 35mm")
        assertThat(media.imageWidth).isEqualTo(6000)
        assertThat(media.imageHeight).isEqualTo(4000)
        assertThat(media.gpsLatitude).isEqualTo(52.52)
        assertThat(media.gpsLongitude).isEqualTo(13.405)
    }
}
