package com.veyq.api.memory

import com.veyq.api.auth.ForbiddenException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@ApplicationScoped
@Path("/api/collaboration/journal")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class JournalCollaborationResource @Inject constructor(
    private val memoryJournalService: MemoryJournalService,
    private val journalLiveConfig: JournalLiveConfig,
) {
    @POST
    @Path("/session/validate")
    fun validateSession(
        @HeaderParam("X-Veyq-Collaboration-Secret") collaborationSecret: String?,
        request: JournalLiveSessionValidationRequest,
    ): JournalLiveSessionValidationResponse {
        requireSidecarSecret(collaborationSecret)
        val document = memoryJournalService.validateLiveSession(request.token, request.documentId)
        return JournalLiveSessionValidationResponse(
            documentId = document.documentId,
            markdown = document.markdown,
            displayName = document.displayName,
            color = document.color,
            expiresAt = document.expiresAt.toString(),
            canEdit = document.canEdit,
        )
    }

    @PUT
    @Path("/session/snapshot")
    fun persistSnapshot(
        @HeaderParam("X-Veyq-Collaboration-Secret") collaborationSecret: String?,
        request: JournalLiveSnapshotRequest,
    ): JournalLiveSnapshotResponse {
        requireSidecarSecret(collaborationSecret)
        val journal = memoryJournalService.persistLiveSnapshot(
            token = request.token,
            documentId = request.documentId,
            markdown = request.markdown,
            allowExpiredToken = configuredSidecarSecret().isNotBlank(),
        )
        return JournalLiveSnapshotResponse(
            memoryId = journal.memoryId,
            markdown = journal.markdown,
            updatedAt = journal.updatedAt.toString(),
        )
    }

    private fun requireSidecarSecret(collaborationSecret: String?) {
        val configuredSecret = configuredSidecarSecret()
        if (configuredSecret.isBlank()) {
            throw ForbiddenException("Journal live sync is not configured", "MEMORY_JOURNAL_LIVE_NOT_CONFIGURED")
        }
        if (collaborationSecret != configuredSecret) {
            throw ForbiddenException("Invalid collaboration service secret", "JOURNAL_COLLABORATION_SECRET_INVALID")
        }
    }

    private fun configuredSidecarSecret(): String =
        journalLiveConfig.sidecarSecret.orElse("").trim()
}

data class JournalLiveSessionValidationRequest(
    val token: String = "",
    val documentId: String = "",
)

data class JournalLiveSessionValidationResponse(
    val documentId: String,
    val markdown: String,
    val displayName: String,
    val color: String,
    val expiresAt: String,
    val canEdit: Boolean,
)

data class JournalLiveSnapshotRequest(
    val token: String = "",
    val documentId: String = "",
    val markdown: String = "",
)

data class JournalLiveSnapshotResponse(
    val memoryId: String,
    val markdown: String,
    val updatedAt: String,
)
