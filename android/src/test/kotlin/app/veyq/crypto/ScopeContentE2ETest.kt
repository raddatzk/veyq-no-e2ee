package app.veyq.crypto

import kotlin.test.Test
import kotlin.test.assertTrue

/** Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `gradle test` no-ops it. */
class ScopeContentE2ETest {
    private val apiBase = System.getenv("VEYQ_API_BASE") ?: "http://localhost:8080"

    @Test
    fun writeAndReadEncryptedContentUnderPersonalScope() {
        if (System.getenv("VEYQ_E2E") != "1") return // opt-in: requires a live backend

        val pass = "correct horse battery staple"
        val (_, kit) = SetupServer.registerServerOwner(apiBase, "owner@example.com", "Owner", pass)
        val session = Login.login(apiBase, "owner@example.com", pass, kit.secretKey)

        val scope = ScopeContent.openPersonalScope(apiBase, session)
        val ck = ScopeContent.getOrCreateContentKey(apiBase, session, scope)

        val secret = "Italien 2024 — Tag 1"
        ScopeContent.writeItem(apiBase, session, scope, ck, secret)
        ScopeContent.writeItem(apiBase, session, scope, ck, "second note")

        val items = ScopeContent.readItems(apiBase, session, scope, ck)
        assertTrue(items.contains(secret))
        assertTrue(items.contains("second note"))

        // a fresh login recovers the same content (stable, scope-wrapped content key)
        val session2 = Login.login(apiBase, "owner@example.com", pass, kit.secretKey)
        val scope2 = ScopeContent.openPersonalScope(apiBase, session2)
        val ck2 = ScopeContent.getOrCreateContentKey(apiBase, session2, scope2)
        assertTrue(ScopeContent.readItems(apiBase, session2, scope2, ck2).contains(secret))
    }
}
