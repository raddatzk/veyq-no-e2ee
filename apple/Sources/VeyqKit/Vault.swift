import Foundation

// The *unlocked* half of the account: the Master Key + identity private key, in memory only — the Apple
// analog of web `app/vault.ts`. Auth ≠ unlock: this never lives in `Session` (that gates a session, this
// opens content), is never persisted, and is cleared on sign-out / background. A fresh app launch
// re-derives it (re-unlock).
//
// The identity private key is held as the **raw unwrapped bytes** (the serialized blob). Parsing it into
// its X25519 / ML-KEM / Ed25519 components is deferred to the first content/scope slice — ML-KEM is not
// ported on Apple yet (cr-0014 / ADR 0009), and login + unlock need only to *hold* it, not use it.
public actor Vault {
    public private(set) var isUnlocked = false
    private var masterKeyBytes: Data?
    private var identityPrivateBytes: Data?

    public init() {}

    /// Hold the freshly opened Master Key + identity private key for this working session.
    func open(masterKey: Data, identityPrivate: Data) {
        masterKeyBytes = masterKey
        identityPrivateBytes = identityPrivate
        isUnlocked = true
    }

    /// Drop all key material (sign-out / re-lock). Best-effort zeroes the bytes before releasing them, so
    /// the keys don't linger in the heap until GC (Swift can't guarantee no copy was made, but it shortens
    /// the window for a heap snapshot to recover a key after sign-out).
    public func clear() {
        if masterKeyBytes != nil { masterKeyBytes!.resetBytes(in: masterKeyBytes!.startIndex ..< masterKeyBytes!.endIndex) }
        if identityPrivateBytes != nil { identityPrivateBytes!.resetBytes(in: identityPrivateBytes!.startIndex ..< identityPrivateBytes!.endIndex) }
        masterKeyBytes = nil
        identityPrivateBytes = nil
        isUnlocked = false
    }

    // `Data` is a value type, so these hand out a COPY, never the vault's own buffer — `clear()` zero-fills
    // the internal bytes in place, so handing out the live reference would let a held copy read zeroes after
    // sign-out. Callers use their copy transiently and must not persist it.

    /// The unlocked Master Key as a fresh copy, or nil while locked.
    public func masterKey() -> Data? { masterKeyBytes }

    /// The unlocked identity private key (raw serialized bytes) as a fresh copy, or nil while locked.
    public func identityPrivateKey() -> Data? { identityPrivateBytes }
}
