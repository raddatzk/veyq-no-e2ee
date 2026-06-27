package com.veyq.client

import kotlin.random.Random

/**
 * Test double for [DeviceTrustStore] — in-memory, no Keystore. Used by the device-trust unit tests and the
 * orchestration tests (the real `KeystoreDeviceTrustStore` lives in `:app` and is exercised by the in-app
 * round-trip). `beginEnrol` mints a **stable** random `deviceFactor` per account (a device-bound key never
 * changes), mirroring the Keystore HMAC.
 */
class InMemoryDeviceTrustStore : DeviceTrustStore {
    private val factors = mutableMapOf<String, ByteArray>()
    private val wrapIds = mutableMapOf<String, String>()

    @Synchronized
    override fun isTrusted(email: String): Boolean = wrapIds.containsKey(email)

    @Synchronized
    override fun load(email: String): TrustRecord? {
        val id = wrapIds[email] ?: return null
        return TrustRecord(factors.getValue(email), id)
    }

    @Synchronized
    override fun beginEnrol(email: String): ByteArray = factors.getOrPut(email) { Random.nextBytes(32) }

    @Synchronized
    override fun commitWrapId(email: String, wrapId: String) {
        wrapIds[email] = wrapId
    }

    @Synchronized
    override fun clear(email: String) {
        wrapIds.remove(email)
        factors.remove(email)
    }
}
