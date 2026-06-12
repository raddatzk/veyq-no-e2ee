package com.veyq.api.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.*
import java.time.Instant

enum class ServerUpdateStatus {
    COMPLETED,
    FAILED,
}

enum class ServerUpdateFailureStage {
    DOCTOR,
    DIGEST_CHECK,
    BACKUP,
    PULL,
    START,
    HEALTHCHECK,
    NOTIFY,
}

enum class ServerNotificationType {
    AUTO_UPDATE_FAILED,
}

@Entity
@Table(name = "veyq_server_update_event")
class ServerUpdateEvent : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    lateinit var status: ServerUpdateStatus

    @Column(name = "from_version")
    var fromVersion: String? = null

    @Column(name = "to_version")
    var toVersion: String? = null

    @Column(name = "image", nullable = false)
    lateinit var image: String

    @Column(name = "digest")
    var digest: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "stage")
    var stage: ServerUpdateFailureStage? = null

    @Column(name = "occurred_at", nullable = false)
    lateinit var occurredAt: Instant

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    lateinit var createdAt: Instant
}

@ApplicationScoped
class ServerUpdateEventRepo : PanacheRepository<ServerUpdateEvent> {
    fun listRecent(limit: Int = 50): List<ServerUpdateEvent> =
        find("order by occurredAt desc").page(0, limit).list()
}

@Entity
@Table(name = "veyq_server_notification")
class ServerNotification : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false)
    lateinit var notificationType: ServerNotificationType

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "update_event_id")
    var updateEvent: ServerUpdateEvent? = null

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    lateinit var createdAt: Instant
}

@ApplicationScoped
class ServerNotificationRepo : PanacheRepository<ServerNotification> {
    fun listActive(): List<ServerNotification> =
        find("order by createdAt desc").list()
}

@Entity
@Table(
    name = "veyq_user_capability_ack",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "capability_id"])],
)
class UserCapabilityAck : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    lateinit var user: User

    @Column(name = "capability_id", nullable = false)
    lateinit var capabilityId: String

    @Column(name = "dismissed_at", nullable = false, insertable = false, updatable = false)
    lateinit var dismissedAt: Instant
}

@ApplicationScoped
class UserCapabilityAckRepo : PanacheRepository<UserCapabilityAck> {
    fun listCapabilityIds(user: User): Set<String> =
        find("user", user).list().map { it.capabilityId }.toSet()

    fun findByUserAndCapability(user: User, capabilityId: String): UserCapabilityAck? =
        find("user = ?1 and capabilityId = ?2", user, capabilityId).firstResult()
}
