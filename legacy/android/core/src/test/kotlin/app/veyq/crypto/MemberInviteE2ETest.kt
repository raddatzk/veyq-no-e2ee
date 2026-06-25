package app.veyq.crypto

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `gradle test` no-ops it. */
class MemberInviteE2ETest {
    private val apiBase = System.getenv("VEYQ_API_BASE") ?: "http://localhost:8080"

    @Test
    fun tempWrapMemberInviteRegistersOnExistingScope() {
        if (System.getenv("VEYQ_E2E") != "1") return // opt-in: requires a live backend

        // owner A claims tenant T1 (holds its scope)
        val aPass = "correct horse battery staple"
        val (_, aKit) = SetupServer.registerServerOwner(apiBase, "admin@example.com", "Admin", aPass)
        val a = Login.login(apiBase, "admin@example.com", aPass, aKit.secretKey)
        val t1 = Tenant.createTenant(apiBase, a.sessionToken, "acme", "Acme", null)
        val inv = Tenant.issueOwnerInvite(apiBase, a.sessionToken, t1.getString("id"))
        Tenant.claimTenantOwnership(apiBase, a.sessionToken, inv.getString("inviteId"), inv.getString("bootstrapSecret"), a.kemPublicKey)

        // A issues a temp-wrap member invite (scope wrapped to a one-time temp key)
        val memberInvite = InviteMembers.issueMemberInvite(apiBase, a, t1.getString("id"), "Member")
        assertTrue(memberInvite.tempPrivateKey.isNotEmpty())

        // B (a brand-new account) accepts: unwraps with the temp key, re-wraps to self, registers
        val bPass = "a whole new passphrase entirely"
        val bReg = InviteMembers.registerViaMemberInvite(
            apiBase, memberInvite.inviteId, memberInvite.bootstrapSecret, memberInvite.tempPrivateKey, "b@example.com", "B", bPass,
        )
        assertEquals("ACTIVE", bReg.tenant.getString("status"))
        assertEquals("ServerMember", bReg.user.getString("serverRole"))

        // B logs in and recovers T1's scope private key from their own grant (full temp-wrap round trip)
        val b = Login.login(apiBase, "b@example.com", bPass, bReg.emergencyKit.secretKey)
        val grant = Tenant.getMyScopeGrant(apiBase, b.sessionToken, t1.getString("id"))
        val scopePriv = Tenant.openScopeGrant(grant, b.identity, b.kemPublicKey)
        val recoveredXPub = X25519PrivateKeyParameters(scopePriv.kemX, 0).generatePublicKey().encoded
        val storedXPub = Base64.getUrlDecoder()
            .decode(grant.getJSONObject("scopePublicKey").getString("kemPublicKey")).copyOfRange(0, 32)
        assertContentEquals(recoveredXPub, storedXPub)
    }
}
