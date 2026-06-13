import XCTest
import Foundation
@testable import VeyqCrypto

/// Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `swift test` skips it.
final class ContentSurvivesRotationE2ETests: XCTestCase {
    func testContentKeyReWrappedOnRotation() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["VEYQ_E2E"] == "1", "set VEYQ_E2E=1 to run against a live backend")
        let apiBase = env["VEYQ_API_BASE"] ?? "http://localhost:8080"

        // owner A claims T1 and writes encrypted content under its (tenant) scope
        let aPass = "correct horse battery staple"
        let (_, aKit) = try await SetupServer.registerServerOwner(apiBase: apiBase, email: "admin@example.com", displayName: "Admin", password: aPass)
        let a = try await Login.login(apiBase: apiBase, email: "admin@example.com", password: aPass, secretKeyB64: aKit.secretKey)
        let t1 = try await Tenant.createTenant(apiBase: apiBase, token: a.sessionToken, name: "acme", displayName: "Acme", domain: nil)
        let inv = try await Tenant.issueOwnerInvite(apiBase: apiBase, token: a.sessionToken, tenantId: t1.id)
        _ = try await Tenant.claimTenantOwnership(apiBase: apiBase, token: a.sessionToken, inviteId: inv.inviteId, bootstrapSecret: inv.bootstrapSecret, identityKemPublicKey: a.kemPublicKey)

        let scopeBefore = try await ScopeContent.openTenantScope(apiBase: apiBase, session: a, tenantId: t1.id)
        let ckBefore = try await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: a, scope: scopeBefore)
        try await ScopeContent.writeItem(apiBase: apiBase, session: a, scope: scopeBefore, contentKey: ckBefore, plaintext: "trip notes")

        // member B joins T1 via a temp-wrap invite
        let bPass = "a whole new passphrase entirely"
        let memberInvite = try await InviteMembers.issueMemberInvite(apiBase: apiBase, owner: a, tenantId: t1.id, role: "Member")
        let bReg = try await InviteMembers.registerViaMemberInvite(
            apiBase: apiBase, inviteId: memberInvite.inviteId, bootstrapSecret: memberInvite.bootstrapSecret,
            tempPrivateKeyB64: memberInvite.tempPrivateKey, email: "b@example.com", displayName: "B", password: bPass)
        let b = try await Login.login(apiBase: apiBase, email: "b@example.com", password: bPass, secretKeyB64: bReg.emergencyKit.secretKey)

        // A rotates the scope — the content key is re-wrapped to the new keypair
        _ = try await InviteMembers.rotateScope(apiBase: apiBase, owner: a, tenantId: t1.id)

        // A still reads pre-rotation content under the freshly-granted new keypair
        let aScope = try await ScopeContent.openTenantScope(apiBase: apiBase, session: a, tenantId: t1.id)
        let aCk = try await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: a, scope: aScope)
        let aItems = try await ScopeContent.readItems(apiBase: apiBase, session: a, scope: aScope, contentKey: aCk)
        XCTAssertTrue(aItems.contains("trip notes"))

        // and B — granted only at rotation — reads content written before B even joined
        let bScope = try await ScopeContent.openTenantScope(apiBase: apiBase, session: b, tenantId: t1.id)
        let bCk = try await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: b, scope: bScope)
        let bItems = try await ScopeContent.readItems(apiBase: apiBase, session: b, scope: bScope, contentKey: bCk)
        XCTAssertTrue(bItems.contains("trip notes"))
    }
}
