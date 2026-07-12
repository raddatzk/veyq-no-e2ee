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
    private let scopes: ScopesAPI.Client
    private let content: ContentAPI.Client
    private let media: MediaAPI.Client
    private let blobTransport: any BlobTransport

    /// Build a client against an already-bootstrapped instance. `transport` is injectable so tests can
    /// drive the orchestration against a stub without a live server (the default is URLSession);
    /// `deviceSecurity` is injectable so tests use an in-memory double instead of the real Keychain;
    /// `blobTransport` is the direct-to-store byte path (default URLSession), injectable for hermetic tests.
    public init(
        instance: VeyqInstance,
        session: Session = Session(),
        vault: Vault = Vault(),
        deviceSecurity: any DeviceSecurityStore = KeychainDeviceSecurityStore(),
        transport: any ClientTransport = VeyqTransport.make(),
        blobTransport: any BlobTransport = URLSessionBlobTransport()
    ) {
        self.instance = instance
        self.session = session
        self.vault = vault
        self.deviceSecurity = deviceSecurity
        self.blobTransport = blobTransport
        let middlewares: [any ClientMiddleware] = [BearerMiddleware(session: session)]
        self.setup = SetupAPI.Client(serverURL: instance.baseURL, transport: transport, middlewares: middlewares)
        self.auth = AuthAPI.Client(serverURL: instance.baseURL, transport: transport, middlewares: middlewares)
        self.account = AccountAPI.Client(serverURL: instance.baseURL, transport: transport, middlewares: middlewares)
        self.scopes = ScopesAPI.Client(serverURL: instance.baseURL, transport: transport, middlewares: middlewares)
        self.content = ContentAPI.Client(serverURL: instance.baseURL, transport: transport, middlewares: middlewares)
        self.media = MediaAPI.Client(serverURL: instance.baseURL, transport: transport, middlewares: middlewares)
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
            // refreshToken is optional on the wire (it rides a cookie in the web remember-me transport, cr-0052);
            // the native data layer never opts into cookies, so a null body token is a protocol error.
            guard let refresh = s.refreshToken else { throw VeyqError.authenticationFailed }
            await session.set(access: s.accessToken, refresh: refresh, familyId: s.family.id, account: email)
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
            guard let refresh = pair.refreshToken else { await session.clear(); throw VeyqError.refreshRejected }
            await session.set(access: pair.accessToken, refresh: refresh)
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
        guard deviceSecurity.currentLevel(email: email) == .onlyPassword,
              let wrapId = deviceSecurity.wrapId(email: email),
              let deviceFactor = deviceSecurity.deviceFactor(email: email) else { return false }
        let material = try await withRefresh { try await self.fetchKeyMaterial() }
        guard let wrap = material.wraps.first(where: { $0.id == wrapId && $0.method == .DEVICE }),
              let kdfParams = wrap.kdfParams
        else { return false }

        let decoded = try V1.decodeKdfParams(Data(kdfParams.data))
        let kek = V1.kekDevicePassword(
            deviceFactor: deviceFactor, password: password, salt: decoded.salt, params: decoded.params
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
        // harmless, and the new commit overwrites the local meta anyway).
        if let priorId = deviceSecurity.wrapId(email: email) {
            try? await withRefresh { try await self.deleteWrap(id: priorId) }
        }

        let deviceFactor = try deviceSecurity.beginEnrol(email: email)
        let salt = secureRandomBytes(16)
        let params = V1.argon
        let kek = V1.kekDevicePassword(deviceFactor: deviceFactor, password: password, salt: salt, params: params)
        let wrappedKey = V1.symWrap(kek: kek, targetKey: masterKey, context: "master-key", nonce: secureRandomBytes(12))
        let kdfParams = V1.encodeKdfParams(params, salt: salt)

        let wrapId = try await withRefresh {
            try await self.addDeviceWrap(wrappedKey: wrappedKey, kdfParams: kdfParams)
        }
        do {
            try deviceSecurity.commitOnlyPassword(wrapId: wrapId, email: email)
        } catch {
            try? await withRefresh { try await self.deleteWrap(id: wrapId) }   // rollback — no orphan
            throw VeyqError.trustFailed
        }
    }

    /// Untrust this device: delete this device's DEVICE wrap server-side (best-effort, idempotent) and
    /// drop the local record. Other devices keep their own wraps; the floor is never removable.
    public func untrustThisDevice(email: String) async throws {
        if let wrapId = deviceSecurity.wrapId(email: email) {
            try? await withRefresh { try await self.deleteWrap(id: wrapId) }
        }
        try deviceSecurity.clear(email: email)
    }

    // MARK: - Passwordless levels (cr-0020, L2 biometric / L3 no-login; uc.account.{biometric,auto}-unlock)

    /// The device's current unlock level (from the ungated meta — no biometric prompt). nil = L0/unset.
    public func currentLevel(email: String) -> DeviceLevel? { deviceSecurity.currentLevel(email: email) }

    /// Set this device to a **passwordless** level (L2 biometric / L3 no-login). The **app layer** has
    /// already created + stored the random key `K` behind the native gate (biometric for L2, ungated for L3)
    /// and passes it in (ADR 0011). This wraps the already-unlocked Master Key under `K`
    /// (`symWrap(K, masterKey)` — the K-wrap, stored server-side as method=DEVICE) and commits
    /// `{level, wrapId, sealed refresh token}` to the ungated meta. **Replaces** any prior convenience wrap
    /// (mutually exclusive). Needs an unlocked vault **and** a live session (the persisted token is what
    /// resumes it without a password). Rolls back the server wrap on a commit failure. The `kdfParams` field
    /// is a marker — a K-wrap has no KDF (K is the KEK directly).
    public func setPasswordlessLevel(_ level: DeviceLevel, email: String, k: Data) async throws {
        precondition(level == .biometric || level == .noLogin, "setPasswordlessLevel is L2/L3 only")
        guard let masterKey = await vault.masterKey() else { throw VeyqError.unlockUnavailable }
        guard let refreshToken = await session.refreshToken else { throw VeyqError.noSession }

        if let priorId = deviceSecurity.wrapId(email: email) {
            try? await withRefresh { try await self.deleteWrap(id: priorId) }
        }

        let wrappedKey = V1.symWrap(kek: k, targetKey: masterKey, context: "master-key", nonce: secureRandomBytes(12))
        let wrapId = try await withRefresh {
            try await self.addDeviceWrap(wrappedKey: wrappedKey, kdfParams: Data("v1/device-key".utf8))
        }
        do {
            try deviceSecurity.commitPasswordless(
                level: level, wrapId: wrapId, refreshToken: tokenSeal(refreshToken, k: k), email: email
            )
        } catch {
            try? await withRefresh { try await self.deleteWrap(id: wrapId) }   // rollback — no orphan
            throw VeyqError.trustFailed
        }
        await vault.holdDeviceKey(k)   // retain K so this session's rotations re-seal the token prompt-free
    }

    /// Resume a persisted session for a passwordless device with the device key `K` the **app layer** has
    /// already released (ADR 0011 — no prompt here): read the stored (sealed) refresh token from the ungated
    /// meta, unseal it with `K`, install it, and rotate to a live session. Returns `false` when there is no
    /// persisted token; `false` (not throw) when the token is dead — the caller then falls back to the floor.
    public func resumeSession(email: String, k: Data) async throws -> Bool {
        guard let stored = deviceSecurity.refreshToken(email: email) else { return false }
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

    /// **App-launch auto-restore** for a passwordless device (ADR 0011): the **app layer** releases `K`
    /// **once** (the single Face ID / fingerprint gesture for L2; free for L3) and hands it in; this resumes
    /// the session and opens the vault from that same `K`, **neither step prompting**. So the single prompt
    /// is an app-layer property, identical to Android. Returns false (caller falls back to the floor) when
    /// there is no passwordless record, the token is dead, or the wrap is unusable.
    public func autoRestore(email: String, k: Data) async throws -> Bool {
        let level = deviceSecurity.currentLevel(email: email)
        guard level == .biometric || level == .noLogin else { return false }
        guard try await resumeSession(email: email, k: k) else { return false }
        return try await unlockWithDeviceKey(email: email, k: k)
    }

    /// Open the Master Key from this device's **K-wrap** (L2/L3) with the device key `K` the **app layer**
    /// released — `K` *is* the KEK, used directly (no password, no Argon2id), no prompt here (ADR 0011).
    /// Returns `false` when unavailable (no passwordless record, or no matching server wrap → fall back to
    /// the floor, **without** self-wiping); throws `.unlockFailed` on an AEAD mismatch.
    public func unlockWithDeviceKey(email: String, k: Data) async throws -> Bool {
        let level = deviceSecurity.currentLevel(email: email)
        guard level == .biometric || level == .noLogin,
              let wrapId = deviceSecurity.wrapId(email: email) else { return false }
        await vault.holdDeviceKey(k)   // retain K for prompt-free rotation this session
        return try await openVault(wrapId: wrapId, k: k)
    }

    /// Fetch the key material and open the Master Key + identity from this device's K-wrap using `K` directly
    /// (the K-wrap KEK). Shared by `unlockWithDeviceKey` and `autoRestore`. Returns false when the matching
    /// server wrap is missing (→ floor, without self-wiping); throws `.unlockFailed` on an AEAD mismatch.
    private func openVault(wrapId: String, k: Data) async throws -> Bool {
        let material = try await withRefresh { try await self.fetchKeyMaterial() }
        guard let wrap = material.wraps.first(where: { $0.id == wrapId && $0.method == .DEVICE })
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
        case .unprocessableContent:
            throw VeyqError.unexpectedStatus(422)
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

    // MARK: - Personal scope (cr-0026, uc.spaces.personal-scope)

    /// The caller's scopes in the active tenant (each with the caller's own ciphertext grant).
    public func listMyScopes() async throws -> [ScopesAPI.Components.Schemas.ScopeSummary] {
        try await withRefresh { try await self.fetchScopes() }
    }

    /// Ensure this member has a PERSONAL scope in the active tenant — auto-birth, operator-blind: generate
    /// the scope key, seal it to our **own** identity, and POST only the ciphertext grant. No-op if one
    /// already exists. Returns whether a scope was created. Needs an unlocked vault (the identity bundle).
    @discardableResult
    public func ensurePersonalScope() async throws -> Bool {
        if try await listMyScopes().contains(where: { $0.kind == .PERSONAL }) { return false }
        guard let idBytes = await vault.identityPrivateKey() else { throw VeyqError.unlockUnavailable }
        let priv = try V1.deserializeIdentityPrivate(idBytes)
        let scopeKey = secureRandomBytes(32)
        let grant = try V1.asymWrap(pk: V1.hybridPublic(priv), targetKey: scopeKey,
                                    context: "scope-grant", nonce: secureRandomBytes(12))
        try await withRefresh { try await self.postPersonalScope(grant) }
        return true
    }

    /// Open the member's personal scope → the in-memory scope key (asymUnwrap our **own** grant with the
    /// vaulted identity), or nil when there is none / the vault is locked.
    public func openPersonalScope() async throws -> Data? {
        guard let idBytes = await vault.identityPrivateKey() else { return nil }
        let priv = try V1.deserializeIdentityPrivate(idBytes)
        guard let personal = try await listMyScopes().first(where: { $0.kind == .PERSONAL }) else { return nil }
        return try V1.asymUnwrap(sk: V1.hybridSecret(priv), pk: V1.hybridPublic(priv),
                                 blob: Data(personal.wrappedGrant.data), context: "scope-grant")
    }

    /// GET the caller's scopes. A 401 surfaces as `.sessionExpired` so `withRefresh` rotates-and-retries.
    private func fetchScopes() async throws -> [ScopesAPI.Components.Schemas.ScopeSummary] {
        let out = try await scopes.listMyScopes(.init())
        switch out {
        case .ok(let ok): return try ok.body.json
        case .notFound: throw VeyqError.scopeTenantUnavailable
        case .unauthorized: throw VeyqError.sessionExpired
        case .undocumented(let status, _): throw VeyqError.unexpectedStatus(status)
        }
    }

    /// POST the personal-scope grant (opaque ciphertext). 409 → already exists, 404 → no tenant context.
    private func postPersonalScope(_ grant: Data) async throws {
        let body = ScopesAPI.Components.Schemas.CreatePersonalScopeRequest(wrappedGrant: .init(grant))
        let out = try await scopes.createPersonalScope(.init(body: .json(body)))
        switch out {
        case .created: return
        case .conflict: throw VeyqError.personalScopeExists
        case .notFound: throw VeyqError.scopeTenantUnavailable
        case .badRequest(let bad): throw VeyqError.invalidRequest(try? bad.body.json.code)
        case .unauthorized: throw VeyqError.sessionExpired
        case .undocumented(let status, _): throw VeyqError.unexpectedStatus(status)
        }
    }

    // MARK: - Memory tree (cr-0027, en.memory.tree)

    /// A decrypted tree node for the UI. `name` is opened on-device under `K_SC`; the server never saw it.
    /// `wrappedContentKey` is carried verbatim (still sealed) — open it with `openMemory` only when needed.
    public struct TreeNode: Sendable, Equatable {
        public let id: String
        public let parentId: String?
        public let kind: ContentAPI.Components.Schemas.NodeKind
        public let orderIndex: Int
        public let name: String
        public let wrappedContentKey: Data?

        public init(id: String, parentId: String?, kind: ContentAPI.Components.Schemas.NodeKind,
                    orderIndex: Int, name: String, wrappedContentKey: Data?) {
            self.id = id
            self.parentId = parentId
            self.kind = kind
            self.orderIndex = orderIndex
            self.name = name
            self.wrappedContentKey = wrappedContentKey
        }
    }

    /// List the scope's tree with names decrypted under `scopeKey` (K_SC).
    public func listTree(scopeId: String, scopeKey: Data) async throws -> [TreeNode] {
        let rows = try await withRefresh { try await self.fetchNodes(scopeId) }
        return try rows.map { r in
            TreeNode(
                id: r.id,
                parentId: r.parentId,
                kind: r.kind,
                orderIndex: Int(r.orderIndex),
                name: try Self.openName(scopeKey, Data(r.encryptedName.data)),
                wrappedContentKey: r.wrappedContentKey.map { Data($0.data) }
            )
        }
    }

    /// Create a FOLDER (organization only — no content key). Returns its node id.
    @discardableResult
    public func createFolder(scopeId: String, scopeKey: Data, name: String, parentId: String? = nil) async throws -> String {
        try await createNode(scopeId: scopeId, scopeKey: scopeKey, name: name, parentId: parentId,
                             kind: .FOLDER, wrappedContentKey: nil).0
    }

    /// Create a MEMORY — births a fresh random `K_mem` wrapped under `K_SC` (the share grain, ADR 0012).
    /// Returns the node id + the in-memory `K_mem`.
    public func createMemory(scopeId: String, scopeKey: Data, name: String, parentId: String? = nil) async throws -> (nodeId: String, memoryKey: Data) {
        let memoryKey = secureRandomBytes(32)
        let wrapped = V1.symWrap(kek: scopeKey, targetKey: memoryKey, context: Self.kmemCtx, nonce: secureRandomBytes(12))
        let (nodeId, _) = try await createNode(scopeId: scopeId, scopeKey: scopeKey, name: name, parentId: parentId,
                                               kind: .MEMORY, wrappedContentKey: wrapped)
        return (nodeId, memoryKey)
    }

    /// Open a Memory's content key (`K_mem`) from its node, or nil if it carries none.
    public func openMemory(scopeKey: Data, node: TreeNode) throws -> Data? {
        guard let wrapped = node.wrappedContentKey else { return nil }
        return try V1.symUnwrap(kek: scopeKey, blob: wrapped, context: Self.kmemCtx)
    }

    /// Rename a node — re-seal the name + recompute the slug for the (unchanged) parent.
    public func renameNode(scopeId: String, scopeKey: Data, node: TreeNode, newName: String) async throws {
        let parentSlot = node.parentId ?? scopeId
        let body = ContentAPI.Components.Schemas.UpdateNodeRequest(
            encryptedName: .init(Self.sealName(scopeKey, newName)),
            slugToken: .init(V1.slugToken(contentKey: scopeKey, parentSlot: parentSlot, slug: Self.slugify(newName)))
        )
        try await withRefresh { try await self.patchNode(scopeId, node.id, body) }
    }

    /// Delete a node and its subtree.
    public func deleteNode(scopeId: String, nodeId: String) async throws {
        try await withRefresh { try await self.removeNode(scopeId, nodeId) }
    }

    // MARK: tree internals

    private static let nameCtx = "node-name"
    private static let kmemCtx = "content-key"

    /// Minimal slug: NFC, lowercased, trimmed, whitespace → "-". Deterministic so the same name → same token.
    static func slugify(_ name: String) -> String {
        let collapsed = name.precomposedStringWithCanonicalMapping.lowercased()
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .split(whereSeparator: { $0.isWhitespace }).joined(separator: "-")
        return collapsed
    }

    static func sealName(_ scopeKey: Data, _ name: String) -> Data {
        V1.symWrap(kek: scopeKey, targetKey: Data(name.utf8), context: nameCtx, nonce: secureRandomBytes(12))
    }
    static func openName(_ scopeKey: Data, _ blob: Data) throws -> String {
        String(decoding: try V1.symUnwrap(kek: scopeKey, blob: blob, context: nameCtx), as: UTF8.self)
    }

    /// Shared create path for FOLDER/MEMORY — seal the name under `K_SC`, blind the slug, POST only ciphertext.
    private func createNode(
        scopeId: String, scopeKey: Data, name: String, parentId: String?,
        kind: ContentAPI.Components.Schemas.NodeKind, wrappedContentKey: Data?
    ) async throws -> (String, Void) {
        let parentSlot = parentId ?? scopeId
        let body = ContentAPI.Components.Schemas.CreateNodeRequest(
            parentId: parentId,
            kind: kind,
            encryptedName: .init(Self.sealName(scopeKey, name)),
            slugToken: .init(V1.slugToken(contentKey: scopeKey, parentSlot: parentSlot, slug: Self.slugify(name))),
            orderIndex: 0,
            wrappedContentKey: wrappedContentKey.map { .init($0) }
        )
        let nodeId = try await withRefresh { try await self.postNode(scopeId, body) }
        return (nodeId, ())
    }

    private func fetchNodes(_ scopeId: String) async throws -> [ContentAPI.Components.Schemas.NodeSummary] {
        let out = try await content.listNodes(.init(path: .init(scopeId: scopeId)))
        switch out {
        case .ok(let ok): return try ok.body.json
        case .notFound: throw VeyqError.scopeNotFound
        case .unauthorized: throw VeyqError.sessionExpired
        case .undocumented(let status, _): throw VeyqError.unexpectedStatus(status)
        }
    }

    private func postNode(_ scopeId: String, _ body: ContentAPI.Components.Schemas.CreateNodeRequest) async throws -> String {
        let out = try await content.createNode(.init(path: .init(scopeId: scopeId), body: .json(body)))
        switch out {
        case .created(let created): return try created.body.json.nodeId
        case .conflict: throw VeyqError.slugTaken
        case .unprocessableContent: throw VeyqError.illegalStructure
        case .notFound: throw VeyqError.scopeNotFound
        case .badRequest(let bad): throw VeyqError.invalidRequest(try? bad.body.json.code)
        case .unauthorized: throw VeyqError.sessionExpired
        case .undocumented(let status, _): throw VeyqError.unexpectedStatus(status)
        }
    }

    private func patchNode(_ scopeId: String, _ nodeId: String, _ body: ContentAPI.Components.Schemas.UpdateNodeRequest) async throws {
        let out = try await content.updateNode(.init(path: .init(scopeId: scopeId, nodeId: nodeId), body: .json(body)))
        switch out {
        case .noContent: return
        case .conflict: throw VeyqError.slugTaken
        case .unprocessableContent: throw VeyqError.illegalStructure
        case .notFound: throw VeyqError.nodeNotFound
        case .badRequest(let bad): throw VeyqError.invalidRequest(try? bad.body.json.code)
        case .unauthorized: throw VeyqError.sessionExpired
        case .undocumented(let status, _): throw VeyqError.unexpectedStatus(status)
        }
    }

    private func removeNode(_ scopeId: String, _ nodeId: String) async throws {
        let out = try await content.deleteNode(.init(path: .init(scopeId: scopeId, nodeId: nodeId)))
        switch out {
        case .noContent: return
        case .notFound: throw VeyqError.nodeNotFound
        case .conflict: throw VeyqError.unexpectedStatus(409)
        case .unauthorized: throw VeyqError.sessionExpired
        case .undocumented(let status, _): throw VeyqError.unexpectedStatus(status)
        }
    }

    // MARK: - Media blobs (cr-0028, en.memory.media)

    private static let kblobCtx = "blob-key"
    /// Plaintext bytes per chunk before sealing (each frame adds a 12-byte nonce + a 16-byte tag).
    public static let chunkSize = 256 * 1024

    /// Everything needed to open one blob: its id + the metadata + the `K_blob` wrap (under `K_mem`).
    public struct BlobRef: Sendable, Equatable {
        public let blobId: String
        public let chunkCount: Int
        public let byteSize: Int
        public let wrappedBlobKey: Data
    }

    /// One role-tagged derivative tier (THUMBNAIL/PREVIEW) of a media — its own blob with its own wrap.
    public struct MediaDerivative: Sendable, Equatable {
        public let kind: MediaAPI.Components.Schemas.MediaDerivativeKind
        public let ref: BlobRef
    }

    /// Which tier of a media to fetch — grid (thumbnail), lightbox (preview), or the pristine original.
    public enum MediaTier: Sendable { case grid, lightbox, original }

    /// A node's media for read-back: the **original** blob ref + its role-tagged derivative tiers. A gallery
    /// fetches `grid` (thumbnail) for the grid, `lightbox` (preview) for the lightbox, and `original` only on
    /// download — never pulling the full original to render a grid (en.memory.derivatives).
    public struct MediaItem: Sendable, Equatable {
        public let mediaId: String
        public let original: BlobRef
        public let derivatives: [MediaDerivative]

        /// Grid tier (small): the thumbnail derivative, or the original when a media has none.
        public var gridRef: BlobRef { derivatives.first { $0.kind == .THUMBNAIL }?.ref ?? original }
        /// Lightbox tier (medium): the preview derivative, or the original when a media has none.
        public var lightboxRef: BlobRef { derivatives.first { $0.kind == .PREVIEW }?.ref ?? original }
        /// Download tier: always the pristine original.
        public var downloadRef: BlobRef { original }

        func ref(for tier: MediaTier) -> BlobRef {
            switch tier {
            case .grid: return gridRef
            case .lightbox: return lightboxRef
            case .original: return downloadRef
            }
        }
    }

    /// A blob sealed and ready to upload: the wrap + the ciphertext frames + the declared shape.
    struct SealedBlob {
        let wrappedBlobKey: Data
        let frames: [Data]
        var chunkCount: Int { frames.count }
        var byteSize: Int { frames.reduce(0) { $0 + $1.count } }
    }

    /// Generate the original + its role-tagged derivatives, seal **each under its own** fresh random `K_blob`
    /// (wrapped under `K_mem`), upload all ciphertext chunks direct-to-store in **one batch**, and attach the
    /// result to `nodeId` (a MEMORY/CHAPTER) as one role-tagged media. For an image the deriver produces a
    /// thumbnail + preview downscale; for a video, one first-frame poster reused for both tiers; any other
    /// type attaches the original only (the gallery falls back to an icon). `deriver` is injectable
    /// (defaulting to the native ImageIO/AVFoundation one) so the wiring is unit-testable. Returns the
    /// **original**'s ids. Operator-blind: bytes never touch the backend.
    @discardableResult
    public func addMedia(
        scopeId: String, nodeId: String, memoryKey: Data, data: Data, mediaType: String,
        deriver: any MediaDeriver = NativeMediaDeriver()
    ) async throws -> (blobId: String, mediaId: String) {
        let tiers = try await Self.deriveTiers(data: data, mediaType: mediaType, deriver: deriver)
        // index 0 = original; the rest are the role-tagged derivatives, in order
        let sealedTiers = tiers.map { (kind: $0.kind, sealed: Self.sealBlob(memoryKey: memoryKey, data: $0.bytes)) }
        let sealedAll = [Self.sealBlob(memoryKey: memoryKey, data: data)] + sealedTiers.map { $0.sealed }

        // start — reserve one upload session + presign one PUT per chunk, per blob
        let started = try await withRefresh { try await self.startUploads(scopeId, blobs: sealedAll) }
        guard started.count == sealedAll.count else { throw VeyqError.mediaItemFailed(nil) }

        var uploadIds: [String] = []
        for (i, result) in started.enumerated() {
            if let err = result.error { throw VeyqError.mediaItemFailed(err) }
            guard let uploadId = result.uploadId, let puts = result.presignedPuts else { throw VeyqError.storeUnavailable }
            uploadIds.append(uploadId)
            // PUT each sealed frame of this blob straight to the store
            for ref in puts.sorted(by: { $0.index < $1.index }) {
                guard let url = URL(string: ref.url) else { throw VeyqError.blobTransferFailed }
                try await blobTransport.put(url, sealedAll[i].frames[Int(ref.index)])
            }
        }

        // complete — one logical media: the original upload + its role-tagged derivative uploads. The backend
        // stats every blob's objects (declare-then-verify) and attaches the media atomically.
        let originalUploadId = uploadIds[0]
        let derivatives = zip(sealedTiers, uploadIds.dropFirst()).map { (kind: $0.0.kind, uploadId: $0.1) }
        let done = try await withRefresh {
            try await self.completeMedia(scopeId, originalUploadId: originalUploadId, nodeId: nodeId, derivatives: derivatives)
        }
        guard let m = done.first else { throw VeyqError.mediaItemFailed(nil) }
        if let err = m.error { throw VeyqError.mediaItemFailed(err) }
        guard let blobId = m.blobId, let mediaId = m.mediaId else { throw VeyqError.mediaItemFailed(nil) }
        return (blobId, mediaId)
    }

    /// List a node's media (opaque metadata + the K_blob wraps for the original + every derivative tier).
    public func listMedia(scopeId: String, nodeId: String) async throws -> [MediaItem] {
        let rows = try await withRefresh { try await self.fetchNodeMedia(scopeId, nodeId) }
        return rows.map { s in
            MediaItem(
                mediaId: s.mediaId,
                original: BlobRef(blobId: s.blobId, chunkCount: Int(s.chunkCount),
                                  byteSize: Int(s.byteSize), wrappedBlobKey: Data(s.wrappedBlobKey.data)),
                derivatives: (s.derivatives ?? []).map { d in
                    MediaDerivative(kind: d.kind,
                                    ref: BlobRef(blobId: d.blobId, chunkCount: Int(d.chunkCount),
                                                 byteSize: Int(d.byteSize), wrappedBlobKey: Data(d.wrappedBlobKey.data)))
                }
            )
        }
    }

    /// Open one tier of a media: pick the blob ref (grid=thumbnail / lightbox=preview / original), unwrap its
    /// `K_blob` under `K_mem`, presign the chunk GETs, fetch the ciphertext frames direct-from-store, and
    /// `openFrame` them back to plaintext (the AAD position check applies). Defaults to the original.
    public func openMedia(scopeId: String, memoryKey: Data, media item: MediaItem, tier: MediaTier = .original) async throws -> Data {
        try await openBlob(scopeId: scopeId, memoryKey: memoryKey, ref: item.ref(for: tier))
    }

    /// Open a single blob ref (any tier): unwrap `K_blob`, presign the chunk GETs, fetch + `openFrame` them.
    public func openBlob(scopeId: String, memoryKey: Data, ref: BlobRef) async throws -> Data {
        let blobKey = try V1.symUnwrap(kek: memoryKey, blob: ref.wrappedBlobKey, context: Self.kblobCtx)

        let gets = try await withRefresh { try await self.presignDownload(scopeId, blobId: ref.blobId) }
        guard let d = gets.first else { throw VeyqError.mediaItemFailed(nil) }
        if let err = d.error { throw VeyqError.mediaItemFailed(err) }
        guard let urls = d.presignedGets else { throw VeyqError.storeUnavailable }

        let ordered = urls.sorted(by: { $0.index < $1.index })
        var out = Data()
        for (i, ref) in ordered.enumerated() {
            guard let url = URL(string: ref.url) else { throw VeyqError.blobTransferFailed }
            let frame = try await blobTransport.get(url)
            out.append(try V1.openFrame(contentKey: blobKey, index: i, total: ordered.count, frame: frame))
        }
        return out
    }

    // MARK: media internals

    /// Generate the role-tagged derivatives for a source (image: two downscales; video: one poster reused for
    /// both; other: none). The pixel stage is injectable so the role/batch wiring is unit-testable.
    static func deriveTiers(data: Data, mediaType: String, deriver: any MediaDeriver)
        async throws -> [(kind: MediaAPI.Components.Schemas.MediaDerivativeKind, bytes: Data)]
    {
        if mediaType.hasPrefix("image/") {
            let thumbnail = try await deriver.deriveImage(data, maxEdge: DerivativeSpec.thumbnail.maxEdge, quality: DerivativeSpec.thumbnail.quality)
            let preview = try await deriver.deriveImage(data, maxEdge: DerivativeSpec.preview.maxEdge, quality: DerivativeSpec.preview.quality)
            return [(.THUMBNAIL, thumbnail), (.PREVIEW, preview)]
        }
        if mediaType.hasPrefix("video/") {
            let poster = try await deriver.deriveVideoPoster(data)   // one poster serves both tiers
            return [(.THUMBNAIL, poster), (.PREVIEW, poster)]
        }
        return []   // no derivable frame → original only (the gallery falls back to an icon)
    }

    /// Seal `data` as a media blob under a fresh random `K_blob` (wrapped under `K_mem`).
    static func sealBlob(memoryKey: Data, data: Data) -> SealedBlob {
        let blobKey = secureRandomBytes(32)
        let wrappedBlobKey = V1.symWrap(kek: memoryKey, targetKey: blobKey, context: kblobCtx, nonce: secureRandomBytes(12))
        let chunks = chunkify(data)
        let total = chunks.count
        let frames = chunks.enumerated().map { i, chunk in
            V1.sealFrame(contentKey: blobKey, index: i, total: total, plaintext: chunk, nonce: secureRandomBytes(12))
        }
        return SealedBlob(wrappedBlobKey: wrappedBlobKey, frames: frames)
    }

    /// Split plaintext into ≥1 chunks of at most `chunkSize` bytes (a 0-byte blob is a single empty chunk).
    static func chunkify(_ data: Data) -> [Data] {
        if data.isEmpty { return [Data()] }
        var out: [Data] = []
        var off = data.startIndex
        while off < data.endIndex {
            let end = data.index(off, offsetBy: chunkSize, limitedBy: data.endIndex) ?? data.endIndex
            out.append(data.subdata(in: off ..< end))
            off = end
        }
        return out
    }

    private func startUploads(_ scopeId: String, blobs: [SealedBlob])
        async throws -> [MediaAPI.Components.Schemas.BlobUploadResultItem]
    {
        let items = blobs.map {
            MediaAPI.Components.Schemas.BlobUploadRequestItem(
                chunkCount: Int32($0.chunkCount), byteSize: Int64($0.byteSize), wrappedBlobKey: .init($0.wrappedBlobKey)
            )
        }
        let out = try await media.startBlobUploads(.init(path: .init(scopeId: scopeId), body: .json(.init(items: items))))
        switch out {
        case .ok(let ok): return try ok.body.json.results
        case .notFound: throw VeyqError.scopeNotFound
        case .serviceUnavailable: throw VeyqError.storeUnavailable
        case .badRequest(let bad): throw VeyqError.invalidRequest(try? bad.body.json.code)
        case .unauthorized: throw VeyqError.sessionExpired
        case .undocumented(let status, _): throw VeyqError.unexpectedStatus(status)
        }
    }

    private func completeMedia(
        _ scopeId: String, originalUploadId: String, nodeId: String,
        derivatives: [(kind: MediaAPI.Components.Schemas.MediaDerivativeKind, uploadId: String)]
    ) async throws -> [MediaAPI.Components.Schemas.MediaResultItem] {
        let derivativeUploads = derivatives.map {
            MediaAPI.Components.Schemas.MediaDerivativeUpload(kind: $0.kind, uploadId: $0.uploadId)
        }
        let item = MediaAPI.Components.Schemas.MediaRequestItem(
            originalUploadId: originalUploadId, nodeId: nodeId, derivatives: derivativeUploads
        )
        let out = try await media.completeMedia(.init(path: .init(scopeId: scopeId), body: .json(.init(items: [item]))))
        switch out {
        case .ok(let ok): return try ok.body.json.results
        case .notFound: throw VeyqError.scopeNotFound
        case .badRequest(let bad): throw VeyqError.invalidRequest(try? bad.body.json.code)
        case .unauthorized: throw VeyqError.sessionExpired
        case .undocumented(let status, _): throw VeyqError.unexpectedStatus(status)
        }
    }

    private func fetchNodeMedia(_ scopeId: String, _ nodeId: String)
        async throws -> [MediaAPI.Components.Schemas.MediaSummary]
    {
        let out = try await media.listNodeMedia(.init(path: .init(scopeId: scopeId, nodeId: nodeId)))
        switch out {
        case .ok(let ok): return try ok.body.json.media // listNodeMedia now returns a MediaListPage wrapper
        case .notModified: throw VeyqError.unexpectedStatus(304) // we send no conditional header, so this shouldn't occur
        case .notFound: throw VeyqError.nodeNotFound
        case .unauthorized: throw VeyqError.sessionExpired
        case .undocumented(let status, _): throw VeyqError.unexpectedStatus(status)
        }
    }

    private func presignDownload(_ scopeId: String, blobId: String)
        async throws -> [MediaAPI.Components.Schemas.BlobDownloadResultItem]
    {
        let item = MediaAPI.Components.Schemas.BlobDownloadRequestItem(blobId: blobId)
        let out = try await media.presignBlobDownloads(.init(path: .init(scopeId: scopeId), body: .json(.init(items: [item]))))
        switch out {
        case .ok(let ok): return try ok.body.json.results
        case .notFound: throw VeyqError.scopeNotFound
        case .serviceUnavailable: throw VeyqError.storeUnavailable
        case .badRequest(let bad): throw VeyqError.invalidRequest(try? bad.body.json.code)
        case .unauthorized: throw VeyqError.sessionExpired
        case .undocumented(let status, _): throw VeyqError.unexpectedStatus(status)
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
