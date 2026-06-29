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

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<ScopeEntity, String> {
        const val TENANT = "TENANT"
        const val GROUP = "GROUP"
        const val PERSONAL = "PERSONAL"
    }
}
