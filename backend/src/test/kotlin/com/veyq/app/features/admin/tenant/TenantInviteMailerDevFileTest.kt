package com.veyq.app.features.admin.tenant

import com.veyq.app.shared.config.testVeyqConfig
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The dev-only invite **file** (cr-0046): with `veyq.tenant.invite.dev-file` set, the mailer appends the
 * invite as one JSON line so the local seed orchestrator reads the bootstrap secret back deterministically
 * (no console-log scraping). `@QuarkusTest` so `LaunchMode.current()` is TEST (not NORMAL) and the append
 * path runs. The bean is constructed directly with a temp path — the dev-file is the only behaviour under test.
 */
@QuarkusTest
class TenantInviteMailerDevFileTest {

    @Test
    fun appendsOneJsonLinePerInviteWhenDevFileSet(@TempDir tmp: Path) {
        val file = tmp.resolve("nested/invites.jsonl") // parent created on demand
        val mailer = LoggingTenantInviteMailer(testVeyqConfig(mapOf("veyq.tenant.invite.dev-file" to file.toString())))

        mailer.sendInvite("media.test", "owner@veyq.test", "BOOT-SECRET-123", Instant.parse("2026-07-06T00:00:00Z"))
        mailer.sendInvite("media.test", "second@veyq.test", "BOOT-SECRET-456", Instant.parse("2026-07-06T00:00:00Z"))

        val lines = Files.readAllLines(file)
        assertEquals(2, lines.size, "one line appended per invite")
        assertTrue(lines[0].contains("\"token\":\"BOOT-SECRET-123\""), "first token recorded")
        assertTrue(lines[0].contains("\"ownerEmail\":\"owner@veyq.test\""), "first owner recorded")
        assertTrue(lines[1].contains("\"token\":\"BOOT-SECRET-456\""), "second token recorded")
    }

    @Test
    fun writesNoFileWhenDevFileUnset(@TempDir tmp: Path) {
        val file = tmp.resolve("never.jsonl")
        LoggingTenantInviteMailer(testVeyqConfig()).sendInvite("media.test", "owner@veyq.test", "T", Instant.parse("2026-07-06T00:00:00Z"))
        assertFalse(Files.exists(file), "no file written when the config is empty (default)")
    }
}
