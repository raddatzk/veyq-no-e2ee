import XCTest
import Foundation
@testable import VeyqCrypto

/// The password-reset crypto, deterministic (no backend): registration mints a RECOVERY wrap keyed by the
/// Secret Key alone; it opens to the SAME master key as the PASSWORD wrap; re-wrapping under a new password
/// round-trips. Mirrors ResetPassword.confirmReset.
final class ResetPasswordTests: XCTestCase {
    func testRecoveryWrapRoundTrips() throws {
        let (req, kit) = try SetupServer.buildRequest(email: "u@example.com", displayName: "U", password: "old password")
        let secretKey = Login.b64urlDecode(kit.secretKey)

        let pw = try XCTUnwrap(req.masterKeyWraps.first { $0.unlockMethod == "PASSWORD" })
        let rec = try XCTUnwrap(req.masterKeyWraps.first { $0.unlockMethod == "RECOVERY" })

        let (pwParams, pwSalt) = V1.decodeKdfParams(Login.b64urlDecode(try XCTUnwrap(pw.kdfParams)))
        let masterViaPassword = try V1.symUnwrap(kek: try V1.kekPasswordSecret(secretKey: secretKey, password: "old password", salt: pwSalt, params: pwParams), blob: Login.b64urlDecode(pw.wrappedKey), context: "master-key")

        let (recParams, recSalt) = V1.decodeKdfParams(Login.b64urlDecode(try XCTUnwrap(rec.kdfParams)))
        let masterViaRecovery = try V1.symUnwrap(kek: try V1.kekRecovery(recoveryCode: secretKey, salt: recSalt, params: recParams), blob: Login.b64urlDecode(rec.wrappedKey), context: "master-key")

        XCTAssertEqual(masterViaRecovery, masterViaPassword)

        // re-wrap the recovered master key under a NEW password (the reset step) — round-trips; old pw fails
        let newSalt = V1.randomBytes(16)
        let newWrap = try V1.symWrap(kek: try V1.kekPasswordSecret(secretKey: secretKey, password: "a brand new password", salt: newSalt, params: SetupServer.argon), targetKey: masterViaRecovery, context: "master-key", nonce: V1.randomBytes(12))
        let reopened = try V1.symUnwrap(kek: try V1.kekPasswordSecret(secretKey: secretKey, password: "a brand new password", salt: newSalt, params: SetupServer.argon), blob: newWrap, context: "master-key")
        XCTAssertEqual(reopened, masterViaRecovery)
        XCTAssertThrowsError(try V1.symUnwrap(kek: try V1.kekPasswordSecret(secretKey: secretKey, password: "old password", salt: newSalt, params: SetupServer.argon), blob: newWrap, context: "master-key"))
    }
}
