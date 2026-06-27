import XCTest
import Foundation
import HTTPTypes
import OpenAPIRuntime
import VeyqCrypto
@testable import VeyqKit

// cr-0019 phase 2 — uc.account.trust-device (apple) data-layer tests. No live server: a stateful
// `TrustStub` serves the account key-material and the wraps endpoints, and — crucially — **captures the
// DEVICE wrap a `trustThisDevice` POSTs and serves it back** in subsequent key-material, so a later
// `unlockWithDevice` opens the vault end-to-end with the real V1 crypto (Argon2id(deviceFactor+password)
// → symUnwrap). Operator-blind: only ciphertext crosses the stub; the deviceFactor/password/KEK never do.
final class TrustDeviceTests: XCTestCase {

    private static let masterKey = Data((0..<32).map { UInt8($0) })
    private static let identityPriv = Data((0..<80).map { UInt8(0x10 &+ $0) })
    private static let secretKey = Data((0..<19).map { UInt8(0x40 &+ $0) })
    private static let password = "open sesame"
    private static let salt = Data((0..<16).map { UInt8(0xE0 &+ $0) })
    private static let lightParams = V1.ArgonParams(t: 3, m: 64, p: 1, dkLen: 32)
    private static let baseURL = URL(string: "https://veyq.example")!
    private static let email = "person@veyq.example"
    /// The device key K the app layer would release and hand in (ADR 0011) — fixed so tests can re-supply it.
    private static let deviceK = Data((0..<32).map { UInt8(0x70 &+ $0) })

    /// The PASSWORD floor wrap + wrapped identity the stub starts with (so the floor unlock can run first).
    private static func floorWraps() -> (wraps: [[String: Any]], identity: String) {
        let kek = V1.kekPasswordSecret(secretKey: secretKey, password: password, salt: salt, params: lightParams)
        let wrappedKey = V1.symWrap(kek: kek, targetKey: masterKey, context: "master-key",
                                    nonce: Data((0..<12).map { UInt8(0x01 &+ $0) }))
        let wrappedIdentity = V1.symWrap(kek: masterKey, targetKey: identityPriv, context: "identity-priv",
                                         nonce: Data((0..<12).map { UInt8(0x20 &+ $0) }))
        let pw: [String: Any] = [
            "id": "pw", "method": "PASSWORD",
            "wrappedKey": wrappedKey.base64EncodedString(),
            "kdfParams": V1.encodeKdfParams(lightParams, salt: salt).base64EncodedString(),
        ]
        return ([pw], wrappedIdentity.base64EncodedString())
    }

    private func makeClient(store: any DeviceSecurityStore, stub: TrustStub) async -> VeyqClient {
        let session = Session()
        await session.set(access: "access-1", refresh: "refresh-1", familyId: "fam")
        return VeyqClient(instance: VeyqInstance(baseURL: Self.baseURL),
                          session: session, deviceSecurity: store, transport: stub)
    }

    /// Floor-unlock + trust, then a fresh client (fresh vault, same store) unlocks with only the password.
    func testTrustThenDeviceUnlockRoundTrip() async throws {
        let floor = Self.floorWraps()
        let stub = TrustStub(wraps: floor.wraps, identity: floor.identity)
        let store = InMemoryDeviceSecurityStore()

        let c1 = await makeClient(store: store, stub: stub)
        try await c1.unlock(password: Self.password, secretKey: Self.secretKey.base64EncodedString())
        try await c1.trustThisDevice(email: Self.email, password: Self.password)
        XCTAssertTrue((store.currentLevel(email: Self.email) == .onlyPassword))
        XCTAssertTrue(stub.hasWrap(method: "DEVICE"), "a DEVICE wrap was stored server-side")

        // Fresh client (new vault), same store + same server state — unlock with only the password.
        let c2 = await makeClient(store: store, stub: stub)
        let opened = try await c2.unlockWithDevice(email: Self.email, password: Self.password)
        XCTAssertTrue(opened)
        let mk = await c2.vault.masterKey()
        let id = await c2.vault.identityPrivateKey()
        XCTAssertEqual(mk, Self.masterKey)
        XCTAssertEqual(id, Self.identityPriv)
    }

    func testDeviceUnlockWrongPasswordThrowsAndLeavesVaultLocked() async throws {
        let floor = Self.floorWraps()
        let stub = TrustStub(wraps: floor.wraps, identity: floor.identity)
        let store = InMemoryDeviceSecurityStore()
        let c1 = await makeClient(store: store, stub: stub)
        try await c1.unlock(password: Self.password, secretKey: Self.secretKey.base64EncodedString())
        try await c1.trustThisDevice(email: Self.email, password: Self.password)

        let c2 = await makeClient(store: store, stub: stub)
        do {
            _ = try await c2.unlockWithDevice(email: Self.email, password: "wrong password")
            XCTFail("expected unlockFailed")
        } catch VeyqError.unlockFailed {
            // expected — AEAD tag mismatch
        }
        let unlocked = await c2.vault.isUnlocked
        XCTAssertFalse(unlocked)
    }

    func testNotTrustedReturnsFalse() async throws {
        let floor = Self.floorWraps()
        let stub = TrustStub(wraps: floor.wraps, identity: floor.identity)
        let c = await makeClient(store: InMemoryDeviceSecurityStore(), stub: stub)

        let opened = try await c.unlockWithDevice(email: Self.email, password: Self.password)
        XCTAssertFalse(opened, "no trust record → device path unavailable")
        let unlocked = await c.vault.isUnlocked
        XCTAssertFalse(unlocked)
    }

    /// A record whose server wrap is gone → false (fall back to floor), and the record is **kept** (no
    /// self-wipe on a single server response — cr-0005 #2).
    func testMissingServerWrapReturnsFalseAndKeepsRecord() async throws {
        let floor = Self.floorWraps()
        let stub = TrustStub(wraps: floor.wraps, identity: floor.identity)
        let store = InMemoryDeviceSecurityStore()
        _ = try store.beginEnrol(email: Self.email)
        try store.commitOnlyPassword(wrapId: "ghost", email: Self.email)   // a record whose server wrap is absent
        let c = await makeClient(store: store, stub: stub)

        let opened = try await c.unlockWithDevice(email: Self.email, password: Self.password)
        XCTAssertFalse(opened)
        XCTAssertTrue((store.currentLevel(email: Self.email) == .onlyPassword), "the local key must survive a missing server wrap")
    }

    func testReTrustReplacesPriorWrap() async throws {
        let floor = Self.floorWraps()
        let stub = TrustStub(wraps: floor.wraps, identity: floor.identity)
        let store = InMemoryDeviceSecurityStore()
        let c = await makeClient(store: store, stub: stub)
        try await c.unlock(password: Self.password, secretKey: Self.secretKey.base64EncodedString())

        try await c.trustThisDevice(email: Self.email, password: Self.password)
        let firstId = try XCTUnwrap(store.wrapId(email: Self.email))
        try await c.trustThisDevice(email: Self.email, password: Self.password)
        let secondId = try XCTUnwrap(store.wrapId(email: Self.email))

        XCTAssertNotEqual(firstId, secondId, "re-trust mints a new wrap id")
        XCTAssertFalse(stub.hasWrap(id: firstId), "the prior DEVICE wrap was deleted")
        XCTAssertTrue(stub.hasWrap(id: secondId), "only the new DEVICE wrap remains")
        XCTAssertEqual(stub.wrapCount(method: "DEVICE"), 1, "no duplicate accumulation")
    }

    func testUntrustRemovesWrapAndRecord() async throws {
        let floor = Self.floorWraps()
        let stub = TrustStub(wraps: floor.wraps, identity: floor.identity)
        let store = InMemoryDeviceSecurityStore()
        let c = await makeClient(store: store, stub: stub)
        try await c.unlock(password: Self.password, secretKey: Self.secretKey.base64EncodedString())
        try await c.trustThisDevice(email: Self.email, password: Self.password)

        try await c.untrustThisDevice(email: Self.email)
        XCTAssertFalse((store.currentLevel(email: Self.email) == .onlyPassword))
        XCTAssertFalse(stub.hasWrap(method: "DEVICE"), "the DEVICE wrap was removed server-side")
    }

    func testTrustOverCapThrowsTooManyDevices() async throws {
        let floor = Self.floorWraps()
        let stub = TrustStub(wraps: floor.wraps, identity: floor.identity)
        stub.conflictOnAdd = true
        let store = InMemoryDeviceSecurityStore()
        let c = await makeClient(store: store, stub: stub)
        try await c.unlock(password: Self.password, secretKey: Self.secretKey.base64EncodedString())

        do {
            try await c.trustThisDevice(email: Self.email, password: Self.password)
            XCTFail("expected tooManyDevices")
        } catch VeyqError.tooManyDevices {
            // expected
        }
        XCTAssertFalse((store.currentLevel(email: Self.email) == .onlyPassword), "no local record when the server rejected the wrap")
    }

    /// A local-save failure after the server wrap is created rolls the wrap back (no orphan) and throws.
    func testEnrolSaveFailureRollsBackServerWrap() async throws {
        let floor = Self.floorWraps()
        let stub = TrustStub(wraps: floor.wraps, identity: floor.identity)
        let store = FailingSaveDeviceTrustStore()
        let c = await makeClient(store: store, stub: stub)
        try await c.unlock(password: Self.password, secretKey: Self.secretKey.base64EncodedString())

        do {
            try await c.trustThisDevice(email: Self.email, password: Self.password)
            XCTFail("expected trustFailed")
        } catch VeyqError.trustFailed {
            // expected
        }
        XCTAssertFalse(stub.hasWrap(method: "DEVICE"), "the server wrap was rolled back — no orphan")
    }

    func testDeviceUnlock401RefreshesThenUnlocks() async throws {
        let floor = Self.floorWraps()
        let stub = TrustStub(wraps: floor.wraps, identity: floor.identity)
        let store = InMemoryDeviceSecurityStore()
        let c1 = await makeClient(store: store, stub: stub)
        try await c1.unlock(password: Self.password, secretKey: Self.secretKey.base64EncodedString())
        try await c1.trustThisDevice(email: Self.email, password: Self.password)

        stub.failKeyMaterialOnce = true
        let c2 = await makeClient(store: store, stub: stub)
        let opened = try await c2.unlockWithDevice(email: Self.email, password: Self.password)
        XCTAssertTrue(opened, "the 401 was rotated and the retry unlocked")
        let access = await c2.session.accessToken
        XCTAssertEqual(access, "access-2", "withRefresh rotated the session before the retry")
    }

    // MARK: - cr-0020 L2/L3 (passwordless) orchestration

    /// Helper: floor-unlock a client so its vault holds the Master Key (for enrol).
    private func unlockedClient(store: any DeviceSecurityStore, stub: TrustStub) async throws -> VeyqClient {
        let c = await makeClient(store: store, stub: stub)
        try await c.unlock(password: Self.password, secretKey: Self.secretKey.base64EncodedString())
        return c
    }

    /// The persisted refresh token is sealed under K (cr-0023); unseal it the way the client does, to assert
    /// the plaintext that was stored.
    private func unsealPersistedToken(_ store: any DeviceSecurityStore) throws -> String? {
        guard let stored = store.refreshToken(email: Self.email),
              let blob = Data(base64Encoded: stored),
              let bytes = try? V1.symUnwrap(kek: Self.deviceK, blob: blob, context: "session-token")
        else { return nil }
        return String(data: bytes, encoding: .utf8)
    }

    func testPasswordlessEnrolResumeUnlockRoundTrip() async throws {
        for level in [DeviceLevel.biometric, .noLogin] {
            let floor = Self.floorWraps()
            let stub = TrustStub(wraps: floor.wraps, identity: floor.identity)
            let store = InMemoryDeviceSecurityStore()

            let c1 = try await unlockedClient(store: store, stub: stub)
            try await c1.setPasswordlessLevel(level, email: Self.email, k: Self.deviceK)
            XCTAssertEqual(store.currentLevel(email: Self.email), level)
            XCTAssertTrue(stub.hasWrap(method: "DEVICE"), "a K-wrap was stored (\(level))")

            // Fresh client (new vault + session): resume the session, then open with the device key.
            let c2 = VeyqClient(instance: VeyqInstance(baseURL: Self.baseURL), deviceSecurity: store, transport: stub)
            let resumed = try await c2.resumeSession(email: Self.email, k: Self.deviceK)
            XCTAssertTrue(resumed, "the persisted token resumed the session (\(level))")
            let opened = try await c2.unlockWithDeviceKey(email: Self.email, k: Self.deviceK)
            XCTAssertTrue(opened, "the device key opened the vault (\(level))")
            let mk = await c2.vault.masterKey()
            XCTAssertEqual(mk, Self.masterKey, "the K-wrap recovered the Master Key (\(level))")
        }
    }

    func testResumeRotatesAndPersistsTheNewToken() async throws {
        let floor = Self.floorWraps()
        let stub = TrustStub(wraps: floor.wraps, identity: floor.identity)
        let store = InMemoryDeviceSecurityStore()
        let c1 = try await unlockedClient(store: store, stub: stub)
        try await c1.setPasswordlessLevel(.noLogin, email: Self.email, k: Self.deviceK)
        XCTAssertNotEqual(store.refreshToken(email: Self.email), "refresh-1",
                          "the token is NOT stored in the clear (sealed under K)")
        XCTAssertEqual(try unsealPersistedToken(store), "refresh-1", "enrol persisted the live token (sealed)")

        let c2 = VeyqClient(instance: VeyqInstance(baseURL: Self.baseURL), deviceSecurity: store, transport: stub)
        _ = try await c2.resumeSession(email: Self.email, k: Self.deviceK)
        XCTAssertEqual(try unsealPersistedToken(store), "refresh-2",
                       "the rotated refresh token was persisted back, still sealed (so the next launch stays valid)")
    }

    func testLegacyPlaintextTokenIsMigratedToSealedOnResume() async throws {
        // Pre-cr-0023 devices persisted the refresh token in the clear. Resume must still work (lazy
        // migration) and re-seal it afterwards.
        let floor = Self.floorWraps()
        let stub = TrustStub(wraps: floor.wraps, identity: floor.identity)
        let store = InMemoryDeviceSecurityStore()
        let c1 = try await unlockedClient(store: store, stub: stub)
        try await c1.setPasswordlessLevel(.noLogin, email: Self.email, k: Self.deviceK)
        try store.updateRefreshToken("refresh-1", email: Self.email)   // simulate a legacy PLAINTEXT token
        XCTAssertEqual(store.refreshToken(email: Self.email), "refresh-1", "precondition: stored in the clear")

        let c2 = VeyqClient(instance: VeyqInstance(baseURL: Self.baseURL), deviceSecurity: store, transport: stub)
        let resumed = try await c2.resumeSession(email: Self.email, k: Self.deviceK)
        XCTAssertTrue(resumed, "a legacy plaintext token still resumes (lazy migration)")
        XCTAssertNotEqual(store.refreshToken(email: Self.email), "refresh-2", "now sealed, not clear")
        XCTAssertEqual(try unsealPersistedToken(store), "refresh-2", "the rotated token was re-sealed under K")
    }

    func testAutoRestoreResumesAndOpensFromSuppliedKey() async throws {
        // ADR 0011: the app layer releases K once and hands it in; autoRestore resumes the session AND opens
        // the vault from that single supplied key — neither step prompts (the one-gesture property is now an
        // app-layer concern, mirroring Android's autoRestoreResumesAndOpensFromSuppliedKey).
        for level in [DeviceLevel.biometric, .noLogin] {
            let floor = Self.floorWraps()
            let stub = TrustStub(wraps: floor.wraps, identity: floor.identity)
            let store = InMemoryDeviceSecurityStore()
            let c1 = try await unlockedClient(store: store, stub: stub)
            try await c1.setPasswordlessLevel(level, email: Self.email, k: Self.deviceK)

            let c2 = VeyqClient(instance: VeyqInstance(baseURL: Self.baseURL), deviceSecurity: store, transport: stub)
            let restored = try await c2.autoRestore(email: Self.email, k: Self.deviceK)
            XCTAssertTrue(restored, "autoRestore resumed + opened the vault from the supplied K (\(level))")
            let mk = await c2.vault.masterKey()
            XCTAssertEqual(mk, Self.masterKey, "the K-wrap recovered the Master Key (\(level))")
            XCTAssertEqual(try unsealPersistedToken(store), "refresh-2", "rotated token re-sealed (\(level))")
        }
    }

    func testLevelSwitchReplacesTheConvenienceWrap() async throws {
        let floor = Self.floorWraps()
        let stub = TrustStub(wraps: floor.wraps, identity: floor.identity)
        let store = InMemoryDeviceSecurityStore()
        let c = try await unlockedClient(store: store, stub: stub)

        try await c.setPasswordlessLevel(.biometric, email: Self.email, k: Self.deviceK)
        try await c.setPasswordlessLevel(.noLogin, email: Self.email, k: Self.deviceK)
        XCTAssertEqual(store.currentLevel(email: Self.email), .noLogin)
        XCTAssertEqual(stub.wrapCount(method: "DEVICE"), 1, "one convenience wrap at a time (mutually exclusive)")
    }

    func testUnlockWithDeviceKeyRejectsAnL1Record() async throws {
        let floor = Self.floorWraps()
        let stub = TrustStub(wraps: floor.wraps, identity: floor.identity)
        let store = InMemoryDeviceSecurityStore()
        let c = try await unlockedClient(store: store, stub: stub)
        try await c.trustThisDevice(email: Self.email, password: Self.password)   // L1

        let opened = try await c.unlockWithDeviceKey(email: Self.email, k: Self.deviceK)
        XCTAssertFalse(opened, "the K-wrap path must not open an Argon2id L1 wrap")
    }

    func testResumeSessionWithNoRecordReturnsFalse() async throws {
        let floor = Self.floorWraps()
        let stub = TrustStub(wraps: floor.wraps, identity: floor.identity)
        let c = VeyqClient(instance: VeyqInstance(baseURL: Self.baseURL),
                           deviceSecurity: InMemoryDeviceSecurityStore(), transport: stub)
        let resumed = try await c.resumeSession(email: Self.email, k: Self.deviceK)
        XCTAssertFalse(resumed)
    }

    func testResumeSessionWithDeadTokenFallsBack() async throws {
        let floor = Self.floorWraps()
        let stub = TrustStub(wraps: floor.wraps, identity: floor.identity)
        let store = InMemoryDeviceSecurityStore()
        let c1 = try await unlockedClient(store: store, stub: stub)
        try await c1.setPasswordlessLevel(.noLogin, email: Self.email, k: Self.deviceK)

        stub.failRefresh = true
        let c2 = VeyqClient(instance: VeyqInstance(baseURL: Self.baseURL), deviceSecurity: store, transport: stub)
        let resumed = try await c2.resumeSession(email: Self.email, k: Self.deviceK)
        XCTAssertFalse(resumed, "a dead/expired token resumes nothing → caller falls back to the floor")
    }

    func testPasswordlessEnrolRollsBackOnSaveFailure() async throws {
        let floor = Self.floorWraps()
        let stub = TrustStub(wraps: floor.wraps, identity: floor.identity)
        let c = try await unlockedClient(store: FailingSaveDeviceTrustStore(), stub: stub)
        do {
            try await c.setPasswordlessLevel(.biometric, email: Self.email, k: Self.deviceK)
            XCTFail("expected trustFailed")
        } catch VeyqError.trustFailed { /* expected */ }
        XCTAssertFalse(stub.hasWrap(method: "DEVICE"), "the server wrap was rolled back — no orphan")
    }
}

/// A `DeviceSecurityStore` whose `save` always fails — to exercise the enrol rollback.
private final class FailingSaveDeviceTrustStore: DeviceSecurityStore, @unchecked Sendable {
    private let factor = Data((0..<32).map { UInt8($0 &+ 1) })
    func currentLevel(email: String) -> DeviceLevel? { nil }
    func wrapId(email: String) -> String? { nil }
    func refreshToken(email: String) -> String? { nil }
    func updateRefreshToken(_ token: String?, email: String) throws {}
    func beginEnrol(email: String) throws -> Data { factor }
    func deviceFactor(email: String) -> Data? { factor }
    func commitOnlyPassword(wrapId: String, email: String) throws { throw DeviceSecurityError.keychain(-1) }
    func commitPasswordless(level: DeviceLevel, wrapId: String, refreshToken: String, email: String) throws { throw DeviceSecurityError.keychain(-1) }
    func clear(email: String) throws {}
}

/// A stateful `ClientTransport` for the key-material + wraps + token endpoints. Captures POSTed DEVICE
/// wraps and serves them back in key-material, deletes by id, and can simulate a 409 cap or a one-shot 401.
private final class TrustStub: ClientTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var wraps: [[String: Any]]
    private let identity: String
    private var nextId = 1
    var conflictOnAdd = false
    var failKeyMaterialOnce = false
    var failRefresh = false

    init(wraps: [[String: Any]], identity: String) {
        self.wraps = wraps
        self.identity = identity
    }

    func hasWrap(method: String) -> Bool { lock.withLock { wraps.contains { $0["method"] as? String == method } } }
    func hasWrap(id: String) -> Bool { lock.withLock { wraps.contains { $0["id"] as? String == id } } }
    func wrapCount(method: String) -> Int { lock.withLock { wraps.filter { $0["method"] as? String == method }.count } }

    func send(_ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String)
        async throws -> (HTTPResponse, HTTPBody?)
    {
        let path = request.path ?? ""
        let method = request.method

        if path == "/api/account/key-material" {
            let shouldFail = lock.withLock { () -> Bool in
                if failKeyMaterialOnce { failKeyMaterialOnce = false; return true }
                return false
            }
            if shouldFail { return Self.json(401, ["code": "authentication_failed"]) }
            let body = lock.withLock {
                ["suiteId": "v1", "wraps": wraps, "wrappedIdentityPrivateKey": identity] as [String: Any]
            }
            return Self.json(200, body)
        }

        if path == "/api/account/wraps", method == .post {
            if conflictOnAdd { return Self.json(409, ["code": "too_many_devices"]) }
            let data = try await Data(collecting: body ?? HTTPBody(), upTo: 1_000_000)
            let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
            let id = lock.withLock { () -> String in
                let id = "dev-\(nextId)"; nextId += 1
                wraps.append([
                    "id": id, "method": obj["method"] as? String ?? "DEVICE",
                    "wrappedKey": obj["wrappedKey"] as? String ?? "",
                    "kdfParams": obj["kdfParams"] as? String ?? "",
                ])
                return id
            }
            return Self.json(201, ["id": id])
        }

        if path.hasPrefix("/api/account/wraps/"), method == .delete {
            let id = String(path.dropFirst("/api/account/wraps/".count))
            lock.withLock { wraps.removeAll { $0["id"] as? String == id } }
            return (HTTPResponse(status: .noContent), nil)
        }

        if path == "/api/token/refresh" {
            if failRefresh { return Self.json(401, ["code": "refresh_rejected"]) }
            return Self.json(200, ["accessToken": "access-2", "refreshToken": "refresh-2"])
        }
        if path == "/api/logout" { return (HTTPResponse(status: .noContent), nil) }
        return (HTTPResponse(status: .notFound), nil)
    }

    private static func json(_ status: Int, _ object: Any) -> (HTTPResponse, HTTPBody?) {
        let data = (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
        var response = HTTPResponse(status: .init(code: status))
        response.headerFields[.contentType] = "application/json"
        return (response, HTTPBody(data))
    }
}
