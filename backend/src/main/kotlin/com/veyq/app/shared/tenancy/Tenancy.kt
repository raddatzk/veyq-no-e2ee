package com.veyq.app.shared.tenancy

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class TenantStatus { PENDING, ACTIVE }
enum class InviteStatus { PENDING, CONSUMED, REVOKED }

@Entity
@Table(name = "veyq_tenant")
class Tenant {
    @Id var id: String = ""
    @Column(nullable = false, unique = true) var name: String = ""
    @Column(name = "display_name", nullable = false) var displayName: String = ""
    @Column var domain: String? = null
    @Column(nullable = false) var status: String = TenantStatus.PENDING.name
    @Column(name = "created_by", nullable = false) var createdBy: String = ""
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@Entity
@Table(name = "veyq_tenant_invite")
class TenantInvite {
    @Id var id: String = ""
    @Column(name = "tenant_id", nullable = false) var tenantId: String = ""
    @Column(nullable = false) var role: String = ""
    @Column(name = "secret_hash", nullable = false) var secretHash: ByteArray = ByteArray(0)
    @Column(nullable = false) var status: String = InviteStatus.PENDING.name
    @Column(name = "consumed_by") var consumedBy: String? = null
    @Column(name = "created_by", nullable = false) var createdBy: String = ""
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
    @Column(name = "expires_at", nullable = false) var expiresAt: Instant = Instant.EPOCH
}

@Entity
@Table(name = "veyq_membership")
class Membership {
    @Id var id: String = ""
    @Column(name = "tenant_id", nullable = false) var tenantId: String = ""
    @Column(name = "user_id", nullable = false) var userId: String = ""
    @Column(nullable = false) var role: String = ""
    @Column(nullable = false) var status: String = "ACTIVE"
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@ApplicationScoped
class TenantRepository : PanacheRepositoryBase<Tenant, String> {
    fun findByName(name: String): Tenant? = find("name", name).firstResult()
}

@ApplicationScoped
class TenantInviteRepository : PanacheRepositoryBase<TenantInvite, String>

@ApplicationScoped
class MembershipRepository : PanacheRepositoryBase<Membership, String> {
    fun find(tenantId: String, userId: String): Membership? =
        find("tenantId = ?1 and userId = ?2", tenantId, userId).firstResult()
}
