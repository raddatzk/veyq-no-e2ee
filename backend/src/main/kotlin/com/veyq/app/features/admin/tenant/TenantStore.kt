package com.veyq.app.features.admin.tenant

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** A tenant plus the state of its current (latest) invite, for the Server-Owner list view. */
data class TenantView(
    val id: String,
    val name: String,
    val domain: String,
    val ownerEmail: String,
    val status: String,
    val inviteStatus: String?,
    val inviteExpiresAt: Instant?,
)

/** The state of a tenant's current invite after a mint/resend/reassign. */
data class InviteView(val ownerEmail: String, val status: String, val expiresAt: Instant)

/**
 * Persistence for tenant creation + invite lifecycle (cr-0009). The invite is its own row history; only the
 * SHA-256 token hash is stored (never the token), and transitions are soft (ACTIVE → SUPERSEDED/CONSUMED/
 * EXPIRED) — nothing is hard-deleted inline; a scheduled purge removes terminal rows later.
 */
@ApplicationScoped
class TenantStore {

    fun domainExists(domain: String): Boolean = TenantEntity.count("domain", domain) > 0

    fun findTenant(id: String): TenantEntity? = TenantEntity.findById(id)

    /** Create a PENDING tenant with its first ACTIVE invite. Returns the new tenant id + invite expiry. */
    @Transactional
    fun createPendingTenant(
        name: String,
        domain: String,
        ownerEmail: String,
        tokenHash: String,
        expiresAt: Instant,
    ): Pair<String, Instant> {
        val now = Instant.now()
        val tenantId = UUID.randomUUID().toString()
        TenantEntity().apply {
            id = tenantId
            this.name = name
            this.domain = domain
            this.ownerEmail = ownerEmail
            status = TenantEntity.PENDING
            createdAt = now
        }.persist()
        mintInvite(tenantId, ownerEmail, tokenHash, expiresAt, now)
        return tenantId to expiresAt
    }

    /** Soft-supersede the tenant's current ACTIVE invite(s) and mint a fresh ACTIVE one (resend/reassign). */
    @Transactional
    fun supersedeAndMintInvite(
        tenantId: String,
        ownerEmail: String,
        tokenHash: String,
        expiresAt: Instant,
    ): InviteView {
        TenantInviteEntity.update(
            "status = ?1 where tenantId = ?2 and status = ?3",
            TenantInviteEntity.SUPERSEDED, tenantId, TenantInviteEntity.ACTIVE,
        )
        mintInvite(tenantId, ownerEmail, tokenHash, expiresAt, Instant.now())
        return InviteView(ownerEmail, TenantInviteEntity.ACTIVE, expiresAt)
    }

    /** Re-bind the tenant's intended owner email (reassign). */
    @Transactional
    fun updateOwnerEmail(tenantId: String, ownerEmail: String) {
        TenantEntity.update("ownerEmail = ?1 where id = ?2", ownerEmail, tenantId)
    }

    /** Revoke a pending tenant: delete its invite history then the tenant itself. */
    @Transactional
    fun deleteTenant(tenantId: String) {
        TenantInviteEntity.delete("tenantId", tenantId)
        TenantEntity.deleteById(tenantId)
    }

    fun listAll(): List<TenantView> =
        TenantEntity.listAll().map { t ->
            val invite = currentInvite(t.id)
            TenantView(
                id = t.id,
                name = t.name,
                domain = t.domain,
                ownerEmail = t.ownerEmail,
                status = t.status,
                inviteStatus = invite?.status,
                inviteExpiresAt = invite?.expiresAt,
            )
        }

    private fun currentInvite(tenantId: String): TenantInviteEntity? =
        TenantInviteEntity.find("tenantId = ?1 order by createdAt desc", tenantId).firstResult()

    // --- scheduled cleanup (see TenantInvitePurge) ---

    /** Flip ACTIVE invites whose TTL has passed to EXPIRED. Returns how many were expired. */
    @Transactional
    fun expireOverdueInvites(now: Instant): Long =
        TenantInviteEntity.update(
            "status = ?1 where status = ?2 and expiresAt < ?3",
            TenantInviteEntity.EXPIRED, TenantInviteEntity.ACTIVE, now,
        ).toLong()

    /** Physically delete terminal-state invites whose TTL passed before [cutoff]. Returns how many. */
    @Transactional
    fun purgeTerminalInvites(cutoff: Instant): Long =
        TenantInviteEntity.delete(
            "status in ?1 and expiresAt < ?2",
            TenantInviteEntity.TERMINAL, cutoff,
        )

    private fun mintInvite(tenantId: String, ownerEmail: String, tokenHash: String, expiresAt: Instant, now: Instant) {
        TenantInviteEntity().apply {
            id = UUID.randomUUID().toString()
            this.tenantId = tenantId
            this.ownerEmail = ownerEmail
            this.tokenHash = tokenHash
            status = TenantInviteEntity.ACTIVE
            this.expiresAt = expiresAt
            createdAt = now
        }.persist()
    }
}
