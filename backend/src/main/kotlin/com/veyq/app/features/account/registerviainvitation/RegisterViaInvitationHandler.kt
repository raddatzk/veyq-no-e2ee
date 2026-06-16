package com.veyq.app.features.account.registerviainvitation

import com.veyq.app.generated.model.RegisterViaInvitationRequest
import com.veyq.app.generated.model.RegisterViaInvitationResponse
import com.veyq.app.generated.model.TenantSummary
import com.veyq.app.generated.model.UserSummary
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.AuthVerifier
import com.veyq.app.shared.keymgmt.AuthVerifierRepository
import com.veyq.app.shared.keymgmt.IdentityKey
import com.veyq.app.shared.keymgmt.IdentityKeyRepository
import com.veyq.app.shared.keymgmt.KeyBlob
import com.veyq.app.shared.keymgmt.KeyBlobRepository
import com.veyq.app.shared.keymgmt.Scope
import com.veyq.app.shared.keymgmt.ScopeGrant
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.InviteKind
import com.veyq.app.shared.tenancy.InviteStatus
import com.veyq.app.shared.tenancy.Membership
import com.veyq.app.shared.tenancy.MembershipRepository
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
import com.veyq.app.generated.model.ServerRole as ApiServerRole

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
    private val users: UserRepository,
    private val identityKeys: IdentityKeyRepository,
    private val keyBlobs: KeyBlobRepository,
    private val authVerifiers: AuthVerifierRepository,
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
    fun registerViaInvitation(inviteId: String, req: RegisterViaInvitationRequest): RegisterViaInvitationResponse {
        val invite = invites.findByIdForUpdate(inviteId) ?: throw notFound("Invite")
        if (invite.status != InviteStatus.PENDING.name) throw ApiException(409, "INVITE_NOT_PENDING", "Invite is not claimable")
        if (invite.expiresAt.isBefore(Instant.now())) throw ApiException(409, "INVITE_EXPIRED", "Invite has expired")
        if (!MessageDigest.isEqual(sha256(dec.decode(req.bootstrapSecret)), invite.secretHash)) {
            throw ApiException(400, "BAD_BOOTSTRAP_SECRET", "Invalid bootstrap secret")
        }
        if (req.masterKeyWraps.isEmpty()) {
            throw ApiException(400, "NO_UNLOCK_METHOD", "At least one unlock method is required")
        }
        if (req.masterKeyWraps.any { it.unlockMethod.value == "RECOVERY" } && req.recoveryAuthTag.isNullOrBlank()) {
            throw ApiException(400, "MISSING_RECOVERY_AUTH", "A RECOVERY wrap requires recoveryAuthTag")
        }
        requireSuite(req.identityPublicKey.suiteId)
        requireSuite(req.wrappedIdentityPrivateKey.suiteId)
        requireSuite(req.authVerifier.suiteId)
        req.masterKeyWraps.forEach { requireSuite(it.suiteId) }
        requireSuite(req.personalScopePublicKey.suiteId)
        requireSuite(req.wrappedPersonalScopePrivateKey.suiteId)
        requireSuite(req.scopePublicKey.suiteId)
        requireSuite(req.wrappedScopePrivateKey.suiteId)
        if (users.findByEmail(req.email) != null) {
            throw ApiException(409, "EMAIL_TAKEN", "An account with that email already exists")
        }

        val tenant = tenants.findById(invite.tenantId) ?: throw notFound("Tenant")
        val now = Instant.now()

        // 1. the cryptographic account (mirrors uc.admin.setup-server, but as a regular member)
        val user = User().apply {
            id = newId(); email = req.email; displayName = req.displayName
            serverRole = ServerRole.ServerMember; systemAccount = false; createdAt = now
        }
        users.persist(user)
        identityKeys.persist(
            IdentityKey().apply {
                id = newId(); userId = user.id; suiteId = req.identityPublicKey.suiteId
                kemPublicKey = dec.decode(req.identityPublicKey.kemPublicKey)
                signPublicKey = dec.decode(req.identityPublicKey.signPublicKey)
                wrappedPrivateKey = dec.decode(req.wrappedIdentityPrivateKey.bytes)
                wrappedPrivateSuiteId = req.wrappedIdentityPrivateKey.suiteId; createdAt = now
            },
        )
        req.masterKeyWraps.forEach { wrap ->
            keyBlobs.persist(
                KeyBlob().apply {
                    id = newId(); userId = user.id; unlockMethod = wrap.unlockMethod.value
                    suiteId = wrap.suiteId; wrappedKey = dec.decode(wrap.wrappedKey)
                    kdfParams = wrap.kdfParams?.let { dec.decode(it) }; credentialRef = wrap.credentialRef; createdAt = now
                    if (wrap.unlockMethod.value == "RECOVERY") recoveryAuthTag = req.recoveryAuthTag?.let { dec.decode(it) }
                },
            )
        }
        authVerifiers.persist(
            AuthVerifier().apply {
                id = newId(); userId = user.id; kind = req.authVerifier.kind.value
                suiteId = req.authVerifier.suiteId; verifier = dec.decode(req.authVerifier.verifier); createdAt = now
            },
        )

        // 2. the personal scope, born as self-owned and unshared
        val personalScope = Scope().apply {
            id = newId(); ownerUserId = user.id; kind = "PERSONAL_ROOT"
            suiteId = req.personalScopePublicKey.suiteId
            kemPublicKey = dec.decode(req.personalScopePublicKey.kemPublicKey); createdAt = now
        }
        scopes.persist(personalScope)
        grants.persist(
            ScopeGrant().apply {
                id = newId(); scopeId = personalScope.id; userId = user.id
                suiteId = req.wrappedPersonalScopePrivateKey.suiteId
                wrappedPrivateKey = dec.decode(req.wrappedPersonalScopePrivateKey.bytes); createdAt = now
            },
        )

        // 3. the tenant scope grant — born here for an owner claim, or re-wrapped from a temp invite
        if (invite.kind == InviteKind.MEMBER.name) {
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
                id = newId(); tenantId = tenant.id; kind = "TENANT_ROOT"
                suiteId = req.scopePublicKey.suiteId; kemPublicKey = dec.decode(req.scopePublicKey.kemPublicKey); createdAt = now
            }
            scopes.persist(scope)
            grants.persist(
                ScopeGrant().apply {
                    id = newId(); scopeId = scope.id; userId = user.id
                    suiteId = req.wrappedScopePrivateKey.suiteId; wrappedPrivateKey = dec.decode(req.wrappedScopePrivateKey.bytes); createdAt = now
                },
            )
            tenant.status = TenantStatus.ACTIVE.name
        }

        memberships.persist(
            Membership().apply {
                id = newId(); tenantId = tenant.id; userId = user.id; role = invite.role; status = "ACTIVE"; createdAt = now
            },
        )

        // 4. consume the invite, hand back a session
        invite.status = InviteStatus.CONSUMED.name
        invite.consumedBy = user.id

        return RegisterViaInvitationResponse(
            sessionToken = sessions.issue(user.id),
            user = user.toSummary(),
            tenant = tenant.toSummary(),
        )
    }

    private fun User.toSummary(): UserSummary =
        UserSummary(id = id, email = email, displayName = displayName, serverRole = ApiServerRole.decode(serverRole.name))

    private fun Tenant.toSummary(): TenantSummary =
        TenantSummary(id = id, name = name, displayName = displayName, status = TenantSummary.Status.valueOf(status), domain = domain)

    private fun requireSuite(suiteId: String) {
        if (!suites.isKnown(suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite: $suiteId")
    }

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    private fun notFound(what: String) = ApiException(404, "NOT_FOUND", "$what not found")
    private fun newId(): String = UUID.randomUUID().toString()
}
