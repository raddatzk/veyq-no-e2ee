package com.veyq.app.features.memory.journal

import com.veyq.app.generated.api.MemoryJournalApi
import com.veyq.app.generated.model.AppendJournalUpdateRequest
import com.veyq.app.generated.model.CompactJournalRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry for the generated MemoryJournalApi; delegates to the handler. */
@ApplicationScoped
class JournalResource(private val handler: JournalHandler) : MemoryJournalApi {
    override fun getJournal(authorization: String, scopeId: String, nodeId: String): Response =
        Response.ok(handler.getJournal(authorization, scopeId, nodeId)).build()

    override fun getJournalLive(authorization: String, scopeId: String, nodeId: String): Response =
        Response.ok(handler.getJournalLive(authorization, scopeId, nodeId)).build()

    override fun appendJournalUpdate(authorization: String, scopeId: String, nodeId: String, appendJournalUpdateRequest: AppendJournalUpdateRequest): Response =
        Response.ok(handler.appendJournalUpdate(authorization, scopeId, nodeId, appendJournalUpdateRequest)).build()

    override fun compactJournal(authorization: String, scopeId: String, nodeId: String, compactJournalRequest: CompactJournalRequest): Response =
        Response.ok(handler.compactJournal(authorization, scopeId, nodeId, compactJournalRequest)).build()
}
