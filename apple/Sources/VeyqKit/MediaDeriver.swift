import Foundation
import CoreGraphics
import ImageIO
import AVFoundation
import UniformTypeIdentifiers

// en.memory.derivatives (apple, cr-0047) — the platform-native pixel stage of the media derivatives
// pipeline, the Apple analog of web `media.ts`'s `MediaDeriver` (Canvas/<video>). On add-media the client
// downscales an image to a **thumbnail** + **preview** JPEG (a video: one **first-frame poster** reused for
// both), seals each as its own `K_blob` blob, and uploads original + derivatives in one batch — so a later
// gallery fetches grid=thumbnail / lightbox=preview without ever pulling the full original.
//
// Parity here is by **property, not bytes** (en.memory.derivatives → *Quality notes*): the sealing stays
// vector-gated/byte-identical across surfaces, but this lossy decode → downscale → JPEG re-encode is
// platform-native and byte-divergent by construction — a derivative need only be a valid JPEG at the target
// longest edge. So the pixel stage hides behind this injectable protocol: the wiring (dimension math,
// role/batch assembly, tier selection) is unit-tested with a stub, while the real ImageIO/AVFoundation decode
// is a manual live round-trip (no automated device-pixel test).
//
// **Cross-platform, no UIKit.** `VeyqKit` builds on macOS = iOS = iPadOS = tvOS, so the native deriver uses
// **ImageIO** (`CGImageSourceCreateThumbnailAtIndex`) for the image downscale and **AVFoundation**
// (`AVAssetImageGenerator`) for the video first-frame poster — both are macOS+iOS+tvOS. UIKit
// `UIImage`/`UIGraphicsImageRenderer` is iOS-only and would break the macOS build.

/// A pinned derivative tier spec — longest-edge cap + JPEG quality. Pinned from the legacy E2EE client
/// (en.memory.derivatives → *The three tiers*): thumbnail 400/q0.80 (grid), preview 2048/q0.85 (lightbox).
public struct DerivativeSpec: Sendable, Equatable {
    public let maxEdge: Int
    public let quality: Double

    public init(maxEdge: Int, quality: Double) {
        self.maxEdge = maxEdge
        self.quality = quality
    }

    /// Gallery **grid** tier: JPEG, longest edge 400 px, quality 0.80.
    public static let thumbnail = DerivativeSpec(maxEdge: 400, quality: 0.80)
    /// **Lightbox** tier: JPEG, longest edge 2048 px, quality 0.85.
    public static let preview = DerivativeSpec(maxEdge: 2048, quality: 0.85)
}

/// The platform-native pixel stage behind an interface: the real one uses ImageIO + AVFoundation; tests inject
/// a stub. A derivative need only be a valid JPEG at the target longest edge — parity is by **property**, not
/// bytes (en.memory.derivatives → *Quality notes*).
public protocol MediaDeriver: Sendable {
    /// Downscale image bytes to a JPEG with longest edge ≤ `maxEdge` at `quality` (EXIF orientation baked in,
    /// metadata stripped). Never upscales.
    func deriveImage(_ data: Data, maxEdge: Int, quality: Double) async throws -> Data
    /// Decode a video's first frame to a JPEG poster (reused for both the thumbnail and the preview tier).
    func deriveVideoPoster(_ data: Data) async throws -> Data
}

/// Longest-edge target math (pure): scale `width`×`height` so the longest edge is ≤ `maxEdge`, preserving the
/// aspect ratio and **never upscaling**. Each side is at least 1 px. The cross-surface contract the native
/// downscalers (here ImageIO, on android Bitmap) must satisfy — and the directly unit-tested spec.
public func scaleToMaxEdge(width: Int, height: Int, maxEdge: Int) -> (width: Int, height: Int) {
    let longest = max(width, height)
    if longest <= maxEdge || longest == 0 { return (width, height) }
    let scale = Double(maxEdge) / Double(longest)
    return (max(1, Int((Double(width) * scale).rounded())), max(1, Int((Double(height) * scale).rounded())))
}

/// The real device deriver — ImageIO decode/downscale + AVFoundation first-frame poster. The default for
/// `addMedia`; replaced by a stub in unit tests (the real decode is proven by a live round-trip).
public struct NativeMediaDeriver: MediaDeriver {
    public init() {}

    public func deriveImage(_ data: Data, maxEdge: Int, quality: Double) async throws -> Data {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { throw VeyqError.mediaDeriveFailed }

        // Read the source's pixel dimensions (no full decode) and compute the longest-edge target with the
        // shared pure math; ImageIO then performs the decode + downscale natively to that cap. Capping at the
        // source's own longest edge guarantees we never upscale.
        let maxPixel: Int
        if let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
           let width = props[kCGImagePropertyPixelWidth] as? Int,
           let height = props[kCGImagePropertyPixelHeight] as? Int {
            let target = scaleToMaxEdge(width: width, height: height, maxEdge: maxEdge)
            maxPixel = max(target.width, target.height, 1)
        } else {
            maxPixel = maxEdge
        }

        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,   // bake in EXIF orientation
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            throw VeyqError.mediaDeriveFailed
        }
        return try Self.encodeJPEG(image, quality: quality)
    }

    public func deriveVideoPoster(_ data: Data) async throws -> Data {
        // AVURLAsset needs a URL (there is no data initializer), so stage the bytes in a temp file. The file
        // needs a container extension — AVFoundation infers the format from the path, and an extension-less
        // file is refused. `.mp4` covers the ISOBMFF family we support (mp4/mov/m4v share the parser).
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".mp4")
        try data.write(to: tmp)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: tmp))
        generator.appliesPreferredTrackTransform = true   // honor the track's rotation metadata
        // First decodable frame at/after t=0: some encodes have no frame exactly at 0, so allow forward
        // tolerance (a strict 0/0 window fails on those) while never picking a frame before the start.
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .positiveInfinity
        // Cap the poster at the preview tier; the (smaller) thumbnail tier reuses the same JPEG bytes as-is.
        generator.maximumSize = CGSize(width: DerivativeSpec.preview.maxEdge, height: DerivativeSpec.preview.maxEdge)

        let image: CGImage
        do {
            image = try await generator.image(at: .zero).image
        } catch {
            throw VeyqError.mediaDeriveFailed
        }
        return try Self.encodeJPEG(image, quality: DerivativeSpec.preview.quality)
    }

    /// Encode a `CGImage` to JPEG at `quality` (no metadata) via ImageIO.
    private static func encodeJPEG(_ image: CGImage, quality: Double) throws -> Data {
        guard let buffer = CFDataCreateMutable(nil, 0),
              let destination = CGImageDestinationCreateWithData(buffer, UTType.jpeg.identifier as CFString, 1, nil)
        else { throw VeyqError.mediaDeriveFailed }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { throw VeyqError.mediaDeriveFailed }
        return buffer as Data
    }
}
