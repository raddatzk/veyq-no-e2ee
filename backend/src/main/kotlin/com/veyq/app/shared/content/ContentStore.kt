package com.veyq.app.shared.content

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// Encrypted content in the blind store. The content key is opaque ciphertext (wrapped to the scope key);
// items are opaque ciphertext (sealed under the content key). The server never interprets either.

@Entity
@Table(name = "veyq_scope_content_key")
class ScopeContentKey {
    @Id var id: String = ""
    @Column(name = "scope_id", nullable = false, unique = true) var scopeId: String = ""
    @Column(name = "suite_id", nullable = false) var suiteId: String = ""
    @Column(name = "wrapped_key", nullable = false) var wrappedKey: ByteArray = ByteArray(0)
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@Entity
@Table(name = "veyq_scope_item")
class ScopeItem {
    @Id var id: String = ""
    @Column(name = "scope_id", nullable = false) var scopeId: String = ""
    @Column(name = "suite_id", nullable = false) var suiteId: String = ""
    @Column(name = "sealed_content", nullable = false) var sealedContent: ByteArray = ByteArray(0)
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@ApplicationScoped
class ScopeContentKeyRepository : PanacheRepositoryBase<ScopeContentKey, String> {
    fun findByScopeId(scopeId: String): ScopeContentKey? = find("scopeId", scopeId).firstResult()
}

@ApplicationScoped
class ScopeItemRepository : PanacheRepositoryBase<ScopeItem, String> {
    fun listByScopeId(scopeId: String): List<ScopeItem> = list("scopeId = ?1 order by createdAt", scopeId)
}
