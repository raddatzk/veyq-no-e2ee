package com.veyq.client

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** cr-0021 phase 1 — the device-trust store contract, on the in-memory double (the real Keystore is
 *  covered by the in-app round-trip). */
class DeviceTrustStoreTest {
    private val email = "person@veyq.example"

    @Test fun enrolCommitLoadClearRoundTrip() {
        val store = InMemoryDeviceTrustStore()
        assertFalse(store.isTrusted(email))
        assertNull(store.load(email))

        val factor = store.beginEnrol(email)
        assertEquals(32, factor.size)
        assertFalse(store.isTrusted(email)) // a key alone is not "trusted" until a wrap id is committed

        store.commitWrapId(email, "wrap-123")
        assertTrue(store.isTrusted(email))
        val record = store.load(email)!!
        assertEquals("wrap-123", record.wrapId)
        assertContentEquals(factor, record.deviceFactor)

        store.clear(email)
        assertFalse(store.isTrusted(email))
        assertNull(store.load(email))
    }

    @Test fun deviceFactorIsStableAcrossEnrols() {
        val store = InMemoryDeviceTrustStore()
        val first = store.beginEnrol(email)
        val second = store.beginEnrol(email)
        assertContentEquals(first, second, "a device-bound key yields the same factor (re-trust reuses it)")
    }

    @Test fun commitWrapIdReplaces() {
        val store = InMemoryDeviceTrustStore()
        store.beginEnrol(email)
        store.commitWrapId(email, "wrap-old")
        store.commitWrapId(email, "wrap-new")
        assertEquals("wrap-new", store.load(email)!!.wrapId)
    }

    @Test fun recordsAreScopedPerAccount() {
        val store = InMemoryDeviceTrustStore()
        store.beginEnrol("a@veyq.example"); store.commitWrapId("a@veyq.example", "wa")
        assertTrue(store.isTrusted("a@veyq.example"))
        assertFalse(store.isTrusted("b@veyq.example"))
    }

    @Test fun clearIsIdempotentWhenAbsent() {
        InMemoryDeviceTrustStore().clear("nobody@veyq.example")
    }
}
