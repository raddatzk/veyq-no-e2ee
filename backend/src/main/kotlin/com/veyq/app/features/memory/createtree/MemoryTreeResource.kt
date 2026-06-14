package com.veyq.app.features.memory.createtree

import com.veyq.app.generated.api.MemoryTreeApi
import com.veyq.app.generated.model.CreateNodeRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated MemoryTreeApi; delegates to the handler. */
@ApplicationScoped
class MemoryTreeResource(private val handler: MemoryTreeHandler) : MemoryTreeApi {

    override fun listNodes(authorization: String, scopeId: String): Response =
        Response.ok(handler.listNodes(authorization, scopeId)).build()

    override fun createNode(authorization: String, scopeId: String, createNodeRequest: CreateNodeRequest): Response =
        Response.ok(handler.createNode(authorization, scopeId, createNodeRequest)).build()
}
