package com.veyq.app.features.content.scopecontent

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.CreateScopeItemRequest
import com.veyq.app.generated.model.ScopeItem as ApiScopeItem
import com.veyq.app.generated.model.ScopeItemList
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.ScopeItem
import com.veyq.app.shared.content.ScopeItemRepository
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.MembershipRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * uc.spaces / content — encrypted items under a scope. Each item is AEAD-sealed under the scope's
 * content key (client-side); the server stores and returns only ciphertext, gated on holding the scope.
 */
@ApplicationScoped
class ScopeItemsHandler(
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val items: ScopeItemRepository,
    private val memberships: MembershipRepository,
    private val sessions: SessionService,
    private val suites: CryptoSuiteRegistry,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    @Transactional
    fun listScopeItems(auth: String?, scopeId: String): ScopeItemList {
        requireKeyHolder(scopeId, auth)
        return ScopeItemList(items = items.listByScopeId(scopeId).map(::toApi))
    }

    @Transactional
    fun createScopeItem(auth: String?, scopeId: String, req: CreateScopeItemRequest): ApiScopeItem {
        requireKeyHolder(scopeId, auth)
        if (!suites.isKnown(req.sealedContent.suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite")
        val item = ScopeItem().apply {
            id = UUID.randomUUID().toString(); this.scopeId = scopeId
            suiteId = req.sealedContent.suiteId; sealedContent = dec.decode(req.sealedContent.bytes); createdAt = Instant.now()
        }
        items.persist(item)
        return toApi(item)
    }

    private fun toApi(item: ScopeItem): ApiScopeItem =
        ApiScopeItem(
            id = item.id,
            sealedContent = Blob(suiteId = item.suiteId, bytes = enc.encodeToString(item.sealedContent)),
            createdAt = OffsetDateTime.ofInstant(item.createdAt, ZoneOffset.UTC),
        )

    private fun requireKeyHolder(scopeId: String, auth: String?) {
        val userId = sessions.resolve(auth) ?: throw ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
        val scope = scopes.findById(scopeId) ?: throw ApiException(404, "NOT_FOUND", "Scope not found")
        grants.find(scope.id, userId) ?: throw ApiException(403, "NOT_A_KEY_HOLDER", "You do not hold this scope")
        val tenantId = scope.tenantId
        if (tenantId != null && memberships.find(tenantId, userId)?.status != "ACTIVE") {
            throw ApiException(403, "NOT_A_MEMBER", "You are not an active member of this tenant")
        }
    }
}
