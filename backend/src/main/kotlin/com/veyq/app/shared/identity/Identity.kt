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

enum class ServerRole { ServerOwner, ServerMember }

@Entity
@Table(name = "veyq_user")
class User {
    @Id
    var id: String = ""

    @Column(nullable = false, unique = true)
    var email: String = ""

    @Column(name = "display_name", nullable = false)
    var displayName: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "server_role", nullable = false)
    var serverRole: ServerRole = ServerRole.ServerMember

    @Column(name = "system_account", nullable = false)
    var systemAccount: Boolean = false

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH
}

@ApplicationScoped
class UserRepository : PanacheRepositoryBase<User, String> {
    fun findByEmail(email: String): User? = find("email", email).firstResult()

    /** Batch lookup by id (one IN-query) — used by roster listings to avoid an N+1 per member. */
    fun listByIds(ids: Collection<String>): List<User> = if (ids.isEmpty()) emptyList() else list("id in ?1", ids)

    fun countServerOwners(): Long =
        count("serverRole = ?1 and systemAccount = false", ServerRole.ServerOwner)
}
