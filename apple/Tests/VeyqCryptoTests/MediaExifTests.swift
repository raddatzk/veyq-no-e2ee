import XCTest
import CoreGraphics
import ImageIO
import VeyqCrypto

// uc.uploads.unified-media.geo-metadata (iOS): ImageIO parses the JPEG bytes; this tests OUR mapping of
// its result — hemisphere -> signed lat/lng and DateTimeOriginal -> ISO. Self-contained: we write a JPEG
// with known GPS via ImageIO, then read it back through Media.extractMeta (no binary fixture needed).
final class MediaExifTests: XCTestCase {
    private func jpegWithGps(lat: Double, latRef: String, lng: Double, lngRef: String, dateTime: String) -> Data {
        let ctx = CGContext(data: nil, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                            space: CGColorSpaceCreateDeviceRGB(),
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        let img = ctx.makeImage()!
        let out = NSMutableData()
        let dest = CGImageDestinationCreateWithData(out, "public.jpeg" as CFString, 1, nil)!
        let props: [CFString: Any] = [
            kCGImagePropertyGPSDictionary: [
                kCGImagePropertyGPSLatitude: lat, kCGImagePropertyGPSLatitudeRef: latRef,
                kCGImagePropertyGPSLongitude: lng, kCGImagePropertyGPSLongitudeRef: lngRef,
            ],
            kCGImagePropertyExifDictionary: [kCGImagePropertyExifDateTimeOriginal: dateTime],
        ]
        CGImageDestinationAddImage(dest, img, props as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(dest))
        return out as Data
    }

    func testExtractsSignedGpsFromHemisphereRefs() {
        let meta = Media.extractMeta(jpegWithGps(lat: 12.3, latRef: "S", lng: 45.6, lngRef: "W", dateTime: "2024:01:02 03:04:05"))
        XCTAssertEqual(meta.lat ?? 0, -12.3, accuracy: 0.01)   // S -> negative
        XCTAssertEqual(meta.lng ?? 0, -45.6, accuracy: 0.01)   // W -> negative
        XCTAssertTrue(meta.takenAt.hasPrefix("2024-01-02"))
    }

    func testKeepsNorthAndEastPositive() {
        let meta = Media.extractMeta(jpegWithGps(lat: 12.3, latRef: "N", lng: 45.6, lngRef: "E", dateTime: "2024:01:02 03:04:05"))
        XCTAssertEqual(meta.lat ?? 0, 12.3, accuracy: 0.01)
        XCTAssertEqual(meta.lng ?? 0, 45.6, accuracy: 0.01)
    }

    func testReturnsFallbackForNonImageBytes() {
        let meta = Media.extractMeta(Data("not an image".utf8))
        XCTAssertNil(meta.lat)
        XCTAssertNil(meta.lng)
        XCTAssertFalse(meta.takenAt.isEmpty)  // fallback timestamp, never empty
    }
}
