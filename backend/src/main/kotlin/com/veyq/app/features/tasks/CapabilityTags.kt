package com.veyq.app.features.tasks

/**
 * The opaque capability tokens the backend pins from the public registry (`contracts/tasks/tags/v1.json`,
 * ADR 0021). A tag is the value that rides the job envelope + a worker's claim on the wire and in the DB, so a
 * bare dump sees `02f271ff`, not `grant-delivery`. Each is the first 4 bytes of SHA-256 over
 * `veyq/v1/task-capability/<name>`; a conformance test asserts these equal the registry (drift-proofing, the
 * same guard the crypto vectors get). The residual "what kind of work" leak is correlation-bounded, not
 * label-bounded — see [docs/crosscutting/background-tasks.md] — so these tokens only keep plaintext labels out
 * of the store.
 */
object CapabilityTags {
    /** grant-delivery (cr-0076 / ADR 0020): wrap K_SC to a pending member's identity — the first consumer. */
    const val GRANT_DELIVERY = "02f271ff"
}
