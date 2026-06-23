package com.veyq.app.features.account.registerviainvitation

import com.veyq.app.generated.model.RegisterViaInvitationRequest
import com.veyq.app.generated.model.RegisterViaInvitationResponse
import com.veyq.app.generated.model.TenantSummary
import com.veyq.app.generated.model.UserSummary
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.identity.AccountFactory
import com.veyq.app.shared.identity.NewAccount
import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.identity.toUserSummary
import com.veyq.app.shared.keymgmt.Scope
import com.veyq.app.shared.keymgmt.ScopeGrant
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeKind
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.IssuedSession
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.InviteKind
import com.veyq.app.shared.tenancy.InviteStatus
import com.veyq.app.shared.tenancy.Membership
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.MembershipStatus
import com.veyq.app.shared.tenancy.Tenant
import com.veyq.app.shared.tenancy.TenantInviteRepository
import com.veyq.app.shared.tenancy.TenantRepository
import com.veyq.app.shared.tenancy.TenantStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import com.veyq.app.platform.error.notFound

data class RegisterViaInvitationResult(
    val response: RegisterViaInvitationResponse,
    val issuedSession: IssuedSession,
)

/**
 * uc.account.register-via-invitation — a brand-new invitee accepts an owner invite. Their client
 * generates all key material; this one atomic step creates the cryptographic account (identity +
 * master-key unlock wraps + auth verifier), mints the empty tenant's scope as a self grant, activates
 * the Owner membership, consumes the invite and issues a session. The server stays blind: it stores
 * only public keys, ciphertext and an auth verifier — never a private key.
 *
 * The owner invite targets an empty tenant, so there are no existing scope keys to temp-wrap; the
 * scope keypair is born here (the new-account counterpart to the existing-user claim path).
 */
@ApplicationScoped
class RegisterViaInvitationHandler(
    private val accounts: AccountFactory,
    private val users: UserRepository,
    private val tenants: TenantRepository,
    private val invites: TenantInviteRepository,
    private val memberships: MembershipRepository,
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val sessions: SessionService,
    private val suites: CryptoSuiteRegistry,
) {
    private val dec = Base64.getUrlDecoder()
    private val enc = Base64.getUrlEncoder().withoutPadding()

    @Transactional
    fun registerViaInvitation(inviteId: String, req: RegisterViaInvitationRequest): RegisterViaInvitationResult {
        val invite = invites.findByIdForUpdate(inviteId) ?: throw notFound("Invite")
        if (invite.status != InviteStatus.PENDING) throw ApiException(409, "INVITE_NOT_PENDING", "Invite is not claimable")
        if (invite.expiresAt.isBefore(Instant.now())) throw ApiException(409, "INVITE_EXPIRED", "Invite has expired")
        if (!MessageDigest.isEqual(sha256(dec.decode(req.bootstrapSecret)), invite.secretHash)) {
            throw ApiException(400, "BAD_BOOTSTRAP_SECRET", "Invalid bootstrap secret")
        }
        if (invite.kind == InviteKind.OWNER) {
            val target = invite.targetEmail ?: throw ApiException(409, "INVITE_TARGET_REQUIRED", "Owner invite is missing a target email")
            if (!target.equals(req.email.trim(), ignoreCase = true)) {
                throw ApiException(403, "INVITE_EMAIL_MISMATCH", "Invite is bound to a different email address")
            }
        }
        // tenant-scope suites are validated here; the account material (identity/wraps/verifier/personal
        // scope) is validated by the AccountFactory below.
        suites.require(req.scopePublicKey.suiteId)
        suites.require(req.wrappedScopePrivateKey.suiteId)
        if (users.findByEmail(req.email) != null) {
            throw ApiException(409, "EMAIL_TAKEN", "An account with that email already exists")
        }

        val tenant = tenants.findById(invite.tenantId) ?: throw notFound("Tenant")
        val now = Instant.now()

        // 1+2. the cryptographic account + tenant-bound personal scope.
        val user = accounts.createAccount(
            NewAccount(
                email = req.email,
                displayName = req.displayName,
                serverRole = ServerRole.ServerMember,
                identityPublicKey = req.identityPublicKey,
                wrappedIdentityPrivateKey = req.wrappedIdentityPrivateKey,
                masterKeyWraps = req.masterKeyWraps,
                recoveryAuthTag = req.recoveryAuthTag,
                authVerifier = req.authVerifier,
                personalScopePublicKey = req.personalScopePublicKey,
                wrappedPersonalScopePrivateKey = req.wrappedPersonalScopePrivateKey,
            ),
            now,
            personalTenantId = tenant.id,
        )

        // 3. the tenant scope grant — born here for an owner claim, or re-wrapped from a temp invite
        if (invite.kind == InviteKind.MEMBER) {
            // MEMBER: the scope already exists. The invitee unwrapped it with the temp key (from the URL
            // fragment) and re-wrapped it to their own identity — add their grant only. No new scope, no
            // re-activation. The posted scope public key must match the existing scope (integrity).
            val scope = invite.scopeId?.let { scopes.findById(it) } ?: throw notFound("Scope")
            if (enc.encodeToString(scope.kemPublicKey) != req.scopePublicKey.kemPublicKey) {
                throw ApiException(400, "SCOPE_MISMATCH", "Scope public key does not match the invite")
            }
            grants.persist(
                ScopeGrant().apply {
                    id = newId(); scopeId = scope.id; userId = user.id
                    suiteId = req.wrappedScopePrivateKey.suiteId; wrappedPrivateKey = dec.decode(req.wrappedScopePrivateKey.bytes); createdAt = now
                },
            )
        } else {
            // OWNER: an empty tenant — the scope keypair is born here (self grant) and the tenant activates.
            if (scopes.findByTenantId(tenant.id) != null) throw ApiException(409, "TENANT_ALREADY_CLAIMED", "Tenant already has an owner")
            val scope = Scope().apply {
                id = newId(); tenantId = tenant.id; kind = ScopeKind.TENANT_ROOT
                suiteId = req.scopePublicKey.suiteId; kemPublicKey = dec.decode(req.scopePublicKey.kemPublicKey); createdAt = now
            }
            scopes.persist(scope)
            grants.persist(
                ScopeGrant().apply {
                    id = newId(); scopeId = scope.id; userId = user.id
                    suiteId = req.wrappedScopePrivateKey.suiteId; wrappedPrivateKey = dec.decode(req.wrappedScopePrivateKey.bytes); createdAt = now
                },
            )
            tenant.status = TenantStatus.ACTIVE
        }

        memberships.persist(
            Membership().apply {
                id = newId(); tenantId = tenant.id; userId = user.id; role = invite.role; status = MembershipStatus.ACTIVE; createdAt = now
            },
        )

        // 4. consume the invite, hand back a session
        invite.status = InviteStatus.CONSUMED
        invite.consumedBy = user.id

        val issued = sessions.issuePair(user.id)
        return RegisterViaInvitationResult(
            response = RegisterViaInvitationResponse(
                sessionToken = issued.accessToken,
                user = user.toUserSummary(),
                tenant = tenant.toSummary(),
            ),
            issuedSession = issued,
        )
    }

    private fun Tenant.toSummary(): TenantSummary =
        TenantSummary(id = id, name = name, displayName = displayName, status = TenantSummary.Status.valueOf(status.name), domain = domain)


    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    private fun newId(): String = UUID.randomUUID().toString()
}
