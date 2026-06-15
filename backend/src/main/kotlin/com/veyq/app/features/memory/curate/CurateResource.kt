package com.veyq.app.features.memory.curate

import com.veyq.app.generated.api.NodeCurateApi
import com.veyq.app.generated.model.UpdateNodeRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry for the generated NodeCurateApi (PATCH /nodes/{nodeId}); delegates to the handler. */
@ApplicationScoped
class CurateResource(private val handler: CurateHandler) : NodeCurateApi {
    override fun updateNode(authorization: String, scopeId: String, nodeId: String, updateNodeRequest: UpdateNodeRequest): Response =
        Response.ok(handler.updateNode(authorization, scopeId, nodeId, updateNodeRequest)).build()
}
