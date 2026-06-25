package app.veyq.crypto

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `gradle test` no-ops it. */
class RotateScopeE2ETest {
    private val apiBase = System.getenv("VEYQ_API_BASE") ?: "http://localhost:8080"

    @Test
    fun rotateScopeReGrantsEveryActiveMember() {
        if (System.getenv("VEYQ_E2E") != "1") return // opt-in: requires a live backend

        // owner A claims T1; member B joins via a temp-wrap invite
        val aPass = "correct horse battery staple"
        val (_, aKit) = SetupServer.registerServerOwner(apiBase, "admin@example.com", "Admin", aPass)
        val a = Login.login(apiBase, "admin@example.com", aPass, aKit.secretKey)
        val t1 = Tenant.createTenant(apiBase, a.sessionToken, "acme", "Acme", null)
        val inv = Tenant.issueOwnerInvite(apiBase, a.sessionToken, t1.getString("id"))
        Tenant.claimTenantOwnership(apiBase, a.sessionToken, inv.getString("inviteId"), inv.getString("bootstrapSecret"), a.kemPublicKey)

        val bPass = "a whole new passphrase entirely"
        val memberInvite = InviteMembers.issueMemberInvite(apiBase, a, t1.getString("id"), "Member")
        val bReg = InviteMembers.registerViaMemberInvite(
            apiBase, memberInvite.inviteId, memberInvite.bootstrapSecret, memberInvite.tempPrivateKey, "b@example.com", "B", bPass)
        val b = Login.login(apiBase, "b@example.com", bPass, bReg.emergencyKit.secretKey)

        val before = xPub(a, t1.getString("id"))

        // A rotates the scope — a fresh keypair re-wrapped to both members
        val result = InviteMembers.rotateScope(apiBase, a, t1.getString("id"))
        assertEquals(2, result.getInt("memberCount"))

        // both members recover the SAME new scope keypair, and it changed
        val aNew = xPub(a, t1.getString("id"))
        val bNew = xPub(b, t1.getString("id"))
        assertContentEquals(aNew, bNew)
        assertFalse(aNew.contentEquals(before))
    }

    private fun xPub(session: Login.UnlockedSession, tenantId: String): ByteArray {
        val grant = Tenant.getMyScopeGrant(apiBase, session.sessionToken, tenantId)
        val scopePriv = Tenant.openScopeGrant(grant, session.identity, session.kemPublicKey)
        return X25519PrivateKeyParameters(scopePriv.kemX, 0).generatePublicKey().encoded
    }
}
