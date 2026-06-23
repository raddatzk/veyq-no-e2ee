package com.veyq.app.features.account.login

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.ChangePasswordRequest
import com.veyq.app.generated.model.DeleteAccountRequest
import com.veyq.app.generated.model.IdentityPublicKey
import com.veyq.app.generated.model.KeyBag
import com.veyq.app.generated.model.KemPublicKey
import com.veyq.app.generated.model.LoginRequest
import com.veyq.app.generated.model.LoginResponse
import com.veyq.app.generated.model.MasterKeyWrap
import com.veyq.app.generated.model.PersonalScopeGrant
import com.veyq.app.generated.model.UpdateProfileRequest
import com.veyq.app.generated.model.UpdateAvatarRequest
import com.veyq.app.generated.model.UserProfileSummary
import com.veyq.app.generated.model.UserSummary
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.crypto.Argon2Verifier
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.content.ContentPurge
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.identity.ProfilePermalinks
import com.veyq.app.shared.keymgmt.AuthVerifierRepository
import com.veyq.app.shared.keymgmt.IdentityKeyRepository
import com.veyq.app.shared.keymgmt.KeyBlob
import com.veyq.app.shared.keymgmt.KeyBlobRepository
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.storage.BlobStore
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.MembershipStatus
import com.veyq.app.shared.tenancy.Tenant
import com.veyq.app.shared.tenancy.TenantRepository
import com.veyq.app.shared.security.IssuedSession
import com.veyq.app.shared.security.SessionService
import com.veyq.app.features.account.emailchange.EmailChangeRepository
import com.veyq.app.features.account.resetpassword.PasswordResetRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.Base64
import java.util.UUID
import com.veyq.app.platform.error.unauthorized
import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.effectivePermalink
import com.veyq.app.shared.identity.toUserSummary

data class LoginResult(val response: LoginResponse, val issuedSession: IssuedSession)
data class AvatarBytes(val contentType: String, val bytes: ByteArray)

/**
 * uc.account.login — authenticate, issue a session, and serve the wrapped key bag.
 * The server verifies a password proof (Argon2id) and returns ciphertext only; unlock is client-side.
 */
@ApplicationScoped
class LoginHandler(
    private val users: UserRepository,
    private val identityKeys: IdentityKeyRepository,
    private val keyBlobs: KeyBlobRepository,
    private val authVerifiers: AuthVerifierRepository,
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val argon2: Argon2Verifier,
    private val sessions: SessionService,
    private val suites: CryptoSuiteRegistry,
    private val store: BlobStore,
    private val purge: ContentPurge,
    private val passwordResetTokens: PasswordResetRepository,
    private val emailChangeTokens: EmailChangeRepository,
    private val tenants: TenantRepository,
    private val memberships: MembershipRepository,
) {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    // a fixed dummy verifier (salt||proof) so an unknown email still runs Argon2 — no enumeration oracle
    private val dummyVerifier = ByteArray(48)

    @Transactional
    fun login(req: LoginRequest): LoginResult {
        val user = users.findByEmail(req.email)
        val verifier = user?.let { authVerifiers.findByUserId(it.id) }
        if (user == null || verifier == null || verifier.kind != "ARGON2_PROOF") {
            argon2.verifyPassword(req.password, dummyVerifier) // equalize timing vs the real path
            throw invalidCredentials()
        }
        if (!argon2.verifyPassword(req.password, verifier.verifier)) {
            throw invalidCredentials()
        }

        val identity = identityKeys.findByUserId(user.id)
            ?: throw ApiException(500, "MISSING_KEY_BAG", "No identity key on file for this user")
        val keyBag = KeyBag(
            identityPublicKey = IdentityPublicKey(
                suiteId = identity.suiteId,
                kemPublicKey = b64.encodeToString(identity.kemPublicKey),
                signPublicKey = b64.encodeToString(identity.signPublicKey),
            ),
            wrappedIdentityPrivateKey = Blob(
                suiteId = identity.wrappedPrivateSuiteId,
                bytes = b64.encodeToString(identity.wrappedPrivateKey),
            ),
            // RECOVERY wraps are deliberately EXCLUDED: they are the Secret-Key-only master-key wrap, released
            // only via the email-token-gated password reset. Shipping them in the login bag would let anyone
            // with the Secret Key alone unlock (bypassing the email second factor that gates recovery).
            masterKeyWraps = keyBlobs.listByUserId(user.id).filter { it.unlockMethod != "RECOVERY" }.map(::toWrap),
        )

        val issued = sessions.issuePair(user.id)
        return LoginResult(
            response = LoginResponse(
                sessionToken = issued.accessToken,
                user = user.toUserSummary(),
                keyBag = keyBag,
            ),
            issuedSession = issued,
        )
    }

    @Transactional
    fun currentUser(authorization: String?): UserSummary {
        val userId = sessions.resolve(authorization) ?: throw unauthorized()
        val user = users.findById(userId) ?: throw unauthorized()
        return user.toUserSummary()
    }

    @Transactional
    fun updateProfile(authorization: String?, req: UpdateProfileRequest): UserSummary {
        val userId = sessions.resolve(authorization) ?: throw unauthorized()
        val user = users.findById(userId) ?: throw unauthorized()
        user.displayName = req.displayName.trim()
        val nextPermalink = requireProfilePermalink(req.permalink)
        val taken = users.findByPermalink(nextPermalink)
        if (taken != null && taken.id != user.id) {
            throw ApiException(409, "PERMALINK_TAKEN", "Profile address is already in use")
        }
        user.permalink = nextPermalink
        return user.toUserSummary()
    }

    @Transactional
    fun updateAvatar(authorization: String?, req: UpdateAvatarRequest): UserSummary {
        val userId = sessions.resolve(authorization) ?: throw unauthorized()
        val user = users.findById(userId) ?: throw unauthorized()
        if (req.bytes.isEmpty()) throw ApiException(400, "AVATAR_EMPTY", "Profile image is empty")
        if (req.bytes.size > maxAvatarBytes) throw ApiException(400, "AVATAR_TOO_LARGE", "Profile image is too large")
        val oldKey = user.avatarObjectKey
        val key = "profile-avatars/${user.id}/${UUID.randomUUID()}"
        store.put(key, req.bytes, req.contentType.value)
        user.avatarObjectKey = key
        user.avatarContentType = req.contentType.value
        oldKey?.let { runCatching { store.delete(it) } }
        return user.toUserSummary()
    }

    @Transactional
    fun deleteAvatar(authorization: String?): UserSummary {
        val userId = sessions.resolve(authorization) ?: throw unauthorized()
        val user = users.findById(userId) ?: throw unauthorized()
        val oldKey = user.avatarObjectKey
        user.avatarObjectKey = null
        user.avatarContentType = null
        oldKey?.let { runCatching { store.delete(it) } }
        return user.toUserSummary()
    }

    @Transactional
    fun currentAvatar(authorization: String?): AvatarBytes {
        val userId = sessions.resolve(authorization) ?: throw unauthorized()
        val user = users.findById(userId) ?: throw unauthorized()
        return avatarBytes(user)
    }

    @Transactional
    fun publicProfile(authorization: String?, tenantName: String, permalink: String): UserProfileSummary {
        val tenant = requireTenantVisible(authorization, tenantName)
        val user = users.findByPermalink(permalink) ?: throw ApiException(404, "PROFILE_NOT_FOUND", "Profile not found")
        requireProfileVisible(tenant.id, user.id)
        return UserProfileSummary(
            id = user.id,
            displayName = user.displayName,
            permalink = user.effectivePermalink(),
            initials = initials(user.displayName),
            avatarUrl = user.avatarObjectKey?.let { "/api/$tenantName/profiles/${user.effectivePermalink()}/avatar?v=${it.substringAfterLast('/')}" },
            memories = emptyList(),
        )
    }

    @Transactional
    fun publicAvatar(authorization: String?, tenantName: String, permalink: String): AvatarBytes {
        val tenant = requireTenantVisible(authorization, tenantName)
        val user = users.findByPermalink(permalink) ?: throw ApiException(404, "PROFILE_NOT_FOUND", "Profile not found")
        requireProfileVisible(tenant.id, user.id)
        return avatarBytes(user)
    }

    @Transactional
    fun changePassword(authorization: String?, req: ChangePasswordRequest) {
        val userId = sessions.resolve(authorization) ?: throw unauthorized()
        val user = users.findById(userId) ?: throw unauthorized()
        verifyPassword(user, req.currentPassword)
        val wrap = req.masterKeyWrap
        if (wrap.unlockMethod != MasterKeyWrap.UnlockMethod.PASSWORD) {
            throw ApiException(400, "EXPECTED_PASSWORD_WRAP", "Password change must submit a PASSWORD master-key wrap")
        }
        suites.require(wrap.suiteId)
        suites.require(req.authVerifier.suiteId)
        val passwordBlob = keyBlobs.findByUserIdAndUnlockMethod(user.id, "PASSWORD").firstOrNull()
            ?: KeyBlob().apply {
                id = UUID.randomUUID().toString(); this.userId = user.id; unlockMethod = "PASSWORD"; createdAt = Instant.now()
                keyBlobs.persist(this)
            }
        passwordBlob.suiteId = wrap.suiteId
        passwordBlob.wrappedKey = dec.decode(wrap.wrappedKey)
        passwordBlob.kdfParams = wrap.kdfParams?.let { dec.decode(it) }

        authVerifiers.findByUserId(user.id)?.apply {
            kind = req.authVerifier.kind.value
            suiteId = req.authVerifier.suiteId
            verifier = dec.decode(req.authVerifier.verifier)
        } ?: throw ApiException(400, "NO_VERIFIER", "Account has no auth verifier")
        sessions.invalidateAll(user.id)
    }

    @Transactional
    fun deleteAccount(authorization: String?, req: DeleteAccountRequest) {
        val userId = sessions.resolve(authorization) ?: throw unauthorized()
        val user = users.findById(userId) ?: throw unauthorized()
        verifyPassword(user, req.password)
        if (user.serverRole == ServerRole.ServerOwner && users.countServerOwners() <= 1) {
            throw ApiException(409, "LAST_SERVER_OWNER", "Cannot delete the last Server Owner")
        }

        scopes.listPersonalByOwnerUserId(user.id).forEach { purge.purgeScope(it.id) }
        passwordResetTokens.deleteByUserId(user.id)
        emailChangeTokens.deleteByUserId(user.id)
        sessions.invalidateAll(user.id)
        keyBlobs.deleteByUserId(user.id)
        authVerifiers.deleteByUserId(user.id)
        identityKeys.deleteByUserId(user.id)
        user.avatarObjectKey?.let { runCatching { store.delete(it) } }
        user.email = "deleted-${user.id}@deleted.veyq.local"
        user.permalink = null
        user.displayName = "Deleted user"
        user.avatarObjectKey = null
        user.avatarContentType = null
        user.serverRole = ServerRole.ServerMember
        user.systemAccount = true
        user.deletedAt = Instant.now()
    }

    /** Logout: drop the caller's access/refresh token family. */
    @Transactional
    fun logout(authorization: String?, refreshToken: String?) {
        if (!sessions.invalidate(authorization, refreshToken)) throw unauthorized()
    }

    @Transactional
    fun personalScope(authorization: String?): PersonalScopeGrant {
        val userId = sessions.resolve(authorization) ?: throw unauthorized()
        val scope = scopes.findPersonalByOwnerUserId(userId) ?: throw ApiException(404, "NOT_FOUND", "Personal scope not found")
        val grant = grants.find(scope.id, userId) ?: throw ApiException(404, "NOT_FOUND", "Personal scope grant not found")
        return PersonalScopeGrant(
            scopeId = scope.id,
            scopePublicKey = KemPublicKey(
                suiteId = scope.suiteId,
                kemPublicKey = b64.encodeToString(scope.kemPublicKey),
            ),
            wrappedScopePrivateKey = Blob(
                suiteId = grant.suiteId,
                bytes = b64.encodeToString(grant.wrappedPrivateKey),
            ),
        )
    }

    private fun toWrap(blob: KeyBlob): MasterKeyWrap =
        MasterKeyWrap(
            unlockMethod = MasterKeyWrap.UnlockMethod.valueOf(blob.unlockMethod),
            suiteId = blob.suiteId,
            wrappedKey = b64.encodeToString(blob.wrappedKey),
            kdfParams = blob.kdfParams?.let { b64.encodeToString(it) },
            credentialRef = blob.credentialRef,
        )

    private fun verifyPassword(user: User, password: String) {
        val verifier = authVerifiers.findByUserId(user.id) ?: throw invalidCredentials()
        if (verifier.kind != "ARGON2_PROOF" || !argon2.verifyPassword(password, verifier.verifier)) {
            throw invalidCredentials()
        }
    }

    private fun requireProfilePermalink(raw: String): String {
        val normalized = ProfilePermalinks.normalize(raw)
        if (normalized.length < 2) throw ApiException(400, "BAD_PERMALINK", "Profile address is too short")
        if (normalized != raw.trim().lowercase()) throw ApiException(400, "BAD_PERMALINK", "Profile address must be lowercase URL-safe text")
        return normalized
    }

    private fun requireTenantVisible(authorization: String?, tenantName: String): Tenant {
        val currentUserId = sessions.resolve(authorization) ?: throw unauthorized()
        val tenant = tenants.findByName(tenantName) ?: throw ApiException(404, "TENANT_NOT_FOUND", "Tenant not found")
        val membership = memberships.find(tenant.id, currentUserId)
            ?: throw ApiException(403, "FORBIDDEN", "Tenant membership required")
        if (membership.status != MembershipStatus.ACTIVE) {
            throw ApiException(403, "FORBIDDEN", "Tenant membership required")
        }
        return tenant
    }

    private fun requireProfileVisible(tenantId: String, userId: String) {
        val membership = memberships.find(tenantId, userId)
            ?: throw ApiException(404, "PROFILE_NOT_FOUND", "Profile not found")
        if (membership.status != MembershipStatus.ACTIVE) {
            throw ApiException(404, "PROFILE_NOT_FOUND", "Profile not found")
        }
    }

    private fun avatarBytes(user: User): AvatarBytes {
        val key = user.avatarObjectKey ?: throw ApiException(404, "AVATAR_NOT_FOUND", "Profile image not found")
        val bytes = store.get(key) ?: throw ApiException(404, "AVATAR_NOT_FOUND", "Profile image not found")
        return AvatarBytes(contentType = user.avatarContentType ?: "application/octet-stream", bytes = bytes)
    }

    private fun initials(displayName: String): String =
        displayName.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "?" }

    private fun invalidCredentials() = ApiException(401, "INVALID_CREDENTIALS", "Invalid email or password")

    private companion object {
        const val maxAvatarBytes = 700_000
    }
}
