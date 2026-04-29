package com.lunva.api.usermgmt

import com.lunva.api.auth.NotFoundException
import com.lunva.api.domain.User
import com.lunva.api.domain.UserRepo
import com.lunva.api.auth.filterchain.RequestContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class UserService @Inject constructor(
    private val userRepo: UserRepo,
    private val requestContext: RequestContext
) {

    fun me(): UserSummary {
        return toUserSummary(requestContext.user!!)
    }

    fun findUser(userId: Long): User {
        return userRepo.findById(userId)
            ?: throw NotFoundException("User $userId not found")
    }

    companion object {
        fun toUserSummary(user: User): UserSummary {
            return UserSummary(user.id, user.email, user.displayName, user.role)
        }
    }
}
