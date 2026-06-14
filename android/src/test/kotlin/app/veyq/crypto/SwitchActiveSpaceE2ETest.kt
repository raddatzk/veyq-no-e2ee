package app.veyq.crypto

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `gradle test` no-ops it. */
class SwitchActiveSpaceE2ETest {
    private val apiBase = System.getenv("VEYQ_API_BASE") ?: "http://localhost:8080"

    private fun firstOfKind(spaces: JSONArray, kind: String): JSONObject? {
        for (i in 0 until spaces.length()) {
            val s = spaces.getJSONObject(i)
            if (s.getString("kind") == kind) return s
        }
        return null
    }

    @Test
    fun listsPersonalPlusGroupAndSwitchesBetweenThem() {
        if (System.getenv("VEYQ_E2E") != "1") return // opt-in: requires a live backend

        val pass = "correct horse battery staple"
        val (_, kit) = SetupServer.registerServerOwner(apiBase, "admin@example.com", "Admin", pass)
        val a = Login.login(apiBase, "admin@example.com", pass, kit.secretKey)
        val t1 = Tenant.createTenant(apiBase, a.sessionToken, "acme", "Acme Group", null)
        val t1Id = t1.getString("id")
        val inv = Tenant.issueOwnerInvite(apiBase, a.sessionToken, t1Id)
        Tenant.claimTenantOwnership(apiBase, a.sessionToken, inv.getString("inviteId"), inv.getString("bootstrapSecret"), a.kemPublicKey)

        // the switcher lists both spaces: the personal one + the group just claimed
        val spaces = SwitchActiveSpace.listMySpaces(apiBase, a)
        val personal = firstOfKind(spaces, "PERSONAL")
        val group = firstOfKind(spaces, "GROUP")
        assertEquals("Admin", personal?.getString("displayName"))
        assertEquals("Owner", group?.getString("role"))
        assertEquals(t1Id, group?.getString("tenantId"))

        // switch into the personal space and write there (keys already held)
        val pScope = SwitchActiveSpace.openSpace(apiBase, a, personal!!)
        val pCk = ScopeContent.getOrCreateContentKey(apiBase, a, pScope)
        ScopeContent.writeItem(apiBase, a, pScope, pCk, "personal note")

        // switch into the group space and write there
        val gScope = SwitchActiveSpace.openSpace(apiBase, a, group!!)
        val gCk = ScopeContent.getOrCreateContentKey(apiBase, a, gScope)
        ScopeContent.writeItem(apiBase, a, gScope, gCk, "group note")

        // each space sees only its own content — switching truly changes the active scope context
        val pItems = ScopeContent.readItems(apiBase, a, pScope, pCk)
        val gItems = ScopeContent.readItems(apiBase, a, gScope, gCk)
        assertTrue(pItems.contains("personal note") && !pItems.contains("group note"))
        assertTrue(gItems.contains("group note") && !gItems.contains("personal note"))
    }
}
