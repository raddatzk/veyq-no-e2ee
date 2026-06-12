package com.veyq.api.domain

import com.soundicly.jnanoidenhanced.jnanoid.NanoIdUtils
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.*
import java.time.Instant

enum class MemoryPlaylistProvider {
    SPOTIFY,
    APPLE_MUSIC,
    YOUTUBE,
    SOUNDCLOUD,
    DEEZER,
}

@Entity
@Table(name = "veyq_memory_playlist")
class MemoryPlaylist : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Column(name = "public_id", nullable = false, unique = true, length = 16, updatable = false)
    lateinit var publicId: String

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    lateinit var tenant: Tenant

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "memory_id", nullable = false)
    lateinit var memory: Memory

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false)
    lateinit var scopeType: LibraryScopeType

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scope_group_id")
    var scopeGroup: TenantGroup? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scope_user_id")
    var scopeUser: User? = null

    @Column(nullable = false, length = 160)
    lateinit var title: String

    @Column(length = 1000)
    var note: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    lateinit var provider: MemoryPlaylistProvider

    @Column(nullable = false, length = 1000)
    lateinit var url: String

    @Column(name = "external_id", nullable = false)
    lateinit var externalId: String

    @Column(nullable = false)
    var featured: Boolean = false

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    var createdByUser: User? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_share_identity_id")
    var createdByShareIdentity: MemoryShareIdentity? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @PrePersist
    fun beforePersist() {
        if (!::publicId.isInitialized) {
            publicId = NanoIdUtils.randomNanoId(16)
        }
        val now = Instant.now()
        if (!::createdAt.isInitialized) createdAt = now
        if (!::updatedAt.isInitialized) updatedAt = now
    }

    @PreUpdate
    fun beforeUpdate() {
        updatedAt = Instant.now()
    }
}

@ApplicationScoped
class MemoryPlaylistRepo : PanacheRepository<MemoryPlaylist> {
    fun findByTenantAndPublicId(tenant: Tenant, publicId: String): MemoryPlaylist? =
        find("tenant = ?1 and publicId = ?2", tenant, publicId).firstResult()

    fun listByMemory(memory: Memory): List<MemoryPlaylist> =
        list("memory = ?1 order by featured desc, createdAt, id", memory)

    fun clearFeaturedByMemory(memory: Memory, exceptPlaylist: MemoryPlaylist? = null) {
        if (exceptPlaylist == null) {
            update("featured = false where memory = ?1 and featured = true", memory)
        } else {
            update("featured = false where memory = ?1 and id <> ?2 and featured = true", memory, exceptPlaylist.id)
        }
    }
}
