package com.veyq.api.memory

import com.veyq.api.auth.ForbiddenException
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@QuarkusTest
@TestProfile(InsecureJournalLiveConfigProfile::class)
class JournalLiveSecurityTest {
    @Inject lateinit var memoryJournalService: MemoryJournalService
    @Inject lateinit var objectMapper: ObjectMapper

    @Test
    fun `rejects forged journal live tokens when live sync uses insecure defaults`() {
        val token = forgedToken(
            MemoryJournalService.JournalLiveTokenPayload(
                documentId = "memory:known-memory:journal",
                tenantName = "known-tenant",
                memoryId = "known-memory",
                displayName = "Forged editor",
                canEdit = true,
                expiresAt = Instant.now().plusSeconds(3600).epochSecond,
            )
        )

        assertThatExceptionOfType(ForbiddenException::class.java).isThrownBy {
            memoryJournalService.validateLiveSession(token, "memory:known-memory:journal")
        }
    }

    @Test
    fun `rejects collaboration session endpoints when sidecar secret is not configured`() {
        given()
            .contentType("application/json")
            .body("""{"token":"invalid","documentId":"memory:known-memory:journal"}""")
            .`when`().post("/api/collaboration/journal/session/validate")
            .then()
            .statusCode(403)
            .body("code", equalTo("MEMORY_JOURNAL_LIVE_NOT_CONFIGURED"))
    }

    private fun forgedToken(payload: MemoryJournalService.JournalLiveTokenPayload): String {
        val encodedPayload = base64Url(objectMapper.writeValueAsBytes(payload))
        val signature = hmac(encodedPayload, "dev-only-change-me")
        return "$encodedPayload.$signature"
    }

    private fun hmac(value: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return base64Url(mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

class InsecureJournalLiveConfigProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "veyq.journal.live.token-secret" to "dev-only-change-me",
        "veyq.journal.live.sidecar-secret" to "",
    )
}
