package com.veyq.app.features.spaces.scope

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A scope — the unit content is keyed on (en.spaces.personal-scope, cr-0026). Generalises the cr-0010
 * "scope == tenant" shortcut into a first-class row: an opaque id + a `kind` (TENANT root / GROUP / PERSONAL),
 * living within exactly one tenant (ADR 0002). Operator-blind: there is **no key here** — the scope key
 * exists only inside each member's ciphertext `wrappedGrant` on their membership row. The TENANT root scope
 * is keyed `id = tenant_id` (born at activation, cr-0010); PERSONAL/GROUP get fresh ids.
 */
@Entity
@Table(name = "veyq_scope")
class ScopeEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "tenant_id", nullable = false, length = 36)
    lateinit var tenantId: String

    @Column(name = "kind", nullable = false, length = 16)
    lateinit var kind: String

    /** A GROUP scope's name, sealed under its `K_SC` (ciphertext base64); null for TENANT/PERSONAL (ADR 0014). */
    @Column(name = "encrypted_name")
    var encryptedName: String? = null

    /** The scope's **current content-key epoch** (ADR 0024, cr-0088) — 0 until the first rotation. New content
     *  seals under this epoch; a removal bumps it. */
    @Column(name = "current_epoch", nullable = false)
    var currentEpoch: Int = 0

    /** When a GROUP was **soft-deleted** (uc.spaces.delete-group, cr-0090); null = active. While set, the group is
     *  in a grace period — readable but read-only, and the scheduled purge hard-erases it after the retention. */
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<ScopeEntity, String> {
        const val TENANT = "TENANT"
        const val GROUP = "GROUP"
        const val PERSONAL = "PERSONAL"
    }
}

/**
 * One epoch-chain link of a scope (ADR 0024, cr-0088). For epoch `n ≥ 1`, `wrappedPrev = symWrap(E_n, E_{n-1})` —
 * the previous epoch key sealed under this epoch's key (opaque ciphertext). A member holding the latest epoch
 * (via their grant) peels these links down to `E_0` to read content of any epoch. Operator-blind: no key here.
 */
@Entity
@Table(name = "veyq_scope_epoch")
class ScopeEpochEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "scope_id", nullable = false, length = 36)
    lateinit var scopeId: String

    @Column(name = "epoch", nullable = false)
    var epoch: Int = 0

    @Column(name = "wrapped_prev", nullable = false)
    lateinit var wrappedPrev: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<ScopeEpochEntity, String>
}
