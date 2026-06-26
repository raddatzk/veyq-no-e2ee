package com.veyq.client

import com.veyq.crypto.V1
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * uc.account.unlock (android) data-layer tests — the Android twin of Apple's `UnlockTests`. No live server:
 * the dispatcher serves key material that the test itself builds with the *same* V1 primitives
 * (kekPasswordSecret → symWrap), then asserts unlock recovers exactly the original Master Key + identity
 * private bytes. That round-trip proves the orchestration wiring (fetch → decodeKdfParams →
 * kekPasswordSecret → symUnwrap master → symUnwrap identity) — operator-blind (only ciphertext fetched;
 * password/Secret Key/KEK never sent), ADR 0003/0005.
 */
class UnlockTest {

    private val masterKey = ByteArray(32) { it.toByte() }
    private val identityPriv = ByteArray(80) { (0x10 + it).toByte() }
    private val secretKey = ByteArray(19) { (0x40 + it).toByte() }
    private val password = "open sesame"
    private val salt = ByteArray(16) { (0xE0 + it).toByte() }

    // Light Argon params (well above libsodium's argon2id minimums) so the test stays fast.
    private val params = V1.ArgonParams(t = 3, m = 64, p = 1, dkLen = 32)

    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("").toString().trimEnd('/')

    /**
     * Build the wrapped key material the server would hand back: Master Key wrapped under the
     * password+Secret-Key KEK, identity private wrapped under the Master Key.
     */
    private fun keyMaterialJson(method: String = "PASSWORD"): String {
        val kek = V1.kekPasswordSecret(secretKey, password, salt, params)
        val nonce1 = ByteArray(12) { (0x01 + it).toByte() }
        val nonce2 = ByteArray(12) { (0x20 + it).toByte() }
        val wrappedKey = V1.symWrap(kek, masterKey, "master-key", nonce1)
        val wrappedIdentity = V1.symWrap(masterKey, identityPriv, "identity-priv", nonce2)
        val kdfParams = V1.encodeKdfParams(params, salt)
        return """
            {"suiteId":"v1",
             "wraps":[{"id":"w1","method":"$method",
                       "wrappedKey":"${b64(wrappedKey)}","kdfParams":"${b64(kdfParams)}"}],
             "wrappedIdentityPrivateKey":"${b64(wrappedIdentity)}"}
        """.trimIndent()
    }

    private fun start(dispatcher: Dispatcher) {
        server.dispatcher = dispatcher
        server.start()
    }

    @Test
    fun unlockOpensVaultWithMasterAndIdentityKeys() {
        start(UnlockDispatcher(keyMaterialJson()))
        val client = VeyqClient(VeyqInstance(baseUrl()))

        client.unlock(password = password, secretKey = b64(secretKey))

        assertTrue(client.vault.isUnlocked)
        assertContentEquals(masterKey, client.vault.masterKey(), "the unwrapped Master Key matches the original")
        assertContentEquals(identityPriv, client.vault.identityPrivateKey(), "the Master-Key-unwrapped identity private matches")
    }

    @Test
    fun wrongSecretKeyThrowsUnlockFailedAndLeavesVaultLocked() {
        start(UnlockDispatcher(keyMaterialJson()))
        val client = VeyqClient(VeyqInstance(baseUrl()))
        val wrong = b64(ByteArray(19) { (0x99 + it).toByte() })

        assertFailsWith<VeyqError.UnlockFailed> { client.unlock(password = password, secretKey = wrong) }
        assertFalse(client.vault.isUnlocked)
    }

    @Test
    fun noPasswordWrapThrowsUnlockUnavailable() {
        start(UnlockDispatcher(keyMaterialJson(method = "DEVICE")))
        val client = VeyqClient(VeyqInstance(baseUrl()))

        assertFailsWith<VeyqError.UnlockUnavailable> { client.unlock(password = password, secretKey = b64(secretKey)) }
    }

    @Test
    fun keyMaterial401RefreshesThenUnlocks() {
        start(UnlockDispatcher(keyMaterialJson(), failKeyMaterialOnce = true))
        val session = Session().apply { set("access-1", "refresh-1", "fam") }
        val client = VeyqClient(VeyqInstance(baseUrl()), session = session)

        client.unlock(password = password, secretKey = b64(secretKey))

        assertTrue(client.vault.isUnlocked, "the 401 was rotated and the retry unlocked")
        assertEquals("access-2", client.session.accessToken, "withRefresh rotated the session before the retry")
    }

    @Test
    fun logoutClearsVault() {
        start(UnlockDispatcher(keyMaterialJson()))
        val client = VeyqClient(VeyqInstance(baseUrl()))
        client.unlock(password = password, secretKey = b64(secretKey))

        client.logout()

        assertFalse(client.vault.isUnlocked)
        assertNull(client.vault.masterKey())
    }

    /** Serves the account key-material + token endpoints from canned data. */
    private class UnlockDispatcher(
        private val keyMaterial: String,
        private val failKeyMaterialOnce: Boolean = false,
    ) : Dispatcher() {
        private val keyMaterialCalls = AtomicInteger(0)

        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
            "/api/account/key-material" -> {
                val n = keyMaterialCalls.incrementAndGet()
                if (failKeyMaterialOnce && n == 1) {
                    MockResponse().setResponseCode(401)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"code":"authentication_failed"}""")
                } else {
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json").setBody(keyMaterial)
                }
            }

            "/api/token/refresh" -> MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"accessToken":"access-2","refreshToken":"refresh-2"}""")

            "/api/logout" -> MockResponse().setResponseCode(204)
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
