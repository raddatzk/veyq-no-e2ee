import XCTest
import VeyqCrypto

// Native parity for invite-link build/parse — byte-compatible with the web parser
// frontend/src/features/spaces/invite/inviteLink.ts (mirrors inviteLink.test.ts).
final class InviteLinkTests: XCTestCase {
    func testRoundTripsPragmaticInviteThroughFragment() {
        let link = InviteLink.build(origin: "https://veyq.example", inviteId: "inv1", bootstrapSecret: "boot", tempPrivateKey: "tkey")
        let p = InviteLink.parse(link)
        XCTAssertNotNil(p)
        XCTAssertEqual(p?.inviteId, "inv1")
        XCTAssertEqual(p?.bootstrapSecret, "boot")
        XCTAssertEqual(p?.tempPrivateKey, "tkey")
        XCTAssertFalse(p?.isProtected ?? true)
    }

    func testRoundTripsPassphraseProtectedInvite() {
        let link = InviteLink.buildProtected(origin: "https://veyq.example", inviteId: "inv1", bootstrapSecret: "boot", encTempKey: "enc", pwSalt: "salt")
        let p = InviteLink.parse(link)
        XCTAssertEqual(p?.encTempKey, "enc")
        XCTAssertEqual(p?.pwSalt, "salt")
        XCTAssertNil(p?.tempPrivateKey)
        XCTAssertTrue(p?.isProtected ?? false)
    }

    func testKeepsSecretsInFragmentNotQuery() {
        let link = InviteLink.build(origin: "https://veyq.example", inviteId: "inv1", bootstrapSecret: "boot", tempPrivateKey: "tkey")
        XCTAssertTrue(link.contains("#invite="))
        XCTAssertFalse(link.contains("?"))  // secrets ride the fragment, never the query
        let frag = link.components(separatedBy: "#").last ?? ""
        XCTAssertTrue(frag.contains("s=boot"))
        XCTAssertTrue(frag.contains("k=tkey"))
    }

    func testReturnsNilForNonInviteHashes() {
        XCTAssertNil(InviteLink.parse("https://veyq.example/#k=onlykey"))  // no invite / s
        XCTAssertNil(InviteLink.parse("#foo=bar"))
        XCTAssertNil(InviteLink.parse("not a link"))
    }
}
