package com.veyq.client

/**
 * A trusted device's local material for the **DEVICE wrap** unlock (cr-0021, L1 — "only password"; ADR
 * 0005 §4, §8): the device-bound `deviceFactor` fed into `KEK = Argon2id(deviceFactor + password)`, and the
 * server `wrapId` of this device's DEVICE wrap (the authoritative match in the fetched key material). The
 * Master Key, the KEK, and the Secret Key are **never** stored. On Android the `deviceFactor` is derived
 * from a **non-extractable Keystore HMAC key** (web parity) — its bytes never leave the Keystore.
 */
class TrustRecord(val deviceFactor: ByteArray, val wrapId: String) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is TrustRecord && wrapId == other.wrapId && deviceFactor.contentEquals(other.deviceFactor))

    override fun hashCode(): Int = 31 * deviceFactor.contentHashCode() + wrapId.hashCode()
}

/**
 * Per-account persistence of the device-trust material. Lives in `:client` (the orchestration depends on
 * the interface); the real Android Keystore impl is in `:app` (`KeystoreDeviceTrustStore`), and `:client`
 * tests inject an in-memory double — the pure-JVM module has no Android SDK, and the Keystore is
 * device-bound and entitlement-sensitive anyway (the real impl is exercised by the in-app round-trip).
 *
 * The `deviceFactor` is **derived**, not handed in: it comes from a device-bound key the store owns, so
 * enrol is two steps — [beginEnrol] (ensure the key, hand back the factor to wrap the Master Key with) then
 * [commitWrapId] (persist the server id once the wrap is stored). A device is "trusted" only once a wrap id
 * is committed.
 */
interface DeviceTrustStore {
    /** True once a DEVICE wrap is enrolled for this account on this device. */
    fun isTrusted(email: String): Boolean

    /** The stored record (derived `deviceFactor` + committed `wrapId`), or null if not trusted. */
    fun load(email: String): TrustRecord?

    /** Ensure the device-bound key exists and return its `deviceFactor` (to derive the KEK at enrol time).
     *  Idempotent — a re-trust reuses the same device-bound key, so the factor is stable. */
    fun beginEnrol(email: String): ByteArray

    /** Persist the server `wrapId`, completing enrol (the device is now trusted). */
    fun commitWrapId(email: String, wrapId: String)

    /** Remove this account's wrap id + device-bound key (untrust). Idempotent. */
    fun clear(email: String)
}
