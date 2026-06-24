package com.veyq.app.features.account.login

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.AvatarUploadTicket
import com.veyq.app.generated.model.ChangePasswordRequest
import com.veyq.app.generated.model.CompleteAvatarUploadRequest
import com.veyq.app.generated.model.DeleteAccountRequest
import com.veyq.app.generated.model.IdentityPublicKey
import com.veyq.app.generated.model.KeyBag
import com.veyq.app.generated.model.KemPublicKey
import com.veyq.app.generated.model.LoginRequest
import com.veyq.app.generated.model.LoginResponse
import com.veyq.app.generated.model.MasterKeyWrap
import com.veyq.app.generated.model.StartAvatarUploadRequest
import com.veyq.app.generated.model.UpdateProfileRequest
import com.veyq.app.generated.model.UserProfileSummary
import com.veyq.app.generated.model.UserSummary
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.crypto.Argon2Verifier
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.content.ContentPurge
import com.veyq.app.shared.identity.AvatarUrls
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.identity.Usernames
import com.veyq.app.shared.keymgmt.AuthVerifierRepository
import com.veyq.app.shared.keymgmt.IdentityKeyRepository
import com.veyq.app.shared.keymgmt.KeyBlob
import com.veyq.app.shared.keymgmt.KeyBlobRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.storage.BlobStore
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.MembershipStatus
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
import com.veyq.app.shared.identity.effectiveUsername
import com.veyq.app.shared.identity.toUserSummary

data class LoginResult(val response: LoginResponse, val issuedSession: IssuedSession)
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
    private val argon2: Argon2Verifier,
    private val sessions: SessionService,
    private val suites: CryptoSuiteRegistry,
    private val store: BlobStore,
    private val purge: ContentPurge,
    private val passwordResetTokens: PasswordResetRepository,
    private val emailChangeTokens: EmailChangeRepository,
    private val memberships: MembershipRepository,
    private val avatarUrls: AvatarUrls,
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

        val issued = sessions.issuePair(user.id, req.deviceRef)
        return LoginResult(
            response = LoginResponse(
                sessionToken = issued.accessToken,
                deviceRef = issued.deviceRef,
                user = avatarUrls.currentUserSummary(user),
                keyBag = keyBag,
            ),
            issuedSession = issued,
        )
    }

    @Transactional
    fun currentUser(authorization: String?): UserSummary {
        val userId = sessions.resolve(authorization) ?: throw unauthorized()
        val user = users.findById(userId) ?: throw unauthorized()
        return avatarUrls.currentUserSummary(user)
    }

    @Transactional
    fun updateProfile(authorization: String?, req: UpdateProfileRequest): UserSummary {
        val userId = sessions.resolve(authorization) ?: throw unauthorized()
        val user = users.findById(userId) ?: throw unauthorized()
        user.displayName = req.displayName.trim()
        val nextUsername = requireUsername(req.username)
        val taken = users.findByUsername(nextUsername)
        if (taken != null && taken.id != user.id) {
            throw ApiException(409, "USERNAME_TAKEN", "Username is already in use")
        }
        user.username = nextUsername
        return avatarUrls.currentUserSummary(user)
    }

    @Transactional
    fun startAvatarUpload(authorization: String?, req: StartAvatarUploadRequest): AvatarUploadTicket {
        val userId = sessions.resolve(authorization) ?: throw unauthorized()
        users.findById(userId) ?: throw unauthorized()
        if (req.byteSize <= 0) throw ApiException(400, "AVATAR_EMPTY", "Profile image is empty")
        if (req.byteSize > maxAvatarBytes) throw ApiException(400, "AVATAR_TOO_LARGE", "Profile image is too large")
        val key = "profile-avatars/$userId/${UUID.randomUUID()}"
        val uploadUrl = store.presignPut(key, avatarUploadTtlSeconds)
            ?: throw ApiException(409, "PRESIGN_UNAVAILABLE", "Avatar direct upload requires a presign-capable object store")
        return AvatarUploadTicket(uploadRef = key, uploadUrl = uploadUrl, expiresInSeconds = avatarUploadTtlSeconds)
    }

    @Transactional
    fun completeAvatarUpload(authorization: String?, req: CompleteAvatarUploadRequest): UserSummary {
        val userId = sessions.resolve(authorization) ?: throw unauthorized()
        val user = users.findById(userId) ?: throw unauthorized()
        val key = req.uploadRef.trim()
        val prefix = "profile-avatars/$userId/"
        val version = key.removePrefix(prefix)
        if (!key.startsWith(prefix) || version.isBlank() || "/" in version) {
            throw ApiException(400, "BAD_AVATAR_UPLOAD_REF", "Avatar upload reference is invalid")
        }
        val size = store.stat(key) ?: throw ApiException(404, "AVATAR_UPLOAD_NOT_FOUND", "Uploaded avatar object not found")
        if (size <= 0) {
            runCatching { store.delete(key) }
            throw ApiException(400, "AVATAR_EMPTY", "Profile image is empty")
        }
        if (size > maxAvatarBytes) {
            runCatching { store.delete(key) }
            throw ApiException(400, "AVATAR_TOO_LARGE", "Profile image is too large")
        }
        val oldKey = user.avatarObjectKey
        user.avatarObjectKey = key
        if (oldKey != null && oldKey != key) oldKey.let { runCatching { store.delete(it) } }
        return avatarUrls.currentUserSummary(user)
    }

    @Transactional
    fun deleteAvatar(authorization: String?): UserSummary {
        val userId = sessions.resolve(authorization) ?: throw unauthorized()
        val user = users.findById(userId) ?: throw unauthorized()
        val oldKey = user.avatarObjectKey
        user.avatarObjectKey = null
        oldKey?.let { runCatching { store.delete(it) } }
        return avatarUrls.currentUserSummary(user)
    }

    @Transactional
    fun publicProfile(authorization: String?, tenantId: String, username: String): UserProfileSummary {
        requireTenantVisible(authorization, tenantId)
        val user = users.findByUsername(username) ?: throw ApiException(404, "PROFILE_NOT_FOUND", "Profile not found")
        requireProfileVisible(tenantId, user.id)
        return UserProfileSummary(
            id = user.id,
            displayName = user.displayName,
            username = user.effectiveUsername(),
            initials = initials(user.displayName),
            avatarUrl = avatarUrls.profileAvatarUrl(user),
            memories = emptyList(),
        )
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
        suites.require("v1")
        val passwordBlob = keyBlobs.findByUserIdAndUnlockMethod(user.id, "PASSWORD").firstOrNull()
            ?: KeyBlob().apply {
                id = UUID.randomUUID().toString(); this.userId = user.id; unlockMethod = "PASSWORD"; createdAt = Instant.now()
                keyBlobs.persist(this)
            }
        passwordBlob.suiteId = wrap.suiteId
        passwordBlob.wrappedKey = dec.decode(wrap.wrappedKey)
        passwordBlob.kdfParams = wrap.kdfParams?.let { dec.decode(it) }

        authVerifiers.findByUserId(user.id)?.apply {
            kind = "ARGON2_PROOF"
            suiteId = "v1"
            verifier = argon2.createPasswordVerifier(req.newPassword)
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
        user.username = null
        user.displayName = "Deleted user"
        user.avatarObjectKey = null
        user.serverRole = ServerRole.ServerMember
        user.systemAccount = true
        user.deletedAt = Instant.now()
    }

    /** Logout: drop the caller's access/refresh token family. */
    @Transactional
    fun logout(authorization: String?, refreshToken: String?) {
        if (!sessions.invalidate(authorization, refreshToken)) throw unauthorized()
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

    private fun requireUsername(raw: String): String {
        val normalized = Usernames.normalize(raw)
        if (normalized.length < 2) throw ApiException(400, "BAD_USERNAME", "Username is too short")
        if (normalized != raw.trim().lowercase()) throw ApiException(400, "BAD_USERNAME", "Username must be lowercase URL-safe text")
        return normalized
    }

    private fun requireTenantVisible(authorization: String?, tenantId: String) {
        val currentUserId = sessions.resolve(authorization) ?: throw unauthorized()
        val membership = memberships.find(tenantId, currentUserId)
            ?: throw ApiException(403, "FORBIDDEN", "Tenant membership required")
        if (membership.status != MembershipStatus.ACTIVE) {
            throw ApiException(403, "FORBIDDEN", "Tenant membership required")
        }
    }

    private fun requireProfileVisible(tenantId: String, userId: String) {
        val membership = memberships.find(tenantId, userId)
            ?: throw ApiException(404, "PROFILE_NOT_FOUND", "Profile not found")
        if (membership.status != MembershipStatus.ACTIVE) {
            throw ApiException(404, "PROFILE_NOT_FOUND", "Profile not found")
        }
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
        const val avatarUploadTtlSeconds = 900
    }
}
