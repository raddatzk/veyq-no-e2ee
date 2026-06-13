package app.veyq.crypto

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `gradle test` no-ops it. */
class RotateIdentityE2ETest {
    private val apiBase = System.getenv("VEYQ_API_BASE") ?: "http://localhost:8080"

    @Test
    fun rotateIdentityKeepsAccessToScopesAndContent() {
        if (System.getenv("VEYQ_E2E") != "1") return // opt-in: requires a live backend

        val pass = "correct horse battery staple"
        val (_, kit) = SetupServer.registerServerOwner(apiBase, "owner@example.com", "Owner", pass)
        val a = Login.login(apiBase, "owner@example.com", pass, kit.secretKey)

        // a tenant scope grant + encrypted content under the personal scope
        val t1 = Tenant.createTenant(apiBase, a.sessionToken, "acme", "Acme", null)
        val inv = Tenant.issueOwnerInvite(apiBase, a.sessionToken, t1.getString("id"))
        Tenant.claimTenantOwnership(apiBase, a.sessionToken, inv.getString("inviteId"), inv.getString("bootstrapSecret"), a.kemPublicKey)
        val scope = ScopeContent.openPersonalScope(apiBase, a)
        val ck = ScopeContent.getOrCreateContentKey(apiBase, a, scope)
        ScopeContent.writeItem(apiBase, a, scope, ck, "personal secret")

        // rotate the identity keypair
        RotateIdentity.rotateIdentity(apiBase, a)

        // re-login unlocks the NEW identity under the unchanged master key
        val a2 = Login.login(apiBase, "owner@example.com", pass, kit.secretKey)
        assertFalse(a2.kemPublicKey.contentEquals(a.kemPublicKey))

        // the personal scope + its content are still readable via the new identity
        val scope2 = ScopeContent.openPersonalScope(apiBase, a2)
        val ck2 = ScopeContent.getOrCreateContentKey(apiBase, a2, scope2)
        assertTrue(ScopeContent.readItems(apiBase, a2, scope2, ck2).contains("personal secret"))

        // and the tenant scope grant opens under the new identity too
        val tGrant = Tenant.getMyScopeGrant(apiBase, a2.sessionToken, t1.getString("id"))
        val tScopePriv = Tenant.openScopeGrant(tGrant, a2.identity, a2.kemPublicKey)
        val recoveredX = X25519PrivateKeyParameters(tScopePriv.kemX, 0).generatePublicKey().encoded
        val storedX = Base64.getUrlDecoder().decode(tGrant.getJSONObject("scopePublicKey").getString("kemPublicKey")).copyOfRange(0, 32)
        assertContentEquals(recoveredX, storedX)
    }
}
