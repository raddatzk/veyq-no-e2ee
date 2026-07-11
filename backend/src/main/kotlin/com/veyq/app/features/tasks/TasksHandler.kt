package com.veyq.app.features.tasks

import com.veyq.app.shared.config.VeyqConfig
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration

/**
 * Use-case logic for the background-task pull queue (ADR 0021, cr-0076). Thin over [TasksStore]; it owns the
 * lease TTL + max-attempts policy (config) and passes them down. Eligibility ("holds the scope key") and the
 * atomic claim/lease live in the store; operator-blindness is structural — nothing here interprets a body.
 */
@ApplicationScoped
class TasksHandler(
    private val store: TasksStore,
    config: VeyqConfig,
) {
    private val leaseTtl: Duration = config.tasks().lease().ttl()
    private val maxAttempts: Int = config.tasks().maxAttempts()

    fun claim(userId: String, tags: List<String>, scopeFilter: String?): ClaimedJobView? =
        store.claim(userId, tags, scopeFilter, leaseTtl, maxAttempts)

    fun heartbeat(userId: String, jobId: String): HeartbeatResult =
        store.heartbeat(userId, jobId, leaseTtl)

    fun complete(userId: String, jobId: String, sealedResult: String?): CompleteResult =
        store.complete(userId, jobId, sealedResult)

    fun fail(userId: String, jobId: String): FailResult =
        store.fail(userId, jobId)

    fun decline(userId: String, jobId: String, reason: String?): DeclineResult =
        store.decline(userId, jobId, reason)

    fun enqueue(userId: String, scopeId: String, capabilityTag: String, resourceRef: String?, sealedBody: String?): String? =
        store.enqueue(userId, scopeId, capabilityTag, resourceRef, sealedBody, maxAttempts)
}
