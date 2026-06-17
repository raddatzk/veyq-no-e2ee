package com.veyq.app.shared.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import io.smallrye.config.WithName
import java.time.Duration
import java.util.Optional

/**
 * Project-owned `veyq.*` configuration, modelled as complete `@ConfigMapping` groups (one per area) rather than
 * scattered per-value `@ConfigProperty` injections — see docs/wiki/crosscutting/developer-styleguide.md (§8).
 * Defaults live here via `@WithDefault`; the deployment overrides them through env vars in
 * infra/docker-compose.yml. Internal `quarkus.*` keys may still use direct injection.
 */

/** `veyq.app.*` — the public web origin used to build links (invites, password reset). No default: required. */
@ConfigMapping(prefix = "veyq.app")
interface AppConfig {
    @WithName("base-url") fun baseUrl(): String
}

/** `veyq.uploads.*` — blob upload bounds (anti-abuse) and the resumable-session GC TTL. */
@ConfigMapping(prefix = "veyq.uploads")
interface UploadsConfig {
    @WithName("max-chunks") @WithDefault("10000") fun maxChunks(): Int
    @WithName("max-upload-bytes") @WithDefault("10737418240") fun maxUploadBytes(): Long
    @WithName("max-frame-bytes") @WithDefault("16777244") fun maxFrameBytes(): Int
    @WithName("max-open-sessions") @WithDefault("100") fun maxOpenSessions(): Long
    @WithName("max-share-open-sessions") @WithDefault("10") fun maxShareOpenSessions(): Long
    @WithName("max-share-bytes") @WithDefault("2147483648") fun maxShareBytes(): Long
    @WithName("session-ttl-hours") @WithDefault("24") fun sessionTtlHours(): Long
}

/** `veyq.ratelimit.*` — per-IP throttling of abuse-prone unauthenticated endpoints (see RateLimitFilter). */
@ConfigMapping(prefix = "veyq.ratelimit")
interface RateLimitConfig {
    @WithDefault("true") fun enabled(): Boolean
    // Optional because SmallRye maps an empty config value to "absent" (the default deployment — trust none).
    @WithName("trusted-proxies") fun trustedProxies(): Optional<String>
    @WithName("share-egress-bytes-per-min") @WithDefault("524288000") fun shareEgressBytesPerMin(): Long
}

/** `veyq.tenant.*` — grace window before a soft-deleted tenant is hard-purged. */
@ConfigMapping(prefix = "veyq.tenant")
interface TenantConfig {
    @WithName("deletion-grace") @WithDefault("P30D") fun deletionGrace(): Duration
}

/** `veyq.password-reset.*` — lifetime of a password-reset token. */
@ConfigMapping(prefix = "veyq.password-reset")
interface PasswordResetConfig {
    @WithDefault("PT1H") fun ttl(): Duration
}

/** `veyq.blob-store.*` — filesystem root the (already-encrypted) blobs are written to; must be a mounted volume. */
@ConfigMapping(prefix = "veyq.blob-store")
interface BlobStoreConfig {
    @WithName("dir") @WithDefault("build/veyq-blobs") fun dir(): String
}
