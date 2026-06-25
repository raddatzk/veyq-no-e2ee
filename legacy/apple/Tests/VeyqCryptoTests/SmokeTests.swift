import XCTest
import CryptoKit
@testable import VeyqCrypto

final class SmokeTests: XCTestCase {
    func testSuiteId() {
        XCTAssertEqual(VeyqCrypto.suiteId, "v1")
    }

    // Proves CryptoKit's ML-KEM-768 (FIPS 203) is available on this toolchain — the PQC
    // primitive the v1 hybrid KEM needs on Apple platforms.
    func testMLKEM768RoundTrip() throws {
        let secretKey = try MLKEM768.PrivateKey()
        let publicKey = secretKey.publicKey
        let result = try publicKey.encapsulate()
        let recovered = try secretKey.decapsulate(result.encapsulated)
        let a = result.sharedSecret.withUnsafeBytes { Data($0) }
        let b = recovered.withUnsafeBytes { Data($0) }
        XCTAssertEqual(a, b)
    }
}
