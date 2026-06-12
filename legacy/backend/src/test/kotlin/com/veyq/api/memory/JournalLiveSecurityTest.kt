package com.veyq.api.memory

import com.veyq.api.auth.ForbiddenException
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThatExceptionOfType
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
        assertThatExceptionOfType(ForbiddenException::class.java).isThrownBy {
            memoryJournalService.validateLiveSession(forgedToken(), DOCUMENT_ID)
        }
    }

    @Test
    fun `rejects forged journal live snapshot writes when live sync uses insecure defaults`() {
        assertThatExceptionOfType(ForbiddenException::class.java).isThrownBy {
            memoryJournalService.persistLiveSnapshot(forgedToken(), DOCUMENT_ID, "# overwritten")
        }
    }

    // A token forged with the well-known development secret. Verification must refuse it because the
    // live token secret is left at that insecure default, before any signature or payload is trusted.
    private fun forgedToken(): String {
        val payload = MemoryJournalService.JournalLiveTokenPayload(
            documentId = DOCUMENT_ID,
            tenantName = "known-tenant",
            memoryId = "known-memory",
            displayName = "Forged editor",
            canEdit = true,
            expiresAt = Instant.now().plusSeconds(3600).epochSecond,
        )
        val encodedPayload = base64Url(objectMapper.writeValueAsBytes(payload))
        return "$encodedPayload.${hmac(encodedPayload, "dev-only-change-me")}"
    }

    private fun hmac(value: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return base64Url(mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    companion object {
        private const val DOCUMENT_ID = "memory:known-memory:journal"
    }
}

class InsecureJournalLiveConfigProfile : QuarkusTestProfile {
    // Leave the live token secret at the insecure development default so token verification must
    // reject every token, mirroring a deployment that never configured journal live sync securely.
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "veyq.journal.live.token-secret" to "dev-only-change-me",
        "veyq.journal.live.sidecar-secret" to "integration-sidecar-secret",
    )
}
