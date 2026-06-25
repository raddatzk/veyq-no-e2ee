import XCTest
import Foundation
import CryptoKit
@testable import VeyqCrypto

/// Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `swift test` skips it.
final class RegisterViaInvitationE2ETests: XCTestCase {
    func testRegisterNewOwnerThenLoginAndGrant() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["VEYQ_E2E"] == "1", "set VEYQ_E2E=1 to run against a live backend")
        let apiBase = env["VEYQ_API_BASE"] ?? "http://localhost:8080"

        // a Server Owner provisions the tenant + owner invite
        let adminPassword = "correct horse battery staple"
        let (_, adminKit) = try await SetupServer.registerServerOwner(apiBase: apiBase, email: "admin@example.com", displayName: "Admin", password: adminPassword)
        let admin = try await Login.login(apiBase: apiBase, email: "admin@example.com", password: adminPassword, secretKeyB64: adminKit.secretKey)
        let tenant = try await Tenant.createTenant(apiBase: apiBase, token: admin.sessionToken, name: "acme", displayName: "Acme", domain: "acme.example")
        let invite = try await Tenant.issueOwnerInvite(apiBase: apiBase, token: admin.sessionToken, tenantId: tenant.id)

        // public metadata is visible without the bootstrap secret
        let meta = try await RegisterViaInvitation.getInviteMetadata(apiBase: apiBase, inviteId: invite.inviteId)
        XCTAssertEqual(meta.tenant.name, "acme")
        XCTAssertEqual(meta.role, "Owner")
        XCTAssertEqual(meta.status, "PENDING")

        // the invitee (a brand-new account) registers and claims the tenant in one step
        let inviteePassword = "a whole new passphrase entirely"
        let result = try await RegisterViaInvitation.registerViaInvitation(
            apiBase: apiBase, inviteId: invite.inviteId, bootstrapSecret: invite.bootstrapSecret,
            email: "tenant-owner@example.com", displayName: "Tenant Owner", password: inviteePassword)
        XCTAssertEqual(result.tenant.status, "ACTIVE")
        XCTAssertEqual(result.user.email, "tenant-owner@example.com")
        XCTAssertEqual(result.user.serverRole, "ServerMember")
        XCTAssertFalse(result.sessionToken.isEmpty)

        // the new account can log in independently (proves the auth verifier + master wrap are consistent)
        let session = try await Login.login(apiBase: apiBase, email: "tenant-owner@example.com", password: inviteePassword, secretKeyB64: result.emergencyKit.secretKey)
        XCTAssertEqual(session.user.id, result.user.id)

        // and recover its personal scope grant — unshared, scoped to this account
        let personalGrant = try await Login.fetchPersonalScope(apiBase: apiBase, sessionToken: session.sessionToken)
        let personalPriv = try Login.openPersonalScopeGrant(grant: personalGrant, identity: session.identity, identityKemPublicKey: session.kemPublicKey)
        let recoveredPersonalXPub = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: personalPriv.kemX).publicKey.rawRepresentation
        let storedPersonalXPub = Data(Login.b64urlDecode(personalGrant.scopePublicKey.kemPublicKey).prefix(32))
        XCTAssertEqual(recoveredPersonalXPub, storedPersonalXPub)

        // and recover its tenant scope grant — the server never held the scope private key
        let grant = try await Tenant.getMyScopeGrant(apiBase: apiBase, token: session.sessionToken, tenantId: tenant.id)
        let scopePriv = try Tenant.openScopeGrant(grant: grant, identity: session.identity, identityKemPublicKey: session.kemPublicKey)
        let recoveredXPub = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: scopePriv.kemX).publicKey.rawRepresentation
        let storedXPub = Data(Tenant.b64urlDecode(grant.scopePublicKey.kemPublicKey).prefix(32))
        XCTAssertEqual(recoveredXPub, storedXPub)
    }
}
