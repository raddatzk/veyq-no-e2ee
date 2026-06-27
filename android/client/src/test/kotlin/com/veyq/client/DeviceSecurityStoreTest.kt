package com.veyq.client

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** cr-0021/cr-0022 — the device-security store contract, on the in-memory double. */
class DeviceSecurityStoreTest {
    private val email = "person@veyq.example"

    @Test fun l1EnrolCommitClearRoundTrip() {
        val store = InMemoryDeviceSecurityStore()
        assertNull(store.currentLevel(email))
        assertNull(store.wrapId(email))

        val factor = store.beginEnrol(email)
        assertEquals(32, factor.size)
        assertNull(store.currentLevel(email)) // a key alone is not a level until committed

        store.commitOnlyPassword(email, "wrap-123")
        assertEquals(DeviceLevel.ONLY_PASSWORD, store.currentLevel(email))
        assertEquals("wrap-123", store.wrapId(email))
        assertContentEquals(factor, store.deviceFactor(email))

        store.clear(email)
        assertNull(store.currentLevel(email))
        assertNull(store.wrapId(email))
        assertNull(store.deviceFactor(email))
    }

    @Test fun deviceFactorIsStableAcrossEnrols() {
        val store = InMemoryDeviceSecurityStore()
        assertContentEquals(store.beginEnrol(email), store.beginEnrol(email))
    }

    @Test fun passwordlessCommitCarriesLevelWrapAndToken() {
        val store = InMemoryDeviceSecurityStore()
        store.commitPasswordless(email, DeviceLevel.BIOMETRIC, "w", "rt")
        assertEquals(DeviceLevel.BIOMETRIC, store.currentLevel(email))
        assertEquals("w", store.wrapId(email))
        assertEquals("rt", store.refreshToken(email))
    }

    @Test fun updateRefreshTokenTracksRotation() {
        val store = InMemoryDeviceSecurityStore()
        store.commitPasswordless(email, DeviceLevel.NO_LOGIN, "w", "rt-1")
        store.updateRefreshToken(email, "rt-2")
        assertEquals("rt-2", store.refreshToken(email))
    }

    @Test fun recordsAreScopedPerAccount() {
        val store = InMemoryDeviceSecurityStore()
        store.commitOnlyPassword("a@veyq.example", "wa")
        assertEquals(DeviceLevel.ONLY_PASSWORD, store.currentLevel("a@veyq.example"))
        assertNull(store.currentLevel("b@veyq.example"))
    }

    @Test fun clearIsIdempotentWhenAbsent() {
        InMemoryDeviceSecurityStore().clear("nobody@veyq.example")
    }
}
