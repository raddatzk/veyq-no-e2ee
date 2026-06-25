package app.veyq.crypto

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/**
 * Maps platform EXIF reader output into sealed media metadata. The byte parser stays platform-specific;
 * this shared mapper keeps GPS sign handling and EXIF formatting testable in the pure JVM core module.
 */
object MediaExif {
    data class Raw(
        val latitude: Double? = null,
        val latitudeRef: String? = null,
        val longitude: Double? = null,
        val longitudeRef: String? = null,
        val dateTimeOriginal: String? = null,
        val cameraMake: String? = null,
        val cameraModel: String? = null,
        val lens: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val fileSize: Int? = null,
        val fNumber: Double? = null,
        val exposureTime: Double? = null,
        val iso: String? = null,
        val focalLength: Double? = null,
        val focalLength35mm: String? = null,
        val flash: Int? = null,
        val software: String? = null,
        val altitude: Double? = null,
        val altitudeRef: Int? = null,
    )

    fun map(raw: Raw, fallbackTakenAt: String): Media.MediaMeta {
        val exif = linkedMapOf<String, String>()
        raw.fNumber?.takeIf { it > 0 }?.let { exif["Aperture"] = "f/${trim(it)}" }
        raw.exposureTime?.takeIf { it > 0 }?.let {
            exif["Exposure"] = if (it < 1) "1/${round(1 / it).toInt()} s" else "${trim(it)} s"
        }
        raw.iso.clean()?.let { exif["ISO"] = it }
        raw.focalLength?.takeIf { it > 0 }?.let { exif["Focal length"] = "${trim(it)} mm" }
        raw.focalLength35mm.clean()?.let { exif["Focal length (35mm)"] = "$it mm" }
        raw.flash?.takeIf { it >= 0 }?.let { exif["Flash"] = if (it and 1 != 0) "On" else "Off" }
        raw.software.clean()?.let { exif["Software"] = it }
        raw.altitude?.let {
            val signed = if (raw.altitudeRef == 1) -abs(it) else it
            exif["Altitude"] = "${trim(signed)} m"
        }

        return Media.MediaMeta(
            parseDateTimeOriginal(raw.dateTimeOriginal) ?: fallbackTakenAt,
            signedCoordinate(raw.latitude, raw.latitudeRef, "S"),
            signedCoordinate(raw.longitude, raw.longitudeRef, "W"),
            raw.cameraMake.clean(),
            raw.cameraModel.clean(),
            raw.lens.clean(),
            raw.width?.takeIf { it > 0 },
            raw.height?.takeIf { it > 0 },
            raw.fileSize,
            exif.ifEmpty { null },
        )
    }

    private fun parseDateTimeOriginal(value: String?): String? = value.clean()?.let {
        runCatching {
            LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
                .atOffset(ZoneOffset.UTC)
                .toString()
        }.getOrNull()
    }

    private fun signedCoordinate(value: Double?, ref: String?, negativeRef: String): Double? {
        if (value == null) return null
        return when (ref.clean()?.uppercase()) {
            negativeRef -> -abs(value)
            "N", "E" -> abs(value)
            else -> value
        }
    }

    private fun trim(n: Double): String =
        (round(n * 100) / 100.0).let { if (it == floor(it)) it.toInt().toString() else it.toString() }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
