import XCTest
import Foundation
@testable import VeyqCrypto

/// Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `swift test` skips it.
final class SwitchActiveSpaceE2ETests: XCTestCase {
    func testListsPersonalPlusTenantRootAndSwitchesBetweenThem() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["VEYQ_E2E"] == "1", "set VEYQ_E2E=1 to run against a live backend")
        let apiBase = env["VEYQ_API_BASE"] ?? "http://localhost:8080"

        let pass = "correct horse battery staple"
        let (_, kit) = try await SetupServer.registerServerOwner(apiBase: apiBase, email: "admin@example.com", displayName: "Admin", password: pass)
        let a = try await Login.login(apiBase: apiBase, email: "admin@example.com", password: pass, secretKeyB64: kit.secretKey)
        let t1 = try await Tenant.createTenant(apiBase: apiBase, token: a.sessionToken, name: "acme", displayName: "Acme Group", domain: nil)
        let inv = try await Tenant.issueOwnerInvite(apiBase: apiBase, token: a.sessionToken, tenantId: t1.id)
        _ = try await Tenant.claimTenantOwnership(apiBase: apiBase, token: a.sessionToken, inviteId: inv.inviteId, bootstrapSecret: inv.bootstrapSecret, identityKemPublicKey: a.kemPublicKey)

        // the switcher lists both spaces: the personal one + the tenant root just claimed
        let spaces = try await SwitchActiveSpace.listMySpaces(apiBase: apiBase, session: a)
        let personal = spaces.first { $0.kind == "PERSONAL" }
        let tenant = spaces.first { $0.kind == "TENANT" }
        XCTAssertEqual(personal?.displayName, "Admin")
        XCTAssertEqual(tenant?.role, "Owner")
        XCTAssertEqual(tenant?.tenantId, t1.id)

        // switch into the personal space and write there (keys already held)
        let pScope = try await SwitchActiveSpace.openSpace(apiBase: apiBase, session: a, entry: personal!)
        let pCk = try await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: a, scope: pScope)
        try await ScopeContent.writeItem(apiBase: apiBase, session: a, scope: pScope, contentKey: pCk, plaintext: "personal note")

        // switch into the tenant-root space and write there
        let gScope = try await SwitchActiveSpace.openSpace(apiBase: apiBase, session: a, entry: tenant!)
        let gCk = try await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: a, scope: gScope)
        try await ScopeContent.writeItem(apiBase: apiBase, session: a, scope: gScope, contentKey: gCk, plaintext: "group note")

        // each space sees only its own content — switching truly changes the active scope context
        let pItems = try await ScopeContent.readItems(apiBase: apiBase, session: a, scope: pScope, contentKey: pCk)
        let gItems = try await ScopeContent.readItems(apiBase: apiBase, session: a, scope: gScope, contentKey: gCk)
        XCTAssertTrue(pItems.contains("personal note") && !pItems.contains("group note"))
        XCTAssertTrue(gItems.contains("group note") && !gItems.contains("personal note"))
    }
}
