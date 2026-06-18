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
    @WithName("presign-ttl-seconds") @WithDefault("900") fun presignTtlSeconds(): Int // direct-to-store PUT URL lifetime
}

/** `veyq.ratelimit.*` — per-IP throttling of abuse-prone unauthenticated endpoints (see RateLimitFilter).
 *  The per-bucket limits are operator-tunable; all windows are one minute. */
@ConfigMapping(prefix = "veyq.ratelimit")
interface RateLimitConfig {
    @WithDefault("true") fun enabled(): Boolean
    // Optional because SmallRye maps an empty config value to "absent" (the default deployment — trust none).
    @WithName("trusted-proxies") fun trustedProxies(): Optional<String>
    @WithName("share-egress-bytes-per-min") @WithDefault("524288000") fun shareEgressBytesPerMin(): Long
    @WithName("login-per-min") @WithDefault("10") fun loginPerMin(): Int
    @WithName("reset-request-per-min") @WithDefault("5") fun resetRequestPerMin(): Int
    @WithName("reset-per-min") @WithDefault("15") fun resetPerMin(): Int
    @WithName("invite-per-min") @WithDefault("30") fun invitePerMin(): Int
    @WithName("invite-read-per-min") @WithDefault("60") fun inviteReadPerMin(): Int
    @WithName("share-upload-per-min") @WithDefault("60") fun shareUploadPerMin(): Int
    @WithName("share-chunk-per-min") @WithDefault("600") fun shareChunkPerMin(): Int
}

/** `veyq.session.*` — opaque Bearer-session lifetime. */
@ConfigMapping(prefix = "veyq.session")
interface SessionConfig {
    @WithDefault("P30D") fun ttl(): Duration
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

/** `veyq.content.*` — the max size of a single sealed-content payload (node name, item, journal, track) the
 *  server will decode into memory. Bounds a DoS via an over-large base64 string; large media goes through the
 *  chunked blob path (bounded separately by veyq.uploads.*), not here. */
@ConfigMapping(prefix = "veyq.content")
interface ContentConfig {
    @WithName("max-sealed-bytes") @WithDefault("1048576") fun maxSealedBytes(): Int
}

/** `veyq.dev-tools.*` — local-only helpers for repeatable smoke data. Disabled outside dev/test. */
@ConfigMapping(prefix = "veyq.dev-tools")
interface DevToolsConfig {
    @WithDefault("false") fun enabled(): Boolean
}

/**
 * `veyq.blob-store.*` — where the (already-encrypted) blob frames live. The server is decoupled from storage:
 * the only production backend is `s3` (an external S3-compatible store, or the bundled MinIO/Garage container).
 * `memory` is an ephemeral in-process backend for dev/test only. The store holds AEAD ciphertext exclusively.
 *
 * Credentials are operator config supplied via env (never committed, never persisted to the DB, never logged or
 * returned by any endpoint). `s3-secret-key`/`s3-access-key` are registered in `smallrye.config.secret-keys`
 * (see application.properties) so they're masked in any config dump / dev console.
 */
@ConfigMapping(prefix = "veyq.blob-store")
interface BlobStoreConfig {
    @WithName("backend") @WithDefault("s3") fun backend(): String // s3 (production) | memory (dev/test)
    @WithName("s3-endpoint") fun s3Endpoint(): Optional<String> // e.g. https://s3.amazonaws.com or http://veyq-minio:9000
    @WithName("s3-bucket") @WithDefault("veyq-blobs") fun s3Bucket(): String
    @WithName("s3-region") @WithDefault("us-east-1") fun s3Region(): String
    @WithName("s3-access-key") fun s3AccessKey(): Optional<String>
    @WithName("s3-secret-key") fun s3SecretKey(): Optional<String>
}
