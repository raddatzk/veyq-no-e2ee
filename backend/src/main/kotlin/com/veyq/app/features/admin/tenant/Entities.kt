package com.veyq.app.features.admin.tenant

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A tenant: a domain-scoped container resolved by Host. Operational metadata only — name, domain and the
 * intended owner email are cleartext; there is no content key here (the cold TENANT-scope birth that
 * activates a tenant is Slice B / cr-0010). PENDING until its owner accepts, then ACTIVE.
 */
@Entity
@Table(name = "veyq_tenant")
class TenantEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "name", nullable = false, length = 100)
    lateinit var name: String

    @Column(name = "domain", nullable = false, length = 253)
    lateinit var domain: String

    @Column(name = "owner_email", nullable = false, length = 254)
    lateinit var ownerEmail: String

    @Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    /** cr-0114 (ADR 0035): the operator's per-locale terms markdown, serialized as a locale→markdown JSON map
     *  (opaque TEXT). Null until the owner first publishes. Public operational content, never a key. */
    @Column(name = "terms_markdown_by_locale")
    var termsMarkdownByLocale: String? = null

    /** cr-0114: the terms version, bumped only on a **normalized** change (idempotent re-saves don't). 0 = no
     *  terms set (the member accept-gate never fires); a member whose accepted version is below this re-accepts. */
    @Column(name = "terms_version", nullable = false)
    var termsVersion: Int = 0

    companion object : PanacheCompanionBase<TenantEntity, String> {
        const val PENDING = "PENDING"
        const val ACTIVE = "ACTIVE"
    }
}

/**
 * A member's acceptance of a tenant's terms (cr-0114, ADR 0035). Keyed logically by (user_id, tenant_id) — a
 * surrogate id + a UNIQUE — it records the highest terms version the member accepted. **Coarse operational
 * metadata** (like membership), never content. When `acceptedVersion < tenant.termsVersion` the member accept
 * gate re-fires (uc.account.accept-terms); accepting upserts this row to the current version.
 */
@Entity
@Table(name = "veyq_tenant_terms_acceptance")
class TenantTermsAcceptanceEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "user_id", nullable = false, length = 36)
    lateinit var userId: String

    @Column(name = "tenant_id", nullable = false, length = 36)
    lateinit var tenantId: String

    @Column(name = "accepted_version", nullable = false)
    var acceptedVersion: Int = 0

    @Column(name = "accepted_at", nullable = false)
    lateinit var acceptedAt: Instant

    companion object : PanacheCompanionBase<TenantTermsAcceptanceEntity, String>
}

/**
 * An owner invite (one tenant has a history of them). Only the SHA-256 token hash is stored — never the
 * token (ADR 0004 model). status: ACTIVE → SUPERSEDED on resend/reassign, → CONSUMED on acceptance
 * (Slice B), → EXPIRED past its TTL. Soft transitions; a scheduled purge removes terminal rows later.
 */
@Entity
@Table(name = "veyq_tenant_invite")
class TenantInviteEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "tenant_id", nullable = false, length = 36)
    lateinit var tenantId: String

    @Column(name = "owner_email", nullable = false, length = 254)
    lateinit var ownerEmail: String

    @Column(name = "token_hash", nullable = false, length = 64)
    lateinit var tokenHash: String

    @Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    /** The role this invite grants on acceptance (cr-0077): OWNER cold-births the tenant, MEMBER joins it
     *  grant-less. Defaults OWNER so pre-cr-0077 rows + the owner-invite flows are unchanged. */
    @Column(name = "role", nullable = false, length = 16)
    var role: String = OWNER

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<TenantInviteEntity, String> {
        const val ACTIVE = "ACTIVE"
        const val SUPERSEDED = "SUPERSEDED"
        const val CONSUMED = "CONSUMED"
        const val EXPIRED = "EXPIRED"

        /** Invite roles (mirror MembershipEntity roles): the role the invite confers on acceptance. */
        const val OWNER = "OWNER"
        const val MEMBER = "MEMBER"

        /** The terminal states a scheduled purge may physically delete. */
        val TERMINAL = listOf(SUPERSEDED, CONSUMED, EXPIRED)
    }
}

/**
 * Membership — the (user, scope, role, grant) relation (ADR 0007). It references a `scopeId` (cr-0026); the
 * TENANT root scope is keyed `scopeId == tenantId`, a PERSONAL/GROUP scope gets its own id. `wrappedGrant` is
 * the scope key sealed to the member's identity public key — opaque ciphertext the server stores but never
 * opens. The first row is the owner's, born at tenant activation (cr-0010); members extend the relation
 * later via the invite-wrap, and each member births their own PERSONAL scope (en.spaces.personal-scope).
 */
@Entity
@Table(name = "veyq_membership")
class MembershipEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "user_id", nullable = false, length = 36)
    lateinit var userId: String

    @Column(name = "tenant_id", nullable = false, length = 36)
    lateinit var tenantId: String

    /** The scope this membership grants (cr-0026). For the TENANT root scope this equals `tenantId`. */
    @Column(name = "scope_id", nullable = false, length = 36)
    lateinit var scopeId: String

    @Column(name = "role", nullable = false, length = 16)
    lateinit var role: String

    /** The scope key sealed to this member's identity public key — opaque ciphertext the server never opens.
     *  **Nullable**: a *pending* member (cr-0076) holds a role but no grant yet until a key-holder delivers one
     *  (grant-delivery job); a grant-less membership is the "hollow" state ADR 0007 blesses. */
    @Column(name = "wrapped_grant")
    var wrappedGrant: String? = null

    /** The scope epoch this membership's `wrappedGrant` wraps (ADR 0024, cr-0088). `< scope.currentEpoch` means the
     *  grant is **stale** (a rotation hasn't re-granted this member yet) — grant-delivery re-grants them. */
    @Column(name = "granted_epoch", nullable = false)
    var grantedEpoch: Int = 0

    /** A **soft consent proposal** the target has not yet accepted (uc.spaces.consent-group-add, cr-0094, ADR 0014
     *  §5). While true this is a grant-less membership grant-delivery **skips** — `K_SC` is gated **before** the
     *  wrap — until the invitee accepts (clears the flag) or declines (deletes the row). Default false = an
     *  ordinary membership (the pre-consent behaviour). */
    @Column(name = "pending_consent", nullable = false)
    var pendingConsent: Boolean = false

    /** For a `pendingConsent` proposal (cr-0096, ADR 0027): the group's **derived name-key `K_name`** sealed to
     *  the invitee's identity public key (`asymWrap(inviteePub, K_name, "group-name-key")`) — opaque ciphertext the
     *  server stores verbatim. Handed to the invitee so they open the scope's **canonical** `encrypted_name` (not
     *  a private name copy) and see the group-consistent name. Null for an ordinary membership; cleared on accept. */
    @Column(name = "sealed_name_key")
    var sealedNameKey: String? = null

    /** For a `pendingConsent` proposal (cr-0094): the userId of the GroupOwner who proposed the add ("who added
     *  you"), resolved to their per-tenant username in the invitee's invitations view. Null for an ordinary
     *  membership; cleared on accept. */
    @Column(name = "added_by", length = 36)
    var addedBy: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<MembershipEntity, String> {
        const val OWNER = "OWNER"
        const val ADMIN = "ADMIN"
        const val MEMBER = "MEMBER"
    }
}
