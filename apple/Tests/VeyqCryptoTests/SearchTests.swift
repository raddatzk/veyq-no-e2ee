import XCTest
import VeyqCrypto

// Native parity for frontend/src/features/search/search.test.ts.
final class SearchTests: XCTestCase {
    private let items = ["Italy 2024", "Italian dinner", "Paris"]

    func testMatchesCaseInsensitivelyAsASubstring() {
        XCTAssertEqual(Search.matchByName(items, query: "ital", name: { $0 }), ["Italy 2024", "Italian dinner"])
        XCTAssertEqual(Search.matchByName(items, query: "PARIS", name: { $0 }), ["Paris"])
    }

    func testReturnsNoHitsForAnEmptyOrWhitespaceQuery() {
        XCTAssertEqual(Search.matchByName(items, query: "", name: { $0 }), [])
        XCTAssertEqual(Search.matchByName(items, query: "   ", name: { $0 }), [])
    }

    func testReturnsNoHitsWhenNothingMatches() {
        XCTAssertEqual(Search.matchByName(items, query: "zzz", name: { $0 }), [])
    }

    func testCapsResultsAt25() {
        let many = (0..<40).map { "node \($0)" }
        XCTAssertEqual(Search.matchByName(many, query: "node", name: { $0 }).count, 25)
    }
}
