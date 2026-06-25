import XCTest
@testable import VeyqCrypto

/// Proves asym-wrap (encap) and asym-unwrap (decap) are mutually consistent on this stack. Combined with
/// the cross-stack decap KAT in ConformanceTests, this makes the encap direction interoperable too.
final class WrapRoundTripTests: XCTestCase {
    func testAsymWrapRoundTrip() throws {
        let id = try V1.generateIdentity()
        let secret = V1.randomBytes(32)
        let blob = try V1.asymWrap(pkX: id.pubKemX, pkM: id.pubKemM, targetKey: secret, context: "scope-grant", nonce: V1.randomBytes(12))
        let opened = try V1.asymUnwrap(
            skX: id.privKemX, mlkemSeed: id.mlkemSeed, pkX: id.pubKemX, pkM: id.pubKemM, blob: blob, context: "scope-grant")
        XCTAssertEqual(opened, secret)
    }
}
