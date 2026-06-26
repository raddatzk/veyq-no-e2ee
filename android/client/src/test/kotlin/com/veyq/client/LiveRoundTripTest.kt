package com.veyq.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Live round-trip against a REAL running instance (cr-0016 phase 5 verification) — the Android twin of
 * Apple's `LiveRoundTripTests`. Skipped unless `VEYQ_LIVE_BASE` points at a bootstrapped instance, so the
 * normal `:client:test` stays hermetic. This is the cross-platform behavioural contract (ADR 0010)
 * exercised for real: the same login → unlock the web/Apple do, against the same backend, over real OkHttp.
 *
 *   VEYQ_LIVE_BASE=http://localhost:8080 \
 *   VEYQ_EMAIL=kevin@veyq.test VEYQ_PASSWORD='…' VEYQ_SECRET='…' \
 *   ./gradlew :client:test --tests '*LiveRoundTripTest'
 */
class LiveRoundTripTest {

    @Test
    fun loginThenUnlockAgainstLiveInstance() {
        val base = System.getenv("VEYQ_LIVE_BASE")
        assumeTrue(base != null, "set VEYQ_LIVE_BASE (+ VEYQ_EMAIL/PASSWORD/SECRET) to run the live round-trip")
        val email = assertNotNull(System.getenv("VEYQ_EMAIL"))
        val password = assertNotNull(System.getenv("VEYQ_PASSWORD"))
        val secret = assertNotNull(System.getenv("VEYQ_SECRET"))

        val client = VeyqClient(VeyqInstance(base!!.trimEnd('/')))

        // The instance is bootstrapped, so setup is not required.
        assertFalse(client.setupRequired(), "instance should already be set up")

        // login → holds a session
        client.login(email = email, password = password)
        assertTrue(client.session.isAuthenticated, "login should hold a session")

        // unlock → opens the vault (Master Key + identity private on-device)
        client.unlock(password = password, secretKey = secret)
        assertTrue(client.vault.isUnlocked, "vault should be unlocked")
        assertEquals(32, client.vault.masterKey()?.size, "Master Key is 32 bytes")
        assertNotNull(client.vault.identityPrivateKey(), "identity private key is held")

        // logout → re-locks
        client.logout()
        assertFalse(client.vault.isUnlocked)
        assertFalse(client.session.isAuthenticated)
    }
}
