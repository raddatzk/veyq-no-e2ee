package app.veyq.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live (VEYQ_E2E=1): two Android JournalLiveProviders (native Yrs via the uniffi binding) join the same
 * journal room through the operator-blind relay and converge — proving the Kotlin binding + the y-websocket
 * wire framing + AEAD + the relay all work. The framing is byte-identical to web/iOS, so this backs interop.
 */
class JournalLiveE2ETest {
    private val apiBase = System.getenv("VEYQ_API_BASE") ?: "http://localhost:8080"

    private fun waitFor(timeoutMs: Long = 6000, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond()) {
            if (System.currentTimeMillis() - t0 > timeoutMs) throw AssertionError("timeout")
            Thread.sleep(50)
        }
    }

    @Test
    fun twoProvidersConverge() {
        if (System.getenv("VEYQ_E2E") != "1") return
        val pass = "correct horse battery staple"
        val kit = SetupServer.registerServerOwner(apiBase, "jlive@example.com", "JLive", pass).second
        val session = Login.login(apiBase, "jlive@example.com", pass, kit.secretKey)
        val scope = ScopeContent.openPersonalScope(apiBase, session)
        val ck = ScopeContent.getOrCreateContentKey(apiBase, session, scope)

        val wsBase = apiBase.replace("http", "ws")
        val url = "$wsBase/ws/journal/${scope.scopeId}/n1?token=${session.sessionToken}"
        val a = JournalLiveProvider(url, ck)
        val b = JournalLiveProvider(url, ck)
        waitFor { a.currentText == "" && b.currentText == "" } // both connected
        Thread.sleep(300)

        a.applyLocalText("hallo welt")
        waitFor { b.currentText == "hallo welt" }
        assertEquals("hallo welt", b.currentText)

        // concurrent edits merge (CRDT): both converge
        b.applyLocalText("hallo welt !")
        a.applyLocalText("> hallo welt")
        waitFor { a.currentText == b.currentText && a.currentText.contains("hallo welt") }
        assertEquals(a.currentText, b.currentText)
        assertTrue(a.currentText.isNotEmpty())

        a.destroy(); b.destroy()
    }
}
