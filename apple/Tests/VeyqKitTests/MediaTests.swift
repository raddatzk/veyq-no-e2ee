import XCTest
import Foundation
import HTTPTypes
import OpenAPIRuntime
import VeyqCrypto
@testable import VeyqKit

// cr-0028 phase 5 — en.memory.media (apple) data-layer tests. A stateful `MediaStub` serves the batch
// control plane (start / complete / list / presign-download) with `mem://…` presigned URLs, and an in-memory
// `FakeBlobTransport` stores PUT bodies + replays them on GET — so addMedia → openMedia round-trips the real
// native v1 chunk seal/open + the K_blob wrap (under K_mem) without a network or MinIO. Operator-blind: the
// "store" only ever holds ciphertext frames; the backend stub only ciphertext wraps + opaque metadata.
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

    func testAddThenOpenRoundTripsBytes() async throws {
        let stub = MediaStub()
        let blobs = FakeBlobTransport()
        let c = await client(stub, blobs)
        let memoryKey = Data(repeating: 7, count: 32)

        // 600 KiB ⇒ 3 chunks at chunkSize = 256 KiB
        var data = Data(count: 600 * 1024)
        for i in 0 ..< data.count { data[i] = UInt8((i * 31 + 7) & 0xff) }

        let (blobId, mediaId) = try await c.addMedia(scopeId: Self.scopeId, nodeId: Self.nodeId, memoryKey: memoryKey, data: data)
        XCTAssertFalse(blobId.isEmpty)
        XCTAssertFalse(mediaId.isEmpty)

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
        XCTAssertEqual(items.count, 1)
        let opened = try await c.openMedia(scopeId: Self.scopeId, memoryKey: memoryKey, media: items[0])
        XCTAssertEqual(opened, data)
    }

    func testStoreUnavailableWhenNoPresign() async throws {
        let stub = MediaStub()
        stub.presign = false   // dev store: opens the session but returns no presigned PUTs
        let c = await client(stub, FakeBlobTransport())
        do {
            _ = try await c.addMedia(scopeId: Self.scopeId, nodeId: Self.nodeId,
                                     memoryKey: Data(repeating: 1, count: 32), data: Data(repeating: 9, count: 10))
            XCTFail("expected storeUnavailable")
        } catch VeyqError.storeUnavailable {
            // expected
        }
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

/// A stateful `ClientTransport` for the media batch control plane. `mem://<storageKey>/<i>` presigned URLs key
/// the FakeBlobTransport; stores only ciphertext wraps + opaque metadata (operator-blind).
private final class MediaStub: ClientTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var seq = 0
    private var sessions: [String: (storageKey: String, chunkCount: Int, byteSize: Int, wrap: String)] = [:]
    private var blobs: [String: (storageKey: String, chunkCount: Int, byteSize: Int, wrap: String)] = [:]
    private var mediaByNode: [String: [(mediaId: String, blobId: String)]] = [:]
    var presign = true
    private(set) var lastChunkCount = 0
    private(set) var lastWrap: Data?

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
                items.map { item in
                    seq += 1
                    let storageKey = "sk\(seq)"
                    let uploadId = "up\(seq)"
                    let chunkCount = item["chunkCount"] as? Int ?? 0
                    let wrap = item["wrappedBlobKey"] as? String ?? ""
                    lastChunkCount = chunkCount
                    lastWrap = Data(base64Encoded: wrap)
                    sessions[uploadId] = (storageKey, chunkCount, item["byteSize"] as? Int ?? 0, wrap)
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
                    seq += 1
                    let uploadId = item["uploadId"] as? String ?? ""
                    let nodeId = item["nodeId"] as? String ?? ""
                    guard let s = sessions[uploadId] else { return ["error": "upload_not_found"] }
                    let blobId = "blob\(seq)"
                    let mediaId = "media\(seq)"
                    blobs[blobId] = s
                    mediaByNode[nodeId, default: []].append((mediaId, blobId))
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
                    return ["mediaId": m.mediaId, "blobId": m.blobId, "chunkCount": b.chunkCount,
                            "byteSize": b.byteSize, "wrappedBlobKey": b.wrap]
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
