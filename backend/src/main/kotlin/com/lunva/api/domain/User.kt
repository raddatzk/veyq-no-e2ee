package com.lunva.api.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.*
import java.time.Instant

enum class ServerRole {
    ServerOwner,
    ServerMember,
}

@Entity
@Table(name = "lunva_user")
class User : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Column(name = "password_hash", nullable = false)
    lateinit var passwordHash: String

    @Column(name = "display_name", nullable = false)
    lateinit var displayName: String

    @Column(name = "email", nullable = false, unique = true)
    lateinit var email: String

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    lateinit var role: ServerRole

    @Column(name = "password_set_at")
    var passwordSetAt: Instant? = null

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    var tenantMemberships: MutableSet<TenantMember> = mutableSetOf()
}

@ApplicationScoped
class UserRepo : PanacheRepository<User> {
    fun findByEmail(email: String): User? {
        return find("email", email).firstResult()
    }

    fun listAllOrderByEmail(): List<User> =
        list("order by email")

    fun countActiveByRole(role: ServerRole): Long =
        count("role = ?1 and passwordSetAt is not null", role)
}
