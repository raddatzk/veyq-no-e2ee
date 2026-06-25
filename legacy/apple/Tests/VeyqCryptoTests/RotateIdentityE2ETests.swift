import XCTest
import Foundation
import CryptoKit
@testable import VeyqCrypto

/// Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `swift test` skips it.
final class RotateIdentityE2ETests: XCTestCase {
    func testRotateIdentityKeepsAccessToScopesAndContent() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["VEYQ_E2E"] == "1", "set VEYQ_E2E=1 to run against a live backend")
        let apiBase = env["VEYQ_API_BASE"] ?? "http://localhost:8080"

        let pass = "correct horse battery staple"
        let (_, kit) = try await SetupServer.registerServerOwner(apiBase: apiBase, email: "owner@example.com", displayName: "Owner", password: pass)
        let a = try await Login.login(apiBase: apiBase, email: "owner@example.com", password: pass, secretKeyB64: kit.secretKey)

        // a tenant scope grant + encrypted content under the personal scope
        let t1 = try await Tenant.createTenant(apiBase: apiBase, token: a.sessionToken, name: "acme", displayName: "Acme", domain: nil)
        let inv = try await Tenant.issueOwnerInvite(apiBase: apiBase, token: a.sessionToken, tenantId: t1.id)
        _ = try await Tenant.claimTenantOwnership(apiBase: apiBase, token: a.sessionToken, inviteId: inv.inviteId, bootstrapSecret: inv.bootstrapSecret, identityKemPublicKey: a.kemPublicKey)
        let scope = try await ScopeContent.openPersonalScope(apiBase: apiBase, session: a)
        let ck = try await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: a, scope: scope)
        try await ScopeContent.writeItem(apiBase: apiBase, session: a, scope: scope, contentKey: ck, plaintext: "personal secret")

        // rotate the identity keypair
        _ = try await RotateIdentity.rotateIdentity(apiBase: apiBase, session: a)

        // re-login unlocks the NEW identity under the unchanged master key
        let a2 = try await Login.login(apiBase: apiBase, email: "owner@example.com", password: pass, secretKeyB64: kit.secretKey)
        XCTAssertNotEqual(a2.kemPublicKey, a.kemPublicKey)

        // the personal scope + its content are still readable via the new identity
        let scope2 = try await ScopeContent.openPersonalScope(apiBase: apiBase, session: a2)
        let ck2 = try await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: a2, scope: scope2)
        let items = try await ScopeContent.readItems(apiBase: apiBase, session: a2, scope: scope2, contentKey: ck2)
        XCTAssertTrue(items.contains("personal secret"))

        // and the tenant scope grant opens under the new identity too
        let tGrant = try await Tenant.getMyScopeGrant(apiBase: apiBase, token: a2.sessionToken, tenantId: t1.id)
        let tScopePriv = try Tenant.openScopeGrant(grant: tGrant, identity: a2.identity, identityKemPublicKey: a2.kemPublicKey)
        let recoveredX = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: tScopePriv.kemX).publicKey.rawRepresentation
        let storedX = Data(Tenant.b64urlDecode(tGrant.scopePublicKey.kemPublicKey).prefix(32))
        XCTAssertEqual(recoveredX, storedX)
    }
}
