import XCTest
import Foundation
@testable import VeyqCrypto

/// Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `swift test` skips it.
final class ResumableUploadE2ETests: XCTestCase {
    func testInterruptAndResumeAChunkedUpload() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["VEYQ_E2E"] == "1", "set VEYQ_E2E=1 to run against a live backend")
        let apiBase = env["VEYQ_API_BASE"] ?? "http://localhost:8080"

        let pass = "correct horse battery staple"
        let (_, kit) = try await SetupServer.registerServerOwner(apiBase: apiBase, email: "admin@example.com", displayName: "Admin", password: pass)
        let a = try await Login.login(apiBase: apiBase, email: "admin@example.com", password: pass, secretKeyB64: kit.secretKey)
        let scope = try await ScopeContent.openPersonalScope(apiBase: apiBase, session: a)
        let ck = try await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: a, scope: scope)
        let memory = try await MemoryTree.createNode(apiBase: apiBase, session: a, scopeId: scope.scopeId, contentKey: ck, type: "MEMORY", name: "Italien 2026", slug: "italien-2026", orderIndex: 0)

        let video = Data((0..<35).map { UInt8(($0 * 7 + 3) & 0xff) })
        let chunks = ResumableUpload.chunkBytes(video, 10)
        XCTAssertEqual(chunks.count, 4)

        let sess = try await ResumableUpload.createUpload(apiBase: apiBase, session: a, scopeId: scope.scopeId, nodeId: memory.id,
                                                          totalChunks: chunks.count, mimeType: "video/mp4", byteSize: Int64(video.count), takenAt: "2026-01-01T10:00:00Z")

        // upload only the first two chunks, then "the connection drops"
        try await ResumableUpload.putChunk(apiBase: apiBase, session: a, uploadId: sess.uploadId, index: 0, total: chunks.count, contentKey: ck, plain: chunks[0])
        try await ResumableUpload.putChunk(apiBase: apiBase, session: a, uploadId: sess.uploadId, index: 1, total: chunks.count, contentKey: ck, plain: chunks[1])
        let mid = try await ResumableUpload.getProgress(apiBase: apiBase, session: a, uploadId: sess.uploadId)
        XCTAssertEqual(mid.status, "OPEN")
        XCTAssertEqual(mid.receivedChunks.sorted(), [0, 1])

        // resume sends the missing chunks then finalizes
        let done = try await ResumableUpload.resumeUpload(apiBase: apiBase, session: a, uploadId: sess.uploadId, contentKey: ck, chunks: chunks)
        XCTAssertEqual(done.status, "COMPLETED")

        // download + decrypt frame by frame, byte-identical
        let recovered = try await ResumableUpload.downloadResumable(apiBase: apiBase, session: a, uploadId: sess.uploadId, contentKey: ck, totalChunks: chunks.count)
        XCTAssertEqual(recovered, video)

        // anti-reorder: a frame sealed at index 0 must not open at index 1
        let frame0 = try V1.sealFrame(contentKey: ck, index: 0, total: chunks.count, plaintext: chunks[0], nonce: V1.randomBytes(12))
        var reorderRejected = false
        do { _ = try V1.openFrame(contentKey: ck, index: 1, total: chunks.count, frame: frame0) } catch { reorderRejected = true }
        XCTAssertTrue(reorderRejected, "a frame must not open at the wrong index")
    }
}
