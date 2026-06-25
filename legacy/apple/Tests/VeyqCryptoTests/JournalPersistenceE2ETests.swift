import XCTest
import Foundation
import YSwift
@testable import VeyqCrypto

/// Live (VEYQ_E2E=1): the operator-blind CRDT journal store on iOS, byte-compatible with web/Android. Proves
/// NON-OVERLAPPING sessions converge without the server decrypting: session A appends an increment; a fresh
/// session B reconstructs A's text from it, appends and compacts (snapshot + Markdown projection, pruning the
/// log); session C reconstructs the compacted state from the snapshot alone; and the Markdown reader matches.
final class JournalPersistenceE2ETests: XCTestCase {
    func testNonOverlappingSessionsConverge() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["VEYQ_E2E"] == "1", "set VEYQ_E2E=1 to run against a live backend")
        let apiBase = env["VEYQ_API_BASE"] ?? "http://localhost:8080"
        let pass = "correct horse battery staple"

        let (_, kit) = try await SetupServer.registerServerOwner(apiBase: apiBase, email: "jp@example.com", displayName: "JP", password: pass)
        let session = try await Login.login(apiBase: apiBase, email: "jp@example.com", password: pass, secretKeyB64: kit.secretKey)
        let scope = try await ScopeContent.openPersonalScope(apiBase: apiBase, session: session)
        let ck = try await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: session, scope: scope)
        let memory = try await MemoryTree.createNode(apiBase: apiBase, session: session, scopeId: scope.scopeId, contentKey: ck, type: "MEMORY", name: "Trip", slug: "trip", orderIndex: 0)

        func textOf(_ doc: YDocument) -> String { let t = doc.getOrCreateText(named: "journal"); return doc.transactSync { t.getString(in: $0) } }
        func load(_ doc: YDocument) async throws -> Int {
            try await JournalPersistence.loadJournalDoc(apiBase: apiBase, session: session, scopeId: scope.scopeId, nodeId: memory.id, contentKey: ck, doc: doc)
        }

        // Session A: append one increment (no compaction yet)
        let dA = YDocument(); _ = try await load(dA)
        let tA = dA.getOrCreateText(named: "journal")
        dA.transactSync { txn in tA.insert("hello", at: 0, in: txn) }
        let seqA = try await JournalPersistence.appendJournalUpdate(apiBase: apiBase, session: session, scopeId: scope.scopeId, nodeId: memory.id, contentKey: ck, update: dA.transactSync { $0.transactionEncodeStateAsUpdate() })
        XCTAssertEqual(seqA, 1)

        // Session B reconstructs A's text from the increment alone
        let dB = YDocument(); let maxB = try await load(dB)
        XCTAssertEqual(textOf(dB), "hello")
        XCTAssertEqual(maxB, 1)

        // Session B appends, then compacts (snapshot + Markdown projection, pruning the log)
        let sv = dB.transactSync { $0.transactionStateVector() }
        let tB = dB.getOrCreateText(named: "journal")
        dB.transactSync { txn in tB.insert(" world", at: tB.length(in: txn), in: txn) }
        let diff = dB.transactSync { (try? $0.transactionEncodeStateAsUpdateFromSv(stateVector: sv)) ?? [] }
        let seqB = try await JournalPersistence.appendJournalUpdate(apiBase: apiBase, session: session, scopeId: scope.scopeId, nodeId: memory.id, contentKey: ck, update: diff)
        try await JournalPersistence.compactJournal(apiBase: apiBase, session: session, scopeId: scope.scopeId, nodeId: memory.id, contentKey: ck, doc: dB, markdown: textOf(dB), coveredSeq: seqB)

        // Session C reconstructs the compacted state from the SNAPSHOT alone (the log was pruned)
        let dC = YDocument(); _ = try await load(dC)
        XCTAssertEqual(textOf(dC), "hello world")

        // the Markdown read projection matches, written at compaction
        let md = try await Journal.getJournal(apiBase: apiBase, session: session, scopeId: scope.scopeId, contentKey: ck, nodeId: memory.id)
        XCTAssertEqual(md.markdown, "hello world")
    }
}
