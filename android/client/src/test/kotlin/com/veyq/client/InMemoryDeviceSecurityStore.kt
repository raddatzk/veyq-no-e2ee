package com.veyq.client

import kotlin.random.Random

/**
 * Test double for [DeviceSecurityStore] — in-memory, no Keystore. `beginEnrol` mints a **stable** random
 * `deviceFactor` per account (a device-bound key never changes). The real `KeystoreDeviceSecurityStore`
 * lives in `:app` and is exercised by the in-app round-trip.
 */
class InMemoryDeviceSecurityStore : DeviceSecurityStore {
    private class Rec(val factor: ByteArray) {
        var level: DeviceLevel? = null
        var wrapId: String? = null
        var token: String? = null
    }

    private val recs = mutableMapOf<String, Rec>()
    private fun rec(email: String): Rec = recs.getOrPut(email) { Rec(Random.nextBytes(32)) }

    @Synchronized override fun currentLevel(email: String): DeviceLevel? = recs[email]?.level
    @Synchronized override fun wrapId(email: String): String? = recs[email]?.wrapId
    @Synchronized override fun refreshToken(email: String): String? = recs[email]?.token
    @Synchronized override fun updateRefreshToken(email: String, token: String?) { recs[email]?.token = token }
    @Synchronized override fun beginEnrol(email: String): ByteArray = rec(email).factor
    @Synchronized override fun deviceFactor(email: String): ByteArray? = recs[email]?.factor

    @Synchronized override fun commitOnlyPassword(email: String, wrapId: String) {
        rec(email).also { it.level = DeviceLevel.ONLY_PASSWORD; it.wrapId = wrapId }
    }

    @Synchronized override fun commitPasswordless(email: String, level: DeviceLevel, wrapId: String, refreshToken: String) {
        rec(email).also { it.level = level; it.wrapId = wrapId; it.token = refreshToken }
    }

    @Synchronized override fun clear(email: String) { recs.remove(email) }
}
