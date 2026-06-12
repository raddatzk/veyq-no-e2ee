package com.veyq.api.settings

import com.veyq.api.domain.User
import com.veyq.api.domain.UserCapabilityAck
import com.veyq.api.domain.UserCapabilityAckRepo
import jakarta.inject.Singleton

@Singleton
class CapabilityAcknowledgementService(
    private val capabilityAckRepo: UserCapabilityAckRepo,
    private val capabilityRegistry: CapabilityRegistry,
) {
    fun acknowledgeCurrentAnnouncements(user: User) {
        val acknowledged = capabilityAckRepo.listCapabilityIds(user)
        capabilityRegistry.notificationCandidatesFor(user)
            .filterNot { it.id in acknowledged }
            .forEach { capability ->
                capabilityAckRepo.persist(UserCapabilityAck().apply {
                    this.user = user
                    this.capabilityId = capability.id
                })
            }
    }
}
