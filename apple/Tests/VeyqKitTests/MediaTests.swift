import XCTest
import Foundation
import HTTPTypes
import OpenAPIRuntime
import VeyqCrypto
@testable import VeyqKit

// cr-0028 + cr-0047 — en.memory.media + en.memory.derivatives (apple) data-layer tests. A stateful `MediaStub`
// serves the batch control plane (start / complete / list / presign-download) with `mem://…` presigned URLs,
// now **derivative-aware** (several blobs per media, role-tagged read-back), and an in-memory
// `FakeBlobTransport` stores PUT bodies + replays them on GET — so addMedia → openMedia round-trips the real
// native v1 chunk seal/open + the per-blob K_blob wrap (under K_mem) without a network or MinIO. The lossy
// pixel stage is behind an injectable `StubDeriver` (parity by property, not bytes), so the wiring (dimension
// math, role/batch assembly, per-blob wraps, tier selection) is unit-tested here; the real ImageIO/AVFoundation
// decode is a manual live round-trip. Operator-blind: the "store" only ever holds ciphertext frames; the
// backend stub only ciphertext wraps + opaque metadata + the role tags.
final class MediaTests: XCTestCase {

    private static let baseURL = URL(string: "https://fam.example.com")!
    private static let scopeId = "11111111-1111-1111-1111-111111111111"
    private static let nodeId = "22222222-2222-2222-2222-222222222222"

    private func client(_ stub: MediaStub, _ blobs: FakeBlobTransport) async -> VeyqClient {
        let session = Session()
        await session.set(access: "a", refresh: "r", familyId: "f", account: "p@veyq.example")
        return VeyqClient(instance: VeyqInstance(baseURL: Self.baseURL),
                          session: session, deviceSecurity: InMemoryDeviceSecurityStore(),
                          transport: stub, blobTransport: blobs)
    }

    // MARK: longest-edge target math (pure)

    func testScaleToMaxEdgeCapsLongestEdgePreservingAspect() {
        XCTAssertEqual(scaleToMaxEdge(width: 4000, height: 2000, maxEdge: 400).width, 400) // landscape
        XCTAssertEqual(scaleToMaxEdge(width: 4000, height: 2000, maxEdge: 400).height, 200)
        XCTAssertEqual(scaleToMaxEdge(width: 2000, height: 4000, maxEdge: 400).width, 200) // portrait
        XCTAssertEqual(scaleToMaxEdge(width: 2000, height: 4000, maxEdge: 400).height, 400)
        XCTAssertEqual(scaleToMaxEdge(width: 1000, height: 1000, maxEdge: 400).width, 400) // square
    }

    func testScaleToMaxEdgeNeverUpscalesAndKeepsEachSideAtLeastOnePx() {
        XCTAssertEqual(scaleToMaxEdge(width: 300, height: 150, maxEdge: 400).width, 300)   // already within cap
        XCTAssertEqual(scaleToMaxEdge(width: 400, height: 400, maxEdge: 400).height, 400)  // exactly at cap
        XCTAssertEqual(scaleToMaxEdge(width: 2048, height: 3, maxEdge: 400).height, 1)     // rounds to ≥ 1px
        XCTAssertEqual(scaleToMaxEdge(width: 0, height: 0, maxEdge: 400).width, 0)         // degenerate
    }

    func testTierSpecsArePinnedFromLegacy() {
        XCTAssertEqual(DerivativeSpec.thumbnail, DerivativeSpec(maxEdge: 512, quality: 0.80))
        XCTAssertEqual(DerivativeSpec.preview, DerivativeSpec(maxEdge: 2048, quality: 0.85))
    }

    // MARK: add-media — derivative generation + role/batch assembly

    func testImageUploadsOriginalThumbnailPreviewInOneBatchRoleTagged() async throws {
        let stub = MediaStub()
        let blobs = FakeBlobTransport()
        let c = await client(stub, blobs)
        let memoryKey = Data(repeating: 7, count: 32)
        let data = Data(repeating: 5, count: 64)

        let (blobId, mediaId) = try await c.addMedia(scopeId: Self.scopeId, nodeId: Self.nodeId,
                                                     memoryKey: memoryKey, data: data, mediaType: "image/png", deriver: stub.derivers)
        XCTAssertFalse(blobId.isEmpty)
        XCTAssertFalse(mediaId.isEmpty)

        // three blobs declared in one start batch (original + 2 derivatives)
        XCTAssertEqual(stub.startItemCount, 3)
        // the deriver produced two downscales at the pinned tier specs; no video poster
        XCTAssertEqual(stub.derivers.imageCalls.map { $0.maxEdge }, [512, 2048])
        XCTAssertEqual(stub.derivers.imageCalls.map { $0.quality }, [0.80, 0.85])
        XCTAssertEqual(stub.derivers.posterCalls, 0)
        // complete carried the role tags in order
        XCTAssertEqual(stub.lastCompleteDerivativeKinds, ["THUMBNAIL", "PREVIEW"])

        // read-back exposes the original + both tiers, each its own blob + wrap
        let items = try await c.listMedia(scopeId: Self.scopeId, nodeId: Self.nodeId)
        XCTAssertEqual(items.count, 1)
        let item = items[0]
        XCTAssertEqual(item.derivatives.map { $0.kind }, [.THUMBNAIL, .PREVIEW])
        XCTAssertNotEqual(item.gridRef.blobId, item.original.blobId)   // grid never the original
        XCTAssertNotEqual(item.gridRef.blobId, item.lightboxRef.blobId)
    }

    func testVideoDerivesOnePosterReusedForBothTiers() async throws {
        let stub = MediaStub()
        let blobs = FakeBlobTransport()
        let c = await client(stub, blobs)
        let memoryKey = Data(repeating: 9, count: 32)

        try await c.addMedia(scopeId: Self.scopeId, nodeId: Self.nodeId, memoryKey: memoryKey,
                             data: Data(repeating: 3, count: 40), mediaType: "video/mp4", deriver: stub.derivers)

        XCTAssertEqual(stub.derivers.posterCalls, 1)        // decoded once
        XCTAssertTrue(stub.derivers.imageCalls.isEmpty)
        XCTAssertEqual(stub.startItemCount, 3)              // original + thumbnail + preview (same poster bytes)
        XCTAssertEqual(stub.lastCompleteDerivativeKinds, ["THUMBNAIL", "PREVIEW"])

        // both the grid and lightbox tier decode back to the one poster
        let items = try await c.listMedia(scopeId: Self.scopeId, nodeId: Self.nodeId)
        let item = try XCTUnwrap(items.first)
        let grid = try await c.openMedia(scopeId: Self.scopeId, memoryKey: memoryKey, media: item, tier: .grid)
        let lightbox = try await c.openMedia(scopeId: Self.scopeId, memoryKey: memoryKey, media: item, tier: .lightbox)
        XCTAssertEqual(grid, StubDeriver.posterBytes)
        XCTAssertEqual(lightbox, StubDeriver.posterBytes)
    }

    func testNonImageNonVideoAttachesOriginalOnly() async throws {
        let stub = MediaStub()
        let c = await client(stub, FakeBlobTransport())

        try await c.addMedia(scopeId: Self.scopeId, nodeId: Self.nodeId, memoryKey: Data(repeating: 1, count: 32),
                             data: Data([1, 2, 3]), mediaType: "application/pdf", deriver: stub.derivers)

        XCTAssertEqual(stub.derivers.imageCalls.count + stub.derivers.posterCalls, 0)
        XCTAssertEqual(stub.startItemCount, 1)               // original only
        XCTAssertEqual(stub.lastCompleteDerivativeKinds, []) // no role tags
        let items = try await c.listMedia(scopeId: Self.scopeId, nodeId: Self.nodeId)
        let item = try XCTUnwrap(items.first)
        XCTAssertTrue(item.derivatives.isEmpty)
        // tier accessors fall back to the original when there are no derivatives
        XCTAssertEqual(item.gridRef.blobId, item.original.blobId)
        XCTAssertEqual(item.lightboxRef.blobId, item.original.blobId)
    }

    func testStoreUnavailableWhenNoPresign() async throws {
        let stub = MediaStub()
        stub.presign = false   // dev store: opens the session but returns no presigned PUTs
        let c = await client(stub, FakeBlobTransport())
        do {
            _ = try await c.addMedia(scopeId: Self.scopeId, nodeId: Self.nodeId, memoryKey: Data(repeating: 1, count: 32),
                                     data: Data(repeating: 9, count: 10), mediaType: "application/octet-stream", deriver: stub.derivers)
            XCTFail("expected storeUnavailable")
        } catch VeyqError.storeUnavailable {
            // expected
        }
    }

    // MARK: read-back — operator-blind round-trip + per-blob wraps + tier selection

    func testOriginalRoundTripsThroughChunkedAEADUnderItsOwnKBlob() async throws {
        let stub = MediaStub()
        let blobs = FakeBlobTransport()
        let c = await client(stub, blobs)
        let memoryKey = Data(repeating: 7, count: 32)

        // 600 KiB ⇒ 3 chunks at chunkSize = 256 KiB (original-only: octet-stream)
        var data = Data(count: 600 * 1024)
        for i in 0 ..< data.count { data[i] = UInt8((i * 31 + 7) & 0xff) }

        try await c.addMedia(scopeId: Self.scopeId, nodeId: Self.nodeId, memoryKey: memoryKey,
                             data: data, mediaType: "application/octet-stream", deriver: stub.derivers)

        // control plane: declared 3 chunks; the store holds 3 ciphertext frames (each = chunk + nonce + tag)
        XCTAssertEqual(stub.lastChunkCount, 3)
        XCTAssertEqual(blobs.objects.count, 3)
        let firstFrame = try XCTUnwrap(blobs.objects.values.first { $0.count == VeyqClient.chunkSize + 12 + 16 })
        XCTAssertNotEqual(firstFrame.prefix(VeyqClient.chunkSize), data.prefix(VeyqClient.chunkSize)) // ciphertext, not plaintext

        // the wrap the backend saw opens back to a real 32-byte K_blob under K_mem with the 'blob-key' context
        let wrap = try XCTUnwrap(stub.lastWrap)
        let blobKey = try V1.symUnwrap(kek: memoryKey, blob: wrap, context: "blob-key")
        XCTAssertEqual(blobKey.count, 32)

        // read-back round-trips to the original plaintext
        let items = try await c.listMedia(scopeId: Self.scopeId, nodeId: Self.nodeId)
        let item = try XCTUnwrap(items.first)
        let opened = try await c.openMedia(scopeId: Self.scopeId, memoryKey: memoryKey, media: item)
        XCTAssertEqual(opened, data)
    }

    func testEachTierIsSealedUnderItsOwnKBlobAndPicksTheRightBytes() async throws {
        let stub = MediaStub()
        let blobs = FakeBlobTransport()
        let c = await client(stub, blobs)
        let memoryKey = Data(repeating: 4, count: 32)
        let original = Data(repeating: 8, count: 70)

        try await c.addMedia(scopeId: Self.scopeId, nodeId: Self.nodeId, memoryKey: memoryKey,
                             data: original, mediaType: "image/jpeg", deriver: stub.derivers)

        let items = try await c.listMedia(scopeId: Self.scopeId, nodeId: Self.nodeId)
        let item = try XCTUnwrap(items.first)

        // every tier keeps its OWN K_blob — the three wraps are distinct (per-blob keys, enabler decision 1)
        let wraps = [item.original.wrappedBlobKey, item.gridRef.wrappedBlobKey, item.lightboxRef.wrappedBlobKey]
        XCTAssertEqual(Set(wraps).count, 3)

        // grid = thumbnail bytes, lightbox = preview bytes, original = the pristine bytes
        let grid = try await c.openMedia(scopeId: Self.scopeId, memoryKey: memoryKey, media: item, tier: .grid)
        let lightbox = try await c.openMedia(scopeId: Self.scopeId, memoryKey: memoryKey, media: item, tier: .lightbox)
        let download = try await c.openMedia(scopeId: Self.scopeId, memoryKey: memoryKey, media: item, tier: .original)
        XCTAssertEqual(grid, StubDeriver.imageBytes(maxEdge: 512))
        XCTAssertEqual(lightbox, StubDeriver.imageBytes(maxEdge: 2048))
        XCTAssertEqual(download, original)
    }
}

/// A stub deriver: returns tiny distinct "JPEG" bytes tagged by the requested longest edge (and a fixed poster
/// marker), and counts calls — so the wiring (which tiers, batch shape, role tags, tier selection) is tested
/// without a real pixel decode. Parity here is by property, not bytes (en.memory.derivatives → Quality notes).
private final class StubDeriver: MediaDeriver, @unchecked Sendable {
    private let lock = NSLock()
    private(set) var imageCalls: [(maxEdge: Int, quality: Double)] = []
    private(set) var posterCalls = 0

    static func imageBytes(maxEdge: Int) -> Data { Data("img:\(maxEdge)".utf8) }
    static let posterBytes = Data("poster".utf8)

    func deriveImage(_ data: Data, maxEdge: Int, quality: Double) async throws -> Data {
        lock.withLock { imageCalls.append((maxEdge, quality)) }
        return Self.imageBytes(maxEdge: maxEdge)
    }
    func deriveVideoPoster(_ data: Data) async throws -> Data {
        lock.withLock { posterCalls += 1 }
        return Self.posterBytes
    }
}

/// In-memory object store for the byte transport: PUT stores the body under the URL, GET replays it.
private final class FakeBlobTransport: BlobTransport, @unchecked Sendable {
    private let lock = NSLock()
    private(set) var objects: [String: Data] = [:]

    func put(_ url: URL, _ body: Data) async throws { lock.withLock { objects[url.absoluteString] = body } }
    func get(_ url: URL) async throws -> Data {
        guard let d = lock.withLock({ objects[url.absoluteString] }) else { throw VeyqError.blobTransferFailed }
        return d
    }
}

/// A stateful, **derivative-aware** `ClientTransport` for the media batch control plane. `mem://<storageKey>/<i>`
/// presigned URLs key the FakeBlobTransport; the stub stores only ciphertext wraps + opaque metadata + the role
/// tags (operator-blind), and uses a real `StubDeriver` injected into `addMedia` so the tests can assert which
/// tiers were generated. One media = an original blob plus a derivative blob per role tag.
private final class MediaStub: ClientTransport, @unchecked Sendable {
    let derivers = StubDeriver()
    private let lock = NSLock()
    private var seq = 0
    private struct Blob { let storageKey: String; let chunkCount: Int; let byteSize: Int; let wrap: String }
    private struct Media { let mediaId: String; let blobId: String; let derivatives: [(kind: String, blobId: String)] }
    private var sessions: [String: Blob] = [:]   // uploadId → staged blob
    private var blobs: [String: Blob] = [:]       // blobId → blob
    private var mediaByNode: [String: [Media]] = [:]
    var presign = true
    private(set) var startItemCount = 0
    private(set) var lastChunkCount = 0
    private(set) var lastWrap: Data?
    private(set) var lastCompleteDerivativeKinds: [String] = []

    func send(_ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String)
        async throws -> (HTTPResponse, HTTPBody?)
    {
        let path = request.path ?? ""
        let bodyData = try await Data(collecting: body ?? HTTPBody(), upTo: 2_000_000)
        let obj = (try? JSONSerialization.jsonObject(with: bodyData)) as? [String: Any] ?? [:]
        let items = obj["items"] as? [[String: Any]] ?? []

        switch operationID {
        case "startBlobUploads":
            let results: [[String: Any]] = lock.withLock {
                startItemCount = items.count
                return items.map { item in
                    seq += 1
                    let storageKey = "sk\(seq)"
                    let uploadId = "up\(seq)"
                    let chunkCount = item["chunkCount"] as? Int ?? 0
                    let wrap = item["wrappedBlobKey"] as? String ?? ""
                    lastChunkCount = chunkCount
                    lastWrap = Data(base64Encoded: wrap)
                    sessions[uploadId] = Blob(storageKey: storageKey, chunkCount: chunkCount,
                                              byteSize: item["byteSize"] as? Int ?? 0, wrap: wrap)
                    var r: [String: Any] = ["uploadId": uploadId]
                    if presign {
                        r["presignedPuts"] = (0 ..< chunkCount).map { ["index": $0, "url": "mem://\(storageKey)/\($0)"] }
                    }
                    return r
                }
            }
            return Self.json(200, ["results": results])

        case "completeMedia":
            let results: [[String: Any]] = lock.withLock {
                items.map { item in
                    let originalUploadId = item["originalUploadId"] as? String ?? ""
                    let nodeId = item["nodeId"] as? String ?? ""
                    let derivativeItems = item["derivatives"] as? [[String: Any]] ?? []
                    lastCompleteDerivativeKinds = derivativeItems.compactMap { $0["kind"] as? String }
                    guard let original = sessions[originalUploadId] else { return ["error": "upload_not_found"] }
                    seq += 1
                    let blobId = "blob\(seq)"
                    blobs[blobId] = original
                    var derivativeBlobs: [(kind: String, blobId: String)] = []
                    for d in derivativeItems {
                        guard let kind = d["kind"] as? String, let upload = d["uploadId"] as? String,
                              let s = sessions[upload] else { return ["error": "upload_not_found"] }
                        seq += 1
                        let dBlobId = "blob\(seq)"
                        blobs[dBlobId] = s
                        derivativeBlobs.append((kind, dBlobId))
                    }
                    seq += 1
                    let mediaId = "media\(seq)"
                    mediaByNode[nodeId, default: []].append(Media(mediaId: mediaId, blobId: blobId, derivatives: derivativeBlobs))
                    return ["blobId": blobId, "mediaId": mediaId]
                }
            }
            return Self.json(200, ["results": results])

        case "listNodeMedia":
            let parts = path.split(separator: "/")
            let nodeId = parts.count >= 2 ? String(parts[parts.count - 2]) : ""
            let summaries: [[String: Any]] = lock.withLock {
                (mediaByNode[nodeId] ?? []).compactMap { m in
                    guard let b = blobs[m.blobId] else { return nil }
                    var summary: [String: Any] = ["mediaId": m.mediaId, "blobId": m.blobId,
                                                  "chunkCount": b.chunkCount, "byteSize": b.byteSize, "wrappedBlobKey": b.wrap]
                    let derivatives: [[String: Any]] = m.derivatives.compactMap { d in
                        guard let db = blobs[d.blobId] else { return nil }
                        return ["kind": d.kind, "blobId": d.blobId, "chunkCount": db.chunkCount,
                                "byteSize": db.byteSize, "wrappedBlobKey": db.wrap]
                    }
                    if !derivatives.isEmpty { summary["derivatives"] = derivatives }
                    return summary
                }
            }
            return Self.json(200, summaries)

        case "presignBlobDownloads":
            let results: [[String: Any]] = lock.withLock {
                items.map { item in
                    let blobId = item["blobId"] as? String ?? ""
                    guard let b = blobs[blobId] else { return ["error": "upload_not_found"] }
                    return ["presignedGets": (0 ..< b.chunkCount).map { ["index": $0, "url": "mem://\(b.storageKey)/\($0)"] }]
                }
            }
            return Self.json(200, ["results": results])

        default:
            return (HTTPResponse(status: .notFound), nil)
        }
    }

    private static func json(_ status: Int, _ object: Any) -> (HTTPResponse, HTTPBody?) {
        let data = (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
        var response = HTTPResponse(status: .init(code: status))
        response.headerFields[.contentType] = "application/json"
        return (response, HTTPBody(data))
    }
}
