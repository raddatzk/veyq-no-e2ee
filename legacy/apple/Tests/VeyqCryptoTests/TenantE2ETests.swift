import XCTest
import Foundation
import CryptoKit
@testable import VeyqCrypto

/// Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `swift test` skips it.
final class TenantE2ETests: XCTestCase {
    func testProvisionClaimAndGrant() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["VEYQ_E2E"] == "1", "set VEYQ_E2E=1 to run against a live backend")
        let apiBase = env["VEYQ_API_BASE"] ?? "http://localhost:8080"
        let email = "owner@example.com"
        let password = "correct horse battery staple"

        let (_, kit) = try await SetupServer.registerServerOwner(apiBase: apiBase, email: email, displayName: "Owner", password: password)
        let session = try await Login.login(apiBase: apiBase, email: email, password: password, secretKeyB64: kit.secretKey)

        let tenant = try await Tenant.createTenant(apiBase: apiBase, token: session.sessionToken, name: "acme", displayName: "Acme", domain: "acme.example")
        XCTAssertEqual(tenant.status, "PENDING")

        let invite = try await Tenant.issueOwnerInvite(apiBase: apiBase, token: session.sessionToken, tenantId: tenant.id)
        XCTAssertFalse(invite.bootstrapSecret.isEmpty)

        let claimed = try await Tenant.claimTenantOwnership(
            apiBase: apiBase, token: session.sessionToken, inviteId: invite.inviteId,
            bootstrapSecret: invite.bootstrapSecret, identityKemPublicKey: session.kemPublicKey)
        XCTAssertEqual(claimed.status, "ACTIVE")

        let grant = try await Tenant.getMyScopeGrant(apiBase: apiBase, token: session.sessionToken, tenantId: tenant.id)
        let scopePriv = try Tenant.openScopeGrant(grant: grant, identity: session.identity, identityKemPublicKey: session.kemPublicKey)

        let recoveredXPub = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: scopePriv.kemX).publicKey.rawRepresentation
        let storedXPub = Data(Tenant.b64urlDecode(grant.scopePublicKey.kemPublicKey).prefix(32))
        XCTAssertEqual(recoveredXPub, storedXPub)
    }
}
