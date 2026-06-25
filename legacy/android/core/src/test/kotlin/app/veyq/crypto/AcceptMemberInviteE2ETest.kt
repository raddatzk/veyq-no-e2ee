package app.veyq.crypto

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `gradle test` no-ops it. */
class AcceptMemberInviteE2ETest {
    private val apiBase = System.getenv("VEYQ_API_BASE") ?: "http://localhost:8080"

    @Test
    fun existingUserConfirmsInviteOntoExistingAccount() {
        if (System.getenv("VEYQ_E2E") != "1") return // opt-in: requires a live backend

        // owner A claims T1
        val aPass = "correct horse battery staple"
        val (_, aKit) = SetupServer.registerServerOwner(apiBase, "admin@example.com", "Admin", aPass)
        val a = Login.login(apiBase, "admin@example.com", aPass, aKit.secretKey)
        val t1 = Tenant.createTenant(apiBase, a.sessionToken, "acme", "Acme", null)
        val inv = Tenant.issueOwnerInvite(apiBase, a.sessionToken, t1.getString("id"))
        Tenant.claimTenantOwnership(apiBase, a.sessionToken, inv.getString("inviteId"), inv.getString("bootstrapSecret"), a.kemPublicKey)

        // B already has a standalone account (owner of T2)
        val bPass = "a whole new passphrase entirely"
        val t2 = Tenant.createTenant(apiBase, a.sessionToken, "globex", "Globex", null)
        val t2Invite = Tenant.issueOwnerInvite(apiBase, a.sessionToken, t2.getString("id"))
        val bReg = RegisterViaInvitation.registerViaInvitation(
            apiBase, t2Invite.getString("inviteId"), t2Invite.getString("bootstrapSecret"), "b@example.com", "B", bPass)
        val b = Login.login(apiBase, "b@example.com", bPass, bReg.emergencyKit.secretKey)

        // A invites B to T1; B confirms onto their EXISTING account
        val memberInvite = InviteMembers.issueMemberInvite(apiBase, a, t1.getString("id"), "Member")
        val membership = InviteMembers.acceptMemberInvite(
            apiBase, b, memberInvite.inviteId, memberInvite.bootstrapSecret, memberInvite.tempPrivateKey)

        // same account — no second account was created
        assertEquals(bReg.user.getString("id"), membership.getString("userId"))
        assertEquals("Member", membership.getString("role"))
        assertEquals("ACTIVE", membership.getString("status"))

        // B recovers T1's scope from the grant they re-wrapped to their own identity
        val grant = Tenant.getMyScopeGrant(apiBase, b.sessionToken, t1.getString("id"))
        val scopePriv = Tenant.openScopeGrant(grant, b.identity, b.kemPublicKey)
        val recoveredXPub = X25519PrivateKeyParameters(scopePriv.kemX, 0).generatePublicKey().encoded
        val storedXPub = Base64.getUrlDecoder()
            .decode(grant.getJSONObject("scopePublicKey").getString("kemPublicKey")).copyOfRange(0, 32)
        assertContentEquals(recoveredXPub, storedXPub)
    }
}
