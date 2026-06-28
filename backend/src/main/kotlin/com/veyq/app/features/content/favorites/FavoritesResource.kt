package com.veyq.app.features.content.favorites

import com.veyq.app.generated.api.FavoritesApi
import com.veyq.app.generated.model.ErrorResponse
import com.veyq.app.generated.model.Favorites
import com.veyq.app.generated.model.PutFavoritesRequest
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * HTTP adapter for per-(user, scope) favorites (en.memory.favorites, cr-0031). Implements the generated
 * [FavoritesApi]; thin (ADR 0001) — delegates to [FavoritesHandler]. `@Authenticated`; the caller (from the
 * `SecurityIdentity` principal) must hold a grant for the scope (a non-member gets 404). Operator-blind: only
 * the caller's sealed ciphertext crosses; the server never sees which Memories are pinned.
 */
@Authenticated
class FavoritesResource(
    private val handler: FavoritesHandler,
    private val identity: SecurityIdentity,
) : FavoritesApi {

    override fun getFavorites(scopeId: UUID): Response =
        when (val outcome = handler.get(identity.principal.name, scopeId.toString())) {
            is GetFavoritesOutcome.Found -> Response.ok(Favorites(ciphertext = outcome.ciphertext)).build()
            GetFavoritesOutcome.NoScope -> error(Response.Status.NOT_FOUND, "scope_not_found")
        }

    override fun putFavorites(scopeId: UUID, putFavoritesRequest: PutFavoritesRequest): Response =
        when (handler.put(identity.principal.name, scopeId.toString(), putFavoritesRequest.ciphertext)) {
            PutFavoritesOutcome.Stored -> Response.noContent().build()
            PutFavoritesOutcome.NoScope -> error(Response.Status.NOT_FOUND, "scope_not_found")
        }

    private fun error(status: Response.Status, code: String): Response =
        Response.status(status).entity(ErrorResponse(code = code)).build()
}
