package com.veyq.app.features.admin.tenant

import com.veyq.app.shared.crypto.Tokens
import com.veyq.app.shared.identity.AccountStore
import com.veyq.app.shared.identity.ServerRoles
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.shared.identity.WrapData
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.time.Instant

/** Cold-birth account material for the register-and-accept path (the email comes from the invite). */
data class NewAccount(
    val displayName: String,
    val suiteId: String,
    val identityPublicKey: String,
    val wrappedIdentityPrivateKey: String,
    val authPublicKey: String,
    val recoveryMasterKeyTag: String?,
    val wraps: List<WrapData>,
)

sealed interface PreviewOutcome {
    data class Found(
        val tenantId: String,
        val tenantName: String,
        val domain: String,
        val ownerEmail: String,
        val hasAccount: Boolean,
    ) : PreviewOutcome
    data object NotFound : PreviewOutcome
    data object NoLongerValid : PreviewOutcome
}

sealed interface AcceptOutcome {
    data class Activated(val tenantId: String) : AcceptOutcome
    data object NotFound : AcceptOutcome
    data object NoLongerValid : AcceptOutcome
    data object NotInvitedOwner : AcceptOutcome
    data object AlreadyActive : AcceptOutcome
}

sealed interface RegisterAcceptOutcome {
    data class Activated(val tenantId: String, val userId: String) : RegisterAcceptOutcome
    data object NotFound : RegisterAcceptOutcome
    data object NoLongerValid : RegisterAcceptOutcome
    data object EmailTaken : RegisterAcceptOutcome
}

/**
 * Tenant activation — invite acceptance (uc.admin.create-tenant Slice B, cr-0010). Verifies the bootstrap
 * secret by hash, applies reuse-detection (a presented non-ACTIVE / expired invite is a replay → rejected +
 * logged, mirroring the session model), and on success cold-activates the tenant atomically: the owner's
 * `OWNER` membership + grant, the tenant flipped `ACTIVE`, the invite `CONSUMED`. Operator-blind — the grant
 * is opaque ciphertext stored verbatim; the server never sees the scope key.
 */
@ApplicationScoped
class TenantAcceptHandler(
    private val store: TenantStore,
    private val accounts: AccountStore,
) {
    private val log = Logger.getLogger(TenantAcceptHandler::class.java)

    @Transactional
    fun preview(bootstrapSecret: String): PreviewOutcome {
        val invite = store.findInviteByTokenHash(Tokens.hash(bootstrapSecret)) ?: return PreviewOutcome.NotFound
        if (!isLive(invite)) return PreviewOutcome.NoLongerValid
        val tenant = store.findTenant(invite.tenantId) ?: return PreviewOutcome.NotFound
        return PreviewOutcome.Found(
            tenantId = tenant.id,
            tenantName = tenant.name,
            domain = tenant.domain,
            ownerEmail = tenant.ownerEmail,
            hasAccount = accounts.findByEmail(tenant.ownerEmail) != null,
        )
    }

    /** Existing-user accept: the caller (resolved from the session) must be the invited owner. */
    @Transactional
    fun accept(userId: String, bootstrapSecret: String, grant: String): AcceptOutcome {
        val invite = store.findInviteByTokenHash(Tokens.hash(bootstrapSecret)) ?: return AcceptOutcome.NotFound
        if (!isLive(invite)) return AcceptOutcome.NoLongerValid
        val tenant = store.findTenant(invite.tenantId) ?: return AcceptOutcome.NotFound
        if (tenant.status != TenantEntity.PENDING) return AcceptOutcome.AlreadyActive

        val caller = VeyqUser.findById(userId) ?: return AcceptOutcome.NotInvitedOwner
        if (caller.email != invite.ownerEmail) return AcceptOutcome.NotInvitedOwner

        store.activate(tenant.id, userId, grant, invite.id)
        return AcceptOutcome.Activated(tenant.id)
    }

    /** Register-and-accept: the invited email has no account yet — born cold here (email from the invite). */
    @Transactional
    fun registerAndAccept(bootstrapSecret: String, account: NewAccount, grant: String): RegisterAcceptOutcome {
        val invite = store.findInviteByTokenHash(Tokens.hash(bootstrapSecret)) ?: return RegisterAcceptOutcome.NotFound
        if (!isLive(invite)) return RegisterAcceptOutcome.NoLongerValid
        val tenant = store.findTenant(invite.tenantId) ?: return RegisterAcceptOutcome.NotFound

        if (accounts.findByEmail(tenant.ownerEmail) != null) return RegisterAcceptOutcome.EmailTaken

        val userId = accounts.createAccount(
            email = tenant.ownerEmail,
            displayName = account.displayName,
            serverRole = ServerRoles.MEMBER,
            suiteId = account.suiteId,
            identityPublicKey = account.identityPublicKey,
            wrappedIdentityPrivateKey = account.wrappedIdentityPrivateKey,
            authPublicKey = account.authPublicKey,
            recoveryMasterKeyTag = account.recoveryMasterKeyTag,
            wraps = account.wraps,
        )
        store.activate(tenant.id, userId, grant, invite.id)
        return RegisterAcceptOutcome.Activated(tenant.id, userId)
    }

    /** A live invite is ACTIVE and unexpired. A presented non-ACTIVE/expired invite is a replay → logged. */
    private fun isLive(invite: TenantInviteEntity): Boolean {
        if (invite.status != TenantInviteEntity.ACTIVE) {
            log.warnf("Tenant invite replay rejected (status=%s, tenant=%s)", invite.status, invite.tenantId)
            return false
        }
        return !invite.expiresAt.isBefore(Instant.now())
    }
}
