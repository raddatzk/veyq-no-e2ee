package com.veyq.api.settings

import com.veyq.api.domain.ServerRole
import com.veyq.api.domain.User
import com.veyq.api.generated.model.CapabilityAudience
import com.veyq.api.generated.model.ServerCapability
import jakarta.inject.Singleton

@Singleton
class CapabilityRegistry {
    fun listFor(user: User? = null): List<ServerCapability> =
        definitions.filter { definition ->
            definition.capability.audience == CapabilityAudience.ALL_USERS ||
                user?.role == ServerRole.ServerOwner
        }.map { it.capability }

    fun notificationCandidatesFor(user: User): List<ServerCapability> =
        definitions.filter { definition ->
            definition.announceAsNew &&
                (definition.capability.audience == CapabilityAudience.ALL_USERS ||
                    user.role == ServerRole.ServerOwner)
        }.map { it.capability }

    fun find(capabilityId: String): ServerCapability? =
        definitions.firstOrNull { it.capability.id == capabilityId }?.capability

    private val definitions = listOf(
        capability(
            id = "server.setup",
            audience = CapabilityAudience.SERVER_OWNER,
        ),
        capability(
            id = "auth.passkeys",
            audience = CapabilityAudience.ALL_USERS,
        ),
        capability(
            id = "spaces.tenant-group-personal",
            audience = CapabilityAudience.ALL_USERS,
        ),
        capability(
            id = "memory.core",
            audience = CapabilityAudience.ALL_USERS,
        ),
        capability(
            id = "memory.media",
            audience = CapabilityAudience.ALL_USERS,
        ),
        capability(
            id = "memory.media-resumable-upload",
            audience = CapabilityAudience.ALL_USERS,
        ),
        capability(
            id = "memory.media-zip-download",
            audience = CapabilityAudience.ALL_USERS,
        ),
        capability(
            id = "memory.journal-markdown",
            audience = CapabilityAudience.ALL_USERS,
        ),
        capability(
            id = "memory.maps",
            audience = CapabilityAudience.ALL_USERS,
        ),
        capability(
            id = "memory.tracks",
            audience = CapabilityAudience.ALL_USERS,
        ),
        capability(
            id = "memory.playlists",
            audience = CapabilityAudience.ALL_USERS,
        ),
        capability(
            id = "memory.public-sharing",
            audience = CapabilityAudience.ALL_USERS,
        ),
        capability(
            id = "memory.public-uploader-links",
            audience = CapabilityAudience.ALL_USERS,
        ),
        capability(
            id = "server.backup-upgrade",
            audience = CapabilityAudience.SERVER_OWNER,
        ),
        capability(
            id = "server.auto-updates",
            audience = CapabilityAudience.SERVER_OWNER,
        ),
    )

    private fun capability(
        id: String,
        audience: CapabilityAudience,
        introducedInVersion: String = BASELINE_VERSION,
        announceAsNew: Boolean = false,
    ): CapabilityDefinition =
        CapabilityDefinition(
            capability = ServerCapability(
                id = id,
                introducedInVersion = introducedInVersion,
                audience = audience,
            ),
            announceAsNew = announceAsNew,
        )

    private data class CapabilityDefinition(
        val capability: ServerCapability,
        val announceAsNew: Boolean,
    )

    companion object {
        private const val BASELINE_VERSION = "1.0"
    }
}
