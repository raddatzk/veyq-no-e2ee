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
    private let setup: SetupAPI.Client
    private let auth: AuthAPI.Client
    private let account: AccountAPI.Client

    /// Build a client against an already-bootstrapped instance. `transport` is injectable so tests can
    /// drive the orchestration against a stub without a live server (the default is URLSession).
    public init(
        instance: VeyqInstance,
        session: Session = Session(),
        vault: Vault = Vault(),
        transport: any ClientTransport = VeyqTransport.make()
    ) {
        self.instance = instance
        self.session = session
        self.vault = vault
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
            await session.set(access: s.accessToken, refresh: s.refreshToken, familyId: s.family.id)
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
        case .badRequest, .unauthorized, .undocumented:
            await session.clear()
            throw VeyqError.refreshRejected
        }
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

        let decoded = V1.decodeKdfParams(Data(kdfParams.data))
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
