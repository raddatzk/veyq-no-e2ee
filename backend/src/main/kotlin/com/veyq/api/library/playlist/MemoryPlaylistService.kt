package com.veyq.api.library.playlist

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.domain.LibraryScopeType
import com.veyq.api.domain.Memory
import com.veyq.api.domain.MemoryPlaylist
import com.veyq.api.domain.MemoryPlaylistProvider
import com.veyq.api.domain.MemoryPlaylistRepo
import com.veyq.api.domain.MemoryRepo
import com.veyq.api.domain.MemoryShareIdentity
import com.veyq.api.domain.TenantFolderRepo
import com.veyq.api.domain.TenantGroupMemberRepo
import com.veyq.api.domain.TenantGroupRepo
import com.veyq.api.domain.User
import com.veyq.api.generated.model.CreateMemoryPlaylistRequest
import com.veyq.api.generated.model.MemoryPlaylistSummary
import com.veyq.api.generated.model.UpdateMemoryPlaylistRequest
import com.veyq.api.library.LibraryScopeKey
import com.veyq.api.library.LibraryScopes
import com.veyq.api.library.LibraryService
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Singleton
class MemoryPlaylistService(
    private val playlistRepo: MemoryPlaylistRepo,
    private val memoryRepo: MemoryRepo,
    folderRepo: TenantFolderRepo,
    groupRepo: TenantGroupRepo,
    private val playlistGroupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext,
) : LibraryService(folderRepo, groupRepo, playlistGroupMemberRepo, requestContext) {
    fun listMemoryPlaylists(memoryId: String): List<MemoryPlaylistSummary> {
        val memory = resolveVisibleMemory(memoryId)
        return playlistRepo.listByMemory(memory).map { it.toSummary() }
    }

    fun listPublicShareMemoryPlaylists(memory: Memory): List<MemoryPlaylistSummary> =
        playlistRepo.listByMemory(memory).map { it.toSummary() }

    @Transactional
    fun createMemoryPlaylist(memoryId: String, request: CreateMemoryPlaylistRequest): MemoryPlaylistSummary {
        val memory = resolveVisibleMemory(memoryId)
        return createPlaylist(memory, request, user(), null).toSummary()
    }

    @Transactional
    fun createPublicShareMemoryPlaylist(
        memory: Memory,
        request: CreateMemoryPlaylistRequest,
        authenticatedUser: User?,
        shareIdentity: MemoryShareIdentity?,
    ): MemoryPlaylistSummary =
        createPlaylist(memory, request, authenticatedUser, shareIdentity).toSummary()

    @Transactional
    fun updateMemoryPlaylist(memoryId: String, playlistId: String, request: UpdateMemoryPlaylistRequest): MemoryPlaylistSummary {
        val memory = resolveVisibleMemory(memoryId)
        val playlist = resolvePlaylist(memory, playlistId)
        playlist.title = requiredTitle(request.title)
        playlist.note = optionalNote(request.note)
        request.url?.let { applyLink(playlist, MemoryPlaylistUrlParser.parse(it)) }
        applyFeatured(playlist, request.featured)
        return playlist.toSummary()
    }

    @Transactional
    fun updatePublicShareMemoryPlaylist(memory: Memory, playlistId: String, request: UpdateMemoryPlaylistRequest): MemoryPlaylistSummary {
        val playlist = resolvePlaylist(memory, playlistId)
        playlist.title = requiredTitle(request.title)
        playlist.note = optionalNote(request.note)
        request.url?.let { applyLink(playlist, MemoryPlaylistUrlParser.parse(it)) }
        applyFeatured(playlist, request.featured)
        return playlist.toSummary()
    }

    @Transactional
    fun deleteMemoryPlaylist(memoryId: String, playlistId: String) {
        val memory = resolveVisibleMemory(memoryId)
        deletePlaylist(resolvePlaylist(memory, playlistId))
    }

    @Transactional
    fun deletePublicShareMemoryPlaylist(memory: Memory, playlistId: String) {
        deletePlaylist(resolvePlaylist(memory, playlistId))
    }

    private fun createPlaylist(
        memory: Memory,
        request: CreateMemoryPlaylistRequest,
        authenticatedUser: User?,
        shareIdentity: MemoryShareIdentity?,
    ): MemoryPlaylist {
        val parsedLink = MemoryPlaylistUrlParser.parse(request.url)
        val playlist = MemoryPlaylist().also {
            it.tenant = memory.tenant
            it.memory = memory
            applyScope(it, LibraryScopes.scope(memory))
            it.title = request.title?.let(::requiredTitle) ?: defaultTitle(parsedLink.provider)
            it.note = optionalNote(request.note)
            applyLink(it, parsedLink)
            it.featured = request.featured ?: false
            it.createdByUser = authenticatedUser
            it.createdByShareIdentity = shareIdentity
            if (it.featured) playlistRepo.clearFeaturedByMemory(memory)
            playlistRepo.persist(it)
            playlistRepo.flush()
        }
        return playlist
    }

    private fun applyLink(playlist: MemoryPlaylist, parsedLink: ParsedPlaylistLink) {
        playlist.provider = parsedLink.provider
        playlist.url = parsedLink.url
        playlist.externalId = parsedLink.externalId
    }

    private fun deletePlaylist(playlist: MemoryPlaylist) {
        playlist.delete()
        playlistRepo.flush()
    }

    private fun applyFeatured(playlist: MemoryPlaylist, featured: Boolean?) {
        featured ?: return
        if (featured) playlistRepo.clearFeaturedByMemory(playlist.memory, playlist)
        playlist.featured = featured
    }

    private fun resolveVisibleMemory(publicId: String): Memory {
        val memory = memoryRepo.findByTenantAndPublicId(tenant(), publicId)
            ?: throw NotFoundException("Memory not found")
        if (!isMemoryVisible(memory)) throw NotFoundException("Memory not found")
        return memory
    }

    private fun resolvePlaylist(memory: Memory, playlistId: String): MemoryPlaylist {
        val playlist = playlistRepo.findByTenantAndPublicId(memory.tenant, playlistId)
            ?: throw NotFoundException("Playlist not found")
        if (playlist.memory.id != memory.id) throw NotFoundException("Playlist not found")
        return playlist
    }

    private fun applyScope(playlist: MemoryPlaylist, scope: LibraryScopeKey) {
        playlist.scopeType = when (scope) {
            LibraryScopeKey.Tenant -> LibraryScopeType.Tenant
            is LibraryScopeKey.Group -> LibraryScopeType.Group
            is LibraryScopeKey.User -> LibraryScopeType.User
        }
        playlist.scopeGroup = (scope as? LibraryScopeKey.Group)?.group
        playlist.scopeUser = (scope as? LibraryScopeKey.User)?.user
    }

    private fun isMemoryVisible(memory: Memory): Boolean =
        memory.tenant.id == tenant().id && when (val scope = memory.scope()) {
            LibraryScopeKey.Tenant -> true
            is LibraryScopeKey.Group -> scope.group.tenant.id == tenant().id && playlistGroupMemberRepo.findByGroupAndUser(scope.group, user()) != null
            is LibraryScopeKey.User -> scope.user.id == user().id
        }

    private fun requiredTitle(value: String?): String {
        val title = value?.trim()?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("title is required", "MEMORY_PLAYLIST_TITLE_REQUIRED")
        if (title.length > 160) throw BadRequestException("title is too long", "MEMORY_PLAYLIST_TITLE_TOO_LONG")
        return title
    }

    private fun optionalNote(value: String?): String? {
        val note = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (note.length > 1000) throw BadRequestException("note is too long", "MEMORY_PLAYLIST_NOTE_TOO_LONG")
        return note
    }

    private fun defaultTitle(provider: MemoryPlaylistProvider): String =
        when (provider) {
            MemoryPlaylistProvider.SPOTIFY -> "Spotify Playlist"
            MemoryPlaylistProvider.APPLE_MUSIC -> "Apple Music Playlist"
            MemoryPlaylistProvider.YOUTUBE -> "YouTube Playlist"
            MemoryPlaylistProvider.SOUNDCLOUD -> "SoundCloud Playlist"
            MemoryPlaylistProvider.DEEZER -> "Deezer Playlist"
        }

    private fun MemoryPlaylist.toSummary(): MemoryPlaylistSummary =
        MemoryPlaylistSummary(
            id = publicId,
            memoryId = memory.publicId,
            title = title,
            note = note,
            featured = featured,
            provider = provider.toApi(),
            url = url,
            externalId = externalId,
            createdAt = createdAt.toApiOffsetDateTime(),
            updatedAt = updatedAt.toApiOffsetDateTime(),
        )

    private fun MemoryPlaylistProvider.toApi(): com.veyq.api.generated.model.MemoryPlaylistProvider =
        com.veyq.api.generated.model.MemoryPlaylistProvider.valueOf(name)

    private fun Instant.toApiOffsetDateTime(): OffsetDateTime =
        OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}

internal data class ParsedPlaylistLink(
    val provider: MemoryPlaylistProvider,
    val url: String,
    val externalId: String,
)

internal object MemoryPlaylistUrlParser {
    fun parse(value: String?): ParsedPlaylistLink {
        val rawUrl = value?.trim()?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("url is required", "MEMORY_PLAYLIST_URL_REQUIRED")
        if (rawUrl.length > 1000) throw BadRequestException("url is too long", "MEMORY_PLAYLIST_URL_TOO_LONG")
        val uri = runCatching { URI(rawUrl) }.getOrNull()
            ?: throw BadRequestException("Unsupported playlist URL", "MEMORY_PLAYLIST_URL_UNSUPPORTED")
        val host = uri.host?.lowercase()?.removePrefix("www.")
        val pathSegments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }

        if (host == "open.spotify.com" && pathSegments.firstOrNull() == "playlist" && pathSegments.size >= 2) {
            val externalId = pathSegments[1].substringBefore("?").trim()
            if (!SPOTIFY_PLAYLIST_ID.matches(externalId)) {
                throw BadRequestException("Unsupported Spotify playlist URL", "MEMORY_PLAYLIST_URL_UNSUPPORTED")
            }
            return ParsedPlaylistLink(
                provider = MemoryPlaylistProvider.SPOTIFY,
                url = normalizedHttpsUrl(uri),
                externalId = externalId,
            )
        }

        if (host == "music.apple.com" && pathSegments.any { it == "playlist" }) {
            val externalId = pathSegments.lastOrNull()?.trim().orEmpty()
            if (externalId.isBlank()) {
                throw BadRequestException("Unsupported Apple Music playlist URL", "MEMORY_PLAYLIST_URL_UNSUPPORTED")
            }
            return ParsedPlaylistLink(
                provider = MemoryPlaylistProvider.APPLE_MUSIC,
                url = normalizedHttpsUrl(uri),
                externalId = externalId,
            )
        }

        if (host in YOUTUBE_HOSTS) {
            val externalId = query(uri)["list"]?.trim().orEmpty()
            if (!YOUTUBE_PLAYLIST_ID.matches(externalId)) {
                throw BadRequestException("Unsupported YouTube playlist URL", "MEMORY_PLAYLIST_URL_UNSUPPORTED")
            }
            return ParsedPlaylistLink(
                provider = MemoryPlaylistProvider.YOUTUBE,
                url = normalizedHttpsUrl(uri),
                externalId = externalId,
            )
        }

        if (host == "soundcloud.com" && pathSegments.size >= 3 && pathSegments[1] == "sets") {
            val normalizedUrl = normalizedHttpsUrl(uri)
            val externalId = pathSegments.take(3).joinToString("/").trim()
            return ParsedPlaylistLink(
                provider = MemoryPlaylistProvider.SOUNDCLOUD,
                url = normalizedUrl,
                externalId = externalId,
            )
        }

        if (host == "deezer.com" && pathSegments.contains("playlist")) {
            val playlistIndex = pathSegments.indexOf("playlist")
            val externalId = pathSegments.getOrNull(playlistIndex + 1)?.trim().orEmpty()
            if (!DEEZER_PLAYLIST_ID.matches(externalId)) {
                throw BadRequestException("Unsupported Deezer playlist URL", "MEMORY_PLAYLIST_URL_UNSUPPORTED")
            }
            return ParsedPlaylistLink(
                provider = MemoryPlaylistProvider.DEEZER,
                url = normalizedHttpsUrl(uri),
                externalId = externalId,
            )
        }

        throw BadRequestException("Unsupported playlist URL", "MEMORY_PLAYLIST_URL_UNSUPPORTED")
    }

    private fun normalizedHttpsUrl(uri: URI): String {
        if (uri.scheme?.lowercase() != "https") {
            throw BadRequestException("Playlist URL must use https", "MEMORY_PLAYLIST_URL_UNSUPPORTED")
        }
        if (uri.fragment != null) {
            throw BadRequestException("Playlist URL must not contain a fragment", "MEMORY_PLAYLIST_URL_UNSUPPORTED")
        }
        return uri.toASCIIString()
    }

    private fun query(uri: URI): Map<String, String> =
        uri.rawQuery.orEmpty()
            .split('&')
            .filter { it.isNotBlank() && it.contains('=') }
            .associate {
                val key = it.substringBefore('=')
                val value = URLDecoder.decode(it.substringAfter('='), StandardCharsets.UTF_8)
                key to value
            }

    private val SPOTIFY_PLAYLIST_ID = Regex("[A-Za-z0-9]{10,}")
    private val YOUTUBE_PLAYLIST_ID = Regex("[A-Za-z0-9_-]{10,}")
    private val DEEZER_PLAYLIST_ID = Regex("[0-9]+")
    private val YOUTUBE_HOSTS = setOf("youtube.com", "music.youtube.com", "m.youtube.com")
}
