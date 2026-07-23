package com.veyq.app.features.admin.tenant

import com.veyq.app.shared.config.VeyqConfig
import io.quarkus.runtime.LaunchMode
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Optional

/**
 * Delivers a tenant-owner invite. A **port**: the structural slice (cr-0009) only needs the link minted and
 * handed off; real SMTP is an infra/runbook concern, deliberately out of scope. The invite link carries the
 * raw bootstrap secret in the URL **fragment** (never sent to the server until acceptance — ADR 0006) and
 * targets the tenant's own domain, where the invited owner accepts and cold-births the scope (Slice B).
 */
interface TenantInviteMailer {
    fun sendInvite(domain: String, ownerEmail: String, token: String, expiresAt: Instant)
}

/**
 * Dev/test impl: does not send mail. Replace with an SMTP-backed impl in infra.
 *
 * Two safeguards so this dev convenience never becomes a prod liability:
 *  - the raw bootstrap token (a redeemable secret that must live only in the URL fragment, ADR 0006) is
 *    **never logged**, in any launch mode (cr-invite-token-log-redaction): a production runtime logs a
 *    secret-free warning that nothing was delivered, and a dev/test runtime logs a secret-free info line —
 *    so the invite secret never lands in server logs regardless of `LaunchMode`. The token reaches those who
 *    need it via the API response or the opt-in dev-file below, not the console;
 *  - the owner-supplied `domain` is validated as a bare hostname up front, so once a real mailer builds a
 *    link from it, it can't be tricked into a different scheme/host/path (phishing / link-injection).
 *
 * Dev-only invite **file** (cr-0046): when `veyq.tenant.invite.dev-file` is set, each invite is appended as
 * one JSON line `{ownerEmail, token, expiresAt}` so the local seed orchestrator can read the bootstrap secret
 * back deterministically (no console-log scraping / ANSI / flush race). This is the **sole** place the raw
 * token is written, and the append sits **after** the production early-return, so a `LaunchMode.NORMAL`
 * runtime never writes the secret; the default is empty (no file). Best-effort: a file error is logged and
 * never breaks invite delivery.
 */
@ApplicationScoped
class LoggingTenantInviteMailer(
    config: VeyqConfig,
) : TenantInviteMailer {
    private val devFile: Optional<String> = config.tenant().invite().devFile()
    private val log = Logger.getLogger(LoggingTenantInviteMailer::class.java)

    override fun sendInvite(domain: String, ownerEmail: String, token: String, expiresAt: Instant) {
        require(HOSTNAME.matches(domain)) { "invalid tenant domain" }
        if (LaunchMode.current() == LaunchMode.NORMAL) {
            // Production: never write the bootstrap secret to logs. A real SMTP-backed mailer must be wired.
            log.warnf(
                "No SMTP mailer configured: tenant invite for %s (expires %s) was NOT delivered.",
                ownerEmail, expiresAt,
            )
            return
        }
        // The raw bootstrap token is a redeemable secret (cr-invite-token-log-redaction): never emit it — or
        // the accept link that embeds it — to a log, in any launch mode. Whoever needs it gets it from the
        // opt-in dev-file (below) or the API response, never the console. TenantInviteMailerDevFileTest pins
        // both halves: the token stays out of the log AND still reaches the dev-file the seed loop reads.
        log.infof(
            "Tenant invite minted for %s (expires %s); secret delivered via dev-file / API response, not logged.",
            ownerEmail, expiresAt,
        )
        appendDevFile(ownerEmail, token, expiresAt)
    }

    /** Dev-only: append the invite as one JSON line so the seed orchestrator can read the bootstrap secret. */
    private fun appendDevFile(ownerEmail: String, token: String, expiresAt: Instant) {
        val target = devFile.orElse("").ifBlank { return }
        val line = """{"ownerEmail":${jsonStr(ownerEmail)},"token":${jsonStr(token)},"expiresAt":${jsonStr(expiresAt.toString())}}""" + "\n"
        runCatching {
            val path = Path.of(target)
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }.onFailure { log.warnf("Could not write dev-invite file %s: %s", target, it.message) }
    }

    private companion object {
        /** A bare DNS hostname (labels of letters/digits/hyphens, dot-separated) — no scheme, port, path or
         *  userinfo, so the interpolated link can only ever point at the host the Server-Owner registered. */
        val HOSTNAME = Regex("^(?=.{1,253}$)([A-Za-z0-9](-?[A-Za-z0-9])*)(\\.[A-Za-z0-9](-?[A-Za-z0-9])*)+$")

        /** Minimal JSON string encoder (escapes the two chars that can break a one-line JSON value). */
        fun jsonStr(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
