import Foundation
import ImageIO

// Pull capture time + GPS + a broad set of EXIF camera details from a JPEG via ImageIO, sealed client-side
// as sealedMeta (operator-blind). Best-effort: fields absent in the file are simply omitted.
// Kept here in VeyqCrypto (not the app) so the GPS hemisphere->sign mapping, the DateTimeOriginal parsing and
// the EXIF formatting are unit-tested — the platform reader does the byte parsing; this maps its result.
extension Media {
    public static func extractMeta(_ data: Data) -> Media.MediaMeta {
        let fallback = ISO8601DateFormatter().string(from: Date())
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any]
        else { return Media.MediaMeta(takenAt: fallback, fileSize: data.count) }
        let tiff = props[kCGImagePropertyTIFFDictionary] as? [CFString: Any] ?? [:]
        let exifD = props[kCGImagePropertyExifDictionary] as? [CFString: Any] ?? [:]
        let gps = props[kCGImagePropertyGPSDictionary] as? [CFString: Any] ?? [:]

        var lat: Double?, lng: Double?
        if let rawLat = gps[kCGImagePropertyGPSLatitude] as? Double, let rawLng = gps[kCGImagePropertyGPSLongitude] as? Double {
            lat = (gps[kCGImagePropertyGPSLatitudeRef] as? String) == "S" ? -rawLat : rawLat
            lng = (gps[kCGImagePropertyGPSLongitudeRef] as? String) == "W" ? -rawLng : rawLng
        }
        var takenAt = fallback
        if let s = exifD[kCGImagePropertyExifDateTimeOriginal] as? String {
            let parser = DateFormatter(); parser.dateFormat = "yyyy:MM:dd HH:mm:ss"; parser.locale = Locale(identifier: "en_US_POSIX")
            if let date = parser.date(from: s) { takenAt = ISO8601DateFormatter().string(from: date) }
        }
        func trim(_ n: Double) -> String { String(format: "%g", (n * 100).rounded() / 100) }
        var exif: [String: String] = [:]
        if let f = exifD[kCGImagePropertyExifFNumber] as? Double { exif["Aperture"] = "f/\(trim(f))" }
        if let t = exifD[kCGImagePropertyExifExposureTime] as? Double, t > 0 { exif["Exposure"] = t < 1 ? "1/\(Int((1 / t).rounded())) s" : "\(trim(t)) s" }
        if let iso = (exifD[kCGImagePropertyExifISOSpeedRatings] as? [Int])?.first { exif["ISO"] = "\(iso)" }
        if let fl = exifD[kCGImagePropertyExifFocalLength] as? Double { exif["Focal length"] = "\(trim(fl)) mm" }
        if let fl35 = exifD[kCGImagePropertyExifFocalLenIn35mmFilm] as? Int { exif["Focal length (35mm)"] = "\(fl35) mm" }
        if let flash = exifD[kCGImagePropertyExifFlash] as? Int { exif["Flash"] = (flash & 1) != 0 ? "On" : "Off" }
        if let sw = tiff[kCGImagePropertyTIFFSoftware] as? String, !sw.isEmpty { exif["Software"] = sw }
        if let alt = gps[kCGImagePropertyGPSAltitude] as? Double {
            let below = (gps[kCGImagePropertyGPSAltitudeRef] as? Int) == 1
            exif["Altitude"] = "\(trim(below ? -alt : alt)) m"
        }
        return Media.MediaMeta(
            takenAt: takenAt, lat: lat, lng: lng,
            cameraMake: (tiff[kCGImagePropertyTIFFMake] as? String)?.trimmingCharacters(in: .whitespaces),
            cameraModel: (tiff[kCGImagePropertyTIFFModel] as? String)?.trimmingCharacters(in: .whitespaces),
            lens: (exifD[kCGImagePropertyExifLensModel] as? String)?.trimmingCharacters(in: .whitespaces),
            width: (exifD[kCGImagePropertyExifPixelXDimension] as? Int) ?? (props[kCGImagePropertyPixelWidth] as? Int),
            height: (exifD[kCGImagePropertyExifPixelYDimension] as? Int) ?? (props[kCGImagePropertyPixelHeight] as? Int),
            fileSize: data.count, exif: exif.isEmpty ? nil : exif,
        )
    }
}
