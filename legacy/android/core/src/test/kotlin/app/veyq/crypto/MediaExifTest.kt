package app.veyq.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// uc.uploads.unified-media.geo-metadata (Android): ExifInterface parses JPEG bytes in :app; this tests OUR
// sealed-metadata mapping in the pure core module: hemisphere refs, DateTimeOriginal and formatted EXIF fields.
class MediaExifTest {
    @Test fun extractsSignedGpsFromHemisphereRefs() {
        val meta = MediaExif.map(
            MediaExif.Raw(
                latitude = 12.3,
                latitudeRef = "S",
                longitude = 45.6,
                longitudeRef = "W",
                dateTimeOriginal = "2024:01:02 03:04:05",
                fileSize = 123,
            ),
            fallbackTakenAt = "fallback",
        )

        assertEquals(-12.3, meta.lat)
        assertEquals(-45.6, meta.lng)
        assertEquals("2024-01-02T03:04:05Z", meta.takenAt)
        assertEquals(123, meta.fileSize)
    }

    @Test fun keepsNorthAndEastPositive() {
        val meta = MediaExif.map(
            MediaExif.Raw(latitude = 12.3, latitudeRef = "N", longitude = 45.6, longitudeRef = "E"),
            fallbackTakenAt = "fallback",
        )

        assertEquals(12.3, meta.lat)
        assertEquals(45.6, meta.lng)
    }

    @Test fun returnsFallbackForMissingExif() {
        val meta = MediaExif.map(MediaExif.Raw(fileSize = 12), fallbackTakenAt = "fallback")

        assertEquals("fallback", meta.takenAt)
        assertNull(meta.lat)
        assertNull(meta.lng)
        assertEquals(12, meta.fileSize)
        assertNull(meta.exif)
    }

    @Test fun mapsCameraAndExifFields() {
        val meta = MediaExif.map(
            MediaExif.Raw(
                cameraMake = "  Leica ",
                cameraModel = "Q3",
                lens = "Summilux 28",
                width = 6000,
                height = 4000,
                fNumber = 1.7,
                exposureTime = 0.004,
                iso = "100",
                focalLength = 28.0,
                focalLength35mm = "28",
                flash = 0,
                software = "VeyqCam",
                altitude = 35.4,
                altitudeRef = 1,
            ),
            fallbackTakenAt = "fallback",
        )

        assertEquals("Leica", meta.cameraMake)
        assertEquals("Q3", meta.cameraModel)
        assertEquals("Summilux 28", meta.lens)
        assertEquals(6000, meta.width)
        assertEquals(4000, meta.height)
        assertEquals(
            mapOf(
                "Aperture" to "f/1.7",
                "Exposure" to "1/250 s",
                "ISO" to "100",
                "Focal length" to "28 mm",
                "Focal length (35mm)" to "28 mm",
                "Flash" to "Off",
                "Software" to "VeyqCam",
                "Altitude" to "-35.4 m",
            ),
            meta.exif,
        )
    }
}
