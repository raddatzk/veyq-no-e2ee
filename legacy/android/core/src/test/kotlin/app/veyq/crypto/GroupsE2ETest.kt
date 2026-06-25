package app.veyq.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

        // passphrase-protected invite (operator-blind): protect the temp key, resolve it with the out-of-band
        // passphrase and register a brand-new user — wrong passphrase must fail.
        val pinv = InviteMembers.issueMemberInvite(apiBase, owner, tid, "Member")
        val invitePass = "shared out of band"
        val (ek, ps) = InviteMembers.protectTempKey(pinv.tempPrivateKey, invitePass)
        assertFailsWith<Exception> { InviteMembers.unprotectTempKey(ek, ps, "wrong") }
        val resolved = InviteMembers.unprotectTempKey(ek, ps, invitePass)
        assertEquals(pinv.tempPrivateKey, resolved)
        val creg = InviteMembers.registerViaMemberInvite(apiBase, pinv.inviteId, pinv.bootstrapSecret, resolved, "carol@example.com", "Carol", pass)
        val carol = Login.login(apiBase, "carol@example.com", pass, creg.emergencyKit.secretKey)
        assertNotNull(firstOfKind(SwitchActiveSpace.listMySpaces(apiBase, carol), "TENANT"))

        // email relay (mailer mock): returns 204 when the link targets this instance's app origin.
        val appOrigin = System.getenv("VEYQ_APP_BASE") ?: "http://localhost:5173"
        val einv = InviteMembers.issueMemberInvite(apiBase, owner, tid, "Member")
        val plainLink = "$appOrigin/#invite=${einv.inviteId}&s=${einv.bootstrapSecret}&k=${einv.tempPrivateKey}"
        InviteMembers.emailMemberInvite(apiBase, owner.sessionToken, tid, "pragmatic@example.com", einv.inviteId, plainLink)

        // delete the group (gone from roster)
        Groups.deleteGroup(apiBase, owner, tid, gScope)
        assertNull(firstWithScope(SwitchActiveSpace.listMySpaces(apiBase, owner), gScope))

        // schedule the tenant for deletion → it stays on the roster but carries a deadline (grace period)
        Groups.deleteTenant(apiBase, owner, tid)
        val scheduled = firstOfKind(SwitchActiveSpace.listMySpaces(apiBase, owner), "TENANT")
        assertNotNull(scheduled)
        assertNotNull(scheduled.optString("scheduledDeletionAt").ifEmpty { null })

        // owner cancels → the deadline clears, personal remains
        Groups.cancelTenantDeletion(apiBase, owner, tid)
        val after = SwitchActiveSpace.listMySpaces(apiBase, owner)
        assertNull(firstOfKind(after, "TENANT")?.optString("scheduledDeletionAt")?.ifEmpty { null })
        assertNotNull(firstOfKind(after, "PERSONAL"))
    }
}
