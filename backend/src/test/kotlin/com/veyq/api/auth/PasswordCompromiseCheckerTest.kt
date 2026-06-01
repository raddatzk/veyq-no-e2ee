package com.veyq.api.auth

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PasswordCompromiseCheckerTest {
    @Test
    fun `rejects passwords returned by the pwned password range response`() {
        val checker = checker(
            response = "1E4C9B93F3F0682250B6CF8331B7EE68FD8:46658894\nABCDEF:1"
        )

        assertThatThrownBy { checker.check("password") }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessage("Password appears in known breaches")
            .extracting("code")
            .isEqualTo(PASSWORD_COMPROMISED_CODE)
    }

    @Test
    fun `allows passwords that are not present in the range response`() {
        val checker = checker(response = "ABCDEF:1")

        assertThatCode { checker.check("not-the-response-password") }.doesNotThrowAnyException()
    }

    @Test
    fun `allows passwords when the remote check fails open`() {
        val checker = checker(failClosed = false, failure = IllegalStateException("timeout"))

        assertThatCode { checker.check("any-password") }.doesNotThrowAnyException()
    }

    @Test
    fun `rejects passwords when the remote check fails closed`() {
        val checker = checker(failClosed = true, failure = IllegalStateException("timeout"))

        assertThatThrownBy { checker.check("any-password") }
            .isInstanceOf(BadRequestException::class.java)
            .extracting("code")
            .isEqualTo("PASSWORD_COMPROMISE_CHECK_UNAVAILABLE")
    }

    private fun checker(
        response: String = "",
        failClosed: Boolean = false,
        failure: RuntimeException? = null,
    ): HaveIBeenPwnedPasswordCompromiseChecker =
        HaveIBeenPwnedPasswordCompromiseChecker(
            object : PwnedPasswordsConfig {
                override fun enabled(): Boolean = true
                override fun endpoint(): String = "https://api.pwnedpasswords.com/range"
                override fun timeoutMs(): Long = 2000
                override fun failClosed(): Boolean = failClosed
            },
            object : PwnedPasswordsRangeClient {
                override fun fetchRange(prefix: String): String {
                    if (failure != null) throw failure
                    return response
                }
            },
        )
}
