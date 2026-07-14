package com.veyq.app.shared.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration
import java.util.Optional

/**
 * The single typed model of the project-owned `veyq.*` configuration (styleguide §8, audit P3c / cr-0134).
 * Replaces the ~28 scattered per-value `@ConfigProperty` injections with one `@ConfigMapping` so every knob is
 * discoverable in one place, typed, and defaulted here rather than repeated at each injection site.
 *
 * Property names are derived from the method path in kebab-case, so `tasks().maxAttempts()` ⇒
 * `veyq.tasks.max-attempts`. Values still come from `application.properties` (+ `%dev`/`%test` overrides); the
 * `@WithDefault`s mirror those files and the previous annotation defaults — they are the fallback when a key is
 * absent, not a second source of truth.
 *
 * Framework config (`quarkus.*`, `mp.jwt.*`, …) stays on direct injection (styleguide §8) and is not modeled here.
 *
 * The `*.purge.every` / `*.reconcile.every` intervals are consumed by the sibling `@Scheduled(every = "{…}")`
 * beans as raw property expressions (a `@Scheduled` string cannot read a mapping), but they are declared here too
 * so this interface stays the complete inventory of `veyq.*`.
 */
@ConfigMapping(prefix = "veyq")
interface VeyqConfig {
    fun version(): Version
    fun capabilities(): Capabilities
    fun tenant(): Tenant
    fun session(): Session
    fun scope(): Scope
    fun media(): Media
    fun blob(): Blob
    fun blobStore(): BlobStore
    fun tasks(): Tasks
    fun ratelimit(): RateLimit
    fun share(): Share

    /** A scheduled cleanup interval (`*.purge.every` / `*.reconcile.every`); read by the `@Scheduled` sibling. */
    interface Interval {
        @WithDefault("1h")
        fun every(): Duration
    }

    interface Version {
        /** Hard compatibility-gate major version. */
        @WithDefault("1")
        fun major(): Int

        /** Informational build version. */
        @WithDefault("1")
        fun build(): Int
    }

    interface Capabilities {
        /** Operator off-switch: capability ids to disable (absent ⇒ all enabled). */
        fun disabled(): Optional<List<String>>
    }

    interface Tenant {
        fun invite(): Invite

        interface Invite {
            /** Owner-invite validity window. */
            @WithDefault("P7D")
            fun ttl(): Duration

            /** How long terminal (accepted/expired) invites are kept before purge. */
            @WithDefault("P30D")
            fun retention(): Duration

            /** Dev-only JSONL sink for invite links (absent in prod). */
            fun devFile(): Optional<String>

            fun purge(): Interval
        }
    }

    interface Session {
        fun cookie(): Cookie
        fun purge(): Interval

        interface Cookie {
            /** `Secure` attribute on the refresh cookie (off in `%dev`/`%test`). */
            @WithDefault("true")
            fun secure(): Boolean
        }
    }

    interface Scope {
        fun delete(): Delete

        interface Delete {
            /** Group soft-delete grace window before the hard purge. */
            @WithDefault("P30D")
            fun retention(): Duration

            fun purge(): Interval
        }
    }

    interface Media {
        fun trash(): Trash

        interface Trash {
            /** Trashed-media restorable window before the hard purge. */
            @WithDefault("P30D")
            fun retention(): Duration

            fun purge(): Interval
        }
    }

    interface Blob {
        /** Per-blob declared byte cap. */
        @WithDefault("2147483648")
        fun maxBytes(): Long

        /** Per-blob declared chunk cap. */
        @WithDefault("100000")
        fun maxChunks(): Int
    }

    interface BlobStore {
        /** Internal S3 endpoint (blank/absent fails startup — media is not gracefully degraded). */
        fun endpoint(): Optional<String>

        /** Client-reachable URL used purely to SIGN presigned URLs (blank ⇒ endpoint). */
        fun publicEndpoint(): Optional<String>

        fun accessKey(): Optional<String>

        fun secretKey(): Optional<String>

        @WithDefault("veyq")
        fun bucket(): String

        @WithDefault("us-east-1")
        fun region(): String

        @WithDefault("600")
        fun presignTtlSeconds(): Int
    }

    interface Tasks {
        fun lease(): Lease

        /** Max attempts before a job is dead-lettered. */
        @WithDefault("5")
        fun maxAttempts(): Int

        fun grantDelivery(): GrantDelivery

        interface Lease {
            /** Lease TTL granted on job claim. */
            @WithDefault("PT5M")
            fun ttl(): Duration
        }

        interface GrantDelivery {
            fun reconcile(): Interval
        }
    }

    /** `veyq.ratelimit.auth.*` — the per-IP auth rate limiter (cr-0130). */
    interface RateLimit {
        fun auth(): Auth

        interface Auth {
            @WithDefault("60")
            fun maxRequests(): Long

            @WithDefault("60")
            fun windowSeconds(): Long
        }
    }

    /** `veyq.share.upload.rate.*` — the uploader-link rate limiter. */
    interface Share {
        fun upload(): Upload

        /** cr-0165: the brute-force bound on wrong share-password attempts (per share, per window). Distinct budget
         *  from [upload] — an order of magnitude tighter, since a password guess is an attack and an upload is not. */
        fun password(): Password

        interface Upload {
            fun rate(): Rate

            interface Rate {
                @WithDefault("300")
                fun maxPerWindow(): Int

                @WithDefault("60")
                fun windowSeconds(): Long
            }
        }

        interface Password {
            fun rate(): Rate

            interface Rate {
                @WithDefault("10")
                fun maxPerWindow(): Int

                @WithDefault("60")
                fun windowSeconds(): Long
            }
        }
    }
}
