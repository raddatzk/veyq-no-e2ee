package com.lunva.api.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "lunva_user")
class User : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Column(nullable = false, unique = true)
    lateinit var username: String

    @Column(name = "password_hash", nullable = false)
    lateinit var passwordHash: String

    @Column(name = "display_name", nullable = false)
    lateinit var displayName: String

    @Column(name = "email", nullable = false)
    lateinit var email: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "lunva_user_role",
        joinColumns = [JoinColumn(name = "user_id")],
        inverseJoinColumns = [JoinColumn(name = "role_id")]
    )
    var roles: MutableSet<Role> = mutableSetOf()
}

@ApplicationScoped
class UserRepo : PanacheRepository<User> {
    fun findByUsername(username: String): User? {
        return find("username", username).firstResult()
    }
}
