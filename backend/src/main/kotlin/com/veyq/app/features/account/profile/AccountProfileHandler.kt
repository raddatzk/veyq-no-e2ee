package com.veyq.app.features.account.profile

import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.shared.identity.TenantProfileStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/** Result of a profile read/update (cr-0083). */
sealed interface ProfileOutcome {
    data class Found(val username: String, val sealedName: String?) : ProfileOutcome
    data object NoTenant : ProfileOutcome        // no tenant for the Host, or no profile there → 404
    data object UsernameTaken : ProfileOutcome   // the new handle is taken by someone else in this tenant → 409
}

/**
 * The caller's per-tenant identity profile (username + sealed real name, ADR 0023) in the tenant resolved from
 * the Host. Operator-blind — the sealed name is ciphertext the server stores verbatim; only the plaintext
 * `username` is read (to enforce per-tenant uniqueness). Auth is central (`@Authenticated` on the resource).
 */
@ApplicationScoped
class AccountProfileHandler(private val profiles: TenantProfileStore) {

    fun get(userId: String, host: String?): ProfileOutcome {
        val tenantId = host?.let(::tenantIdByDomain) ?: return ProfileOutcome.NoTenant
        val profile = profiles.find(userId, tenantId) ?: return ProfileOutcome.NoTenant
        return ProfileOutcome.Found(profile.username, profile.sealedName)
    }

    @Transactional
    fun update(userId: String, host: String?, username: String?, sealedName: String?): ProfileOutcome {
        val tenantId = host?.let(::tenantIdByDomain) ?: return ProfileOutcome.NoTenant
        val existing = profiles.find(userId, tenantId) ?: return ProfileOutcome.NoTenant
        // A username change to a handle another member already holds is rejected; keeping (or re-setting) your
        // own is fine.
        if (username != null) {
            val norm = username.trim().lowercase()
            if (norm != existing.username && profiles.usernameTaken(tenantId, norm)) return ProfileOutcome.UsernameTaken
        }
        profiles.update(userId, tenantId, username, sealedName)
        val updated = profiles.find(userId, tenantId)!!
        return ProfileOutcome.Found(updated.username, updated.sealedName)
    }

    private fun tenantIdByDomain(host: String): String? =
        TenantEntity.find("domain", host.lowercase()).firstResult()?.id
}
