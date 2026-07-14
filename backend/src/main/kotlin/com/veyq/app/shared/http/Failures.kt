package com.veyq.app.shared.http

import com.veyq.app.shared.http.FailureStatus.BAD_REQUEST
import com.veyq.app.shared.http.FailureStatus.CONFLICT
import com.veyq.app.shared.http.FailureStatus.FORBIDDEN
import com.veyq.app.shared.http.FailureStatus.NOT_FOUND
import com.veyq.app.shared.http.FailureStatus.RATE_LIMITED
import com.veyq.app.shared.http.FailureStatus.UNPROCESSABLE

/**
 * Canonical [HttpFailure]s for semantic failures that recur across **more than one feature slice** — the single
 * source of truth for their `(code, status)` (ADR 0042). A failure variant in any slice delegates to one of these
 * with Kotlin interface delegation (`object … : SomeOutcome, HttpFailure by Failures.scopeNotFound`), so the
 * projection can never drift between the independent slices that raise it. Failures whose `code` is local to a
 * single slice (however often they recur *within* it) use the inline [httpFailure] factory at the variant instead
 * of an entry here — a global catalog is for cross-cutting failures, not one slice's private vocabulary.
 */
object Failures {
    /** Caller holds no membership for the scope → 404, operator-blind (a non-member must not learn it exists). */
    val scopeNotFound: HttpFailure = httpFailure("scope_not_found", NOT_FOUND)

    /** Target node absent (or membership-gated away) → 404. */
    val nodeNotFound: HttpFailure = httpFailure("node_not_found", NOT_FOUND)

    /** Target tenant absent → 404. */
    val tenantNotFound: HttpFailure = httpFailure("tenant_not_found", NOT_FOUND)

    /** Target membership/member absent → 404. */
    val memberNotFound: HttpFailure = httpFailure("member_not_found", NOT_FOUND)

    /**
     * Caller is not a member of the scope, surfaced as **403** at `scopes.epochs`/`scopes.listMembers`/
     * `tasks.createJob`. This is the operator-blind status drift ADR 0042 records: it should be 404 (see
     * [scopeNotFound]) and is flipped by inbox #83. Kept 403 here to preserve behaviour (cr-0161).
     */
    val notScopeMember: HttpFailure = httpFailure("not_scope_member", FORBIDDEN)

    /** Scope is pending deletion; mutating it conflicts with that terminal transition → 409. */
    val scopeDeleting: HttpFailure = httpFailure("scope_deleting", CONFLICT)

    /** Slug already taken under the parent (uniqueness) → 409. */
    val slugTaken: HttpFailure = httpFailure("slug_taken", CONFLICT)

    /** The subject is already a member of the target scope/tenant → 409. */
    val alreadyMember: HttpFailure = httpFailure("already_member", CONFLICT)

    /** The change would remove the last owner of the scope/tenant → 409 (ADR 0007). */
    val lastOwner: HttpFailure = httpFailure("last_owner", CONFLICT)

    /** A required capability is disabled for the tenant → 422. */
    val capabilityDisabled: HttpFailure = httpFailure("capability_disabled", UNPROCESSABLE)

    /** Request violates the Memory-tree structural rules → 422. */
    val illegalStructure: HttpFailure = httpFailure("illegal_structure", UNPROCESSABLE)

    /** The subject's username collides with an existing one → 409. */
    val usernameTaken: HttpFailure = httpFailure("username_taken", CONFLICT)

    /** A structurally-valid request whose cross-field/state preconditions fail → 400. */
    val invalidRequest: HttpFailure = httpFailure("invalid_request", BAD_REQUEST)

    /** Too many requests against a rate-limited endpoint → 429. */
    val rateLimited: HttpFailure = httpFailure("rate_limited", RATE_LIMITED)
}
