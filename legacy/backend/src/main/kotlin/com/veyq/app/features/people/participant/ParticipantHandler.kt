package com.veyq.app.features.people.participant

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.CreateParticipantRequest
import com.veyq.app.generated.model.MemoryParticipant as ApiParticipant
import com.veyq.app.generated.model.MemoryParticipantList
import com.veyq.app.generated.model.NodeType
import com.veyq.app.generated.model.UpdateParticipantRequest
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.MemoryParticipant
import com.veyq.app.shared.content.MemoryParticipantRepository
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.content.SealedContent
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.security.ScopeAccessGuard
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * uc.people.manage-participants — sealed participant payloads attached to Memories/Chapters. Linked-user vs.
 * anonymous semantics live inside ciphertext, so the backend never learns the Memory-person graph.
 */
@ApplicationScoped
class ParticipantHandler(
    private val nodes: NodeRepository,
    private val participants: MemoryParticipantRepository,
    private val guard: ScopeAccessGuard,
    private val suites: CryptoSuiteRegistry,
    private val content: com.veyq.app.shared.config.ContentConfig,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    @Transactional
    fun listParticipants(auth: String?, scopeId: String, nodeId: String): MemoryParticipantList {
        guard.requireKeyHolder(auth, scopeId)
        requireParticipantNode(scopeId, nodeId)
        return MemoryParticipantList(participants = participants.listByNodeId(nodeId).map(::toApi))
    }

    @Transactional
    fun createParticipant(auth: String?, scopeId: String, nodeId: String, req: CreateParticipantRequest): ApiParticipant {
        guard.requireKeyHolder(auth, scopeId)
        requireParticipantNode(scopeId, nodeId)
        if (!suites.isKnown(req.sealedPayload.suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite")
        val participant = MemoryParticipant().apply {
            id = UUID.randomUUID().toString()
            this.nodeId = nodeId
            this.scopeId = scopeId
            suiteId = req.sealedPayload.suiteId
            sealedPayload = SealedContent.decodeCapped(req.sealedPayload.bytes, content.maxSealedBytes(), dec)
            createdAt = Instant.now()
        }
        participants.persist(participant)
        return toApi(participant)
    }

    @Transactional
    fun updateParticipant(auth: String?, scopeId: String, nodeId: String, participantId: String, req: UpdateParticipantRequest): ApiParticipant {
        guard.requireKeyHolder(auth, scopeId)
        val participant = require(scopeId, nodeId, participantId)
        if (!suites.isKnown(req.sealedPayload.suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite")
        participant.suiteId = req.sealedPayload.suiteId
        participant.sealedPayload = SealedContent.decodeCapped(req.sealedPayload.bytes, content.maxSealedBytes(), dec)
        return toApi(participant)
    }

    @Transactional
    fun deleteParticipant(auth: String?, scopeId: String, nodeId: String, participantId: String) {
        guard.requireKeyHolder(auth, scopeId)
        participants.delete(require(scopeId, nodeId, participantId))
    }

    private fun require(scopeId: String, nodeId: String, participantId: String): MemoryParticipant {
        val participant = participants.findById(participantId) ?: throw ApiException(404, "PARTICIPANT_NOT_FOUND", "Participant not found")
        if (participant.scopeId != scopeId || participant.nodeId != nodeId) throw ApiException(404, "PARTICIPANT_NOT_FOUND", "Participant not found here")
        return participant
    }

    private fun requireParticipantNode(scopeId: String, nodeId: String) {
        val node = nodes.findById(nodeId) ?: throw ApiException(404, "NODE_NOT_FOUND", "Node not found")
        if (node.scopeId != scopeId) throw ApiException(404, "NODE_NOT_FOUND", "Node not found in this scope")
        if (node.type == NodeType.FOLDER) throw ApiException(400, "NOT_A_MEMORY", "Only a Memory/Chapter has participants")
    }

    fun toApi(participant: MemoryParticipant): ApiParticipant =
        ApiParticipant(
            id = participant.id,
            nodeId = participant.nodeId,
            sealedPayload = Blob(suiteId = participant.suiteId, bytes = enc.encodeToString(participant.sealedPayload)),
            createdAt = OffsetDateTime.ofInstant(participant.createdAt, ZoneOffset.UTC),
        )
}
