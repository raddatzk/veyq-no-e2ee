import XCTest
import Foundation
@testable import VeyqCrypto

/// Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `swift test` skips it.
final class UseGroupE2ETests: XCTestCase {
    func testTwoMembersCollaborateOnOneSharedScope() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["VEYQ_E2E"] == "1", "set VEYQ_E2E=1 to run against a live backend")
        let apiBase = env["VEYQ_API_BASE"] ?? "http://localhost:8080"

        // owner A claims tenant T1
        let aPass = "correct horse battery staple"
        let (_, aKit) = try await SetupServer.registerServerOwner(apiBase: apiBase, email: "admin@example.com", displayName: "Admin", password: aPass)
        let a = try await Login.login(apiBase: apiBase, email: "admin@example.com", password: aPass, secretKeyB64: aKit.secretKey)
        let t1 = try await Tenant.createTenant(apiBase: apiBase, token: a.sessionToken, name: "acme", displayName: "Acme Group", domain: nil)
        let inv = try await Tenant.issueOwnerInvite(apiBase: apiBase, token: a.sessionToken, tenantId: t1.id)
        _ = try await Tenant.claimTenantOwnership(apiBase: apiBase, token: a.sessionToken, inviteId: inv.inviteId, bootstrapSecret: inv.bootstrapSecret, identityKemPublicKey: a.kemPublicKey)

        // member B joins T1 via a temp-wrap invite
        let bPass = "a whole new passphrase entirely"
        let memberInvite = try await InviteMembers.issueMemberInvite(apiBase: apiBase, owner: a, tenantId: t1.id, role: "Member")
        let bReg = try await InviteMembers.registerViaMemberInvite(
            apiBase: apiBase, inviteId: memberInvite.inviteId, bootstrapSecret: memberInvite.bootstrapSecret,
            tempPrivateKeyB64: memberInvite.tempPrivateKey, email: "b@example.com", displayName: "B", password: bPass)
        let b = try await Login.login(apiBase: apiBase, email: "b@example.com", password: bPass, secretKeyB64: bReg.emergencyKit.secretKey)

        // the group roster shows T1 to both — with each member's own role
        let aGroups = try await UseGroup.listMyGroups(apiBase: apiBase, session: a)
        let aT1 = aGroups.first { $0.tenantId == t1.id }
        XCTAssertEqual(aT1?.role, "Owner")
        XCTAssertEqual(aT1?.displayName, "Acme Group")
        let bGroups = try await UseGroup.listMyGroups(apiBase: apiBase, session: b)
        XCTAssertEqual(bGroups.first { $0.tenantId == t1.id }?.role, "Member")

        // both members write shared content under the one group scope (content key provisioned once)
        let aScope = try await ScopeContent.openTenantScope(apiBase: apiBase, session: a, tenantId: t1.id)
        let aCk = try await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: a, scope: aScope)
        try await ScopeContent.writeItem(apiBase: apiBase, session: a, scope: aScope, contentKey: aCk, plaintext: "note from A")

        let bScope = try await ScopeContent.openTenantScope(apiBase: apiBase, session: b, tenantId: t1.id)
        let bCk = try await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: b, scope: bScope)
        try await ScopeContent.writeItem(apiBase: apiBase, session: b, scope: bScope, contentKey: bCk, plaintext: "note from B")

        // any member reads the whole shared set — both see both items (access = the wrap, ADR 0005)
        let aItems = try await ScopeContent.readItems(apiBase: apiBase, session: a, scope: aScope, contentKey: aCk)
        XCTAssertTrue(aItems.contains("note from A") && aItems.contains("note from B"))
        let bItems = try await ScopeContent.readItems(apiBase: apiBase, session: b, scope: bScope, contentKey: bCk)
        XCTAssertTrue(bItems.contains("note from A") && bItems.contains("note from B"))
    }
}
