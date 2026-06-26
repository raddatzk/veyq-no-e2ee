package com.veyq.app.features.admin.tenant

import com.veyq.app.shared.crypto.Tokens
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration
import java.time.Instant

/** Result of a create-tenant attempt. */
sealed interface CreateTenantOutcome {
    data class Created(val tenantId: String, val expiresAt: Instant) : CreateTenantOutcome
    data object DomainTaken : CreateTenantOutcome
}

/** Result of a resend/reassign on a pending tenant's invite. */
sealed interface InviteOutcome {
    data class Done(val invite: InviteView) : InviteOutcome
    data object NotFound : InviteOutcome
    data object NotPending : InviteOutcome
}

/** Result of revoking a pending tenant. */
sealed interface RevokeOutcome {
    data object Done : RevokeOutcome
    data object NotFound : RevokeOutcome
    data object NotPending : RevokeOutcome
}

/**
 * Server-Owner tenant administration, structural half (uc.admin.create-tenant Slice A, cr-0009). Purely
 * fachlich: authentication + the Server-Owner gate are central (`@RolesAllowed` on [TenantsResource] →
 * `SecurityIdentity`, cr-0012), so the handler only sees already-authorized calls. It mints the hashed bootstrap
 * invite (only the hash is stored; the raw token goes to the [TenantInviteMailer] for the link) and keeps the
 * Resource a thin HTTP adapter (ADR 0001). No content crypto, no scope key — activation is Slice B. The caller's
 * identity is not recorded (Slice A tracks no `created_by`), so no userId is threaded in.
 */
@ApplicationScoped
class TenantHandler(
    private val store: TenantStore,
    private val mailer: TenantInviteMailer,
    @param:ConfigProperty(name = "veyq.tenant.invite.ttl", defaultValue = "P7D") private val inviteTtl: Duration,
) {

    @Transactional
    fun create(name: String, domain: String, ownerEmail: String): CreateTenantOutcome {
        val normalizedDomain = domain.lowercase()
        val normalizedEmail = ownerEmail.lowercase()
        if (store.domainExists(normalizedDomain)) return CreateTenantOutcome.DomainTaken

        val token = Tokens.random()
        val expiresAt = Instant.now().plus(inviteTtl)
        val (tenantId, expiry) = store.createPendingTenant(name, normalizedDomain, normalizedEmail, Tokens.hash(token), expiresAt)
        mailer.sendInvite(normalizedDomain, normalizedEmail, token, expiry)
        return CreateTenantOutcome.Created(tenantId, expiry)
    }

    @Transactional
    fun list(): List<TenantView> = store.listAll()

    /** Resend: re-mint to the SAME owner email. */
    @Transactional
    fun resendInvite(tenantId: String): InviteOutcome =
        onPendingTenant(tenantId) { tenant -> mintTo(tenant.id, tenant.ownerEmail, tenant.domain) }

    /** Reassign: re-bind to a NEW owner email, then re-mint. */
    @Transactional
    fun reassignInvite(tenantId: String, newOwnerEmail: String): InviteOutcome =
        onPendingTenant(tenantId) { tenant ->
            val email = newOwnerEmail.lowercase()
            store.updateOwnerEmail(tenant.id, email)
            mintTo(tenant.id, email, tenant.domain)
        }

    @Transactional
    fun revoke(tenantId: String): RevokeOutcome {
        val tenant = store.findTenant(tenantId) ?: return RevokeOutcome.NotFound
        if (tenant.status != TenantEntity.PENDING) return RevokeOutcome.NotPending
        store.deleteTenant(tenantId)
        return RevokeOutcome.Done
    }

    private fun mintTo(tenantId: String, ownerEmail: String, domain: String): InviteView {
        val token = Tokens.random()
        val expiresAt = Instant.now().plus(inviteTtl)
        val invite = store.supersedeAndMintInvite(tenantId, ownerEmail, Tokens.hash(token), expiresAt)
        mailer.sendInvite(domain, ownerEmail, token, expiresAt)
        return invite
    }

    private inline fun onPendingTenant(tenantId: String, block: (TenantEntity) -> InviteView): InviteOutcome {
        val tenant = store.findTenant(tenantId) ?: return InviteOutcome.NotFound
        if (tenant.status != TenantEntity.PENDING) return InviteOutcome.NotPending
        return InviteOutcome.Done(block(tenant))
    }
}
