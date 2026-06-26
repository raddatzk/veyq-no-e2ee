package com.veyq.client

import com.veyq.crypto.V1
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * uc.account.login (android) data-layer tests — the Android twin of Apple's `LoginTests`. No live server:
 * a [MockWebServer] answers the two login endpoints with canned JSON and records what the client sent. The
 * headline assertion is that the signature the client produced actually **verifies** against the keypair
 * derived from the same password + instance salt — i.e. the challenge → deriveAuthSalt → deriveAuthKeypair
 * → authSign chain is wired correctly end-to-end, the operator-blind guarantee (ADR 0003) holding without
 * a backend.
 */
class LoginTest {

    private val instanceSalt = ByteArray(16) { it.toByte() }
    private val nonce = ByteArray(32) { (0xA0 + it).toByte() }
    private val email = "kevin@example.com"
    private val password = "correct horse battery staple"
    private val familyId = "7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c"

    private lateinit var server: MockWebServer
    private lateinit var dispatcher: AuthDispatcher

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        dispatcher = AuthDispatcher()
        server.dispatcher = dispatcher
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun newClient(): VeyqClient =
        VeyqClient(VeyqInstance(server.url("").toString().trimEnd('/')))

    @Test
    fun loginDerivesSignsAndHoldsSession() {
        val client = newClient()

        client.login(email = email, password = password)

        // The session is held with exactly the tokens the server issued.
        val session = client.session
        assertTrue(session.isAuthenticated)
        assertEquals("access-1", session.accessToken)
        assertEquals("refresh-1", session.refreshToken)
        assertEquals(familyId, session.familyId)

        // Headline check: the signature the client sent verifies against the keypair derived from the same
        // password + instance salt over the issued nonce.
        val sent = assertNotNull(dispatcher.recordedSignature)
        val authSalt = V1.deriveAuthSalt(instanceSalt, email)
        val keypair = V1.deriveAuthKeypair(password, authSalt)
        assertTrue(V1.authVerify(keypair.publicKey, nonce, sent))

        // The verify request echoed the same email.
        assertEquals(email, dispatcher.recordedEmail)
    }

    @Test
    fun refreshRotatesTokens() {
        val client = newClient()
        client.login(email = email, password = password)

        client.refresh()

        assertEquals("access-2", client.session.accessToken)
        assertEquals("refresh-2", client.session.refreshToken)
        assertEquals(familyId, client.session.familyId) // set at login, kept across refreshes
    }

    @Test
    fun refreshWithoutSessionThrows() {
        val client = newClient()
        assertFailsWith<VeyqError.NoSession> { client.refresh() }
    }

    @Test
    fun withRefreshRotatesOnSessionExpiredThenRetries() {
        val client = newClient()
        client.login(email = email, password = password)

        // A resource op that 401s the first time, then succeeds — withRefresh must rotate and retry.
        val attempts = AtomicInteger(0)
        val result = client.withRefresh {
            val n = attempts.incrementAndGet()
            if (n == 1) throw VeyqError.SessionExpired
            "ok-on-attempt-$n"
        }

        assertEquals("ok-on-attempt-2", result)
        assertEquals("access-2", client.session.accessToken) // the 401 triggered a refresh before the retry
    }

    /**
     * Answers the login + token endpoints with canned JSON and records the signature/email the client sent.
     * It does not verify anything — the *test* verifies the recorded signature against the independently
     * derived keypair, which is what makes this a real proof.
     */
    private inner class AuthDispatcher : Dispatcher() {
        @Volatile var recordedSignature: ByteArray? = null
        @Volatile var recordedEmail: String? = null
        private val refreshCount = AtomicInteger(0)

        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
            "/api/login/challenge" -> json(
                """{"nonce":"${b64(nonce)}","instanceSalt":"${b64(instanceSalt)}"}""",
            )

            "/api/login/verify" -> {
                val obj = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                recordedEmail = obj["email"]?.jsonPrimitive?.content
                recordedSignature = obj["signature"]?.jsonPrimitive?.content?.let { Base64.getDecoder().decode(it) }
                json("""{"accessToken":"access-1","refreshToken":"refresh-1","family":{"id":"$familyId"}}""")
            }

            "/api/token/refresh" -> {
                val n = refreshCount.incrementAndGet()
                json("""{"accessToken":"access-${n + 1}","refreshToken":"refresh-${n + 1}"}""")
            }

            "/api/logout" -> MockResponse().setResponseCode(204)
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun json(body: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
