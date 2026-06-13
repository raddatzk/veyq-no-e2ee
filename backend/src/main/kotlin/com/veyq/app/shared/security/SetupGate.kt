package com.veyq.app.shared.security

import com.veyq.app.shared.identity.UserRepository
import jakarta.enterprise.context.ApplicationScoped

/** Setup is complete once a non-system Server Owner exists. */
@ApplicationScoped
class SetupGate(private val users: UserRepository) {
    fun isComplete(): Boolean = users.countServerOwners() > 0
}
