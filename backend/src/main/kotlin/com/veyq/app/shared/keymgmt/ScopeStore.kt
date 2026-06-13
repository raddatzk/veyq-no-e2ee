package com.veyq.app.shared.keymgmt

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// Scope key material in the blind store. The scope public key is plaintext; the scope private key only
// ever exists as a per-user grant (asym-wrapped to that user's identity key) — never held by the server.

@Entity
@Table(name = "veyq_scope")
class Scope {
    @Id var id: String = ""
    @Column(name = "tenant_id") var tenantId: String? = null
    @Column(name = "owner_user_id") var ownerUserId: String? = null
    @Column(nullable = false) var kind: String = ""
    @Column(name = "suite_id", nullable = false) var suiteId: String = ""
    @Column(name = "kem_public_key", nullable = false) var kemPublicKey: ByteArray = ByteArray(0)
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@Entity
@Table(name = "veyq_scope_grant")
class ScopeGrant {
    @Id var id: String = ""
    @Column(name = "scope_id", nullable = false) var scopeId: String = ""
    @Column(name = "user_id", nullable = false) var userId: String = ""
    @Column(name = "suite_id", nullable = false) var suiteId: String = ""
    @Column(name = "wrapped_private_key", nullable = false) var wrappedPrivateKey: ByteArray = ByteArray(0)
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@ApplicationScoped
class ScopeRepository : PanacheRepositoryBase<Scope, String> {
    fun findByTenantId(tenantId: String): Scope? = find("tenantId", tenantId).firstResult()
    fun findPersonalByOwnerUserId(userId: String): Scope? =
        find("ownerUserId = ?1 and kind = ?2", userId, "PERSONAL_ROOT").firstResult()
}

@ApplicationScoped
class ScopeGrantRepository : PanacheRepositoryBase<ScopeGrant, String> {
    fun find(scopeId: String, userId: String): ScopeGrant? =
        find("scopeId = ?1 and userId = ?2", scopeId, userId).firstResult()
}
