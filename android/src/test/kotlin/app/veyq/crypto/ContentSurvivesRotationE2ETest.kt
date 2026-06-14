package app.veyq.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `gradle test` no-ops it. */
class ContentSurvivesRotationE2ETest {
    private val apiBase = System.getenv("VEYQ_API_BASE") ?: "http://localhost:8080"

    @Test
    fun contentKeyReWrappedOnRotation() {
        if (System.getenv("VEYQ_E2E") != "1") return // opt-in: requires a live backend

        // owner A claims T1 and writes encrypted content under its (tenant) scope
        val aPass = "correct horse battery staple"
        val (_, aKit) = SetupServer.registerServerOwner(apiBase, "admin@example.com", "Admin", aPass)
        val a = Login.login(apiBase, "admin@example.com", aPass, aKit.secretKey)
        val t1 = Tenant.createTenant(apiBase, a.sessionToken, "acme", "Acme", null)
        val inv = Tenant.issueOwnerInvite(apiBase, a.sessionToken, t1.getString("id"))
        Tenant.claimTenantOwnership(apiBase, a.sessionToken, inv.getString("inviteId"), inv.getString("bootstrapSecret"), a.kemPublicKey)

        val scopeBefore = ScopeContent.openTenantScope(apiBase, a, t1.getString("id"))
        val ckBefore = ScopeContent.getOrCreateContentKey(apiBase, a, scopeBefore)
        ScopeContent.writeItem(apiBase, a, scopeBefore, ckBefore, "trip notes")
        // also a Memory-tree node (name + slug sealed under the content key), to prove the tree survives rotation
        val nodeBefore = MemoryTree.createNode(apiBase, a, scopeBefore.scopeId, ckBefore, "FOLDER", "Urlaube", "urlaube", 0)

        // member B joins T1 via a temp-wrap invite
        val bPass = "a whole new passphrase entirely"
        val memberInvite = InviteMembers.issueMemberInvite(apiBase, a, t1.getString("id"), "Member")
        val bReg = InviteMembers.registerViaMemberInvite(
            apiBase, memberInvite.inviteId, memberInvite.bootstrapSecret, memberInvite.tempPrivateKey, "b@example.com", "B", bPass)
        val b = Login.login(apiBase, "b@example.com", bPass, bReg.emergencyKit.secretKey)

        // A rotates the scope — a FRESH content key is minted and every item re-sealed under it
        InviteMembers.rotateScope(apiBase, a, t1.getString("id"))

        // A still reads pre-rotation content under the freshly-granted new keypair + fresh content key
        val aScope = ScopeContent.openTenantScope(apiBase, a, t1.getString("id"))
        val aCk = ScopeContent.getOrCreateContentKey(apiBase, a, aScope)
        assertTrue(ScopeContent.readItems(apiBase, a, aScope, aCk).contains("trip notes"))

        // forward secrecy: the OLD content key (which a removed member could have cached) opens nothing now —
        // the item ciphertext was re-sealed under the fresh key, so the cached key no longer decrypts it
        assertFalse(aCk.contentEquals(ckBefore))
        var oldKeyFailed = false
        try { ScopeContent.readItems(apiBase, a, aScope, ckBefore) } catch (e: Exception) { oldKeyFailed = true }
        assertTrue(oldKeyFailed, "old content key must not decrypt re-sealed content")

        // the Memory-tree node was re-sealed too: its name reads under the fresh key, its slug token was
        // recomputed under the fresh key, and the cached old key can no longer decrypt the node name
        val nodesAfter = MemoryTree.listNodes(apiBase, a, aScope.scopeId)
        var node: org.json.JSONObject? = null
        for (i in 0 until nodesAfter.length()) if (nodesAfter.getJSONObject(i).getString("id") == nodeBefore.getString("id")) node = nodesAfter.getJSONObject(i)
        assertEquals("Urlaube", MemoryTree.decodeNodeName(aCk, node!!))
        assertEquals(MemoryTree.slugTokenFor(aCk, aScope.scopeId, "urlaube"), node.getString("slugToken"))
        assertFalse(node.getString("slugToken") == nodeBefore.getString("slugToken"))
        var oldKeyNodeFailed = false
        try { MemoryTree.decodeNode(ckBefore, node) } catch (e: Exception) { oldKeyNodeFailed = true }
        assertTrue(oldKeyNodeFailed, "old content key must not decrypt the re-sealed node")

        // and B — granted only at rotation — reads content written before B even joined
        val bScope = ScopeContent.openTenantScope(apiBase, b, t1.getString("id"))
        val bCk = ScopeContent.getOrCreateContentKey(apiBase, b, bScope)
        assertTrue(ScopeContent.readItems(apiBase, b, bScope, bCk).contains("trip notes"))
    }
}
