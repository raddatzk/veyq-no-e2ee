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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * cr-0021 phase 2 — uc.account.trust-device (android) data-layer tests, the twin of Apple's
 * `TrustDeviceTests`. A stateful [WrapsDispatcher] **captures the DEVICE wrap a `trustThisDevice` POSTs and
 * serves it back** in key material, so a later `unlockWithDevice` opens the vault end-to-end with the real
 * V1 crypto (Argon2id(deviceFactor + password) → symUnwrap). Operator-blind: only ciphertext crosses.
 */
class TrustDeviceTest {
    private val masterKey = ByteArray(32) { it.toByte() }
    private val identityPriv = ByteArray(80) { (0x10 + it).toByte() }
    private val secretKey = ByteArray(19) { (0x40 + it).toByte() }
    private val password = "open sesame"
    private val salt = ByteArray(16) { (0xE0 + it).toByte() }
    private val params = V1.ArgonParams(t = 3, m = 64, p = 1, dkLen = 32)
    private val email = "person@veyq.example"

    private lateinit var server: MockWebServer

    @BeforeTest fun setUp() { server = MockWebServer() }
    @AfterTest fun tearDown() { server.shutdown() }
    private fun baseUrl(): String = server.url("").toString().trimEnd('/')

    /** The PASSWORD floor wrap + wrapped identity the dispatcher starts with (so the floor unlock can run). */
    private fun floorWrapJson(): String {
        val kek = V1.kekPasswordSecret(secretKey, password, salt, params)
        val wrappedKey = V1.symWrap(kek, masterKey, "master-key", ByteArray(12) { (0x01 + it).toByte() })
        val kdfParams = V1.encodeKdfParams(params, salt)
        return """{"id":"pw","method":"PASSWORD","wrappedKey":"${b64(wrappedKey)}","kdfParams":"${b64(kdfParams)}"}"""
    }

    private fun wrappedIdentityB64(): String =
        b64(V1.symWrap(masterKey, identityPriv, "identity-priv", ByteArray(12) { (0x20 + it).toByte() }))

    private fun start(d: WrapsDispatcher) { server.dispatcher = d; server.start() }

    private fun makeClient(store: DeviceSecurityStore): VeyqClient {
        val session = Session().apply { set("access-1", "refresh-1", "fam") }
        return VeyqClient(VeyqInstance(baseUrl()), session = session, deviceSecurity = store)
    }

    private fun floorUnlock(store: DeviceSecurityStore): VeyqClient =
        makeClient(store).also { it.unlock(password = password, secretKey = b64(secretKey)) }

    @Test fun trustThenDeviceUnlockRoundTrip() {
        start(WrapsDispatcher(floorWrapJson(), wrappedIdentityB64()))
        val store = InMemoryDeviceSecurityStore()

        val c1 = floorUnlock(store)
        c1.trustThisDevice(email, password)
        assertTrue((store.currentLevel(email) == DeviceLevel.ONLY_PASSWORD))
        assertTrue(server.dispatcher.let { (it as WrapsDispatcher).hasWrap("DEVICE") }, "a DEVICE wrap was stored")

        // Fresh client (new vault), same store + server: unlock with only the password.
        val c2 = makeClient(store)
        assertTrue(c2.unlockWithDevice(email, password))
        assertContentEquals(masterKey, c2.vault.masterKey())
        assertContentEquals(identityPriv, c2.vault.identityPrivateKey())
    }

    @Test fun deviceUnlockWrongPasswordThrowsAndLeavesVaultLocked() {
        start(WrapsDispatcher(floorWrapJson(), wrappedIdentityB64()))
        val store = InMemoryDeviceSecurityStore()
        floorUnlock(store).trustThisDevice(email, password)

        val c2 = makeClient(store)
        assertFailsWith<VeyqError.UnlockFailed> { c2.unlockWithDevice(email, "wrong password") }
        assertFalse(c2.vault.isUnlocked)
    }

    @Test fun notTrustedReturnsFalse() {
        start(WrapsDispatcher(floorWrapJson(), wrappedIdentityB64()))
        val c = makeClient(InMemoryDeviceSecurityStore())
        assertFalse(c.unlockWithDevice(email, password))
        assertFalse(c.vault.isUnlocked)
    }

    @Test fun missingServerWrapReturnsFalseAndKeepsRecord() {
        start(WrapsDispatcher(floorWrapJson(), wrappedIdentityB64()))
        val store = InMemoryDeviceSecurityStore()
        store.beginEnrol(email); store.commitOnlyPassword(email, "ghost")   // a record whose server wrap is absent
        val c = makeClient(store)

        assertFalse(c.unlockWithDevice(email, password))
        assertTrue((store.currentLevel(email) == DeviceLevel.ONLY_PASSWORD), "the local key must survive a missing server wrap (no self-wipe)")
    }

    @Test fun reTrustReplacesPriorWrap() {
        val d = WrapsDispatcher(floorWrapJson(), wrappedIdentityB64())
        start(d)
        val store = InMemoryDeviceSecurityStore()
        val c = floorUnlock(store)

        c.trustThisDevice(email, password)
        val firstId = store.wrapId(email)!!
        c.trustThisDevice(email, password)
        val secondId = store.wrapId(email)!!

        assertNotEquals(firstId, secondId, "re-trust mints a new wrap id")
        assertFalse(d.hasWrapId(firstId), "the prior DEVICE wrap was deleted")
        assertTrue(d.hasWrapId(secondId))
        assertEquals(1, d.wrapCount("DEVICE"), "no duplicate accumulation")
    }

    @Test fun untrustRemovesWrapAndRecord() {
        val d = WrapsDispatcher(floorWrapJson(), wrappedIdentityB64())
        start(d)
        val store = InMemoryDeviceSecurityStore()
        val c = floorUnlock(store)
        c.trustThisDevice(email, password)

        c.untrustThisDevice(email)
        assertFalse((store.currentLevel(email) == DeviceLevel.ONLY_PASSWORD))
        assertFalse(d.hasWrap("DEVICE"), "the DEVICE wrap was removed server-side")
    }

    @Test fun trustOverCapThrowsTooManyDevices() {
        val d = WrapsDispatcher(floorWrapJson(), wrappedIdentityB64()).apply { conflictOnAdd = true }
        start(d)
        val store = InMemoryDeviceSecurityStore()
        val c = floorUnlock(store)

        assertFailsWith<VeyqError.TooManyDevices> { c.trustThisDevice(email, password) }
        assertFalse((store.currentLevel(email) == DeviceLevel.ONLY_PASSWORD), "no local record when the server rejected the wrap")
    }

    @Test fun enrolRollsBackServerWrapOnCommitFailure() {
        val d = WrapsDispatcher(floorWrapJson(), wrappedIdentityB64())
        start(d)
        val c = floorUnlock(FailingCommitStore())

        assertFailsWith<VeyqError.TrustFailed> { c.trustThisDevice(email, password) }
        assertFalse(d.hasWrap("DEVICE"), "the server wrap was rolled back — no orphan")
    }

    @Test fun deviceUnlock401RefreshesThenUnlocks() {
        val d = WrapsDispatcher(floorWrapJson(), wrappedIdentityB64())
        start(d)
        val store = InMemoryDeviceSecurityStore()
        floorUnlock(store).trustThisDevice(email, password)

        d.failKeyMaterialOnce = true
        val c2 = makeClient(store)
        assertTrue(c2.unlockWithDevice(email, password), "the 401 was rotated and the retry unlocked")
        assertEquals("access-2", c2.session.accessToken, "withRefresh rotated the session before the retry")
    }

    // --- cr-0022 L2/L3 (passwordless) orchestration ---

    private val deviceK = ByteArray(32) { (0x70 + it).toByte() }

    /** The persisted refresh token is sealed under K (cr-0023); unseal it to assert the plaintext stored. */
    private fun unsealStoredToken(store: InMemoryDeviceSecurityStore): String? {
        val stored = store.refreshToken(email) ?: return null
        return try {
            String(V1.symUnwrap(deviceK, Base64.getDecoder().decode(stored), "session-token"), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun enrolResumeUnlock(level: DeviceLevel) {
        val d = WrapsDispatcher(floorWrapJson(), wrappedIdentityB64())
        start(d)
        val store = InMemoryDeviceSecurityStore()

        floorUnlock(store).setPasswordlessLevel(email, level, deviceK)
        assertEquals(level, store.currentLevel(email))
        assertTrue(d.hasWrap("DEVICE"), "a K-wrap was stored ($level)")

        val c2 = makeClient(store)
        assertTrue(c2.resumeSession(email, deviceK), "the persisted token resumed the session ($level)")
        assertTrue(c2.unlockWithKey(email, deviceK), "the device key opened the vault ($level)")
        assertContentEquals(masterKey, c2.vault.masterKey(), "the K-wrap recovered the Master Key ($level)")
    }

    @Test fun biometricEnrolResumeUnlockRoundTrip() = enrolResumeUnlock(DeviceLevel.BIOMETRIC)

    @Test fun noLoginEnrolResumeUnlockRoundTrip() = enrolResumeUnlock(DeviceLevel.NO_LOGIN)

    @Test fun resumeRotatesAndPersistsTheNewToken() {
        start(WrapsDispatcher(floorWrapJson(), wrappedIdentityB64()))
        val store = InMemoryDeviceSecurityStore()
        floorUnlock(store).setPasswordlessLevel(email, DeviceLevel.NO_LOGIN, deviceK)
        assertNotEquals("refresh-1", store.refreshToken(email), "the token is NOT stored in the clear (sealed under K)")
        assertEquals("refresh-1", unsealStoredToken(store), "enrol persisted the live token (sealed)")

        makeClient(store).resumeSession(email, deviceK)
        assertEquals("refresh-2", unsealStoredToken(store), "the rotated refresh token was persisted back, still sealed")
    }

    @Test fun legacyPlaintextTokenIsMigratedToSealedOnResume() {
        // Pre-cr-0023 devices persisted the refresh token in the clear. Resume must still work (lazy
        // migration) and re-seal it afterwards.
        start(WrapsDispatcher(floorWrapJson(), wrappedIdentityB64()))
        val store = InMemoryDeviceSecurityStore()
        floorUnlock(store).setPasswordlessLevel(email, DeviceLevel.NO_LOGIN, deviceK)
        store.updateRefreshToken(email, "refresh-1")   // simulate a legacy PLAINTEXT token
        assertEquals("refresh-1", store.refreshToken(email), "precondition: stored in the clear")

        assertTrue(makeClient(store).resumeSession(email, deviceK), "a legacy plaintext token still resumes")
        assertNotEquals("refresh-2", store.refreshToken(email), "now sealed, not clear")
        assertEquals("refresh-2", unsealStoredToken(store), "the rotated token was re-sealed under K")
    }

    @Test fun levelSwitchReplacesTheConvenienceWrap() {
        val d = WrapsDispatcher(floorWrapJson(), wrappedIdentityB64())
        start(d)
        val store = InMemoryDeviceSecurityStore()
        val c = floorUnlock(store)
        c.setPasswordlessLevel(email, DeviceLevel.BIOMETRIC, deviceK)
        c.setPasswordlessLevel(email, DeviceLevel.NO_LOGIN, deviceK)
        assertEquals(DeviceLevel.NO_LOGIN, store.currentLevel(email))
        assertEquals(1, d.wrapCount("DEVICE"), "one convenience wrap at a time")
    }

    @Test fun unlockWithKeyRejectsAnL1Record() {
        start(WrapsDispatcher(floorWrapJson(), wrappedIdentityB64()))
        val store = InMemoryDeviceSecurityStore()
        val c = floorUnlock(store)
        c.trustThisDevice(email, password)   // L1
        assertFalse(c.unlockWithKey(email, deviceK), "the K-path must not open an L1 wrap")
    }

    @Test fun autoRestoreResumesAndOpensFromSuppliedKey() {
        start(WrapsDispatcher(floorWrapJson(), wrappedIdentityB64()))
        val store = InMemoryDeviceSecurityStore()
        floorUnlock(store).setPasswordlessLevel(email, DeviceLevel.BIOMETRIC, deviceK)

        // :app would release K once (one biometric) and hand it in; autoRestore resumes + opens, no prompt.
        val c2 = makeClient(store)
        assertTrue(c2.autoRestore(email, deviceK), "autoRestore resumed the session + opened the vault from the supplied K")
        assertContentEquals(masterKey, c2.vault.masterKey(), "autoRestore recovered the Master Key")
    }

    @Test fun autoRestoreRejectsAnL1Record() {
        start(WrapsDispatcher(floorWrapJson(), wrappedIdentityB64()))
        val store = InMemoryDeviceSecurityStore()
        val c = floorUnlock(store)
        c.trustThisDevice(email, password)   // L1 — not a passwordless record
        assertFalse(c.autoRestore(email, deviceK), "autoRestore must not run on an L1 record")
    }

    @Test fun autoRestoreWithDeadTokenReturnsFalse() {
        val d = WrapsDispatcher(floorWrapJson(), wrappedIdentityB64())
        start(d)
        val store = InMemoryDeviceSecurityStore()
        floorUnlock(store).setPasswordlessLevel(email, DeviceLevel.NO_LOGIN, deviceK)
        d.failRefresh = true
        assertFalse(makeClient(store).autoRestore(email, deviceK), "a dead token resumes nothing → floor")
    }

    @Test fun resumeSessionWithNoRecordReturnsFalse() {
        start(WrapsDispatcher(floorWrapJson(), wrappedIdentityB64()))
        assertFalse(makeClient(InMemoryDeviceSecurityStore()).resumeSession(email, deviceK))
    }

    @Test fun resumeSessionWithDeadTokenFallsBack() {
        val d = WrapsDispatcher(floorWrapJson(), wrappedIdentityB64())
        start(d)
        val store = InMemoryDeviceSecurityStore()
        floorUnlock(store).setPasswordlessLevel(email, DeviceLevel.NO_LOGIN, deviceK)
        d.failRefresh = true
        assertFalse(makeClient(store).resumeSession(email, deviceK), "a dead token resumes nothing → floor")
    }

    @Test fun passwordlessEnrolRollsBackOnCommitFailure() {
        val d = WrapsDispatcher(floorWrapJson(), wrappedIdentityB64())
        start(d)
        val c = floorUnlock(FailingCommitStore())
        assertFailsWith<VeyqError.TrustFailed> { c.setPasswordlessLevel(email, DeviceLevel.BIOMETRIC, deviceK) }
        assertFalse(d.hasWrap("DEVICE"), "the server wrap was rolled back — no orphan")
    }

    /** A store whose commit always fails — to exercise the enrol rollback. */
    private class FailingCommitStore : DeviceSecurityStore {
        private val factor = ByteArray(32) { (it + 1).toByte() }
        override fun currentLevel(email: String): DeviceLevel? = null
        override fun wrapId(email: String): String? = null
        override fun refreshToken(email: String): String? = null
        override fun updateRefreshToken(email: String, token: String?) {}
        override fun beginEnrol(email: String) = factor
        override fun deviceFactor(email: String): ByteArray? = factor
        override fun commitOnlyPassword(email: String, wrapId: String) = throw RuntimeException("commit failed")
        override fun commitPasswordless(email: String, level: DeviceLevel, wrapId: String, refreshToken: String) =
            throw RuntimeException("commit failed")
        override fun clear(email: String) {}
    }

    /** Stateful dispatcher: serves key material from a mutable wrap list, captures POSTed DEVICE wraps and
     *  serves them back, deletes by id; can simulate a 409 cap or a one-shot 401. */
    private inner class WrapsDispatcher(floorWrap: String, private val identity: String) : Dispatcher() {
        private val wraps = mutableListOf(floorWrap)
        private var nextId = 1
        private val keyMaterialCalls = AtomicInteger(0)
        var conflictOnAdd = false
        @Volatile var failKeyMaterialOnce = false
        @Volatile var failRefresh = false

        @Synchronized fun hasWrap(method: String) = wraps.any { """"method":"$method"""".toRegex().containsMatchIn(it) }
        @Synchronized fun hasWrapId(id: String) = wraps.any { """"id":"$id"""".toRegex().containsMatchIn(it) }
        @Synchronized fun wrapCount(method: String) = wraps.count { """"method":"$method"""".toRegex().containsMatchIn(it) }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path ?: ""
            if (path == "/api/account/key-material" && request.method == "GET") {
                if (failKeyMaterialOnce) { failKeyMaterialOnce = false; return json(401, """{"code":"authentication_failed"}""") }
                val body = synchronized(this) { """{"suiteId":"v1","wraps":[${wraps.joinToString(",")}],"wrappedIdentityPrivateKey":"$identity"}""" }
                return json(200, body)
            }
            if (path == "/api/account/wraps" && request.method == "POST") {
                if (conflictOnAdd) return json(409, """{"code":"too_many_devices"}""")
                val b = request.body.readUtf8()
                val wrappedKey = field(b, "wrappedKey"); val kdfParams = field(b, "kdfParams")
                val id = synchronized(this) {
                    val id = "dev-${nextId++}"
                    wraps.add("""{"id":"$id","method":"DEVICE","wrappedKey":"$wrappedKey","kdfParams":"$kdfParams"}""")
                    id
                }
                return json(201, """{"id":"$id"}""")
            }
            if (path.startsWith("/api/account/wraps/") && request.method == "DELETE") {
                val id = path.removePrefix("/api/account/wraps/")
                synchronized(this) { wraps.removeAll { """"id":"$id"""".toRegex().containsMatchIn(it) } }
                return MockResponse().setResponseCode(204)
            }
            if (path == "/api/token/refresh") {
                if (failRefresh) return json(401, """{"code":"refresh_rejected"}""")
                return json(200, """{"accessToken":"access-2","refreshToken":"refresh-2"}""")
            }
            if (path == "/api/logout") return MockResponse().setResponseCode(204)
            return MockResponse().setResponseCode(404)
        }

        private fun field(body: String, name: String): String =
            """"$name":"([^"]*)"""".toRegex().find(body)?.groupValues?.get(1) ?: ""

        private fun json(code: Int, body: String) =
            MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
