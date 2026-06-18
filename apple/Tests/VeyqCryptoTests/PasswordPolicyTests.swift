import XCTest
import VeyqCrypto

// Native parity for the client password policy — byte-compatible with the web parser
// frontend/src/shared/passwordPolicy.ts (mirrors passwordPolicy.test.ts).
final class PasswordPolicyTests: XCTestCase {
    func testMinimumLengthIs10() {
        XCTAssertEqual(PasswordPolicy.minLength, 10)
    }

    func testTooShortOnlyFlagsNonEmptyBelowMinimum() {
        XCTAssertFalse(PasswordPolicy.tooShort(""))            // empty → never flagged
        XCTAssertTrue(PasswordPolicy.tooShort("abc"))          // below the minimum
        XCTAssertFalse(PasswordPolicy.tooShort("abcdefghij"))  // exactly 10 → ok
    }

    func testHintKeyFollowsLengthTiers() {
        XCTAssertNil(PasswordPolicy.hintKey(""))                                    // empty → no hint
        XCTAssertEqual(PasswordPolicy.hintKey("abc"), "password.minLength")         // too short
        XCTAssertNil(PasswordPolicy.hintKey("abcdefghij"))                          // 10–15 → acceptable middle
        XCTAssertEqual(PasswordPolicy.hintKey("abcdefghij123456"), "password.strong")  // ≥16
    }
}
