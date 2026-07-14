package com.veyq.app.shared.http

import com.veyq.app.generated.model.ErrorResponse
import jakarta.ws.rs.core.Response

/**
 * Semantic status bucket → HTTP status code. The **one** place a failure's status number is decided, so no
 * `…Resource` re-derives it and two sites cannot disagree on the status of the same semantic failure (ADR 0042).
 *
 * `FORBIDDEN` (403) is only for a caller who legitimately knows the resource exists; a **membership-gated
 * absence** must be `NOT_FOUND` (404) so a non-member cannot distinguish "forbidden" from "not found" and thereby
 * learn a scope exists across the membership boundary. The 3 sites still tagged `FORBIDDEN` for a non-member are
 * the operator-blind drift tracked for a separate, security-reviewed fix (inbox #83).
 */
enum class FailureStatus(val httpStatus: Int) {
    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    CONFLICT(409),
    GONE(410),
    UNPROCESSABLE(422),
    RATE_LIMITED(429),
    SERVER_ERROR(500),
    SERVICE_UNAVAILABLE(503),
}

/**
 * A failure that projects to the wire `ErrorResponse{code}`. Every sealed `…Outcome` failure variant implements
 * this, so its `(code, status)` is single-sourced on the variant (directly, or by delegating to a [Failures]
 * catalog entry for a recurring failure) and never re-derived in a resource's `when` (ADR 0042). Success variants
 * do **not** implement it, which keeps each resource `when` exhaustive as `is <Success> → 2xx; is HttpFailure →
 * [httpError]`.
 */
interface HttpFailure {
    /** Stable, client-localizable error code (styleguide §7) — the failure's identity on the wire. */
    val code: String

    /** The semantic bucket this failure projects to; [FailureStatus] owns the bucket → HTTP-status mapping. */
    val status: FailureStatus
}

/** Build a one-off [HttpFailure] for a failure whose `code` is unique to a single variant. */
fun httpFailure(code: String, status: FailureStatus): HttpFailure = Failure(code, status)

/** The concrete carrier behind [httpFailure] and the [Failures] catalog. */
private data class Failure(override val code: String, override val status: FailureStatus) : HttpFailure

/**
 * The single place `ErrorResponse` is constructed. Renders any [HttpFailure] to its JAX-RS response — the status
 * from the bucket, the body the uniform `ErrorResponse{code}` (never content/keys/secrets, styleguide §7).
 */
fun httpError(failure: HttpFailure): Response =
    Response.status(failure.status.httpStatus)
        .entity(ErrorResponse(code = failure.code))
        .build()
