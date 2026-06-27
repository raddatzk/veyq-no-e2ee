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
        try store.save(DeviceSecurityRecord(level: .onlyPassword, wrapId: "ghost", secret: makeDeviceFactor()), email: Self.email)
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
        let firstId = try XCTUnwrap((try store.load(email: Self.email))?.wrapId)
        try await c.trustThisDevice(email: Self.email, password: Self.password)
        let secondId = try XCTUnwrap((try store.load(email: Self.email))?.wrapId)

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
}

/// A `DeviceSecurityStore` whose `save` always fails — to exercise the enrol rollback.
private final class FailingSaveDeviceTrustStore: DeviceSecurityStore, @unchecked Sendable {
    func currentLevel(email: String) -> DeviceLevel? { nil }
    func load(email: String) throws -> DeviceSecurityRecord? { nil }
    func save(_ record: DeviceSecurityRecord, email: String) throws { throw DeviceSecurityError.keychain(-1) }
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
