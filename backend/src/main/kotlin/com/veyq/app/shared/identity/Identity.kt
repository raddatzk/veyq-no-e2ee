package com.veyq.app.shared.identity

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.text.Normalizer
import java.util.Locale
import com.veyq.app.generated.model.UserSummary
import com.veyq.app.generated.model.ServerRole as ApiServerRole

enum class ServerRole { ServerOwner, ServerMember }

@Entity
@Table(name = "veyq_user")
class User {
    @Id
    var id: String = ""

    @Column(nullable = false, unique = true)
    var email: String = ""

    @Column(name = "username", unique = true, length = 80)
    var username: String? = null

    @Column(name = "display_name", nullable = false)
    var displayName: String = ""

    @Column(name = "avatar_object_key", length = 256)
    var avatarObjectKey: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "server_role", nullable = false)
    var serverRole: ServerRole = ServerRole.ServerMember

    @Column(name = "system_account", nullable = false)
    var systemAccount: Boolean = false

    @Column(name = "auth_verifier_kind")
    var authVerifierKind: String? = null

    @Column(name = "auth_verifier_suite_id")
    var authVerifierSuiteId: String? = null

    @Column(name = "auth_verifier")
    var authVerifier: String? = null

    @Column(name = "auth_verifier_created_at")
    var authVerifierCreatedAt: Instant? = null

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH
}

@ApplicationScoped
class UserRepository : PanacheRepositoryBase<User, String> {
    fun findByEmail(email: String): User? =
        find("email = ?1 and deletedAt is null", email).firstResult()

    fun findByUsername(username: String): User? =
        find("username = ?1 and deletedAt is null", username).firstResult()

    /** Batch lookup by id (one IN-query) — used by roster listings to avoid an N+1 per member. */
    fun listByIds(ids: Collection<String>): List<User> = if (ids.isEmpty()) emptyList() else list("id in ?1", ids)

    fun countServerOwners(): Long =
        count("serverRole = ?1 and systemAccount = false and deletedAt is null", ServerRole.ServerOwner)
}

fun User.toUserSummary(avatarUrl: String? = null): UserSummary =
    UserSummary(
        id = id,
        email = email,
        displayName = displayName,
        serverRole = ApiServerRole.decode(serverRole.name),
        username = effectiveUsername(),
        avatarUrl = avatarUrl,
    )

fun User.effectiveUsername(): String = username ?: Usernames.fallback(displayName, email, id)

object Usernames {
    private val duplicateDash = Regex("-+")
    private val invalid = Regex("[^a-z0-9-]")

    fun fallback(displayName: String, email: String, id: String): String {
        val seed = displayName.ifBlank { email.substringBefore('@') }.ifBlank { id }
        return normalize(seed).ifBlank { "user-${id.take(8).lowercase(Locale.ROOT)}" }
    }

    fun normalize(raw: String): String {
        val ascii = Normalizer.normalize(raw.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\s+"), "-")
        return invalid.replace(ascii, "-")
            .replace(duplicateDash, "-")
            .trim('-')
            .take(80)
            .trim('-')
    }
}
