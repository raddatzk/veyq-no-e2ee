package com.veyq.api.library.media

import jakarta.enterprise.context.ApplicationScoped
import java.nio.file.Files
import java.nio.file.Path

data class DetectedMediaType(
    val mimeType: String,
    val kind: MediaKind,
)

enum class MediaKind {
    Image,
    Video,
}

@ApplicationScoped
class MediaTypeDetector {
    fun detect(path: Path): DetectedMediaType? {
        val header = ByteArray(32)
        val read = Files.newInputStream(path).use { input -> input.read(header) }
        if (read >= 3 &&
            header[0] == 0xFF.toByte() &&
            header[1] == 0xD8.toByte() &&
            header[2] == 0xFF.toByte()
        ) {
            return DetectedMediaType("image/jpeg", MediaKind.Image)
        }
        if (read >= 8 &&
            header[0] == 0x89.toByte() &&
            header[1] == 'P'.code.toByte() &&
            header[2] == 'N'.code.toByte() &&
            header[3] == 'G'.code.toByte() &&
            header[4] == 0x0D.toByte() &&
            header[5] == 0x0A.toByte() &&
            header[6] == 0x1A.toByte() &&
            header[7] == 0x0A.toByte()
        ) {
            return DetectedMediaType("image/png", MediaKind.Image)
        }
        if (read >= 6 &&
            header[0] == 'G'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() &&
            header[3] == '8'.code.toByte() &&
            (header[4] == '7'.code.toByte() || header[4] == '9'.code.toByte()) &&
            header[5] == 'a'.code.toByte()
        ) {
            return DetectedMediaType("image/gif", MediaKind.Image)
        }
        if (read >= 12 &&
            header[4] == 'f'.code.toByte() &&
            header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() &&
            header[7] == 'p'.code.toByte()
        ) {
            val majorBrand = String(header, 8, 4, Charsets.ISO_8859_1)
            return if (majorBrand == "qt  ") {
                DetectedMediaType("video/quicktime", MediaKind.Video)
            } else {
                DetectedMediaType("video/mp4", MediaKind.Video)
            }
        }
        return null
    }
}
