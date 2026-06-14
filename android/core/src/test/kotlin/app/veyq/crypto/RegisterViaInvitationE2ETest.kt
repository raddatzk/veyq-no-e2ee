package app.veyq.crypto

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `gradle test` no-ops it. */
class RegisterViaInvitationE2ETest {
    private val apiBase = System.getenv("VEYQ_API_BASE") ?: "http://localhost:8080"

    @Test
    fun registerNewOwnerThenLoginAndGrant() {
        if (System.getenv("VEYQ_E2E") != "1") return // opt-in: requires a live backend

        // a Server Owner provisions the tenant + owner invite
        val adminPassword = "correct horse battery staple"
        val (_, adminKit) = SetupServer.registerServerOwner(apiBase, "admin@example.com", "Admin", adminPassword)
        val admin = Login.login(apiBase, "admin@example.com", adminPassword, adminKit.secretKey)
        val tenant = Tenant.createTenant(apiBase, admin.sessionToken, "acme", "Acme", "acme.example")
        val invite = Tenant.issueOwnerInvite(apiBase, admin.sessionToken, tenant.getString("id"))

        // public metadata is visible without the bootstrap secret
        val meta = RegisterViaInvitation.getInviteMetadata(apiBase, invite.getString("inviteId"))
        assertEquals("acme", meta.getJSONObject("tenant").getString("name"))
        assertEquals("Owner", meta.getString("role"))
        assertEquals("PENDING", meta.getString("status"))

        // the invitee (a brand-new account) registers and claims the tenant in one step
        val inviteePassword = "a whole new passphrase entirely"
        val result = RegisterViaInvitation.registerViaInvitation(
            apiBase, invite.getString("inviteId"), invite.getString("bootstrapSecret"),
            "tenant-owner@example.com", "Tenant Owner", inviteePassword,
        )
        assertEquals("ACTIVE", result.tenant.getString("status"))
        assertEquals("tenant-owner@example.com", result.user.getString("email"))
        assertEquals("ServerMember", result.user.getString("serverRole"))
        assertTrue(result.sessionToken.isNotEmpty())

        // the new account can log in independently (proves the auth verifier + master wrap are consistent)
        val session = Login.login(apiBase, "tenant-owner@example.com", inviteePassword, result.emergencyKit.secretKey)

        // and recover its personal scope grant — unshared, scoped to this account
        val personalGrant = Login.fetchPersonalScope(apiBase, session.sessionToken)
        val personalPriv = Login.openPersonalScopeGrant(personalGrant, session.identity, session.kemPublicKey)
        val recoveredPersonalXPub = X25519PrivateKeyParameters(personalPriv.kemX, 0).generatePublicKey().encoded
        val storedPersonalXPub = Base64.getUrlDecoder()
            .decode(personalGrant.getJSONObject("scopePublicKey").getString("kemPublicKey")).copyOfRange(0, 32)
        assertContentEquals(recoveredPersonalXPub, storedPersonalXPub)

        // and recover its tenant scope grant — the server never held the scope private key
        val grant = Tenant.getMyScopeGrant(apiBase, session.sessionToken, tenant.getString("id"))
        val scopePriv = Tenant.openScopeGrant(grant, session.identity, session.kemPublicKey)
        val recoveredXPub = X25519PrivateKeyParameters(scopePriv.kemX, 0).generatePublicKey().encoded
        val storedXPub = Base64.getUrlDecoder()
            .decode(grant.getJSONObject("scopePublicKey").getString("kemPublicKey")).copyOfRange(0, 32)
        assertContentEquals(recoveredXPub, storedXPub)
    }
}
