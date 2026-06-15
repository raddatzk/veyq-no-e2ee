package app.veyq.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `gradle test` no-ops it. */
class GroupsE2ETest {
    private val apiBase = System.getenv("VEYQ_API_BASE") ?: "http://localhost:8080"
    private val enc = Base64.getUrlEncoder().withoutPadding()

    private fun firstOfKind(spaces: JSONArray, kind: String): JSONObject? =
        (0 until spaces.length()).map { spaces.getJSONObject(it) }.firstOrNull { it.getString("kind") == kind }
    private fun firstWithScope(spaces: JSONArray, scopeId: String): JSONObject? =
        (0 until spaces.length()).map { spaces.getJSONObject(it) }.firstOrNull { it.getString("scopeId") == scopeId }

    @Test
    fun groupLifecycle() {
        if (System.getenv("VEYQ_E2E") != "1") return // opt-in: requires a live backend
        val pass = "correct horse battery staple"

        val (_, kit) = SetupServer.registerServerOwner(apiBase, "gowner@example.com", "GOwner", pass)
        val owner = Login.login(apiBase, "gowner@example.com", pass, kit.secretKey)
        val tenant = Tenant.createTenant(apiBase, owner.sessionToken, "gacme", "GAcme", null)
        val tid = tenant.getString("id")
        val oinv = Tenant.issueOwnerInvite(apiBase, owner.sessionToken, tid)
        Tenant.claimTenantOwnership(apiBase, owner.sessionToken, oinv.getString("inviteId"), oinv.getString("bootstrapSecret"), owner.kemPublicKey)

        // create a group; the creator is its GroupOwner
        val group = Groups.createGroup(apiBase, owner, tid, "Reisegruppe")
        val gScope = group.getString("scopeId")
        assertEquals("GroupOwner", group.getString("role"))

        // roster shows PERSONAL + TENANT + GROUP; open by id, decrypt the sealed name, write content
        val groupEntry = firstWithScope(SwitchActiveSpace.listMySpaces(apiBase, owner), gScope)!!
        assertEquals("GROUP", groupEntry.getString("kind"))
        val oScope = SwitchActiveSpace.openSpace(apiBase, owner, groupEntry)
        val oCk = ScopeContent.getOrCreateContentKey(apiBase, owner, oScope)
        assertEquals("Reisegruppe", Groups.openSealedName(oCk, groupEntry.getJSONObject("sealedName").getString("bytes")))
        ScopeContent.writeItem(apiBase, owner, oScope, oCk, "crew secret")

        // a second user joins the tenant via a member invite; the owner adds them to the group
        val minv = InviteMembers.issueMemberInvite(apiBase, owner, tid, "Member")
        val reg = InviteMembers.registerViaMemberInvite(apiBase, minv.inviteId, minv.bootstrapSecret, minv.tempPrivateKey, "gbob@example.com", "Bob", pass)
        val bobId = reg.user.getString("id")
        val bob = Login.login(apiBase, "gbob@example.com", pass, reg.emergencyKit.secretKey)
        Groups.addGroupMember(apiBase, owner, tid, gScope, bobId, enc.encodeToString(bob.kemPublicKey))

        // Bob's re-wrapped grant opens the group and decrypts its name + content
        val bobGroup = firstWithScope(SwitchActiveSpace.listMySpaces(apiBase, bob), gScope)!!
        val bScope = SwitchActiveSpace.openSpace(apiBase, bob, bobGroup)
        val bCk = ScopeContent.getOrCreateContentKey(apiBase, bob, bScope)
        assertEquals("Reisegruppe", Groups.openSealedName(bCk, bobGroup.getJSONObject("sealedName").getString("bytes")))
        assertTrue(ScopeContent.readItems(apiBase, bob, bScope, bCk).contains("crew secret"))

        // owner sees 2 members and promotes Bob
        assertEquals(2, Groups.listGroupMembers(apiBase, owner, tid, gScope).length())
        assertEquals("GroupOwner", Groups.setGroupMemberRole(apiBase, owner, tid, gScope, bobId, "GroupOwner").getString("role"))

        // delete the group (gone from roster), then the tenant (TENANT gone, personal remains)
        Groups.deleteGroup(apiBase, owner, tid, gScope)
        assertNull(firstWithScope(SwitchActiveSpace.listMySpaces(apiBase, owner), gScope))
        Groups.deleteTenant(apiBase, owner, tid)
        val after = SwitchActiveSpace.listMySpaces(apiBase, owner)
        assertNull(firstOfKind(after, "TENANT"))
        assertNotNull(firstOfKind(after, "PERSONAL"))
    }
}
