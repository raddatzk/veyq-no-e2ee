import Foundation
import OpenAPIRuntime
import VeyqApi
import VeyqCrypto

// uc.account.login (apple) — operator-blind challenge-response login on the Model B data layer, the
// Apple analog of web `features/account/login/login.ts`. The password never leaves the device: the
// client derives the Ed25519 auth keypair (ADR 0003) from the password + the instance salt the
// challenge carries, signs the server nonce, and stores only the opaque session tokens it gets back.
// Auth ≠ unlock — this gates a session, not content (the vault is phase 3).
public final class VeyqClient: Sendable {
    public let instance: VeyqInstance
    public let session: Session
    public let vault: Vault
    private let deviceSecurity: any DeviceSecurityStore
    private let setup: SetupAPI.Client
    private let auth: AuthAPI.Client
    private let account: AccountAPI.Client

    /// Build a client against an already-bootstrapped instance. `transport` is injectable so tests can
    /// drive the orchestration against a stub without a live server (the default is URLSession);
    /// `deviceSecurity` is injectable so tests use an in-memory double instead of the real Keychain.
    public init(
        instance: VeyqInstance,
        session: Session = Session(),
        vault: Vault = Vault(),
        deviceSecurity: any DeviceSecurityStore = KeychainDeviceSecurityStore(),
        transport: any ClientTransport = VeyqTransport.make()
    ) {
        self.instance = instance
        self.session = session
        self.vault = vault
        self.deviceSecurity = deviceSecurity
        let middlewares: [any ClientMiddleware] = [BearerMiddleware(session: session)]
        self.setup = SetupAPI.Client(serverURL: instance.baseURL, transport: transport, middlewares: middlewares)
        self.auth = AuthAPI.Client(serverURL: instance.baseURL, transport: transport, middlewares: middlewares)
        self.account = AccountAPI.Client(serverURL: instance.baseURL, transport: transport, middlewares: middlewares)
    }

    /// Whether this instance still needs first-run setup. Native never bootstraps (setup is browser-only),
    /// so the connect screen uses this to tell the user to finish setup in a browser rather than offering a
    /// form. Unauthenticated — no session needed.
    public func setupRequired() async throws -> Bool {
        let out = try await setup.getSetupStatus(.init())
        switch out {
        case .ok(let ok):
            return try ok.body.json.setupRequired
        case .undocumented(let status, _):
            throw VeyqError.unexpectedStatus(status)
        }
    }

    /// Log in with email + password. Requests a challenge, derives the auth keypair with the instance
    /// salt the challenge carries, signs the nonce, and stores the issued session. Throws on failure —
    /// a wrong password or unknown account both surface as `.authenticationFailed` (non-enumerable).
    public func login(email: String, password: String) async throws {
        let challengeOut = try await auth.requestLoginChallenge(.init(body: .json(.init(email: email))))
        let challenge: AuthAPI.Components.Schemas.LoginChallenge
        switch challengeOut {
        case .ok(let ok):
            challenge = try ok.body.json
        case .badRequest(let bad):
            throw VeyqError.invalidRequest(try? bad.body.json.code)
        case .undocumented(let status, _):
            throw VeyqError.unexpectedStatus(status)
        }

        let authSalt = V1.deriveAuthSalt(instanceSalt: Data(challenge.instanceSalt.data), email: email)
        let keypair = V1.deriveAuthKeypair(password: password, authSalt: authSalt)
        let signature = V1.authSign(privateKey: keypair.privateKey, message: Data(challenge.nonce.data))

        let verifyOut = try await auth.verifyLogin(
            .init(body: .json(.init(email: email, signature: .init(signature))))
        )
        switch verifyOut {
        case .ok(let ok):
            let s = try ok.body.json
            await session.set(access: s.accessToken, refresh: s.refreshToken, familyId: s.family.id, account: email)
        case .unauthorized:
            throw VeyqError.authenticationFailed
        case .badRequest(let bad):
            throw VeyqError.invalidRequest(try? bad.body.json.code)
        case .undocumented(let status, _):
            throw VeyqError.unexpectedStatus(status)
        }
    }

    /// Rotate the session tokens (silent refresh). On rejection it drops the dead tokens so the session
    /// lands in a clean logged-out state instead of looping 401 → refresh → throw (mirrors the web).
    public func refresh() async throws {
        guard let refreshToken = await session.refreshToken else { throw VeyqError.noSession }
        let out = try await auth.refreshSession(.init(body: .json(.init(refreshToken: refreshToken))))
        switch out {
        case .ok(let ok):
            let pair = try ok.body.json
            await session.set(access: pair.accessToken, refresh: pair.refreshToken)
            await persistRotatedTokenIfPasswordless()
        case .badRequest, .unauthorized, .undocumented:
            await session.clear()
            throw VeyqError.refreshRejected
        }
    }

    /// After a refresh rotates the tokens, keep the **persisted** refresh token in sync for an L2/L3
    /// device — so the next app launch resumes with a live token. The token is **sealed under K**
    /// (cr-0023) so it is never stored in the clear; sealing uses the in-memory K (held since
    /// resume/enrol), so this stays **prompt-free**. No-op for L0/L1, or if K isn't held — we never write a
    /// plaintext token (a stale sealed token just falls back to the floor on next launch, which is safe).
    private func persistRotatedTokenIfPasswordless() async {
        guard let account = await session.account,
              let level = deviceSecurity.currentLevel(email: account),
              level == .biometric || level == .noLogin,
              let newToken = await session.refreshToken,
              let k = await vault.deviceKey() else { return }
        try? deviceSecurity.updateRefreshToken(tokenSeal(newToken, k: k), email: account)
    }

    /// Seal a refresh token under the device wrap key K (cr-0023): `symWrap(K, utf8(token), "session-token")`,
    /// base64. Stored where the plaintext token used to live.
    private func tokenSeal(_ token: String, k: Data) -> String {
        V1.symWrap(kek: k, targetKey: Data(token.utf8), context: "session-token", nonce: secureRandomBytes(12))
            .base64EncodedString()
    }

    /// Open a sealed token, or nil if it isn't a valid `session-token` blob — which is also how the
    /// **lazy migration** of a legacy plaintext token is detected (unseal fails → use the value as-is once,
    /// then the next rotation re-seals it).
    private func tokenUnseal(_ stored: String, k: Data) -> String? {
        guard let blob = Data(base64Encoded: stored),
              let bytes = try? V1.symUnwrap(kek: k, blob: blob, context: "session-token")
        else { return nil }
        return String(data: bytes, encoding: .utf8)
    }

    /// Revoke the current session family and drop local tokens. Idempotent — best-effort on the wire
    /// (a network error still clears locally), the vault clear is wired in phase 3.
    public func logout() async throws {
        _ = try? await auth.logout(.init())
        await session.clear()
        await vault.clear()
    }

    /// Unlock with the password + Secret Key (PASSWORD floor, 2SKD). Fetches the caller's key material
    /// (refreshing the session once on a 401), derives the Unlock KEK on-device, AEAD-unwraps the Master
    /// Key, then the identity private key — both into the in-memory `Vault`. Operator-blind: only
    /// ciphertext is fetched; the password, the Secret Key, the KEK and the unwrapped keys never leave the
    /// device. Throws `.unlockFailed` on a wrong password / Secret Key (one generic failure), or
    /// `.unlockUnavailable` when there is no PASSWORD wrap. Mirrors web `features/account/unlock`.
    public func unlock(password: String, secretKey secretKeyB64: String) async throws {
        let material = try await withRefresh { try await self.fetchKeyMaterial() }

        guard let wrap = material.wraps.first(where: { $0.method == .PASSWORD }),
              let kdfParams = wrap.kdfParams
        else { throw VeyqError.unlockUnavailable }

        let trimmed = secretKeyB64.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let secretKey = Data(base64Encoded: trimmed) else { throw VeyqError.invalidSecretKey }

        let decoded = try V1.decodeKdfParams(Data(kdfParams.data))
        let kek = V1.kekPasswordSecret(
            secretKey: secretKey, password: password, salt: decoded.salt, params: decoded.params
        )
        do {
            let masterKey = try V1.symUnwrap(kek: kek, blob: Data(wrap.wrappedKey.data), context: "master-key")
            let identityPrivate = try V1.symUnwrap(
                kek: masterKey, blob: Data(material.wrappedIdentityPrivateKey.data), context: "identity-priv"
            )
            await vault.open(masterKey: masterKey, identityPrivate: identityPrivate)
        } catch {
            throw VeyqError.unlockFailed
        }
    }

    // MARK: - Device trust (cr-0019, L1 — "only password"; uc.account.trust-device)

    /// Whether this device is at the "only password" level (an L1 DEVICE wrap is enrolled → the login-fold
    /// can unlock with just the password).
    public func isTrusted(email: String) -> Bool { deviceSecurity.currentLevel(email: email) == .onlyPassword }

    /// Routine unlock on a trusted device: open the Master Key from this device's DEVICE wrap with **only
    /// the password** (no Secret Key). Returns `false` when this path is unavailable — no trust record, or
    /// the server has no matching DEVICE wrap — so the caller falls back to the floor; throws
    /// `.unlockFailed` on a wrong password (AEAD tag mismatch). Operator-blind: only ciphertext is fetched;
    /// the `deviceFactor`, password, KEK and unwrapped keys never leave the device.
    ///
    /// Does **not** clear the local record on a missing server wrap (cr-0005 #2): a blind operator must not
    /// be able to withhold the wrap to force every trusted device to wipe its key. Explicit untrust /
    /// re-trust is the only path that clears it.
    public func unlockWithDevice(email: String, password: String) async throws -> Bool {
        guard let record = (try? deviceSecurity.load(email: email)) ?? nil,
              record.level == .onlyPassword else { return false }
        let material = try await withRefresh { try await self.fetchKeyMaterial() }
        guard let wrap = material.wraps.first(where: { $0.id == record.wrapId && $0.method == .DEVICE }),
              let kdfParams = wrap.kdfParams
        else { return false }

        let decoded = try V1.decodeKdfParams(Data(kdfParams.data))
        let kek = V1.kekDevicePassword(
            deviceFactor: record.secret, password: password, salt: decoded.salt, params: decoded.params
        )
        do {
            let masterKey = try V1.symUnwrap(kek: kek, blob: Data(wrap.wrappedKey.data), context: "master-key")
            let identityPrivate = try V1.symUnwrap(
                kek: masterKey, blob: Data(material.wrappedIdentityPrivateKey.data), context: "identity-priv"
            )
            await vault.open(masterKey: masterKey, identityPrivate: identityPrivate)
        } catch {
            throw VeyqError.unlockFailed
        }
        return true
    }

    /// Trust this device: enrol a DEVICE wrap of the **already-unlocked** Master Key under
    /// `KEK = Argon2id(deviceFactor + password)`, store `{deviceFactor, wrapId}` locally. Runs at unlock
    /// time, reusing the known-correct password just typed. **Re-trust replaces** (deletes any prior wrap
    /// first). The **Secret Key is never read or stored** — enrolment wraps the in-memory Master Key.
    ///
    /// On a local-save failure the just-created server wrap is **rolled back** (no orphan) and
    /// `.trustFailed` is thrown; the preceding unlock still stands.
    public func trustThisDevice(email: String, password: String) async throws {
        guard let masterKey = await vault.masterKey() else { throw VeyqError.unlockUnavailable }

        // Re-trust replaces: drop any prior wrap for this device first (best-effort — a stale id is
        // harmless, and the new record overwrites the local one anyway).
        if let prior = (try? deviceSecurity.load(email: email)) ?? nil {
            try? await withRefresh { try await self.deleteWrap(id: prior.wrapId) }
        }

        let deviceFactor = makeDeviceFactor()
        let salt = secureRandomBytes(16)
        let params = V1.argon
        let kek = V1.kekDevicePassword(deviceFactor: deviceFactor, password: password, salt: salt, params: params)
        let wrappedKey = V1.symWrap(kek: kek, targetKey: masterKey, context: "master-key", nonce: secureRandomBytes(12))
        let kdfParams = V1.encodeKdfParams(params, salt: salt)

        let wrapId = try await withRefresh {
            try await self.addDeviceWrap(wrappedKey: wrappedKey, kdfParams: kdfParams)
        }
        do {
            try deviceSecurity.save(
                DeviceSecurityRecord(level: .onlyPassword, wrapId: wrapId, secret: deviceFactor), email: email
            )
        } catch {
            try? await withRefresh { try await self.deleteWrap(id: wrapId) }   // rollback — no orphan
            throw VeyqError.trustFailed
        }
    }

    /// Untrust this device: delete this device's DEVICE wrap server-side (best-effort, idempotent) and
    /// drop the local record. Other devices keep their own wraps; the floor is never removable.
    public func untrustThisDevice(email: String) async throws {
        if let record = (try? deviceSecurity.load(email: email)) ?? nil {
            try? await withRefresh { try await self.deleteWrap(id: record.wrapId) }
        }
        try deviceSecurity.clear(email: email)
    }

    // MARK: - Passwordless levels (cr-0020, L2 biometric / L3 no-login; uc.account.{biometric,auto}-unlock)

    /// The device's current unlock level (from the ungated meta — no biometric prompt). nil = L0/unset.
    public func currentLevel(email: String) -> DeviceLevel? { deviceSecurity.currentLevel(email: email) }

    /// Set this device to a **passwordless** level (L2 biometric / L3 no-login). Wraps the already-unlocked
    /// Master Key under a fresh **random key K** (`symWrap(K, masterKey)` — the K-wrap, stored server-side
    /// as method=DEVICE), and persists `{K, current refresh token}` under the level's gate (K
    /// biometric-gated for L2, ungated for L3; the token always in the ungated meta). **Replaces** any
    /// prior convenience wrap (mutually exclusive). Needs an unlocked vault **and** a live session (the
    /// persisted token is what resumes it without a password). Rolls back the server wrap on a save
    /// failure. The `kdfParams` field is a marker — a K-wrap has no KDF (K is the KEK directly).
    public func setPasswordlessLevel(_ level: DeviceLevel, email: String) async throws {
        precondition(level == .biometric || level == .noLogin, "setPasswordlessLevel is L2/L3 only")
        guard let masterKey = await vault.masterKey() else { throw VeyqError.unlockUnavailable }
        guard let refreshToken = await session.refreshToken else { throw VeyqError.noSession }

        if let prior = (try? deviceSecurity.load(email: email)) ?? nil {
            try? await withRefresh { try await self.deleteWrap(id: prior.wrapId) }
        }

        let k = secureRandomBytes(32)
        let wrappedKey = V1.symWrap(kek: k, targetKey: masterKey, context: "master-key", nonce: secureRandomBytes(12))
        let wrapId = try await withRefresh {
            try await self.addDeviceWrap(wrappedKey: wrappedKey, kdfParams: Data("v1/device-key".utf8))
        }
        do {
            try deviceSecurity.save(
                DeviceSecurityRecord(level: level, wrapId: wrapId, secret: k, refreshToken: tokenSeal(refreshToken, k: k)),
                email: email
            )
        } catch {
            try? await withRefresh { try await self.deleteWrap(id: wrapId) }   // rollback — no orphan
            throw VeyqError.trustFailed
        }
        await vault.holdDeviceKey(k)   // retain K so this session's rotations re-seal the token prompt-free
    }

    /// Resume a persisted session for a passwordless device: read the stored refresh token (for L2 this is
    /// in the ungated meta, so **no biometric prompt** here), install it, and rotate to a live session.
    /// Returns `false` when there is no persisted token; `false` (not throw) when the token is dead — the
    /// caller then falls back to the floor.
    public func resumeSession(email: String) async throws -> Bool {
        guard let record = (try? deviceSecurity.load(email: email)) ?? nil,
              let stored = record.refreshToken else { return false }
        let k = record.secret
        let token = tokenUnseal(stored, k: k) ?? stored   // lazy migration: legacy plaintext unseal-fails
        await vault.holdDeviceKey(k)                       // so the rotation below re-seals prompt-free
        await session.set(access: "", refresh: token, account: email)
        do {
            try await refresh()   // rotates to live tokens + re-seals the new refresh token under K
        } catch {
            await vault.clear()
            return false
        }
        return true
    }

    /// **App-launch auto-restore** for a passwordless device, in a **single** gated read (cr-0023): load the
    /// record once (the one Face ID / fingerprint prompt for L2; free for L3), unseal the token with K,
    /// resume + rotate the session, then open the vault from the **same** K — so there is exactly one
    /// biometric prompt at launch, not the two the separate `resumeSession` + `unlockWithDeviceKey` calls
    /// would cause. Returns false (caller falls back to the floor) when there is no passwordless record, the
    /// token is dead, or the wrap is unusable.
    public func autoRestore(email: String) async throws -> Bool {
        guard let record = (try? deviceSecurity.load(email: email)) ?? nil,
              record.level == .biometric || record.level == .noLogin,
              let stored = record.refreshToken else { return false }
        let k = record.secret
        let token = tokenUnseal(stored, k: k) ?? stored
        await vault.holdDeviceKey(k)
        await session.set(access: "", refresh: token, account: email)
        do {
            try await refresh()
        } catch {
            await vault.clear()
            return false
        }
        return try await openVault(fromRecord: record, k: k)
    }

    /// Open the Master Key from this device's **K-wrap** (L2/L3) — the random key K *is* the KEK, used
    /// directly (no password, no Argon2id). For an L2 device, `load` prompts Face ID to release K. Returns
    /// `false` when unavailable (no passwordless record, or no matching server wrap → fall back to the
    /// floor, **without** self-wiping); throws `.unlockFailed` on an AEAD mismatch.
    public func unlockWithDeviceKey(email: String) async throws -> Bool {
        guard let record = (try? deviceSecurity.load(email: email)) ?? nil,
              record.level == .biometric || record.level == .noLogin else { return false }
        await vault.holdDeviceKey(record.secret)   // retain K for prompt-free rotation this session
        return try await openVault(fromRecord: record, k: record.secret)
    }

    /// Fetch the key material and open the Master Key + identity from a passwordless record using K directly
    /// (the K-wrap KEK). Shared by `unlockWithDeviceKey` and `autoRestore` so the latter reuses an
    /// already-loaded record (no second gated read / prompt). Returns false when the matching server wrap is
    /// missing (→ floor, without self-wiping); throws `.unlockFailed` on an AEAD mismatch.
    private func openVault(fromRecord record: DeviceSecurityRecord, k: Data) async throws -> Bool {
        let material = try await withRefresh { try await self.fetchKeyMaterial() }
        guard let wrap = material.wraps.first(where: { $0.id == record.wrapId && $0.method == .DEVICE })
        else { return false }
        do {
            let masterKey = try V1.symUnwrap(kek: k, blob: Data(wrap.wrappedKey.data), context: "master-key")
            let identityPrivate = try V1.symUnwrap(
                kek: masterKey, blob: Data(material.wrappedIdentityPrivateKey.data), context: "identity-priv"
            )
            await vault.open(masterKey: masterKey, identityPrivate: identityPrivate)
        } catch {
            throw VeyqError.unlockFailed
        }
        return true
    }

    /// POST a DEVICE wrap; returns its server id. A 401 throws `.sessionExpired` (so `withRefresh` rotates
    /// and retries), a 409 throws `.tooManyDevices`, a 400 surfaces the boundary code.
    private func addDeviceWrap(wrappedKey: Data, kdfParams: Data) async throws -> String {
        let body = AccountAPI.Components.Schemas.AddWrapRequest(
            method: .DEVICE, wrappedKey: .init(wrappedKey), kdfParams: .init(kdfParams)
        )
        let out = try await account.addAccountWrap(.init(body: .json(body)))
        switch out {
        case .created(let created):
            return try created.body.json.id
        case .conflict:
            throw VeyqError.tooManyDevices
        case .badRequest(let bad):
            throw VeyqError.invalidRequest(try? bad.body.json.code)
        case .unauthorized:
            throw VeyqError.sessionExpired
        case .undocumented(let status, _):
            throw VeyqError.unexpectedStatus(status)
        }
    }

    /// DELETE a wrap by id (idempotent 204 server-side). A 401 throws `.sessionExpired` for `withRefresh`.
    private func deleteWrap(id: String) async throws {
        let out = try await account.deleteAccountWrap(.init(path: .init(id: id)))
        switch out {
        case .noContent:
            return
        case .unauthorized:
            throw VeyqError.sessionExpired
        case .undocumented(let status, _):
            throw VeyqError.unexpectedStatus(status)
        }
    }

    /// Fetch the caller's wrapped key material. A 401 is surfaced as `.sessionExpired` so `withRefresh`
    /// can rotate-and-retry (the resource analog of the web 401→refresh wiring).
    private func fetchKeyMaterial() async throws -> AccountAPI.Components.Schemas.AccountKeyMaterial {
        let out = try await account.getAccountKeyMaterial(.init())
        switch out {
        case .ok(let ok):
            return try ok.body.json
        case .unauthorized:
            throw VeyqError.sessionExpired
        case .undocumented(let status, _):
            throw VeyqError.unexpectedStatus(status)
        }
    }

    /// Run an authed call, transparently refreshing once on a `.sessionExpired` and retrying — the
    /// rotate-on-401 wiring the phase-3 key-material fetch uses. Resource calls signal a 401 by throwing
    /// `VeyqError.sessionExpired`; this catches it, refreshes, and retries once.
    public func withRefresh<T: Sendable>(_ operation: @Sendable () async throws -> T) async throws -> T {
        do {
            return try await operation()
        } catch VeyqError.sessionExpired {
            guard await session.refreshToken != nil else { throw VeyqError.sessionExpired }
            try await refresh()
            return try await operation()
        }
    }
}
