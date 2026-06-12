package com.veyq.api.library.media

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import com.drew.metadata.gif.GifHeaderDirectory
import com.drew.metadata.jpeg.JpegDirectory
import com.drew.metadata.png.PngDirectory
import com.veyq.api.domain.MediaBlob
import jakarta.inject.Singleton
import org.jboss.logging.Logger
import java.nio.file.Path
import java.time.Instant
import java.util.TimeZone

data class ExtractedMediaMetadata(
    val capturedAt: Instant? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val lensModel: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
)

@Singleton
class MediaMetadataExtractor {
    private val log: Logger = Logger.getLogger(MediaMetadataExtractor::class.java)

    fun extract(path: Path, mimeType: String): ExtractedMediaMetadata {
        if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) return ExtractedMediaMetadata()

        return try {
            readMetadata(ImageMetadataReader.readMetadata(path.toFile()))
        } catch (e: Exception) {
            log.debug("Could not extract metadata from $path", e)
            ExtractedMediaMetadata()
        }
    }

    fun apply(blob: MediaBlob, metadata: ExtractedMediaMetadata) {
        blob.capturedAt = metadata.capturedAt
        blob.cameraMake = metadata.cameraMake
        blob.cameraModel = metadata.cameraModel
        blob.lensModel = metadata.lensModel
        blob.imageWidth = metadata.imageWidth
        blob.imageHeight = metadata.imageHeight
        blob.gpsLatitude = metadata.gpsLatitude
        blob.gpsLongitude = metadata.gpsLongitude
    }

    private fun readMetadata(metadata: Metadata): ExtractedMediaMetadata {
        val ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
        val subIfd = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
        val gps = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)
        val gif = metadata.getFirstDirectoryOfType(GifHeaderDirectory::class.java)
        val jpeg = metadata.getFirstDirectoryOfType(JpegDirectory::class.java)
        val png = metadata.getFirstDirectoryOfType(PngDirectory::class.java)
        val orientation = orientation(ifd0)
        val dimensions = imageDimensions(subIfd, gif, jpeg, png, orientation)

        return ExtractedMediaMetadata(
            capturedAt = capturedAt(subIfd),
            cameraMake = ifd0?.getString(ExifIFD0Directory.TAG_MAKE)?.clean(),
            cameraModel = ifd0?.getString(ExifIFD0Directory.TAG_MODEL)?.clean(),
            lensModel = subIfd?.getString(ExifSubIFDDirectory.TAG_LENS_MODEL)?.clean(),
            imageWidth = dimensions?.first,
            imageHeight = dimensions?.second,
            gpsLatitude = gps?.geoLocation?.latitude,
            gpsLongitude = gps?.geoLocation?.longitude,
        )
    }

    fun extractOrientation(path: Path): Int? =
        try {
            val metadata = ImageMetadataReader.readMetadata(path.toFile())
            orientation(metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java))
        } catch (e: Exception) {
            log.debug("Could not extract image orientation from $path", e)
            null
        }

    private fun capturedAt(directory: ExifSubIFDDirectory?): Instant? =
        directory
            ?.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, TimeZone.getTimeZone("UTC"))
            ?.toInstant()

    private fun orientation(directory: ExifIFD0Directory?): Int? =
        directory
            ?.getInteger(ExifIFD0Directory.TAG_ORIENTATION)
            ?.takeIf { it in 1..8 }

    private fun imageDimensions(
        subIfd: ExifSubIFDDirectory?,
        gif: GifHeaderDirectory?,
        jpeg: JpegDirectory?,
        png: PngDirectory?,
        orientation: Int?,
    ): Pair<Int, Int>? {
        val width = subIfd?.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH)
            ?: gif?.getInteger(GifHeaderDirectory.TAG_IMAGE_WIDTH)
            ?: jpeg?.getInteger(JpegDirectory.TAG_IMAGE_WIDTH)
            ?: png?.getInteger(PngDirectory.TAG_IMAGE_WIDTH)
        val height = subIfd?.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT)
            ?: gif?.getInteger(GifHeaderDirectory.TAG_IMAGE_HEIGHT)
            ?: jpeg?.getInteger(JpegDirectory.TAG_IMAGE_HEIGHT)
            ?: png?.getInteger(PngDirectory.TAG_IMAGE_HEIGHT)

        return if (width != null && height != null && width > 0 && height > 0) {
            MediaImageOrientation.visibleDimensions(width, height, orientation)
        } else {
            null
        }
    }

    private fun String.clean(): String? = trim().takeIf { it.isNotBlank() }
}
