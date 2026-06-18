import XCTest
import VeyqCrypto

// Native parity for the space-path selector + slug — byte-compatible with the web parser
// frontend/src/features/home/homeRoute.ts (mirrors homeRoute.test.ts).
final class SpacePathTests: XCTestCase {
    func testParsesPersonalGroupAndTenantPrefixes() {
        let personal = SpacePath.parsePath("/~me/a/b")
        XCTAssertEqual(personal.kind, .personal)
        XCTAssertEqual(personal.segments, ["a", "b"])

        let group = SpacePath.parsePath("/@reisegruppe/urlaube")
        XCTAssertEqual(group.kind, .group)
        XCTAssertEqual(group.groupSlug, "reisegruppe")
        XCTAssertEqual(group.segments, ["urlaube"])

        let tenant = SpacePath.parsePath("/urlaube/italien")
        XCTAssertEqual(tenant.kind, .tenant)
        XCTAssertEqual(tenant.segments, ["urlaube", "italien"])

        XCTAssertEqual(SpacePath.parsePath("/").kind, .tenant)
        XCTAssertEqual(SpacePath.parsePath("/").segments, [])
    }

    func testSlugifyMatchesWebRoundTrip() {
        XCTAssertEqual(SpacePath.slugify("Reise Gruppe"), "reise-gruppe")
        XCTAssertEqual(SpacePath.slugify("Über  Alles!!"), "uber-alles")  // diacritics folded, runs collapsed
        XCTAssertEqual(SpacePath.slugify("---"), "item")                  // nothing left after stripping
        XCTAssertEqual(SpacePath.slugify("Ab"), "ab")
    }
}
