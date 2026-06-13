package app.veyq.crypto

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `gradle test` no-ops it. */
class LoginE2ETest {
    private val apiBase = System.getenv("VEYQ_API_BASE") ?: "http://localhost:8080"

    @Test
    fun loginAndUnlock() {
        if (System.getenv("VEYQ_E2E") != "1") return // opt-in: requires a live backend
        val email = "owner@example.com"
        val password = "correct horse battery staple"

        val (_, kit) = SetupServer.registerServerOwner(apiBase, email, "Owner", password)

        val session = Login.login(apiBase, email, password, kit.secretKey)
        assertTrue(session.sessionToken.isNotEmpty())
        assertEquals(32, session.identity.kemX.size)
        assertEquals(64, session.identity.mlkemSeed.size)
        assertEquals(32, session.identity.sign.size)

        val me = JSONObject(Login.fetchCurrentUser(apiBase, session.sessionToken))
        assertEquals(email, me.getString("email"))

        val personalGrant = Login.fetchPersonalScope(apiBase, session.sessionToken)
        val personalPriv = Login.openPersonalScopeGrant(personalGrant, session.identity, session.kemPublicKey)
        val recoveredPersonalXPub = X25519PrivateKeyParameters(personalPriv.kemX, 0).generatePublicKey().encoded
        val storedPersonalXPub = Base64.getUrlDecoder()
            .decode(personalGrant.getJSONObject("scopePublicKey").getString("kemPublicKey")).copyOfRange(0, 32)
        assertContentEquals(recoveredPersonalXPub, storedPersonalXPub)

        // the recovered private key matches the server's stored public key (X25519 half)
        val recoveredXPub = X25519PrivateKeyParameters(session.identity.kemX, 0).generatePublicKey().encoded
        assertContentEquals(recoveredXPub, session.kemPublicKey.copyOfRange(0, 32))
    }
}
