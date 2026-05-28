package com.veyq.api.settings

import com.veyq.api.auth.NotFoundException
import com.veyq.api.auth.UnauthorizedException
import com.veyq.api.domain.*
import com.veyq.api.generated.model.CapabilityNotification
import com.veyq.api.generated.model.CapabilityNotificationsResponse
import com.veyq.api.generated.model.ServerNotification as ApiServerNotification
import com.veyq.api.generated.model.ServerNotificationType as ApiServerNotificationType
import com.veyq.api.generated.model.ServerNotificationsResponse
import com.veyq.api.generated.model.ServerUpdateEvent as ApiServerUpdateEvent
import com.veyq.api.generated.model.ServerUpdateEventsResponse
import com.veyq.api.generated.model.UpdateFailureStage as ApiUpdateFailureStage
import com.veyq.api.generated.model.UpdateStatus as ApiUpdateStatus
import com.veyq.api.generated.model.UpdaterUpdateCompletedRequest
import com.veyq.api.generated.model.UpdaterUpdateFailedRequest
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Optional

@Singleton
class ServerOperationsService(
    private val updateEventRepo: ServerUpdateEventRepo,
    private val serverNotificationRepo: ServerNotificationRepo,
    private val capabilityAckRepo: UserCapabilityAckRepo,
    private val capabilityRegistry: CapabilityRegistry,
    @param:ConfigProperty(name = "veyq.updater.notify-token")
    private val updaterNotifyToken: Optional<String>,
) {
    fun listUpdateEvents(): ServerUpdateEventsResponse =
        ServerUpdateEventsResponse(updateEventRepo.listRecent().map { it.toApi() })

    fun listServerNotifications(): ServerNotificationsResponse =
        ServerNotificationsResponse(serverNotificationRepo.listActive().map { it.toApi() })

    @Transactional
    fun deleteServerNotification(notificationId: Long) {
        val notification = serverNotificationRepo.findById(notificationId)
            ?: throw NotFoundException("Server notification not found")
        serverNotificationRepo.delete(notification)
    }

    fun listCapabilityNotifications(user: User): CapabilityNotificationsResponse {
        val dismissed = capabilityAckRepo.listCapabilityIds(user)
        val notifications = capabilityRegistry.notificationCandidatesFor(user)
            .filterNot { it.id in dismissed }
            .map { CapabilityNotification(it) }
        return CapabilityNotificationsResponse(notifications)
    }

    @Transactional
    fun dismissCapabilityNotification(user: User, capabilityId: String) {
        val capability = capabilityRegistry.find(capabilityId)
            ?: throw NotFoundException("Capability not found")
        if (capabilityAckRepo.findByUserAndCapability(user, capability.id) != null) {
            return
        }
        val ack = UserCapabilityAck().apply {
            this.user = user
            this.capabilityId = capability.id
        }
        capabilityAckRepo.persist(ack)
    }

    @Transactional
    fun reportCompleted(authHeader: String?, request: UpdaterUpdateCompletedRequest) {
        requireUpdaterToken(authHeader)
        val event = ServerUpdateEvent().apply {
            status = ServerUpdateStatus.COMPLETED
            fromVersion = request.fromVersion
            toVersion = request.toVersion
            image = request.image
            digest = request.digest
            occurredAt = request.occurredAt.toInstant()
        }
        updateEventRepo.persist(event)
    }

    @Transactional
    fun reportFailed(authHeader: String?, request: UpdaterUpdateFailedRequest) {
        requireUpdaterToken(authHeader)
        val event = ServerUpdateEvent().apply {
            status = ServerUpdateStatus.FAILED
            fromVersion = request.fromVersion
            toVersion = request.toVersion
            image = request.image
            digest = request.digest
            stage = request.stage.toDomain()
            occurredAt = request.occurredAt.toInstant()
        }
        updateEventRepo.persist(event)
        serverNotificationRepo.persist(ServerNotification().apply {
            notificationType = ServerNotificationType.AUTO_UPDATE_FAILED
            updateEvent = event
        })
    }

    private fun requireUpdaterToken(authHeader: String?) {
        val expected = updaterNotifyToken.orElse("").trim()
        val actual = authHeader?.removePrefix("Bearer ")?.trim().orEmpty()
        if (expected.isBlank() || actual != expected) {
            throw UnauthorizedException("Updater notification token is invalid")
        }
    }

    private fun ServerUpdateEvent.toApi(): ApiServerUpdateEvent =
        ApiServerUpdateEvent(
            id = id ?: 0,
            status = status.toApi(),
            image = image,
            occurredAt = occurredAt.atOffset(ZoneOffset.UTC),
            fromVersion = fromVersion,
            toVersion = toVersion,
            digest = digest,
            stage = stage?.toApi(),
        )

    private fun ServerNotification.toApi(): ApiServerNotification =
        ApiServerNotification(
            id = id ?: 0,
            type = ApiServerNotificationType.AUTO_UPDATE_FAILED,
            createdAt = createdAt.atOffset(ZoneOffset.UTC),
            updateEvent = updateEvent?.toApi(),
        )

    private fun ServerUpdateStatus.toApi(): ApiUpdateStatus =
        when (this) {
            ServerUpdateStatus.COMPLETED -> ApiUpdateStatus.COMPLETED
            ServerUpdateStatus.FAILED -> ApiUpdateStatus.FAILED
        }

    private fun ServerUpdateFailureStage.toApi(): ApiUpdateFailureStage =
        ApiUpdateFailureStage.valueOf(name)

    private fun ApiUpdateFailureStage.toDomain(): ServerUpdateFailureStage =
        ServerUpdateFailureStage.valueOf(name)
}
