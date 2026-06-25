import XCTest
import Foundation
@testable import VeyqCrypto

/// Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `swift test` skips it.
/// One upload/download path for small + large + thumbnail, plus dedup, resume, and gallery ordering.
final class MediaE2ETests: XCTestCase {
    func testUnifiedUploadDownloadDedupResumeAndGallery() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["VEYQ_E2E"] == "1", "set VEYQ_E2E=1 to run against a live backend")
        let apiBase = env["VEYQ_API_BASE"] ?? "http://localhost:8080"

        let pass = "correct horse battery staple"
        let (_, kit) = try await SetupServer.registerServerOwner(apiBase: apiBase, email: "admin@example.com", displayName: "Admin", password: pass)
        let a = try await Login.login(apiBase: apiBase, email: "admin@example.com", password: pass, secretKeyB64: kit.secretKey)
        let scope = try await ScopeContent.openPersonalScope(apiBase: apiBase, session: a)
        let ck = try await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: a, scope: scope)
        let memory = try await MemoryTree.createNode(apiBase: apiBase, session: a, scopeId: scope.scopeId, contentKey: ck, type: "MEMORY", name: "Italien 2026", slug: "italien-2026", orderIndex: 0)

        // a small photo (one frame) + its thumbnail, both through the SAME upload path
        let photo = Data((0..<200).map { UInt8(($0 * 13 + 1) & 0xff) })
        let thumb = Data((0..<40).map { UInt8(($0 * 5 + 2) & 0xff) })
        let m1 = try await Media.uploadMedia(apiBase: apiBase, session: a, scopeId: scope.scopeId, contentKey: ck, nodeId: memory.id, bytes: photo, thumb: thumb, takenAt: "2026-03-15T12:00:00Z", mimeType: "image/jpeg")
        XCTAssertEqual(m1.original.totalChunks, 1)
        XCTAssertEqual(m1.derivatives.map { $0.kind }, ["THUMBNAIL"])

        // a large "video" split into many frames — same call, chunked transparently
        let chunkSize = 10
        let video = Data((0..<35).map { UInt8(($0 * 7 + 3) & 0xff) })
        let m2 = try await Media.uploadMedia(apiBase: apiBase, session: a, scopeId: scope.scopeId, contentKey: ck, nodeId: memory.id, bytes: video, takenAt: "2026-01-01T08:00:00Z", mimeType: "video/mp4", chunkSize: chunkSize)
        XCTAssertEqual(m2.original.totalChunks, 4)
        XCTAssertEqual(m2.derivatives.count, 0)

        // both download byte-identical through the SAME frame path
        let p = try await Media.downloadBlob(apiBase: apiBase, session: a, scopeId: scope.scopeId, contentKey: ck, ref: m1.original)
        XCTAssertEqual(p, photo)
        let v = try await Media.downloadBlob(apiBase: apiBase, session: a, scopeId: scope.scopeId, contentKey: ck, ref: m2.original)
        XCTAssertEqual(v, video)

        // dedup: re-uploading identical bytes returns the existing blob (READY), no frames sent
        let ticket = try await Media.startBlob(apiBase: apiBase, session: a, scopeId: scope.scopeId, dedupToken: Media.dedupTokenFor(contentKey: ck, content: photo), totalChunks: 1, byteSize: Int64(photo.count))
        XCTAssertEqual(ticket.status, "READY")
        XCTAssertEqual(ticket.blobId, m1.original.blobId)

        // resume: open a fresh upload, send only chunk 0, then uploadBlob fills the gap and finalizes
        let partial = Data((0..<25).map { UInt8(($0 * 3 + 9) & 0xff) })
        let pchunks = Media.chunkBytes(partial, chunkSize)
        XCTAssertEqual(pchunks.count, 3)
        let t = try await Media.startBlob(apiBase: apiBase, session: a, scopeId: scope.scopeId, dedupToken: Media.dedupTokenFor(contentKey: ck, content: partial), totalChunks: pchunks.count, byteSize: Int64(partial.count))
        try await Media.putChunk(apiBase: apiBase, session: a, scopeId: scope.scopeId, uploadId: t.uploadId!, index: 0, total: pchunks.count, contentKey: ck, plain: pchunks[0])
        let mid = try await Media.getProgress(apiBase: apiBase, session: a, scopeId: scope.scopeId, uploadId: t.uploadId!)
        XCTAssertEqual(mid.receivedChunks.sorted(), [0])
        let resumed = try await Media.uploadBlob(apiBase: apiBase, session: a, scopeId: scope.scopeId, contentKey: ck, bytes: partial, chunkSize: chunkSize)
        let r = try await Media.downloadBlob(apiBase: apiBase, session: a, scopeId: scope.scopeId, contentKey: ck, ref: resumed)
        XCTAssertEqual(r, partial)

        // the gallery lists both media in upload order (photo then video; the client sorts by decrypted takenAt)
        let gallery = try await Media.listMedia(apiBase: apiBase, session: a, scopeId: scope.scopeId, nodeId: memory.id)
        XCTAssertEqual(gallery.map { $0.id }, [m1.id, m2.id])
        XCTAssertEqual(gallery[0].derivatives[0].kind, "THUMBNAIL")
        // sealed metadata round-trips: the decrypted capture time matches what we uploaded
        XCTAssertEqual(try Media.decodeMediaMeta(contentKey: ck, item: gallery[0]).takenAt, "2026-03-15T12:00:00Z")

        // anti-reorder still holds: a frame sealed at index 0 must not open at index 1
        let frame0 = try V1.sealFrame(contentKey: ck, index: 0, total: pchunks.count, plaintext: pchunks[0], nonce: V1.randomBytes(12))
        var reorderRejected = false
        do { _ = try V1.openFrame(contentKey: ck, index: 1, total: pchunks.count, frame: frame0) } catch { reorderRejected = true }
        XCTAssertTrue(reorderRejected, "a frame must not open at the wrong index")
    }
}
