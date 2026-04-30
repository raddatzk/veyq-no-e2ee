package com.lunva.api.domain

import com.soundicly.jnanoidenhanced.jnanoid.NanoIdUtils
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.*
import java.time.Instant

enum class LibraryScopeType {
    Tenant,
    Group,
    User
}

@Entity
@Table(name = "lunva_tenant_folder")
class TenantFolder : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Column(name = "public_id", nullable = false, unique = true, length = 16, updatable = false)
    lateinit var publicId: String

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    lateinit var tenant: Tenant

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_folder_id")
    var parentFolder: TenantFolder? = null

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
    lateinit var name: String

    @Column(nullable = false)
    lateinit var displayName: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @OneToMany(mappedBy = "parentFolder", fetch = FetchType.LAZY, cascade = [CascadeType.REMOVE])
    var childFolders: MutableSet<TenantFolder> = mutableSetOf()

    @OneToMany(mappedBy = "parentFolder", fetch = FetchType.LAZY, cascade = [CascadeType.REMOVE])
    var albums: MutableSet<TenantAlbum> = mutableSetOf()

    @PrePersist
    fun beforePersist() {
        if (!::publicId.isInitialized) {
            publicId = NanoIdUtils.randomNanoId(16)
        }
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun beforeUpdate() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "lunva_tenant_album")
class TenantAlbum : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Column(name = "public_id", nullable = false, unique = true, length = 16, updatable = false)
    lateinit var publicId: String

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    lateinit var tenant: Tenant

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_folder_id")
    var parentFolder: TenantFolder? = null

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
    lateinit var name: String

    @Column(nullable = false)
    lateinit var displayName: String

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cover_media_id")
    var coverMedia: Media? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @OneToMany(mappedBy = "album", fetch = FetchType.LAZY, cascade = [CascadeType.REMOVE])
    var media: MutableSet<AlbumMedia> = mutableSetOf()

    @PrePersist
    fun beforePersist() {
        if (!::publicId.isInitialized) {
            publicId = NanoIdUtils.randomNanoId(16)
        }
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun beforeUpdate() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "lunva_album_media")
class AlbumMedia : PanacheEntityBase {
    @EmbeddedId
    lateinit var id: AlbumMediaId

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @MapsId("albumId")
    @JoinColumn(name = "album_id", nullable = false)
    lateinit var album: TenantAlbum

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @MapsId("mediaId")
    @JoinColumn(name = "media_id", nullable = false)
    lateinit var media: Media

    @Column(nullable = false)
    var position: Int = 0

    @Column(nullable = false)
    var hidden: Boolean = false

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @PrePersist
    fun beforePersist() {
        createdAt = Instant.now()
    }
}

@Embeddable
data class AlbumMediaId(
    @Column(name = "album_id")
    var albumId: Long = 0,
    @Column(name = "media_id")
    var mediaId: Long = 0,
) : java.io.Serializable

@ApplicationScoped
class TenantFolderRepo : PanacheRepository<TenantFolder> {
    fun listByTenant(tenant: Tenant): List<TenantFolder> = list("tenant", tenant)
    fun listChildren(tenant: Tenant, parent: TenantFolder?): List<TenantFolder> =
        if (parent == null) list("tenant = ?1 and parentFolder is null order by displayName", tenant)
        else list("tenant = ?1 and parentFolder = ?2 order by displayName", tenant, parent)
}

@ApplicationScoped
class TenantAlbumRepo : PanacheRepository<TenantAlbum> {
    fun listByTenant(tenant: Tenant): List<TenantAlbum> = list("tenant", tenant)
    fun findByPublicId(publicId: String): TenantAlbum? = find("publicId", publicId).firstResult()
    fun listChildren(tenant: Tenant, parent: TenantFolder?): List<TenantAlbum> =
        if (parent == null) list("tenant = ?1 and parentFolder is null order by displayName", tenant)
        else list("tenant = ?1 and parentFolder = ?2 order by displayName", tenant, parent)
}

@ApplicationScoped
class AlbumMediaRepo : PanacheRepository<AlbumMedia> {
    fun findByAlbumAndMedia(album: TenantAlbum, media: Media): AlbumMedia? =
        find("album = ?1 and media = ?2", album, media).firstResult()

    fun listVisibleByAlbum(album: TenantAlbum): List<AlbumMedia> =
        list("album = ?1 and hidden = false order by position, createdAt", album)
}
