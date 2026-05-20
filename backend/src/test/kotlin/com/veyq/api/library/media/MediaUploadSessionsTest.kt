package com.veyq.api.library.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class MediaUploadSessionsTest {
    @Test
    fun `round trips upload session properties`() {
        val now = Instant.parse("2026-01-01T10:00:00Z")
        val session = UploadSession(
            uploadId = "123e4567-e89b-12d3-a456-426614174000",
            tenantId = 42,
            tenantName = "friends",
            ownerUserId = 7,
            albumPath = "/photos",
            filename = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 123,
            title = "Photo",
            createdAt = now,
            updatedAt = now,
        )

        val restored = MediaUploadSessions.fromProperties(
            properties = MediaUploadSessions.toProperties(session),
            updatedAtFallback = Instant.EPOCH,
            tenantNameFallback = { null },
        )

        assertThat(restored).isEqualTo(session)
    }

    @Test
    fun `validates upload ids and supports legacy tenant name fallback`() {
        assertThat(MediaUploadSessions.hasValidUploadId("123e4567-e89b-12d3-a456-426614174000")).isTrue()
        assertThat(MediaUploadSessions.hasValidUploadId("../bad")).isFalse()

        val properties = MediaUploadSessions.toProperties(
            UploadSession(
                uploadId = "123e4567-e89b-12d3-a456-426614174000",
                tenantId = 42,
                tenantName = "friends",
                ownerUserId = 7,
                albumPath = "/photos",
                filename = "photo.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 123,
                title = null,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
        )
        properties.remove("tenantName")

        val restored = MediaUploadSessions.fromProperties(
            properties = properties,
            updatedAtFallback = Instant.EPOCH,
            tenantNameFallback = { "fallback" },
        )

        assertThat(restored.tenantName).isEqualTo("fallback")
    }
}
