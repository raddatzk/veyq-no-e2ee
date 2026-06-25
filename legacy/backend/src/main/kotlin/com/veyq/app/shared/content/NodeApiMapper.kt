package com.veyq.app.shared.content

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.MemoryNode
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

/**
 * The one node → API MemoryNode mapper: the root-sentinel parentId rule (parent == scope id → null) and the
 * sealed-content encoding. Shared by create (MemoryTreeHandler), curate (CurateHandler) and share (ShareHandler)
 * so the wire shape can't drift between three private copies (mirrors MediaApiMapper / NodeStructure).
 */
object NodeApiMapper {
    fun toApi(node: Node, scopeId: String, enc: Base64.Encoder): MemoryNode =
        MemoryNode(
            id = node.id,
            parentId = if (node.parentId == scopeId) null else node.parentId,
            scopeId = node.scopeId,
            type = node.type,
            slugToken = node.slugToken,
            orderIndex = node.orderIndex,
            sealedContent = Blob(suiteId = node.suiteId, bytes = enc.encodeToString(node.sealedContent)),
            coverMediaId = node.coverMediaId,
            createdAt = OffsetDateTime.ofInstant(node.createdAt, ZoneOffset.UTC),
        )
}
