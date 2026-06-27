package com.veyq.client

/**
 * The per-device unlock level (uc.account.set-device-security; ADR 0005 §8–13). L0 = no convenience wrap
 * (the bare floor) is represented by a null level. Generalised from cr-0021's L1-only model (cr-0022).
 */
enum class DeviceLevel { ONLY_PASSWORD, BIOMETRIC, NO_LOGIN }

/**
 * Per-account persistence of the device level + the material the **orchestration** needs (the `:client`
 * view). Lives in `:client` (pure JVM); the real Android impl (`:app` `KeystoreDeviceSecurityStore`) adds
 * the platform-gated key storage (the L1 Keystore HMAC `deviceFactor`; the L2/L3 random key `K`,
 * biometric-gated / ungated), and `:client` tests inject an in-memory double.
 *
 * Split (ADR 0010): `:client` reads the **level / wrapId / refreshToken** (to open a wrap and resume a
 * session); the **biometric flow + K** live in `:app` (`BiometricPrompt` is Activity-bound + async), which
 * hands `:client` the plaintext `K` via `VeyqClient.unlockWithKey`.
 *
 * For L1 the `deviceFactor` is **derived** from a device-bound key, so enrol is two steps:
 * [beginEnrol] (ensure the key, hand back the factor to wrap with) then [commitOnlyPassword] (persist the
 * server id). L2/L3 are committed via [commitPasswordless] after `:app` stores K.
 */
interface DeviceSecurityStore {
    /** The device's current level (null = L0 / unset). */
    fun currentLevel(email: String): DeviceLevel?

    /** The server wrap id to open/remove, or null if none. */
    fun wrapId(email: String): String?

    /** The persisted refresh token for a passwordless device (L2/L3), or null. */
    fun refreshToken(email: String): String?

    /** Update only the persisted refresh token (prompt-free) — tracks refresh-token rotation for L2/L3. */
    fun updateRefreshToken(email: String, token: String?)

    /** Ensure the L1 device-bound key exists and return its `deviceFactor` (stable per device). */
    fun beginEnrol(email: String): ByteArray

    /** The existing L1 `deviceFactor` (for unlock), or null if no device-bound key exists. */
    fun deviceFactor(email: String): ByteArray?

    /** Commit the L1 ("only password") level with its server `wrapId`. */
    fun commitOnlyPassword(email: String, wrapId: String)

    /** Commit a passwordless level (L2/L3) with its server `wrapId` + persisted refresh token (the random
     *  key `K` is stored separately by the `:app` impl). */
    fun commitPasswordless(email: String, level: DeviceLevel, wrapId: String, refreshToken: String)

    /** Remove this account's level + material (untrust / down to L0). Idempotent. */
    fun clear(email: String)
}

/**
 * A never-set no-op store — the default when no platform store is injected (a headless JVM agent, or
 * login/unlock-only tests). `:app` injects the real `KeystoreDeviceSecurityStore`; the enrol paths throw.
 */
object DisabledDeviceSecurityStore : DeviceSecurityStore {
    override fun currentLevel(email: String): DeviceLevel? = null
    override fun wrapId(email: String): String? = null
    override fun refreshToken(email: String): String? = null
    override fun updateRefreshToken(email: String, token: String?) {}
    override fun beginEnrol(email: String): ByteArray = throw IllegalStateException("device security not configured")
    override fun deviceFactor(email: String): ByteArray? = null
    override fun commitOnlyPassword(email: String, wrapId: String) {}
    override fun commitPasswordless(email: String, level: DeviceLevel, wrapId: String, refreshToken: String) {}
    override fun clear(email: String) {}
}
