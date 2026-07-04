package com.veyq.app.features.admin.tenant

import com.veyq.app.shared.crypto.Tokens
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration
import java.time.Instant

/** Result of a create-tenant attempt. */
sealed interface CreateTenantOutcome {
    data class Created(val tenantId: String, val expiresAt: Instant, val bootstrapSecret: String) : CreateTenantOutcome
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

/** Result of a tenant owner inviting a member by email (cr-0077). */
sealed interface MemberInviteOutcome {
    data class Done(val invite: InviteView) : MemberInviteOutcome
    data object NotFound : MemberInviteOutcome
    data object NotOwner : MemberInviteOutcome
    data object NotActive : MemberInviteOutcome
    data object AlreadyMember : MemberInviteOutcome
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
        // Return the raw token so the Server Owner can copy the invite link (works without a mailer, cr-0080).
        return CreateTenantOutcome.Created(tenantId, expiry, token)
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

    /**
     * A tenant **owner** invites a person (by email) into the ACTIVE tenant as a MEMBER (cr-0077 / ADR 0020).
     * Admission is owner-gated (the caller must hold the tenant's OWNER membership); the invitee onboards via
     * the same cold-birth accept flow with the invite's MEMBER role. The scope key is delivered afterward by a
     * key-holder (cr-0076) — this only mints the admission token.
     */
    @Transactional
    fun inviteMember(callerId: String, tenantId: String, email: String): MemberInviteOutcome {
        val tenant = store.findTenant(tenantId) ?: return MemberInviteOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return MemberInviteOutcome.NotOwner
        if (tenant.status != TenantEntity.ACTIVE) return MemberInviteOutcome.NotActive
        val normalizedEmail = email.lowercase()
        if (store.isEmailMember(tenantId, normalizedEmail)) return MemberInviteOutcome.AlreadyMember

        val token = Tokens.random()
        val expiresAt = Instant.now().plus(inviteTtl)
        store.mintMemberInvite(tenantId, normalizedEmail, Tokens.hash(token), expiresAt)
        mailer.sendInvite(tenant.domain, normalizedEmail, token, expiresAt)
        return MemberInviteOutcome.Done(InviteView(normalizedEmail, TenantInviteEntity.ACTIVE, expiresAt, token))
    }

    private fun mintTo(tenantId: String, ownerEmail: String, domain: String): InviteView {
        val token = Tokens.random()
        val expiresAt = Instant.now().plus(inviteTtl)
        val invite = store.supersedeAndMintInvite(tenantId, ownerEmail, Tokens.hash(token), expiresAt)
        mailer.sendInvite(domain, ownerEmail, token, expiresAt)
        return invite.copy(bootstrapSecret = token)
    }

    private inline fun onPendingTenant(tenantId: String, block: (TenantEntity) -> InviteView): InviteOutcome {
        val tenant = store.findTenant(tenantId) ?: return InviteOutcome.NotFound
        if (tenant.status != TenantEntity.PENDING) return InviteOutcome.NotPending
        return InviteOutcome.Done(block(tenant))
    }
}
