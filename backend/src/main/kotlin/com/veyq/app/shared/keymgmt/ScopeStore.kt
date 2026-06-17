package com.veyq.app.shared.keymgmt

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import java.time.Instant

// Scope key material in the blind store. The scope public key is plaintext; the scope private key only
// ever exists as a per-user grant (asym-wrapped to that user's identity key) — never held by the server.

// The root a scope anchors: a user's PERSONAL space, a tenant-wide TENANT_ROOT, or a GROUP_ROOT subspace.
// Constant names ARE the persisted values, so the column stays string-valued under @Enumerated(STRING).
enum class ScopeKind { PERSONAL_ROOT, TENANT_ROOT, GROUP_ROOT }

@Entity
@Table(name = "veyq_scope")
class Scope {
    @Id var id: String = ""
    @Column(name = "tenant_id") var tenantId: String? = null
    @Column(name = "owner_user_id") var ownerUserId: String? = null
    @Enumerated(EnumType.STRING) @Column(nullable = false) var kind: ScopeKind = ScopeKind.PERSONAL_ROOT
    @Column(name = "suite_id", nullable = false) var suiteId: String = ""
    @Column(name = "kem_public_key", nullable = false) var kemPublicKey: ByteArray = ByteArray(0)
    // GROUP_ROOT only: the group name sealed under the group content key (operator-blind). Null otherwise.
    @Column(name = "sealed_name") var sealedName: ByteArray? = null
    @Column(name = "name_suite_id") var nameSuiteId: String? = null
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
    /** The tenant-wide space. A tenant has exactly one TENANT_ROOT (groups also carry the tenant id). */
    fun findByTenantId(tenantId: String): Scope? = find("tenantId = ?1 and kind = ?2", tenantId, ScopeKind.TENANT_ROOT).firstResult()

    /** Every scope under a tenant — its TENANT_ROOT plus all GROUP_ROOT scopes (used by tenant delete). */
    fun listByTenantId(tenantId: String): List<Scope> = list("tenantId", tenantId)

    fun listByIds(ids: Collection<String>): List<Scope> = if (ids.isEmpty()) emptyList() else list("id in ?1", ids)

    /** The TENANT_ROOT scope of each given tenant (the roster's tenant-space lookup, batched). */
    fun listTenantRootsByTenantIds(tenantIds: Collection<String>): List<Scope> =
        if (tenantIds.isEmpty()) emptyList() else list("kind = ?1 and tenantId in ?2", ScopeKind.TENANT_ROOT, tenantIds)

    fun listGroupsByTenantId(tenantId: String): List<Scope> =
        list("tenantId = ?1 and kind = ?2", tenantId, ScopeKind.GROUP_ROOT)
    fun findPersonalByOwnerUserId(userId: String): Scope? =
        find("ownerUserId = ?1 and kind = ?2", userId, ScopeKind.PERSONAL_ROOT).firstResult()

    /** Row-locked scope read — serialize per-scope insert-if-absent paths (e.g. dedup blob first upload). */
    fun lockById(id: String): Scope? = find("id", id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
}

@ApplicationScoped
class ScopeGrantRepository : PanacheRepositoryBase<ScopeGrant, String> {
    fun find(scopeId: String, userId: String): ScopeGrant? =
        find("scopeId = ?1 and userId = ?2", scopeId, userId).firstResult()

    fun listByUserId(userId: String): List<ScopeGrant> = list("userId", userId)

    fun deleteByScopeId(scopeId: String): Long = delete("scopeId", scopeId)
}
