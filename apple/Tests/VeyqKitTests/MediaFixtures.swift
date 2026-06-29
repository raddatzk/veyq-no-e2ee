import Foundation
import CoreGraphics
import ImageIO
import AVFoundation
import CoreVideo
import UniformTypeIdentifiers
@testable import VeyqKit

// Shared real-media fixtures for the deriver/transport tests (cr-0047): generate genuine PNG + H.264 MP4 bytes
// on-device so a test can exercise the real `NativeMediaDeriver` (ImageIO / AVFoundation), and read a JPEG's
// dimensions back. Used by the hermetic `NativeMediaDeriverTests` (pixel stage, no network) and the env-gated
// `LiveRoundTripTests` (transport). Parity is by **property**, not bytes (en.memory.derivatives → Quality notes).
enum MediaFixtures {

    /// A real PNG of `width`×`height` with a coarse diagonal gradient — decodable source for the image deriver.
    static func png(width: Int, height: Int) throws -> Data {
        let cs = CGColorSpace(name: CGColorSpace.sRGB)!
        guard let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: cs, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { throw VeyqError.mediaDeriveFailed }
        for y in stride(from: 0, to: height, by: 8) {
            for x in stride(from: 0, to: width, by: 8) {
                ctx.setFillColor(red: CGFloat(x) / CGFloat(width), green: CGFloat(y) / CGFloat(height),
                                 blue: 0.5, alpha: 1)
                ctx.fill(CGRect(x: x, y: y, width: 8, height: 8))
            }
        }
        guard let image = ctx.makeImage() else { throw VeyqError.mediaDeriveFailed }
        let buffer = CFDataCreateMutable(nil, 0)!
        guard let dest = CGImageDestinationCreateWithData(buffer, UTType.png.identifier as CFString, 1, nil)
        else { throw VeyqError.mediaDeriveFailed }
        CGImageDestinationAddImage(dest, image, nil)
        guard CGImageDestinationFinalize(dest) else { throw VeyqError.mediaDeriveFailed }
        return buffer as Data
    }

    /// A real H.264 MP4 of `frameCount` frames (color shifts per frame) — decodable source for the video deriver.
    static func mp4(width: Int, height: Int, frameCount: Int) async throws -> Data {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".mp4")
        defer { try? FileManager.default.removeItem(at: url) }
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: width, AVVideoHeightKey: height,
        ])
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32ARGB,
            kCVPixelBufferWidthKey as String: width, kCVPixelBufferHeightKey as String: height,
        ])
        guard writer.canAdd(input) else { throw VeyqError.mediaDeriveFailed }
        writer.add(input)
        guard writer.startWriting() else { throw VeyqError.mediaDeriveFailed }
        writer.startSession(atSourceTime: .zero)

        for i in 0 ..< frameCount {
            while !input.isReadyForMoreMediaData { try await Task.sleep(nanoseconds: 5_000_000) }
            let pixelBuffer = try pixelBuffer(adaptor: adaptor, width: width, height: height, frame: i, total: frameCount)
            adaptor.append(pixelBuffer, withPresentationTime: CMTime(value: CMTimeValue(i), timescale: 30))
        }
        input.markAsFinished()
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in writer.finishWriting { cont.resume() } }
        guard writer.status == .completed else { throw VeyqError.mediaDeriveFailed }
        return try Data(contentsOf: url)
    }

    /// The pixel dimensions of a JPEG blob (asserts it is a decodable JPEG image).
    static func jpegDimensions(_ data: Data) throws -> (width: Int, height: Int) {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? Int, let h = props[kCGImagePropertyPixelHeight] as? Int,
              (CGImageSourceGetType(source) as String?) == UTType.jpeg.identifier
        else { throw VeyqError.mediaDeriveFailed }
        return (w, h)
    }

    private static func pixelBuffer(adaptor: AVAssetWriterInputPixelBufferAdaptor, width: Int, height: Int,
                                    frame: Int, total: Int) throws -> CVPixelBuffer {
        var pixelBuffer: CVPixelBuffer?
        guard let pool = adaptor.pixelBufferPool,
              CVPixelBufferPoolCreatePixelBuffer(nil, pool, &pixelBuffer) == kCVReturnSuccess,
              let pb = pixelBuffer else { throw VeyqError.mediaDeriveFailed }
        CVPixelBufferLockBaseAddress(pb, [])
        defer { CVPixelBufferUnlockBaseAddress(pb, []) }
        let cs = CGColorSpace(name: CGColorSpace.sRGB)!
        guard let ctx = CGContext(data: CVPixelBufferGetBaseAddress(pb), width: width, height: height,
                                  bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(pb), space: cs,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        else { throw VeyqError.mediaDeriveFailed }
        let t = CGFloat(frame) / CGFloat(max(1, total))
        ctx.setFillColor(red: t, green: 0.3, blue: 1 - t, alpha: 1)
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        ctx.setFillColor(red: 1, green: 1, blue: 1, alpha: 1)
        ctx.fill(CGRect(x: CGFloat(width) * t, y: 0, width: 80, height: CGFloat(height)))
        return pb
    }
}
