package com.veyq.api.domain

import com.soundicly.jnanoidenhanced.jnanoid.NanoIdUtils
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "veyq_media")
class Media : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Column(name = "public_id", nullable = false, unique = true, length = 16, updatable = false)
    lateinit var publicId: String

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    lateinit var tenant: Tenant

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id", nullable = false)
    lateinit var owner: User

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false)
    lateinit var scopeType: LibraryScopeType

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scope_group_id")
    var scopeGroup: TenantGroup? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scope_user_id")
    var scopeUser: User? = null

    @Column(nullable = false)
    lateinit var title: String

    @Column(name = "mime_type", nullable = false)
    lateinit var mimeType: String

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Long = 0

    @Column(name = "storage_key", nullable = false, length = 512)
    lateinit var storageKey: String

    @Column(name = "original_filename")
    var originalFilename: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "captured_at")
    var capturedAt: Instant? = null

    @Column(name = "captured_at_override", nullable = false)
    var capturedAtOverride: Boolean = false

    @Column(name = "camera_make")
    var cameraMake: String? = null

    @Column(name = "camera_model")
    var cameraModel: String? = null

    @Column(name = "lens_model")
    var lensModel: String? = null

    @Column(name = "image_width")
    var imageWidth: Int? = null

    @Column(name = "image_height")
    var imageHeight: Int? = null

    @Column(name = "gps_latitude")
    var gpsLatitude: Double? = null

    @Column(name = "gps_longitude")
    var gpsLongitude: Double? = null

    @PrePersist
    fun assignPublicId() {
        if (!::publicId.isInitialized) {
            publicId = NanoIdUtils.randomNanoId(16)
        }
        if (!::createdAt.isInitialized) {
            createdAt = Instant.now()
        }
    }
}

@ApplicationScoped
class MediaRepo : PanacheRepository<Media> {
    fun findByTenantAndPublicId(tenant: Tenant, publicId: String): Media? =
        find("tenant = ?1 and publicId = ?2", tenant, publicId).firstResult()
}
