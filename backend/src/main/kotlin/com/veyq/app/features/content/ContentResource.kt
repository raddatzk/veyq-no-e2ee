package com.veyq.app.features.content

import com.veyq.app.generated.api.ContentApi
import com.veyq.app.generated.model.CreateNodeRequest
import com.veyq.app.generated.model.ErrorResponse
import com.veyq.app.generated.model.NodeCreated
import com.veyq.app.generated.model.NodeKind
import com.veyq.app.generated.model.NodeSummary
import com.veyq.app.generated.model.UpdateNodeRequest
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * HTTP adapter for the Memory tree (en.memory.tree, cr-0027). Implements the generated [ContentApi]; thin
 * (ADR 0001) — delegates to [NodesHandler]. `@Authenticated`; the caller (from the `SecurityIdentity`
 * principal) must hold a grant for the scope (a non-member gets 404). Operator-blind: only ciphertext +
 * opaque tokens cross. The scope root's parent slot is the scope id; the API surfaces it as `parentId = null`.
 */
@Authenticated
class ContentResource(
    private val handler: NodesHandler,
    private val identity: SecurityIdentity,
) : ContentApi {

    override fun createNode(scopeId: UUID, createNodeRequest: CreateNodeRequest): Response {
        val outcome = handler.create(
            identity.principal.name, scopeId.toString(), createNodeRequest.parentId?.toString(),
            createNodeRequest.kind.value, createNodeRequest.encryptedName, createNodeRequest.slugToken,
            createNodeRequest.orderIndex ?: 0, createNodeRequest.epoch ?: 0,
            createNodeRequest.wrappedContentKey, createNodeRequest.wrappedMeta,
        )
        return when (outcome) {
            is CreateNodeOutcome.Created ->
                Response.status(Response.Status.CREATED).entity(NodeCreated(nodeId = UUID.fromString(outcome.nodeId))).build()
            CreateNodeOutcome.NoScope -> error(Response.Status.NOT_FOUND, "scope_not_found")
            CreateNodeOutcome.ScopeDeleting -> error(Response.Status.CONFLICT, "scope_deleting")
            CreateNodeOutcome.IllegalStructure -> error(422, "illegal_structure")
            CreateNodeOutcome.SlugTaken -> error(Response.Status.CONFLICT, "slug_taken")
        }
    }

    override fun listNodes(scopeId: UUID): Response {
        val sid = scopeId.toString()
        return when (val o = handler.list(identity.principal.name, sid)) {
            is ListNodesOutcome.Nodes -> Response.ok(o.rows.map { it.toSummary(sid) }).build()
            ListNodesOutcome.NoScope -> error(Response.Status.NOT_FOUND, "scope_not_found")
        }
    }

    override fun updateNode(scopeId: UUID, nodeId: UUID, updateNodeRequest: UpdateNodeRequest): Response =
        when (handler.update(
            identity.principal.name, scopeId.toString(), nodeId.toString(),
            updateNodeRequest.encryptedName, updateNodeRequest.slugToken,
            updateNodeRequest.parentId?.toString(), updateNodeRequest.orderIndex, updateNodeRequest.wrappedMeta,
        )) {
            UpdateNodeOutcome.Updated -> Response.noContent().build()
            UpdateNodeOutcome.NotFound -> error(Response.Status.NOT_FOUND, "node_not_found")
            UpdateNodeOutcome.ScopeDeleting -> error(Response.Status.CONFLICT, "scope_deleting")
            UpdateNodeOutcome.IllegalStructure -> error(422, "illegal_structure")
            UpdateNodeOutcome.SlugTaken -> error(Response.Status.CONFLICT, "slug_taken")
        }

    override fun deleteNode(scopeId: UUID, nodeId: UUID): Response =
        when (handler.delete(identity.principal.name, scopeId.toString(), nodeId.toString())) {
            DeleteNodeOutcome.Deleted -> Response.noContent().build()
            DeleteNodeOutcome.NoScope -> error(Response.Status.NOT_FOUND, "scope_not_found")
            DeleteNodeOutcome.ScopeDeleting -> error(Response.Status.CONFLICT, "scope_deleting")
        }

    /** Map a stored row to the API shape: the root's parent slot (= scope id) surfaces as `parentId = null`. */
    private fun NodeRow.toSummary(scopeId: String) = NodeSummary(
        id = UUID.fromString(id),
        kind = NodeKind.valueOf(kind),
        encryptedName = encryptedName,
        slugToken = slugToken,
        orderIndex = orderIndex,
        epoch = epoch,
        parentId = if (parentId == scopeId) null else UUID.fromString(parentId),
        wrappedContentKey = wrappedContentKey,
        wrappedMeta = wrappedMeta,
    )

    private fun error(status: Response.Status, code: String): Response =
        Response.status(status).entity(ErrorResponse(code = code)).build()

    private fun error(status: Int, code: String): Response =
        Response.status(status).entity(ErrorResponse(code = code)).build()
}
