package app.veyq.crypto

import uniffi.yniffi.YrsDoc
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Live (VEYQ_E2E=1): the operator-blind CRDT journal store on Android (native Yrs via yniffi), byte-compatible
 * with web/iOS. Proves NON-OVERLAPPING sessions converge without the server decrypting: session A appends an
 * increment; a fresh session B reconstructs A's text from it, appends and compacts (snapshot + Markdown
 * projection, pruning the log); session C reconstructs the compacted state from the snapshot alone; and the
 * Markdown read projection matches.
 */
class JournalPersistenceE2ETest {
    private val apiBase = System.getenv("VEYQ_API_BASE") ?: "http://localhost:8080"

    private fun ByteArray.toUByteList(): List<UByte> = map { it.toUByte() }
    private fun List<UByte>.toByteArray(): ByteArray = ByteArray(size) { this[it].toByte() }

    @Test
    fun nonOverlappingSessionsConverge() {
        if (System.getenv("VEYQ_E2E") != "1") return
        val pass = "correct horse battery staple"
        val kit = SetupServer.registerServerOwner(apiBase, "jp@example.com", "JP", pass).second
        val session = Login.login(apiBase, "jp@example.com", pass, kit.secretKey)
        val scope = ScopeContent.openPersonalScope(apiBase, session)
        val ck = ScopeContent.getOrCreateContentKey(apiBase, session, scope)
        val nodeId = MemoryTree.createNode(apiBase, session, scope.scopeId, ck, "MEMORY", "Trip", "trip", 0).getString("id")

        fun load(doc: YrsDoc, lock: Any) = JournalPersistence.loadJournalDoc(apiBase, session, scope.scopeId, nodeId, ck, doc, lock)
        // getText must be called OUTSIDE an active transaction (it borrows the doc), like the provider does.
        fun textOf(doc: YrsDoc, lock: Any): String { val t = doc.getText("journal"); return synchronized(lock) { doc.transact(null).use { t.getString(it) } } }
        fun fullState(doc: YrsDoc, lock: Any): ByteArray = synchronized(lock) { doc.transact(null).use { it.transactionEncodeStateAsUpdate().toByteArray() } }

        // Session A: append one increment (no compaction yet)
        val lockA = Any(); val dA = YrsDoc(); load(dA, lockA)
        val tA = dA.getText("journal")
        synchronized(lockA) { dA.transact(null).use { tA.insert(it, 0u, "hello") } }
        val seqA = JournalPersistence.appendJournalUpdate(apiBase, session, scope.scopeId, nodeId, ck, fullState(dA, lockA))
        assertEquals(1, seqA)

        // Session B reconstructs A's text from the increment alone
        val lockB = Any(); val dB = YrsDoc(); val maxB = load(dB, lockB)
        assertEquals("hello", textOf(dB, lockB))
        assertEquals(1, maxB)

        // Session B appends, then compacts (snapshot + Markdown projection, pruning the log)
        val sv = synchronized(lockB) { dB.transact(null).use { it.transactionStateVector().toByteArray() } }
        val tB = dB.getText("journal")
        synchronized(lockB) { dB.transact(null).use { txn -> tB.insert(txn, tB.getString(txn).toByteArray(Charsets.UTF_8).size.toUInt(), " world") } }
        val diff = synchronized(lockB) { dB.transact(null).use { it.transactionEncodeStateAsUpdateFromSv(sv.toUByteList()).toByteArray() } }
        val seqB = JournalPersistence.appendJournalUpdate(apiBase, session, scope.scopeId, nodeId, ck, diff)
        JournalPersistence.compactJournal(apiBase, session, scope.scopeId, nodeId, ck, dB, lockB, textOf(dB, lockB), seqB)

        // Session C reconstructs the compacted state from the SNAPSHOT alone (the log was pruned)
        val lockC = Any(); val dC = YrsDoc(); load(dC, lockC)
        assertEquals("hello world", textOf(dC, lockC))

        // the Markdown read projection matches, written at compaction
        assertEquals("hello world", Journal.getJournal(apiBase, session, scope.scopeId, ck, nodeId).markdown)
    }
}
