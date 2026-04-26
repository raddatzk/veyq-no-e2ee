package com.lunva.api.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.*

@Entity
@Table(name = "lunva_role")
class Role : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "role_name", nullable = false, unique = true)
    lateinit var roleName: String

    @ManyToMany(mappedBy = "roles", fetch = FetchType.LAZY)
    var users: MutableSet<User> = mutableSetOf()
}

@ApplicationScoped
class RoleRepo : PanacheRepository<Role> {
    fun findByRoleName(roleName: String): Role? =
        find("roleName", roleName).firstResult()

    fun findUsersByRoleName(roleName: String): List<User> {
        val role = findByRoleName(roleName) ?: return emptyList()
        return role.users.toList()
    }
}
