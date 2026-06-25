package com.veyq.app.shared.tenancy

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

enum class TenantStatus { PENDING, ACTIVE }
enum class InviteStatus { PENDING, CONSUMED, REVOKED }
enum class InviteKind { OWNER, MEMBER }

// A tenant member's role. Constant names ARE the persisted/API values ("Owner"/"Member"), so they match the
// generated API role enum and the column stays string-valued under @Enumerated(STRING) — no migration needed.
enum class MemberRole { Owner, Member }

// A membership's lifecycle state. REMOVED is a soft removal (rejoinable); content + attribution are kept.
enum class MembershipStatus { ACTIVE, REMOVED }

@Entity
@Table(name = "veyq_tenant")
class Tenant {
    @Id var id: String = ""
    @Column(nullable = false, unique = true) var name: String = ""
    @Column(name = "display_name", nullable = false) var displayName: String = ""
    @Column(nullable = false, unique = true) var domain: String = ""
    @Column(name = "logo_object_key", length = 256) var logoObjectKey: String? = null
    @Column(name = "logo_content_type", length = 64) var logoContentType: String? = null
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: TenantStatus = TenantStatus.PENDING
    @Column(name = "terms_markdown", columnDefinition = "text") var termsMarkdown: String? = null
    @Column(name = "terms_version", nullable = false) var termsVersion: Int = 0
    @Column(name = "created_by", nullable = false) var createdBy: String = ""
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
    // Non-null once a deletion is scheduled; the tenant stays usable until then, then the GC hard-purges it.
    @Column(name = "scheduled_deletion_at") var scheduledDeletionAt: Instant? = null
}

@Entity
@Table(name = "veyq_tenant_invite")
class TenantInvite {
    @Id var id: String = ""
    @Column(name = "tenant_id", nullable = false) var tenantId: String = ""
    @Enumerated(EnumType.STRING) @Column(nullable = false) var role: MemberRole = MemberRole.Member
    @Column(name = "secret_hash", nullable = false) var secretHash: ByteArray = ByteArray(0)
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: InviteStatus = InviteStatus.PENDING
    @Enumerated(EnumType.STRING) @Column(nullable = false) var kind: InviteKind = InviteKind.OWNER
    @Column(name = "scope_id") var scopeId: String? = null
    @Column(name = "temp_public_key") var tempPublicKey: ByteArray? = null
    @Column(name = "temp_wrapped_scope_private_key") var tempWrappedScopePrivateKey: ByteArray? = null
    @Column(name = "temp_wrap_suite_id") var tempWrapSuiteId: String? = null
    @Column(name = "target_email") var targetEmail: String? = null
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
    @Enumerated(EnumType.STRING) @Column(nullable = false) var role: MemberRole = MemberRole.Member
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: MembershipStatus = MembershipStatus.ACTIVE
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@ApplicationScoped
class TenantRepository : PanacheRepositoryBase<Tenant, String> {
    fun findByName(name: String): Tenant? = find("name", name).firstResult()
    fun findByDomain(domain: String): Tenant? = find("domain", domain).firstResult()
    fun listByIds(ids: Collection<String>): List<Tenant> = if (ids.isEmpty()) emptyList() else list("id in ?1", ids)

    /** Tenants whose scheduled deletion deadline has passed — the GC hard-purges these. */
    fun listDue(now: Instant): List<Tenant> =
        list("scheduledDeletionAt is not null and scheduledDeletionAt < ?1", now)
}

@ApplicationScoped
class TenantInviteRepository : PanacheRepositoryBase<TenantInvite, String> {
    /** Row-locked invite read for security-sensitive single-use redemption paths. */
    fun findByIdForUpdate(id: String): TenantInvite? =
        find("id", id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()

    fun deleteByTenantId(tenantId: String): Long = delete("tenantId", tenantId)
}

@ApplicationScoped
class MembershipRepository : PanacheRepositoryBase<Membership, String> {
    fun find(tenantId: String, userId: String): Membership? =
        find("tenantId = ?1 and userId = ?2", tenantId, userId).firstResult()

    fun listByTenant(tenantId: String): List<Membership> = list("tenantId", tenantId)

    fun listActive(tenantId: String): List<Membership> =
        list("tenantId = ?1 and status = ?2", tenantId, MembershipStatus.ACTIVE)

    fun listActiveByUserId(userId: String): List<Membership> =
        list("userId = ?1 and status = ?2", userId, MembershipStatus.ACTIVE)

    fun deleteByTenantId(tenantId: String): Long = delete("tenantId", tenantId)
}
