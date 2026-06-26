import XCTest
import Foundation
@testable import VeyqKit

// uc.account.unlock — Emergency Kit import (apple, cr-0017). Proves the parse path: a `veyqkit:` token
// (built here the way setup/web's `kitPayload` does) round-trips through `parseKit` to the exact email +
// Secret Key, including when embedded in PDF-shaped bytes; a non-kit blob yields nil. On-device only.
final class KitTests: XCTestCase {

    private let email = "kevin@veyq.test"
    private let secretKey = "QEp7TgT/fdk7MhHigYQTS9o47PK7TSKjB/dpVq+bs14="

    /// Build a kit token exactly like web `kitPayload`: `veyqkit:` + base64url(JSON{v:1,email,sk}).
    private func makeToken(email: String, sk: String) -> String {
        let json = try! JSONSerialization.data(withJSONObject: ["v": 1, "email": email, "sk": sk])
        let b64url = json.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return "veyqkit:" + b64url
    }

    func testParseKitRoundTrips() throws {
        let kit = parseKit(makeToken(email: email, sk: secretKey))
        XCTAssertEqual(kit, KitData(email: email, secretKey: secretKey))
    }

    func testImportFromPdfShapedBytes() throws {
        // The token is embedded uncompressed amongst binary PDF noise, as the kit PDF stores it.
        let token = makeToken(email: email, sk: secretKey)
        var bytes = Data([0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37, 0x00, 0xFF]) // "%PDF-1.7" + noise
        bytes.append(Data(token.utf8))
        bytes.append(Data([0x00, 0xFE, 0x0A, 0x25, 0x25, 0x45, 0x4F, 0x46])) // noise + "%%EOF"
        XCTAssertEqual(importKitFromData(bytes), KitData(email: email, secretKey: secretKey))
    }

    func testNonKitFileIsNil() throws {
        XCTAssertNil(importKitFromData(Data("just some text, no kit here".utf8)))
        XCTAssertNil(parseKit("veyqkit:not-valid-base64-json!!!"))
        // Wrong version is rejected.
        let json = try! JSONSerialization.data(withJSONObject: ["v": 2, "email": email, "sk": secretKey])
        let b64url = json.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        XCTAssertNil(parseKit("veyqkit:" + b64url))
    }
}
