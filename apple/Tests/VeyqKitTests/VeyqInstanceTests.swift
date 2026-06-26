import XCTest
@testable import VeyqKit

final class VeyqInstanceTests: XCTestCase {
    /// Phase-1 smoke: the scaffold links (VeyqKit → VeyqApi → generated clients → VeyqCrypto).
    func testInstanceHoldsBaseURL() throws {
        let url = try XCTUnwrap(URL(string: "https://veyq.example"))
        let instance = VeyqInstance(baseURL: url)
        XCTAssertEqual(instance.baseURL.absoluteString, "https://veyq.example")
    }
}
