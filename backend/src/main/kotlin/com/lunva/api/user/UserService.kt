package com.lunva.api.user

import com.lunva.api.auth.UnauthorizedException
import com.lunva.api.domain.User
import com.lunva.api.domain.UserRepo
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class UserService @Inject constructor(
    private val userRepo: UserRepo,
    private val securityIdentity: SecurityIdentity,
) {

    fun me(): User {
        return userRepo.findByUsername(securityIdentity.principal.name)
            ?: throw UnauthorizedException("Invalid username or password")
    }
}