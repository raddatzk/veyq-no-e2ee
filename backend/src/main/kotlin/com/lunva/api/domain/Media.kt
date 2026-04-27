package com.lunva.api.domain

import com.soundicly.jnanoidenhanced.jnanoid.NanoIdUtils
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "lunva_media")
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

    @Column(nullable = false)
    lateinit var title: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @PrePersist
    fun assignPublicId() {
        if (!::publicId.isInitialized) {
            publicId = NanoIdUtils.randomNanoId(16)
        }
    }
}

@Entity
@Table(name = "lunva_media_user_share")
class MediaUserShare : PanacheEntityBase {
    @EmbeddedId
    lateinit var id: MediaUserShareId

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @MapsId("mediaId")
    @JoinColumn(name = "media_id", nullable = false)
    lateinit var media: Media

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id", nullable = false)
    lateinit var user: User

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant
}

@Embeddable
data class MediaUserShareId(
    @Column(name = "media_id")
    var mediaId: Long = 0,
    @Column(name = "user_id")
    var userId: Long = 0,
) : java.io.Serializable

@Entity
@Table(name = "lunva_media_group_share")
class MediaGroupShare : PanacheEntityBase {
    @EmbeddedId
    lateinit var id: MediaGroupShareId

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @MapsId("mediaId")
    @JoinColumn(name = "media_id", nullable = false)
    lateinit var media: Media

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @MapsId("groupId")
    @JoinColumn(name = "group_id", nullable = false)
    lateinit var group: TenantGroup

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant
}

@Embeddable
data class MediaGroupShareId(
    @Column(name = "media_id")
    var mediaId: Long = 0,
    @Column(name = "group_id")
    var groupId: Long = 0,
) : java.io.Serializable

@ApplicationScoped
class MediaRepo : PanacheRepository<Media> {
    fun listAccessible(tenant: Tenant, user: User): List<Media> =
        list(
            """
            tenant = ?1 and (
                owner = ?2
                or exists (
                    select 1 from MediaUserShare us
                    where us.media = this and us.user = ?2
                )
                or exists (
                    select 1 from MediaGroupShare gs, TenantGroupMember gm
                    where gs.media = this and gm.group = gs.group and gm.user = ?2
                )
            )
            order by createdAt desc
            """.trimIndent(),
            tenant,
            user,
        )

    fun findAccessible(tenant: Tenant, user: User, mediaId: Long): Media? =
        find(
            """
            id = ?3 and tenant = ?1 and (
                owner = ?2
                or exists (
                    select 1 from MediaUserShare us
                    where us.media = this and us.user = ?2
                )
                or exists (
                    select 1 from MediaGroupShare gs, TenantGroupMember gm
                    where gs.media = this and gm.group = gs.group and gm.user = ?2
                )
            )
            """.trimIndent(),
            tenant,
            user,
            mediaId,
        ).firstResult()

    fun findByTenantAndId(tenant: Tenant, mediaId: Long): Media? =
        find("tenant = ?1 and id = ?2", tenant, mediaId).firstResult()
}

@ApplicationScoped
class MediaUserShareRepo : PanacheRepository<MediaUserShare> {
    fun findByMediaAndUser(media: Media, user: User): MediaUserShare? =
        find("media = ?1 and user = ?2", media, user).firstResult()
}

@ApplicationScoped
class MediaGroupShareRepo : PanacheRepository<MediaGroupShare> {
    fun findByMediaAndGroup(media: Media, group: TenantGroup): MediaGroupShare? =
        find("media = ?1 and group = ?2", media, group).firstResult()
}
