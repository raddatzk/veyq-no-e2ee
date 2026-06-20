package com.veyq.app.features.admin.serverusers

import com.veyq.app.generated.model.ServerUserList
import com.veyq.app.generated.model.SetServerRoleRequest
import com.veyq.app.generated.model.UserSummary
import com.veyq.app.generated.model.ServerRole as ApiServerRole
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.security.SessionService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * uc.admin.server-users — the instance roster + server-role assignment for the Server Owner. Operator-blind:
 * only low-sensitivity account fields (id/email/displayName/serverRole) are read; never keys or content.
 * Server role is set at account creation (Owner at setup, Member via invites) and was previously immutable —
 * this slice lets an Owner promote/demote, with a guard that the instance always keeps ≥1 Server Owner.
 */
@ApplicationScoped
class ServerAdminHandler(
    private val sessions: SessionService,
    private val users: UserRepository,
) {
    fun listUsers(auth: String?): ServerUserList {
        requireServerOwner(auth)
        val roster = users.listAll().filter { !it.systemAccount }.sortedBy { it.displayName.lowercase() }
        return ServerUserList(users = roster.map { it.toSummary() })
    }

    @Transactional
    fun setServerRole(auth: String?, userId: String, req: SetServerRoleRequest): UserSummary {
        requireServerOwner(auth)
        val target = users.findById(userId) ?: throw ApiException(404, "USER_NOT_FOUND", "User not found")
        if (target.systemAccount) throw ApiException(403, "FORBIDDEN", "System accounts cannot be modified")
        val newRole = ServerRole.valueOf(req.serverRole.value)
        // The instance must keep at least one Server Owner — refuse to demote the last one.
        if (target.serverRole == ServerRole.ServerOwner && newRole != ServerRole.ServerOwner && users.countServerOwners() <= 1) {
            throw ApiException(409, "LAST_SERVER_OWNER", "Cannot demote the last Server Owner")
        }
        target.serverRole = newRole
        return target.toSummary()
    }

    private fun requireUser(auth: String?): User {
        val userId = sessions.resolve(auth) ?: throw ApiException(401, "UNAUTHORIZED", "Authentication required")
        return users.findById(userId) ?: throw ApiException(401, "UNAUTHORIZED", "Authentication required")
    }

    private fun requireServerOwner(auth: String?): User {
        val user = requireUser(auth)
        if (user.serverRole != ServerRole.ServerOwner) throw ApiException(403, "FORBIDDEN", "Server Owner role required")
        return user
    }

    private fun User.toSummary(): UserSummary =
        UserSummary(id = id, email = email, displayName = displayName, serverRole = ApiServerRole.decode(serverRole.name))
}
