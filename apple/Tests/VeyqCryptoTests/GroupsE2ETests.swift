import XCTest
import Foundation
@testable import VeyqCrypto

/// Real end-to-end against a running backend; opt-in via VEYQ_E2E=1. One server owner per boot, so the
/// whole group lifecycle (create → roster → invited member re-wrap → delete) runs in a single test.
final class GroupsE2ETests: XCTestCase {
    func testGroupLifecycle() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["VEYQ_E2E"] == "1", "set VEYQ_E2E=1 to run against a live backend")
        let apiBase = env["VEYQ_API_BASE"] ?? "http://localhost:8080"
        let pass = "correct horse battery staple"

        let (_, kit) = try await SetupServer.registerServerOwner(apiBase: apiBase, email: "gowner@example.com", displayName: "GOwner", password: pass)
        let owner = try await Login.login(apiBase: apiBase, email: "gowner@example.com", password: pass, secretKeyB64: kit.secretKey)
        let tenant = try await Tenant.createTenant(apiBase: apiBase, token: owner.sessionToken, name: "gacme", displayName: "GAcme", domain: nil)
        let oinv = try await Tenant.issueOwnerInvite(apiBase: apiBase, token: owner.sessionToken, tenantId: tenant.id)
        _ = try await Tenant.claimTenantOwnership(apiBase: apiBase, token: owner.sessionToken, inviteId: oinv.inviteId, bootstrapSecret: oinv.bootstrapSecret, identityKemPublicKey: owner.kemPublicKey)

        // create a group; the creator is its GroupOwner
        let group = try await Groups.createGroup(apiBase: apiBase, session: owner, tenantId: tenant.id, name: "Reisegruppe")
        XCTAssertEqual(group.role, "GroupOwner")

        // roster shows PERSONAL + TENANT + GROUP; open the group by id and decrypt its sealed name + write content
        let spaces = try await SwitchActiveSpace.listMySpaces(apiBase: apiBase, session: owner)
        XCTAssertEqual(Set(spaces.map { $0.kind }), ["PERSONAL", "TENANT", "GROUP"])
        let groupEntry = spaces.first { $0.kind == "GROUP" }!
        let oScope = try await SwitchActiveSpace.openSpace(apiBase: apiBase, session: owner, entry: groupEntry)
        let oCk = try await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: owner, scope: oScope)
        XCTAssertEqual(try Groups.openSealedName(contentKey: oCk, sealedB64: groupEntry.sealedName!.bytes), "Reisegruppe")
        _ = try await ScopeContent.writeItem(apiBase: apiBase, session: owner, scope: oScope, contentKey: oCk, plaintext: "crew secret")

        // a second user joins the tenant via a member invite; the owner adds them to the group
        let minv = try await InviteMembers.issueMemberInvite(apiBase: apiBase, owner: owner, tenantId: tenant.id, role: "Member")
        let reg = try await InviteMembers.registerViaMemberInvite(apiBase: apiBase, inviteId: minv.inviteId, bootstrapSecret: minv.bootstrapSecret, tempPrivateKeyB64: minv.tempPrivateKey, email: "gbob@example.com", displayName: "Bob", password: pass)
        let bob = try await Login.login(apiBase: apiBase, email: "gbob@example.com", password: pass, secretKeyB64: reg.emergencyKit.secretKey)
        _ = try await Groups.addGroupMember(apiBase: apiBase, session: owner, tenantId: tenant.id, scopeId: group.scopeId, userId: bob.user.id, targetKemPublicKeyB64: SetupServer.b64url(bob.kemPublicKey))

        // Bob's re-wrapped grant opens the group and decrypts its name + content
        let bobSpaces = try await SwitchActiveSpace.listMySpaces(apiBase: apiBase, session: bob)
        let bobGroup = bobSpaces.first { $0.kind == "GROUP" && $0.scopeId == group.scopeId }!
        let bScope = try await SwitchActiveSpace.openSpace(apiBase: apiBase, session: bob, entry: bobGroup)
        let bCk = try await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: bob, scope: bScope)
        XCTAssertEqual(try Groups.openSealedName(contentKey: bCk, sealedB64: bobGroup.sealedName!.bytes), "Reisegruppe")
        let items = try await ScopeContent.readItems(apiBase: apiBase, session: bob, scope: bScope, contentKey: bCk)
        XCTAssertTrue(items.contains("crew secret"))

        // owner sees 2 members and promotes Bob
        let memberCount = try await Groups.listGroupMembers(apiBase: apiBase, session: owner, tenantId: tenant.id, scopeId: group.scopeId).count
        XCTAssertEqual(memberCount, 2)
        let promoted = try await Groups.setGroupMemberRole(apiBase: apiBase, session: owner, tenantId: tenant.id, scopeId: group.scopeId, userId: bob.user.id, role: "GroupOwner")
        XCTAssertEqual(promoted.role, "GroupOwner")

        // delete the group (gone from roster), then the tenant (TENANT gone, personal remains)
        try await Groups.deleteGroup(apiBase: apiBase, session: owner, tenantId: tenant.id, scopeId: group.scopeId)
        let afterGroupDelete = try await SwitchActiveSpace.listMySpaces(apiBase: apiBase, session: owner).first { $0.scopeId == group.scopeId }
        XCTAssertNil(afterGroupDelete)
        try await Groups.deleteTenant(apiBase: apiBase, session: owner, tenantId: tenant.id)
        let after = try await SwitchActiveSpace.listMySpaces(apiBase: apiBase, session: owner)
        XCTAssertNil(after.first { $0.kind == "TENANT" })
        XCTAssertNotNil(after.first { $0.kind == "PERSONAL" })
    }
}
