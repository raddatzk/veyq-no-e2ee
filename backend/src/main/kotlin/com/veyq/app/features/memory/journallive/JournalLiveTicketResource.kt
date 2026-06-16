package com.veyq.app.features.memory.journallive

import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.security.SessionService
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/**
 * `POST /api/journal-live/ticket` — exchange a Bearer session for a short-lived single-use relay ticket, so
 * the live-journal WebSocket can authenticate via `?ticket=` instead of carrying the session token in the URL
 * (which leaks into proxy access logs / browser history). Part of the live-journal transport, which lives
 * outside the generated REST contract alongside the relay itself.
 */
@Path("/api/journal-live/ticket")
class JournalLiveTicketResource(
    private val sessions: SessionService,
    private val tickets: JournalLiveTicketService,
) {
    data class TicketResponse(val ticket: String, val expiresInSeconds: Long)

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    fun issue(@HeaderParam("Authorization") authorization: String?): TicketResponse {
        val userId = sessions.resolve(authorization) ?: throw ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
        return TicketResponse(ticket = tickets.issue(userId), expiresInSeconds = tickets.ttlSeconds())
    }
}
