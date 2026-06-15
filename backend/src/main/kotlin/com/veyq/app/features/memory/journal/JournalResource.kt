package com.veyq.app.features.memory.journal

import com.veyq.app.generated.api.MemoryJournalApi
import com.veyq.app.generated.model.UpdateMemoryJournalRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry for the generated MemoryJournalApi (GET/PUT /nodes/{nodeId}/journal); delegates to the handler. */
@ApplicationScoped
class JournalResource(private val handler: JournalHandler) : MemoryJournalApi {
    override fun getJournal(authorization: String, scopeId: String, nodeId: String): Response =
        Response.ok(handler.getJournal(authorization, scopeId, nodeId)).build()

    override fun updateJournal(authorization: String, scopeId: String, nodeId: String, updateMemoryJournalRequest: UpdateMemoryJournalRequest): Response =
        Response.ok(handler.updateJournal(authorization, scopeId, nodeId, updateMemoryJournalRequest)).build()
}
