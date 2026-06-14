package com.veyq.app.features.account.acceptmemberinvite

import com.veyq.app.generated.model.AcceptMemberInviteRequest
import com.veyq.app.generated.model.MembershipSummary
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.keymgmt.ScopeGrant
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.InviteKind
import com.veyq.app.shared.tenancy.InviteStatus
import com.veyq.app.shared.tenancy.Membership
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.TenantInviteRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * uc.admin.invite-members-roles (existing-user accept) — an already-authenticated user accepts a
 * temp-wrap member invite onto their EXISTING account. Their client unwrapped the scope with the temp
 * key (from the URL fragment) and re-wrapped it to their own identity; the server just adds the grant +
 * membership on the existing scope and consumes the invite. No new account, no session issued. This is
 * the logged-in counterpart to register-via-invitation (which mints a fresh account).
 */
@ApplicationScoped
class AcceptMemberInviteHandler(
    private val invites: TenantInviteRepository,
    private val memberships: MembershipRepository,
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val sessions: SessionService,
    private val suites: CryptoSuiteRegistry,
) {
    private val dec = Base64.getUrlDecoder()

    @Transactional
    fun acceptMemberInvite(auth: String?, inviteId: String, req: AcceptMemberInviteRequest): MembershipSummary {
        val userId = sessions.resolve(auth) ?: throw unauthorized()
        val invite = invites.findByIdForUpdate(inviteId) ?: throw notFound("Invite")
        if (invite.status != InviteStatus.PENDING.name) throw ApiException(409, "INVITE_NOT_PENDING", "Invite is not claimable")
        if (invite.expiresAt.isBefore(Instant.now())) throw ApiException(409, "INVITE_EXPIRED", "Invite has expired")
        if (!MessageDigest.isEqual(sha256(dec.decode(req.bootstrapSecret)), invite.secretHash)) {
            throw ApiException(400, "BAD_BOOTSTRAP_SECRET", "Invalid bootstrap secret")
        }
        if (invite.kind != InviteKind.MEMBER.name) {
            throw ApiException(400, "NOT_A_MEMBER_INVITE", "Only member invites can be accepted onto an existing account")
        }
        requireSuite(req.wrappedScopePrivateKey.suiteId)

        val scope = invite.scopeId?.let { scopes.findById(it) } ?: throw notFound("Scope")
        if (grants.find(scope.id, userId) != null) {
            throw ApiException(409, "ALREADY_GRANTED", "You already hold this scope")
        }

        val now = Instant.now()
        grants.persist(
            ScopeGrant().apply {
                id = newId(); scopeId = scope.id; this.userId = userId
                suiteId = req.wrappedScopePrivateKey.suiteId
                wrappedPrivateKey = dec.decode(req.wrappedScopePrivateKey.bytes); createdAt = now
            },
        )
        // upsert membership — a previously-removed member rejoining flips back to ACTIVE
        val membership = memberships.find(invite.tenantId, userId) ?: Membership().apply {
            id = newId(); tenantId = invite.tenantId; this.userId = userId; createdAt = now
            memberships.persist(this)
        }
        membership.role = invite.role
        membership.status = "ACTIVE"

        invite.status = InviteStatus.CONSUMED.name
        invite.consumedBy = userId

        return MembershipSummary(tenantId = invite.tenantId, userId = userId, role = membership.role, status = membership.status)
    }

    private fun requireSuite(suiteId: String) {
        if (!suites.isKnown(suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite: $suiteId")
    }

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    private fun unauthorized() = ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
    private fun notFound(what: String) = ApiException(404, "NOT_FOUND", "$what not found")
    private fun newId(): String = UUID.randomUUID().toString()
}
