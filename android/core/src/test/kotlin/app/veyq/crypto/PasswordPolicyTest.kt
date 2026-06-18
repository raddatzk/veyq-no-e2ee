package app.veyq.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Native parity for the client password policy — byte-compatible with the web and iOS versions
// (frontend/src/shared/passwordPolicy.ts; mirrors passwordPolicy.test.ts).
class PasswordPolicyTest {
    @Test fun minimumLengthIs10() {
        assertEquals(10, PasswordPolicy.MIN_LENGTH)
    }

    @Test fun tooShortOnlyFlagsNonEmptyBelowMinimum() {
        assertFalse(PasswordPolicy.tooShort(""))            // empty → never flagged
        assertTrue(PasswordPolicy.tooShort("abc"))          // below the minimum
        assertFalse(PasswordPolicy.tooShort("abcdefghij"))  // exactly 10 → ok
    }

    @Test fun hintKeyFollowsLengthTiers() {
        assertNull(PasswordPolicy.hintKey(""))                                     // empty → no hint
        assertEquals("password.minLength", PasswordPolicy.hintKey("abc"))          // too short
        assertNull(PasswordPolicy.hintKey("abcdefghij"))                           // 10–15 → acceptable middle
        assertEquals("password.strong", PasswordPolicy.hintKey("abcdefghij123456"))  // >=16
    }
}
