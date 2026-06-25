import XCTest
import Foundation
import CryptoKit
@testable import VeyqCrypto

/// Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `swift test` skips it.
final class MemberInviteE2ETests: XCTestCase {
    func testTempWrapMemberInviteRegistersOnExistingScope() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["VEYQ_E2E"] == "1", "set VEYQ_E2E=1 to run against a live backend")
        let apiBase = env["VEYQ_API_BASE"] ?? "http://localhost:8080"

        // owner A claims tenant T1 (holds its scope)
        let aPass = "correct horse battery staple"
        let (_, aKit) = try await SetupServer.registerServerOwner(apiBase: apiBase, email: "admin@example.com", displayName: "Admin", password: aPass)
        let a = try await Login.login(apiBase: apiBase, email: "admin@example.com", password: aPass, secretKeyB64: aKit.secretKey)
        let t1 = try await Tenant.createTenant(apiBase: apiBase, token: a.sessionToken, name: "acme", displayName: "Acme", domain: nil)
        let inv = try await Tenant.issueOwnerInvite(apiBase: apiBase, token: a.sessionToken, tenantId: t1.id)
        _ = try await Tenant.claimTenantOwnership(
            apiBase: apiBase, token: a.sessionToken, inviteId: inv.inviteId, bootstrapSecret: inv.bootstrapSecret, identityKemPublicKey: a.kemPublicKey)

        // A issues a temp-wrap member invite (scope wrapped to a one-time temp key)
        let memberInvite = try await InviteMembers.issueMemberInvite(apiBase: apiBase, owner: a, tenantId: t1.id, role: "Member")
        XCTAssertFalse(memberInvite.tempPrivateKey.isEmpty)

        // B (a brand-new account) accepts: unwraps with the temp key, re-wraps to self, registers
        let bPass = "a whole new passphrase entirely"
        let bReg = try await InviteMembers.registerViaMemberInvite(
            apiBase: apiBase, inviteId: memberInvite.inviteId, bootstrapSecret: memberInvite.bootstrapSecret,
            tempPrivateKeyB64: memberInvite.tempPrivateKey, email: "b@example.com", displayName: "B", password: bPass)
        XCTAssertEqual(bReg.tenant.status, "ACTIVE")
        XCTAssertEqual(bReg.user.serverRole, "ServerMember")

        // B logs in and recovers T1's scope private key from their own grant (full temp-wrap round trip)
        let b = try await Login.login(apiBase: apiBase, email: "b@example.com", password: bPass, secretKeyB64: bReg.emergencyKit.secretKey)
        let grant = try await Tenant.getMyScopeGrant(apiBase: apiBase, token: b.sessionToken, tenantId: t1.id)
        let scopePriv = try Tenant.openScopeGrant(grant: grant, identity: b.identity, identityKemPublicKey: b.kemPublicKey)
        let recoveredXPub = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: scopePriv.kemX).publicKey.rawRepresentation
        let storedXPub = Data(Tenant.b64urlDecode(grant.scopePublicKey.kemPublicKey).prefix(32))
        XCTAssertEqual(recoveredXPub, storedXPub)
    }
}
