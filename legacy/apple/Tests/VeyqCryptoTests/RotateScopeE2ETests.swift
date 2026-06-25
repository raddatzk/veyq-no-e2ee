import XCTest
import Foundation
import CryptoKit
@testable import VeyqCrypto

/// Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `swift test` skips it.
final class RotateScopeE2ETests: XCTestCase {
    func testRotateScopeReGrantsEveryActiveMember() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["VEYQ_E2E"] == "1", "set VEYQ_E2E=1 to run against a live backend")
        let apiBase = env["VEYQ_API_BASE"] ?? "http://localhost:8080"

        // owner A claims T1; member B joins via a temp-wrap invite
        let aPass = "correct horse battery staple"
        let (_, aKit) = try await SetupServer.registerServerOwner(apiBase: apiBase, email: "admin@example.com", displayName: "Admin", password: aPass)
        let a = try await Login.login(apiBase: apiBase, email: "admin@example.com", password: aPass, secretKeyB64: aKit.secretKey)
        let t1 = try await Tenant.createTenant(apiBase: apiBase, token: a.sessionToken, name: "acme", displayName: "Acme", domain: nil)
        let inv = try await Tenant.issueOwnerInvite(apiBase: apiBase, token: a.sessionToken, tenantId: t1.id)
        _ = try await Tenant.claimTenantOwnership(apiBase: apiBase, token: a.sessionToken, inviteId: inv.inviteId, bootstrapSecret: inv.bootstrapSecret, identityKemPublicKey: a.kemPublicKey)

        let bPass = "a whole new passphrase entirely"
        let memberInvite = try await InviteMembers.issueMemberInvite(apiBase: apiBase, owner: a, tenantId: t1.id, role: "Member")
        let bReg = try await InviteMembers.registerViaMemberInvite(
            apiBase: apiBase, inviteId: memberInvite.inviteId, bootstrapSecret: memberInvite.bootstrapSecret,
            tempPrivateKeyB64: memberInvite.tempPrivateKey, email: "b@example.com", displayName: "B", password: bPass)
        _ = bReg
        let b = try await Login.login(apiBase: apiBase, email: "b@example.com", password: bPass, secretKeyB64: bReg.emergencyKit.secretKey)

        let before = try await xPub(apiBase, a, t1.id)

        // A rotates the scope — a fresh keypair re-wrapped to both members
        let result = try await InviteMembers.rotateScope(apiBase: apiBase, owner: a, tenantId: t1.id)
        XCTAssertEqual(result.memberCount, 2)

        // both members recover the SAME new scope keypair, and it changed
        let aNew = try await xPub(apiBase, a, t1.id)
        let bNew = try await xPub(apiBase, b, t1.id)
        XCTAssertEqual(aNew, bNew)
        XCTAssertNotEqual(aNew, before)
    }

    private func xPub(_ apiBase: String, _ session: Login.UnlockedSession, _ tenantId: String) async throws -> Data {
        let grant = try await Tenant.getMyScopeGrant(apiBase: apiBase, token: session.sessionToken, tenantId: tenantId)
        let scopePriv = try Tenant.openScopeGrant(grant: grant, identity: session.identity, identityKemPublicKey: session.kemPublicKey)
        return try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: scopePriv.kemX).publicKey.rawRepresentation
    }
}
