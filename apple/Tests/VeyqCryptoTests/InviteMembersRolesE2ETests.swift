import XCTest
import Foundation
import CryptoKit
@testable import VeyqCrypto

/// Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `swift test` skips it.
final class InviteMembersRolesE2ETests: XCTestCase {
    func testGrantExistingUserDirectScopeAccess() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["VEYQ_E2E"] == "1", "set VEYQ_E2E=1 to run against a live backend")
        let apiBase = env["VEYQ_API_BASE"] ?? "http://localhost:8080"

        // owner A: server owner, claims tenant T1 (holds its scope)
        let aPass = "correct horse battery staple"
        let (_, aKit) = try await SetupServer.registerServerOwner(apiBase: apiBase, email: "admin@example.com", displayName: "Admin", password: aPass)
        let a = try await Login.login(apiBase: apiBase, email: "admin@example.com", password: aPass, secretKeyB64: aKit.secretKey)
        let t1 = try await Tenant.createTenant(apiBase: apiBase, token: a.sessionToken, name: "acme", displayName: "Acme", domain: nil)
        let t1Invite = try await Tenant.issueOwnerInvite(apiBase: apiBase, token: a.sessionToken, tenantId: t1.id)
        _ = try await Tenant.claimTenantOwnership(
            apiBase: apiBase, token: a.sessionToken, inviteId: t1Invite.inviteId,
            bootstrapSecret: t1Invite.bootstrapSecret, identityKemPublicKey: a.kemPublicKey)

        // user B: a brand-new account (becomes owner of T2) — now in the key directory
        let bPass = "a whole new passphrase entirely"
        let t2 = try await Tenant.createTenant(apiBase: apiBase, token: a.sessionToken, name: "globex", displayName: "Globex", domain: nil)
        let t2Invite = try await Tenant.issueOwnerInvite(apiBase: apiBase, token: a.sessionToken, tenantId: t2.id)
        let bReg = try await RegisterViaInvitation.registerViaInvitation(
            apiBase: apiBase, inviteId: t2Invite.inviteId, bootstrapSecret: t2Invite.bootstrapSecret,
            email: "b@example.com", displayName: "B", password: bPass)
        let b = try await Login.login(apiBase: apiBase, email: "b@example.com", password: bPass, secretKeyB64: bReg.emergencyKit.secretKey)

        // A grants B direct access to T1's scope + the Member role
        let membership = try await InviteMembers.grantScopeToMember(
            apiBase: apiBase, owner: a, tenantId: t1.id, recipientEmail: "b@example.com", role: "Member")
        XCTAssertEqual(membership.userId, b.user.id)
        XCTAssertEqual(membership.role, "Member")
        XCTAssertEqual(membership.status, "ACTIVE")

        // B recovers T1's scope private key from the grant A wrapped to B's identity (server never held it)
        let grant = try await Tenant.getMyScopeGrant(apiBase: apiBase, token: b.sessionToken, tenantId: t1.id)
        let scopePriv = try Tenant.openScopeGrant(grant: grant, identity: b.identity, identityKemPublicKey: b.kemPublicKey)
        let recoveredXPub = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: scopePriv.kemX).publicKey.rawRepresentation
        let storedXPub = Data(Tenant.b64urlDecode(grant.scopePublicKey.kemPublicKey).prefix(32))
        XCTAssertEqual(recoveredXPub, storedXPub)
    }
}
