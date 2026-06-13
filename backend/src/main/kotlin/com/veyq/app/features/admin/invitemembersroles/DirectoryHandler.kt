package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.generated.model.DirectoryEntry
import com.veyq.app.generated.model.IdentityPublicKey
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.IdentityKeyRepository
import com.veyq.app.shared.security.SessionService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.Base64

/**
 * uc.admin.invite-members-roles (key directory) — resolve a user's published identity public key by
 * email so a grantor can wrap a scope key to them. Returns only public material; trust is TOFU/pinning
 * now, Key Transparency later (AD-07).
 */
@ApplicationScoped
class DirectoryHandler(
    private val users: UserRepository,
    private val identityKeys: IdentityKeyRepository,
    private val sessions: SessionService,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()

    @Transactional
    fun lookupDirectoryEntry(auth: String?, email: String): DirectoryEntry {
        sessions.resolve(auth) ?: throw unauthorized()
        val user = users.findByEmail(email) ?: throw notFound("User")
        val identity = identityKeys.findByUserId(user.id) ?: throw notFound("Identity key")
        return DirectoryEntry(
            userId = user.id,
            displayName = user.displayName,
            identityPublicKey = IdentityPublicKey(
                suiteId = identity.suiteId,
                kemPublicKey = enc.encodeToString(identity.kemPublicKey),
                signPublicKey = enc.encodeToString(identity.signPublicKey),
            ),
        )
    }

    private fun unauthorized() = ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
    private fun notFound(what: String) = ApiException(404, "NOT_FOUND", "$what not found")
}
