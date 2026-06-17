import XCTest
import VeyqCrypto

final class PwnedTests: XCTestCase {
    func testSha1HexUpperKnownVector() {
        XCTAssertEqual(Pwned.sha1HexUpper("password"), "5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8")
    }

    func testBreachCountParsesMatchingSuffix() {
        let hash = "5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8"
        let body = "0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n1E4C9B93F3F0682250B6CF8331B7EE68FD8:9999999\r\nFFFF:3"
        XCTAssertEqual(Pwned.breachCount(hashHexUpper: hash, rangeBody: body), 9999999)
    }

    func testBreachCountZeroWhenAbsent() {
        XCTAssertEqual(Pwned.breachCount(hashHexUpper: "5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8",
                                        rangeBody: "0018A45C4D1DEF81644B54AB7F969B88D65:1"), 0)
    }
}
