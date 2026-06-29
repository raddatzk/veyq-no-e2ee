import XCTest
import Foundation
@testable import VeyqKit

// cr-0047 — hermetic property tests of the REAL `NativeMediaDeriver` (ImageIO image downscale + AVFoundation
// first-frame poster), run as part of plain `swift test` (no network, no live backend). Unlike web — where
// jsdom can't decode Canvas/<video>, so the deriver must be live-only — ImageIO/AVFoundation decode fine in the
// macOS test process, so the pixel stage IS automatable here, **by property** (decodable JPEG, longest edge ≤
// the tier cap, aspect preserved), not by byte vector (en.memory.derivatives → Quality notes). This is the
// fast feedback loop: deriver regressions (e.g. a missing container extension, a strict first-frame tolerance)
// surface here in ~1s instead of only in the slow env-gated live transport round-trip.
final class NativeMediaDeriverTests: XCTestCase {

    private let deriver = NativeMediaDeriver()

    func testImageDownscaleCapsLongestEdgeAtEachTierPreservingAspect() async throws {
        let png = try MediaFixtures.png(width: 1600, height: 900)

        let thumbnail = try await deriver.deriveImage(png, maxEdge: DerivativeSpec.thumbnail.maxEdge,
                                                      quality: DerivativeSpec.thumbnail.quality)
        let (tw, th) = try MediaFixtures.jpegDimensions(thumbnail)   // also asserts it decodes as JPEG
        XCTAssertEqual(max(tw, th), 400, "thumbnail longest edge hits the 400 cap")
        XCTAssertEqual(Double(tw) / Double(th), 1600.0 / 900.0, accuracy: 0.02, "aspect ratio preserved")

        let preview = try await deriver.deriveImage(png, maxEdge: DerivativeSpec.preview.maxEdge,
                                                    quality: DerivativeSpec.preview.quality)
        let (pw, ph) = try MediaFixtures.jpegDimensions(preview)
        XCTAssertEqual((pw, ph) == (1600, 900) ? true : false, true, "source within the 2048 cap is never upscaled")
    }

    func testImageDownscaleNeverUpscalesASmallSource() async throws {
        let png = try MediaFixtures.png(width: 120, height: 80)   // already under every cap
        let thumbnail = try await deriver.deriveImage(png, maxEdge: 400, quality: 0.8)
        let (w, h) = try MediaFixtures.jpegDimensions(thumbnail)
        XCTAssertEqual([w, h], [120, 80], "a source within the cap passes through at its own size")
    }

    func testVideoPosterIsADecodableJpegWithinThePreviewCap() async throws {
        // A small clip keeps the H.264 encode cheap — the poster path only needs a decodable first frame.
        let mp4 = try await MediaFixtures.mp4(width: 320, height: 240, frameCount: 2)
        let poster = try await deriver.deriveVideoPoster(mp4)
        let (w, h) = try MediaFixtures.jpegDimensions(poster)       // decodes as JPEG = first frame extracted
        XCTAssertLessThanOrEqual(max(w, h), DerivativeSpec.preview.maxEdge, "poster longest edge ≤ 2048")
        XCTAssertEqual(Double(w) / Double(h), 320.0 / 240.0, accuracy: 0.02, "poster keeps the video aspect ratio")
    }

    func testUndecodableImageBytesFailCleanly() async throws {
        do {
            _ = try await deriver.deriveImage(Data([0x00, 0x01, 0x02, 0x03]), maxEdge: 400, quality: 0.8)
            XCTFail("expected mediaDeriveFailed on garbage bytes")
        } catch VeyqError.mediaDeriveFailed {
            // expected — generation is best-effort; the original is never affected
        }
    }
}
