package com.veyq.client

/**
 * The *unlocked* half of the account: the Master Key + identity private key, in memory only — the Android
 * twin of Apple's `Vault` and web `app/vault.ts`. Auth ≠ unlock: this never lives in [Session] (that gates
 * a session, this opens content), is never persisted, and is cleared on sign-out / background. A fresh app
 * launch re-derives it (re-unlock).
 *
 * The identity private key is held as the **raw unwrapped bytes** (the serialized blob). Parsing it into
 * its X25519 / ML-KEM / Ed25519 components is deferred to the first content/scope slice — ML-KEM is not
 * ported yet (cr-0014 / ADR 0009), and login + unlock need only to *hold* it, not use it.
 */
class Vault {
    @Volatile
    var isUnlocked: Boolean = false
        private set

    private var masterKeyBytes: ByteArray? = null
    private var identityPrivateBytes: ByteArray? = null

    /** Hold the freshly opened Master Key + identity private key for this working session. */
    @Synchronized
    fun open(masterKey: ByteArray, identityPrivate: ByteArray) {
        masterKeyBytes = masterKey
        identityPrivateBytes = identityPrivate
        isUnlocked = true
    }

    /**
     * Drop all key material (sign-out / re-lock). Zero-fills the buffers before releasing them so the keys
     * don't linger on the heap until GC (shortens the window for a heap snapshot to recover a key).
     */
    @Synchronized
    fun clear() {
        masterKeyBytes?.fill(0)
        identityPrivateBytes?.fill(0)
        masterKeyBytes = null
        identityPrivateBytes = null
        isUnlocked = false
    }

    // These hand out a COPY, never the vault's own buffer — `clear()` zero-fills the internal bytes in
    // place, so handing out the live reference would let a held copy read zeroes after sign-out. Callers
    // use their copy transiently and must not persist it.

    /** The unlocked Master Key as a fresh copy, or null while locked. */
    @Synchronized
    fun masterKey(): ByteArray? = masterKeyBytes?.copyOf()

    /** The unlocked identity private key (raw serialized bytes) as a fresh copy, or null while locked. */
    @Synchronized
    fun identityPrivateKey(): ByteArray? = identityPrivateBytes?.copyOf()
}
