package com.veyq.client

import com.veyq.crypto.V1
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * cr-0026 phase 6 — uc.spaces.personal-scope (android) data-layer tests, the twin of Apple's `ScopesTests`.
 * A stateful [ScopesDispatcher] captures the ciphertext grant `ensurePersonalScope` POSTs and serves it back
 * in `listMyScopes`, so `openPersonalScope` recovers the scope key end-to-end with the real hybrid KEM
 * (asymWrap → asymUnwrap, cr-0025). Operator-blind: only the ciphertext grant crosses the wire.
 */
class ScopesTest {
    private val masterKey = ByteArray(32) { it.toByte() }
    private val email = "person@veyq.example"
    private lateinit var server: MockWebServer

    @BeforeTest fun setUp() { server = MockWebServer() }
    @AfterTest fun tearDown() { server.shutdown() }
    private fun baseUrl(): String = server.url("").toString().trimEnd('/')

    /** A client whose vault already holds a REAL identity bundle (we bypass the floor — the identity is what
     *  seals/opens a grant). Returns the client + the identity for independent verification. */
    private fun unlockedClient(d: ScopesDispatcher): Pair<VeyqClient, V1.Identity> {
        server.dispatcher = d
        server.start()
        val id = V1.generateIdentity()
        val session = Session().apply { set("access-1", "refresh-1", "fam") }
        val c = VeyqClient(VeyqInstance(baseUrl()), session = session, deviceSecurity = InMemoryDeviceSecurityStore())
        c.vault.open(masterKey.copyOf(), V1.serializeIdentityPrivate(id.priv))
        return c to id
    }

    @Test fun ensureBirthsThenOpenRecoversTheScopeKey() {
        val d = ScopesDispatcher(mutableListOf(tenantScope()))
        val (c, id) = unlockedClient(d)

        assertTrue(c.ensurePersonalScope(), "a personal scope was born")
        assertTrue(d.hasKind("PERSONAL"), "the server stored a PERSONAL scope")

        val opened = c.openPersonalScope()
        assertEquals(32, opened?.size)
        // The opened key is exactly the one sealed inside the captured grant (end-to-end with the real KEM).
        val manual = V1.asymUnwrap(V1.hybridSecret(id.priv), V1.hybridPublic(id.priv), Base64.getDecoder().decode(d.lastGrant!!), "scope-grant")
        assertContentEquals(manual, opened)
    }

    @Test fun ensureIsIdempotentWhenPersonalExists() {
        val d = ScopesDispatcher(mutableListOf(tenantScope(), personalScope()))
        val (c, _) = unlockedClient(d)
        assertFalse(c.ensurePersonalScope(), "no second personal scope is born")
        assertFalse(d.sawPost, "no POST when a personal scope already exists")
    }

    @Test fun openReturnsNullWhenNoPersonal() {
        val d = ScopesDispatcher(mutableListOf(tenantScope()))
        val (c, _) = unlockedClient(d)
        assertNull(c.openPersonalScope())
    }

    private fun tenantScope() =
        """{"id":"11111111-1111-1111-1111-111111111111","kind":"TENANT","tenantId":"11111111-1111-1111-1111-111111111111","wrappedGrant":"AQ==","role":"OWNER","nameEpoch":0,"currentEpoch":0,"grantedEpoch":0}"""
    private fun personalScope() =
        """{"id":"22222222-2222-2222-2222-222222222222","kind":"PERSONAL","tenantId":"11111111-1111-1111-1111-111111111111","wrappedGrant":"AQ==","role":"OWNER","nameEpoch":0,"currentEpoch":0,"grantedEpoch":0}"""

    /** Serves /api/scopes: GET returns the list; POST captures the grant, appends a PERSONAL scope, 201. */
    private class ScopesDispatcher(private val scopes: MutableList<String>) : Dispatcher() {
        @Volatile var sawPost = false
        @Volatile var lastGrant: String? = null

        @Synchronized fun hasKind(kind: String) = scopes.any { """"kind":"$kind"""".toRegex().containsMatchIn(it) }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path ?: ""
            if (path == "/api/scopes" && request.method == "GET") {
                val body = synchronized(this) { "[${scopes.joinToString(",")}]" }
                return json(200, body)
            }
            if (path == "/api/scopes" && request.method == "POST") {
                val grant = """"wrappedGrant":"([^"]*)"""".toRegex()
                    .find(request.body.readUtf8())?.groupValues?.get(1) ?: ""
                synchronized(this) {
                    sawPost = true
                    lastGrant = grant
                    scopes.add("""{"id":"33333333-3333-3333-3333-333333333333","kind":"PERSONAL","tenantId":"11111111-1111-1111-1111-111111111111","wrappedGrant":"$grant","role":"OWNER","nameEpoch":0,"currentEpoch":0,"grantedEpoch":0}""")
                }
                return json(201, """{"scopeId":"33333333-3333-3333-3333-333333333333","kind":"PERSONAL"}""")
            }
            return MockResponse().setResponseCode(404)
        }

        private fun json(code: Int, body: String) =
            MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    }
}
