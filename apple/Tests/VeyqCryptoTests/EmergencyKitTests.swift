import XCTest
@testable import VeyqCrypto

final class EmergencyKitTests: XCTestCase {
    let data = EmergencyKit.KitData(instance: "https://veyq.example", email: "a@veyq.app", secretKey: "KEY-abc_123")

    func testRoundTrip() {
        XCTAssertEqual(EmergencyKit.parse(EmergencyKit.payload(data)), data)
    }

    func testFindsPayloadEmbeddedInText() {
        let blob = "%PDF-1.7 …/Keywords (\(EmergencyKit.payload(data)))… stream"
        XCTAssertEqual(EmergencyKit.parse(blob), data)
    }

    // A kit produced by the web client must parse here byte-for-byte (cross-platform token).
    func testParsesWebProducedToken() {
        let web = "veyqkit:" + base64url("{\"v\":1,\"instance\":\"\",\"email\":\"a@veyq.app\",\"sk\":\"KEY-abc_123\"}")
        XCTAssertEqual(EmergencyKit.parse(web), EmergencyKit.KitData(instance: "", email: "a@veyq.app", secretKey: "KEY-abc_123"))
    }

    func testRejectsGarbage() {
        XCTAssertNil(EmergencyKit.parse("no kit token here"))
        XCTAssertNil(EmergencyKit.parse("veyqkit:not-valid-json"))
    }

    private func base64url(_ s: String) -> String {
        Data(s.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
