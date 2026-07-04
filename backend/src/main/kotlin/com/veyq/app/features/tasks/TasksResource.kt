package com.veyq.app.features.tasks

import com.veyq.app.generated.api.TasksApi
import com.veyq.app.generated.model.ClaimRequest
import com.veyq.app.generated.model.ClaimedJob
import com.veyq.app.generated.model.CompleteRequest
import com.veyq.app.generated.model.EnqueueRequest
import com.veyq.app.generated.model.ErrorResponse
import com.veyq.app.generated.model.FailRequest
import com.veyq.app.generated.model.GrantDeliveryTarget
import com.veyq.app.generated.model.JobCreated
import com.veyq.app.generated.model.JobStatus
import com.veyq.app.generated.model.LeaseState
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.time.ZoneOffset
import java.util.UUID

/**
 * HTTP adapter for the operator-blind background-task pull queue (ADR 0021, cr-0076). Implements the generated
 * [TasksApi]; thin (ADR 0001) — delegates to [TasksHandler]. Central auth (`@Authenticated`): a missing/invalid
 * token is a generic 401 before this runs; the caller's worker identity is the `SecurityIdentity` principal.
 * Authorization (the caller must hold a grant in the job's scope — be a key-holder there) lives in the store.
 */
@Authenticated
class TasksResource(
    private val handler: TasksHandler,
    private val identity: SecurityIdentity,
) : TasksApi {

    private val me: String get() = identity.principal.name

    override fun claimTask(claimRequest: ClaimRequest): Response =
        when (val job = handler.claim(me, claimRequest.capabilityTags, claimRequest.scopeId?.toString())) {
            null -> Response.noContent().build()
            else -> Response.ok(job.toDto()).build()
        }

    override fun enqueueTask(enqueueRequest: EnqueueRequest): Response {
        // Structural tags are server-originated only — reject them at the front door with a clear 400 (the store
        // enforces this too as defence in depth). A client may only enqueue content-processing jobs.
        if (enqueueRequest.capabilityTag == CapabilityTags.GRANT_DELIVERY) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ErrorResponse(code = "invalid_request")).build()
        }
        return when (val jobId = handler.enqueue(
            me, enqueueRequest.scopeId.toString(), enqueueRequest.capabilityTag,
            enqueueRequest.resourceRef, enqueueRequest.sealedBody,
        )) {
            null -> Response.status(Response.Status.FORBIDDEN).entity(ErrorResponse(code = "not_scope_member")).build()
            else -> Response.status(Response.Status.CREATED).entity(JobCreated(jobId = UUID.fromString(jobId))).build()
        }
    }

    override fun heartbeatTask(jobId: UUID): Response =
        when (val r = handler.heartbeat(me, jobId.toString())) {
            is HeartbeatResult.Extended ->
                Response.ok(LeaseState(leaseExpiresAt = r.leaseExpiresAt.atOffset(ZoneOffset.UTC))).build()
            HeartbeatResult.NotFound ->
                Response.status(Response.Status.NOT_FOUND).entity(ErrorResponse(code = "job_not_found")).build()
            HeartbeatResult.NotHolder ->
                Response.status(Response.Status.CONFLICT).entity(ErrorResponse(code = "not_lease_holder")).build()
        }

    override fun completeTask(jobId: UUID, completeRequest: CompleteRequest?): Response =
        when (handler.complete(me, jobId.toString(), completeRequest?.sealedResult)) {
            CompleteResult.Done -> Response.noContent().build()
            CompleteResult.NotFound ->
                Response.status(Response.Status.NOT_FOUND).entity(ErrorResponse(code = "job_not_found")).build()
            CompleteResult.MissingResult ->
                Response.status(Response.Status.BAD_REQUEST).entity(ErrorResponse(code = "invalid_request")).build()
        }

    override fun failTask(jobId: UUID, failRequest: FailRequest?): Response =
        when (val r = handler.fail(me, jobId.toString())) {
            is FailResult.Recorded -> Response.ok(JobStatus(status = r.status.lowercase(), attempts = r.attempts)).build()
            FailResult.NotFound ->
                Response.status(Response.Status.NOT_FOUND).entity(ErrorResponse(code = "job_not_found")).build()
        }

    private fun ClaimedJobView.toDto() = ClaimedJob(
        jobId = UUID.fromString(jobId),
        scopeId = UUID.fromString(scopeId),
        capabilityTag = capabilityTag,
        attempts = attempts,
        resourceRef = resourceRef,
        sealedBody = sealedBody,
        leaseExpiresAt = leaseExpiresAt?.atOffset(ZoneOffset.UTC),
        grantDelivery = grantDelivery?.let {
            GrantDeliveryTarget(
                targetUserId = UUID.fromString(it.targetUserId),
                targetMembershipId = UUID.fromString(it.targetMembershipId),
                identityPublicKey = it.identityPublicKey,
            )
        },
    )
}
