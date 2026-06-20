package com.veyq.app.features.memory.favorite

import com.veyq.app.generated.model.NodeType
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.MemoryFavorite
import com.veyq.app.shared.content.MemoryFavoriteRepository
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.security.ScopeAccessGuard
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import java.time.Instant
import java.util.UUID

data class MemoryFavoriteList(val nodeIds: List<String>)
data class MemoryFavoriteStatus(val favorite: Boolean)

// Two resource classes with SPECIFIC class-level @Paths — never the shared `/api/scopes/{scopeId}` prefix.
// A root resource whose @Path is that greedy prefix is selected (most-specific match) for ANY
// `/api/scopes/{scopeId}/...` request and then 404s siblings it has no method for (it shadowed KeysApi's
// `/grants/me`). Same class-level greedy-prefix gotcha the password-reset spec warns about — keep each
// favorites route on its own full-path resource so it claims only its own URI.

@ApplicationScoped
@Path("/api/scopes/{scopeId}/favorites")
@Produces(MediaType.APPLICATION_JSON)
class MemoryFavoriteListResource(
    private val favorites: MemoryFavoriteRepository,
    private val guard: ScopeAccessGuard,
) {
    @GET
    @Transactional
    fun listFavorites(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("scopeId") scopeId: String,
    ): MemoryFavoriteList {
        val userId = guard.requireKeyHolder(auth, scopeId)
        return MemoryFavoriteList(nodeIds = favorites.listNodeIds(userId, scopeId))
    }
}

@ApplicationScoped
@Path("/api/scopes/{scopeId}/nodes/{nodeId}/favorite")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class MemoryFavoriteResource(
    private val nodes: NodeRepository,
    private val favorites: MemoryFavoriteRepository,
    private val guard: ScopeAccessGuard,
) {
    @PUT
    @Transactional
    fun favoriteMemory(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("scopeId") scopeId: String,
        @PathParam("nodeId") nodeId: String,
    ): MemoryFavoriteStatus {
        val userId = guard.requireKeyHolder(auth, scopeId)
        requireMemoryNode(scopeId, nodeId)
        if (favorites.find(userId, nodeId) == null) {
            favorites.persist(MemoryFavorite().apply {
                id = UUID.randomUUID().toString()
                this.userId = userId
                this.scopeId = scopeId
                this.nodeId = nodeId
                createdAt = Instant.now()
            })
        }
        return MemoryFavoriteStatus(favorite = true)
    }

    @DELETE
    @Transactional
    fun unfavoriteMemory(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("scopeId") scopeId: String,
        @PathParam("nodeId") nodeId: String,
    ): MemoryFavoriteStatus {
        val userId = guard.requireKeyHolder(auth, scopeId)
        requireMemoryNode(scopeId, nodeId)
        favorites.deleteByUserAndNode(userId, nodeId)
        return MemoryFavoriteStatus(favorite = false)
    }

    private fun requireMemoryNode(scopeId: String, nodeId: String) {
        val node = nodes.findById(nodeId) ?: throw ApiException(404, "NODE_NOT_FOUND", "Node not found")
        if (node.scopeId != scopeId) throw ApiException(404, "NODE_NOT_FOUND", "Node not found in this scope")
        if (node.type != NodeType.MEMORY) throw ApiException(400, "NOT_A_MEMORY", "Only a Memory can be favorited")
    }
}
