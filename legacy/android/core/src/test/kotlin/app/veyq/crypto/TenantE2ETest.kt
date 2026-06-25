package app.veyq.crypto

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `gradle test` no-ops it. */
class TenantE2ETest {
    private val apiBase = System.getenv("VEYQ_API_BASE") ?: "http://localhost:8080"

    @Test
    fun provisionClaimAndGrant() {
        if (System.getenv("VEYQ_E2E") != "1") return // opt-in: requires a live backend
        val email = "owner@example.com"
        val password = "correct horse battery staple"

        val (_, kit) = SetupServer.registerServerOwner(apiBase, email, "Owner", password)
        val session = Login.login(apiBase, email, password, kit.secretKey)

        val tenant = Tenant.createTenant(apiBase, session.sessionToken, "acme", "Acme", "acme.example")
        assertEquals("PENDING", tenant.getString("status"))

        val invite = Tenant.issueOwnerInvite(apiBase, session.sessionToken, tenant.getString("id"))
        val claimed = Tenant.claimTenantOwnership(
            apiBase, session.sessionToken, invite.getString("inviteId"), invite.getString("bootstrapSecret"), session.kemPublicKey,
        )
        assertEquals("ACTIVE", claimed.getString("status"))

        val grant = Tenant.getMyScopeGrant(apiBase, session.sessionToken, tenant.getString("id"))
        val scopePriv = Tenant.openScopeGrant(grant, session.identity, session.kemPublicKey)

        // the recovered scope private key matches the stored scope public key (X25519 half)
        val recoveredXPub = X25519PrivateKeyParameters(scopePriv.kemX, 0).generatePublicKey().encoded
        val storedXPub = Base64.getUrlDecoder()
            .decode(grant.getJSONObject("scopePublicKey").getString("kemPublicKey")).copyOfRange(0, 32)
        assertContentEquals(recoveredXPub, storedXPub)
    }
}
