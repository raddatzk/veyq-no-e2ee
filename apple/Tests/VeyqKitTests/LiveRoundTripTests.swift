import XCTest
import Foundation
@testable import VeyqKit

// Live round-trip against a REAL running instance (cr-0015 phase 4/5 verification). Skipped unless the
// VEYQ_LIVE_BASE env var points at a bootstrapped instance — so the normal `swift test` stays hermetic.
// This is the cross-platform behavioural contract (ADR 0010) exercised for real: the same login → unlock
// the web does, against the same backend, over the real URLSession transport.
//
//   VEYQ_LIVE_BASE=http://localhost:8080 \
//   VEYQ_EMAIL=kevin@veyq.test VEYQ_PASSWORD='…' VEYQ_SECRET='…' \
//   swift test --filter LiveRoundTripTests
final class LiveRoundTripTests: XCTestCase {

    func testLoginThenUnlockAgainstLiveInstance() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let base = env["VEYQ_LIVE_BASE"], let baseURL = URL(string: base) else {
            throw XCTSkip("set VEYQ_LIVE_BASE (+ VEYQ_EMAIL/PASSWORD/SECRET) to run the live round-trip")
        }
        let email = try XCTUnwrap(env["VEYQ_EMAIL"])
        let password = try XCTUnwrap(env["VEYQ_PASSWORD"])
        let secret = try XCTUnwrap(env["VEYQ_SECRET"])

        let client = VeyqClient(instance: VeyqInstance(baseURL: baseURL))

        // The instance is bootstrapped, so setup is not required.
        let setupReq = try await client.setupRequired()
        XCTAssertFalse(setupReq, "instance should already be set up")

        // login → holds a session
        try await client.login(email: email, password: password)
        let isAuth = await client.session.isAuthenticated
        XCTAssertTrue(isAuth, "login should hold a session")

        // unlock → opens the vault (Master Key + identity private on-device)
        try await client.unlock(password: password, secretKey: secret)
        let unlocked = await client.vault.isUnlocked
        let mk = await client.vault.masterKey()
        let id = await client.vault.identityPrivateKey()
        XCTAssertTrue(unlocked, "vault should be unlocked")
        XCTAssertEqual(mk?.count, 32, "Master Key is 32 bytes")
        XCTAssertNotNil(id, "identity private key is held")

        // logout → re-locks
        try await client.logout()
        let stillUnlocked = await client.vault.isUnlocked
        let stillAuth = await client.session.isAuthenticated
        XCTAssertFalse(stillUnlocked)
        XCTAssertFalse(stillAuth)
    }

    /// cr-0019 — the DEVICE-wrap trust round-trip against the **live** wraps endpoints. Uses an in-memory
    /// `DeviceTrustStore` (the real Keychain is entitlement-gated from the SPM CLI; the in-app UI round-trip
    /// exercises it). Proves: floor unlock → `trustThisDevice` (POST /api/account/wraps) → a fresh client
    /// opens with only the password via the served DEVICE wrap → `untrustThisDevice` (DELETE) cleans up.
    func testTrustDeviceRoundTripAgainstLiveInstance() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let base = env["VEYQ_LIVE_BASE"], let baseURL = URL(string: base) else {
            throw XCTSkip("set VEYQ_LIVE_BASE (+ VEYQ_EMAIL/PASSWORD/SECRET) to run the live round-trip")
        }
        let email = try XCTUnwrap(env["VEYQ_EMAIL"])
        let password = try XCTUnwrap(env["VEYQ_PASSWORD"])
        let secret = try XCTUnwrap(env["VEYQ_SECRET"])
        let store = InMemoryDeviceSecurityStore()

        // 1 — floor unlock, then trust this device (enrol a DEVICE wrap server-side).
        let c1 = VeyqClient(instance: VeyqInstance(baseURL: baseURL), deviceSecurity: store)
        try await c1.login(email: email, password: password)
        try await c1.unlock(password: password, secretKey: secret)
        try await c1.trustThisDevice(email: email, password: password)
        XCTAssertTrue((store.currentLevel(email: email) == .onlyPassword), "a DEVICE wrap + local record now exist")

        // 2 — a fresh client (new vault), same store: unlock with ONLY the password (no Secret Key).
        let c2 = VeyqClient(instance: VeyqInstance(baseURL: baseURL), deviceSecurity: store)
        try await c2.login(email: email, password: password)
        let opened = try await c2.unlockWithDevice(email: email, password: password)
        XCTAssertTrue(opened, "the trusted device opens with only the password")
        let mk = await c2.vault.masterKey()
        XCTAssertEqual(mk?.count, 32, "the Master Key opened from the DEVICE wrap")

        // 3 — wrong password on the trusted device fails generically (AEAD tag), vault stays locked.
        let c3 = VeyqClient(instance: VeyqInstance(baseURL: baseURL), deviceSecurity: store)
        try await c3.login(email: email, password: password)
        do {
            _ = try await c3.unlockWithDevice(email: email, password: "definitely wrong")
            XCTFail("expected unlockFailed")
        } catch VeyqError.unlockFailed { /* expected */ }

        // 4 — untrust: delete the wrap server-side + drop the local record (cleanup, leaves the owner clean).
        try await c2.untrustThisDevice(email: email)
        XCTAssertFalse((store.currentLevel(email: email) == .onlyPassword))
        let c4 = VeyqClient(instance: VeyqInstance(baseURL: baseURL), deviceSecurity: store)
        try await c4.login(email: email, password: password)
        let openedAfterUntrust = try await c4.unlockWithDevice(email: email, password: password)
        XCTAssertFalse(openedAfterUntrust, "after untrust the device path is unavailable (no record)")
    }

    /// cr-0020 — the **passwordless** (L2/L3) round-trip against the live wraps endpoints. Uses an
    /// in-memory store (the L2 biometric gate is exercised by the in-app UI round-trip). Validates that the
    /// backend accepts the K-wrap's marker `kdfParams`, and that a fresh client **resumes the persisted
    /// session** and opens the vault with **only the device key** (no password). Tested with `.noLogin`
    /// (the data layer is identical to `.biometric` — only the local Keychain gate differs).
    func testPasswordlessRoundTripAgainstLiveInstance() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let base = env["VEYQ_LIVE_BASE"], let baseURL = URL(string: base) else {
            throw XCTSkip("set VEYQ_LIVE_BASE (+ VEYQ_EMAIL/PASSWORD/SECRET) to run the live round-trip")
        }
        let email = try XCTUnwrap(env["VEYQ_EMAIL"])
        let password = try XCTUnwrap(env["VEYQ_PASSWORD"])
        let secret = try XCTUnwrap(env["VEYQ_SECRET"])
        let store = InMemoryDeviceSecurityStore()

        // Floor unlock, then set this device to a passwordless level (enrols the K-wrap + persists session).
        // ADR 0011: the app layer owns K; here the test supplies it (an in-memory store, no Keychain gate).
        let k = secureRandomBytes(32)
        let c1 = VeyqClient(instance: VeyqInstance(baseURL: baseURL), deviceSecurity: store)
        try await c1.login(email: email, password: password)
        try await c1.unlock(password: password, secretKey: secret)
        try await c1.setPasswordlessLevel(.noLogin, email: email, k: k)
        XCTAssertEqual(store.currentLevel(email: email), .noLogin, "the live POST accepted the K-wrap")

        // A fresh client (new vault + no live session): resume from the persisted token, open with the key.
        let c2 = VeyqClient(instance: VeyqInstance(baseURL: baseURL), deviceSecurity: store)
        let resumed = try await c2.resumeSession(email: email, k: k)
        XCTAssertTrue(resumed, "the persisted refresh token resumed a live session")
        let opened = try await c2.unlockWithDeviceKey(email: email, k: k)
        XCTAssertTrue(opened, "the device key opened the vault with no password")
        let mk = await c2.vault.masterKey()
        XCTAssertEqual(mk?.count, 32)

        // Cleanup — drop the level (delete the wrap, leaving the owner clean for re-runs).
        try await c2.untrustThisDevice(email: email)
        XCTAssertNil(store.currentLevel(email: email))
    }

    /// cr-0028 — the **media byte-transport** round-trip against the live store. Needs the instance running
    /// with a bundled MinIO (`directTransport: ok`). Validates the one path the mocked `MediaTests` cannot:
    /// real presigned PUT/GET over URLSession. login → unlock → personal scope → create a Memory → addMedia
    /// (seal + direct-to-store upload) → listMedia → openMedia, asserting the bytes round-trip.
    func testMediaRoundTripAgainstLiveInstance() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let base = env["VEYQ_LIVE_BASE"], let baseURL = URL(string: base) else {
            throw XCTSkip("set VEYQ_LIVE_BASE (+ VEYQ_EMAIL/PASSWORD/SECRET) to run the live round-trip")
        }
        let email = try XCTUnwrap(env["VEYQ_EMAIL"])
        let password = try XCTUnwrap(env["VEYQ_PASSWORD"])
        let secret = try XCTUnwrap(env["VEYQ_SECRET"])

        let c = VeyqClient(instance: VeyqInstance(baseURL: baseURL))
        try await c.login(email: email, password: password)
        try await c.unlock(password: password, secretKey: secret)
        _ = try await c.ensurePersonalScope()
        let openedScope = try await c.openPersonalScope()
        let scopeKey = try XCTUnwrap(openedScope, "no personal scope")
        let scopes = try await c.listMyScopes()
        let personal = try XCTUnwrap(scopes.first { $0.kind == .PERSONAL })

        let (nodeId, memoryKey) = try await c.createMemory(scopeId: personal.id, scopeKey: scopeKey, name: "E2E media \(UUID().uuidString)")

        var data = Data(count: 300 * 1024)
        for i in 0 ..< data.count { data[i] = UInt8((i * 17 + 3) & 0xff) }
        // octet-stream → no derivable frame, so this stays the original-only byte-transport round-trip the
        // derivatives pipeline (cr-0047) leaves untouched; the live image+video tier round-trip is the manual gate.
        let (blobId, _) = try await c.addMedia(scopeId: personal.id, nodeId: nodeId, memoryKey: memoryKey,
                                               data: data, mediaType: "application/octet-stream")
        XCTAssertFalse(blobId.isEmpty)

        let items = try await c.listMedia(scopeId: personal.id, nodeId: nodeId)
        let item = try XCTUnwrap(items.first)
        let opened = try await c.openMedia(scopeId: personal.id, memoryKey: memoryKey, media: item)
        XCTAssertEqual(opened, data, "media bytes round-trip through real presigned upload + download")
    }

    /// cr-0047 — the **derivative tiers** round-trip against the live store, exercising the one path the
    /// stubbed `MediaTests` cannot: the real `NativeMediaDeriver` (ImageIO image downscale + AVFoundation
    /// first-frame poster). A real PNG and a real MP4 go through decode→downscale/poster→seal→upload→list→
    /// fetch-tier→decode; each fetched tier is asserted **decodable** with its **longest edge within the tier
    /// cap** (parity by property, not bytes). Decoded tiers are written to `VEYQ_ARTIFACT_DIR` (when set) as
    /// the visual proof. Operator-blind: only ciphertext travels; the pixel work is on-device.
    func testDerivativeTiersRoundTripAgainstLiveInstance() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let base = env["VEYQ_LIVE_BASE"], let baseURL = URL(string: base) else {
            throw XCTSkip("set VEYQ_LIVE_BASE (+ VEYQ_EMAIL/PASSWORD/SECRET) to run the live round-trip")
        }
        let email = try XCTUnwrap(env["VEYQ_EMAIL"])
        let password = try XCTUnwrap(env["VEYQ_PASSWORD"])
        let secret = try XCTUnwrap(env["VEYQ_SECRET"])

        let c = VeyqClient(instance: VeyqInstance(baseURL: baseURL))
        try await c.login(email: email, password: password)
        try await c.unlock(password: password, secretKey: secret)
        _ = try await c.ensurePersonalScope()
        let openedScope = try await c.openPersonalScope()
        let scopeKey = try XCTUnwrap(openedScope, "no personal scope")
        let myScopes = try await c.listMyScopes()
        let personal = try XCTUnwrap(myScopes.first { $0.kind == .PERSONAL })
        let (nodeId, memoryKey) = try await c.createMemory(scopeId: personal.id, scopeKey: scopeKey,
                                                           name: "E2E derivatives \(UUID().uuidString)")

        // --- a real image: 1600×900 PNG → the ImageIO deriver downscales it to thumbnail + preview JPEGs ---
        let png = try MediaFixtures.png(width: 1600, height: 900)
        let (imageBlobId, imageMediaId) = try await c.addMedia(scopeId: personal.id, nodeId: nodeId,
                                                               memoryKey: memoryKey, data: png, mediaType: "image/png")
        XCTAssertFalse(imageBlobId.isEmpty)

        // --- a real video: a 1280×720 MP4 → the AVFoundation deriver makes one first-frame poster for both ---
        let mp4 = try await MediaFixtures.mp4(width: 1280, height: 720, frameCount: 6)
        let (_, videoMediaId) = try await c.addMedia(scopeId: personal.id, nodeId: nodeId,
                                                     memoryKey: memoryKey, data: mp4, mediaType: "video/mp4")

        let items = try await c.listMedia(scopeId: personal.id, nodeId: nodeId)
        let image = try XCTUnwrap(items.first { $0.mediaId == imageMediaId })
        let video = try XCTUnwrap(items.first { $0.mediaId == videoMediaId })

        let artifactDir = env["VEYQ_ARTIFACT_DIR"].map { URL(fileURLWithPath: $0, isDirectory: true) }
        try artifactDir.map { try FileManager.default.createDirectory(at: $0, withIntermediateDirectories: true) }

        // image: thumbnail and preview are distinct blobs, each a decodable JPEG within its tier's cap
        XCTAssertEqual(image.derivatives.count, 2)
        XCTAssertNotEqual(image.gridRef.blobId, image.lightboxRef.blobId)
        let imgThumb = try await c.openMedia(scopeId: personal.id, memoryKey: memoryKey, media: image, tier: .grid)
        let imgPreview = try await c.openMedia(scopeId: personal.id, memoryKey: memoryKey, media: image, tier: .lightbox)
        let (tw, th) = try MediaFixtures.jpegDimensions(imgThumb)
        let (pw, ph) = try MediaFixtures.jpegDimensions(imgPreview)
        XCTAssertLessThanOrEqual(max(tw, th), 400, "thumbnail longest edge ≤ 400")
        XCTAssertLessThanOrEqual(max(pw, ph), 2048, "preview longest edge ≤ 2048")
        XCTAssertEqual(Double(tw) / Double(th), 1600.0 / 900.0, accuracy: 0.02, "thumbnail keeps the aspect ratio")
        try Self.write(imgThumb, to: artifactDir, named: "image-thumbnail-\(tw)x\(th).jpg")
        try Self.write(imgPreview, to: artifactDir, named: "image-preview-\(pw)x\(ph).jpg")

        // video: one poster reused for both tiers — distinct blobs, identical decoded bytes, decodable JPEG
        XCTAssertEqual(video.derivatives.count, 2)
        let vidThumb = try await c.openMedia(scopeId: personal.id, memoryKey: memoryKey, media: video, tier: .grid)
        let vidPreview = try await c.openMedia(scopeId: personal.id, memoryKey: memoryKey, media: video, tier: .lightbox)
        XCTAssertEqual(vidThumb, vidPreview, "the video poster is reused verbatim for both tiers")
        let (vw, vh) = try MediaFixtures.jpegDimensions(vidThumb)
        XCTAssertLessThanOrEqual(max(vw, vh), 2048, "poster longest edge ≤ 2048 (preview cap)")
        try Self.write(vidThumb, to: artifactDir, named: "video-poster-\(vw)x\(vh).jpg")

        // the original stays pristine: the PNG byte-round-trips through its own blob
        let openedOriginal = try await c.openMedia(scopeId: personal.id, memoryKey: memoryKey, media: image, tier: .original)
        XCTAssertEqual(openedOriginal, png, "the original is byte-pristine")
    }

    // MARK: - live-test artifact helper (the PNG/MP4 fixtures + JPEG-dim reader live in `MediaFixtures`)

    private static func write(_ data: Data, to dir: URL?, named: String) throws {
        guard let dir else { return }
        try data.write(to: dir.appendingPathComponent(named))
    }
}
