package app.veyq.crypto

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `gradle test` no-ops it. */
class UseGroupE2ETest {
    private val apiBase = System.getenv("VEYQ_API_BASE") ?: "http://localhost:8080"

    private fun groupFor(groups: org.json.JSONArray, tenantId: String): JSONObject? {
        for (i in 0 until groups.length()) {
            val g = groups.getJSONObject(i)
            if (g.getString("tenantId") == tenantId) return g
        }
        return null
    }

    @Test
    fun twoMembersCollaborateOnOneSharedScope() {
        if (System.getenv("VEYQ_E2E") != "1") return // opt-in: requires a live backend

        // owner A claims tenant T1
        val aPass = "correct horse battery staple"
        val (_, aKit) = SetupServer.registerServerOwner(apiBase, "admin@example.com", "Admin", aPass)
        val a = Login.login(apiBase, "admin@example.com", aPass, aKit.secretKey)
        val t1 = Tenant.createTenant(apiBase, a.sessionToken, "acme", "Acme Group", null)
        val t1Id = t1.getString("id")
        val inv = Tenant.issueOwnerInvite(apiBase, a.sessionToken, t1Id)
        Tenant.claimTenantOwnership(apiBase, a.sessionToken, inv.getString("inviteId"), inv.getString("bootstrapSecret"), a.kemPublicKey)

        // member B joins T1 via a temp-wrap invite
        val bPass = "a whole new passphrase entirely"
        val memberInvite = InviteMembers.issueMemberInvite(apiBase, a, t1Id, "Member")
        val bReg = InviteMembers.registerViaMemberInvite(
            apiBase, memberInvite.inviteId, memberInvite.bootstrapSecret, memberInvite.tempPrivateKey, "b@example.com", "B", bPass)
        val b = Login.login(apiBase, "b@example.com", bPass, bReg.emergencyKit.secretKey)

        // the group roster shows T1 to both — with each member's own role
        val aT1 = groupFor(UseGroup.listMyGroups(apiBase, a), t1Id)
        assertEquals("Owner", aT1?.getString("role"))
        assertEquals("Acme Group", aT1?.getString("displayName"))
        val bT1 = groupFor(UseGroup.listMyGroups(apiBase, b), t1Id)
        assertEquals("Member", bT1?.getString("role"))

        // both members write shared content under the one group scope (content key provisioned once)
        val aScope = ScopeContent.openTenantScope(apiBase, a, t1Id)
        val aCk = ScopeContent.getOrCreateContentKey(apiBase, a, aScope)
        ScopeContent.writeItem(apiBase, a, aScope, aCk, "note from A")

        val bScope = ScopeContent.openTenantScope(apiBase, b, t1Id)
        val bCk = ScopeContent.getOrCreateContentKey(apiBase, b, bScope)
        ScopeContent.writeItem(apiBase, b, bScope, bCk, "note from B")

        // any member reads the whole shared set — both see both items (access = the wrap, ADR 0005)
        val aItems = ScopeContent.readItems(apiBase, a, aScope, aCk)
        assertTrue(aItems.contains("note from A") && aItems.contains("note from B"))
        val bItems = ScopeContent.readItems(apiBase, b, bScope, bCk)
        assertTrue(bItems.contains("note from A") && bItems.contains("note from B"))
    }
}
