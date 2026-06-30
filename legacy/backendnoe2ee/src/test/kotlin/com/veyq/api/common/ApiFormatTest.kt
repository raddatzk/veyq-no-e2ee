package com.veyq.api.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ApiFormatTest {
    @Test
    fun `encodes api path segments without form-style spaces`() {
        assertThat(apiPathSegment("Summer Trip 2026/Tag 1")).isEqualTo("Summer%20Trip%202026%2FTag%201")
    }

    @Test
    fun `maps instants to utc api offset date-times`() {
        assertThat(Instant.parse("2026-06-01T10:15:30Z").toApiOffsetDateTime())
            .isEqualTo(OffsetDateTime.of(2026, 6, 1, 10, 15, 30, 0, ZoneOffset.UTC))
    }
}
