package com.veyq.api.memory

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ForbiddenException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.auth.PasswordService
import com.veyq.api.auth.UnauthorizedException
import com.veyq.api.auth.cookie.CookieConfig
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.auth.token.TokenConfig
import com.veyq.api.common.HashingService
import com.veyq.api.common.NoticeMarkdown
import com.veyq.api.common.UpdateMemoryShareRequestReader
import com.veyq.api.domain.*
import com.veyq.api.generated.model.CreateMemoryShareRequest
import com.veyq.api.generated.model.CreateMemoryMediaUploadRequest
import com.veyq.api.generated.model.CreatePublicShareMemoryRequest
import com.veyq.api.generated.model.MemoryMediaUploadSession
import com.veyq.api.generated.model.MemoryShareSummary
import com.veyq.api.generated.model.PendingMemoryMediaUploadSession
import com.veyq.api.generated.model.PublicShareCoverCrop
import com.veyq.api.generated.model.PublicShareLoginRequest
import com.veyq.api.generated.model.PublicShareMemoryHeaderSummary
import com.veyq.api.generated.model.PublicShareMemorySummary
import com.veyq.api.generated.model.PublicShareNotice
import com.veyq.api.generated.model.PublicShareNoticeScope
import com.veyq.api.generated.model.PublicShareSummary
import com.veyq.api.generated.model.PublicShareTreeResponse
import com.veyq.api.generated.model.UpdateMemoryJournalRequest
import com.veyq.api.generated.model.UpdateMemoryMediaMetadataRequest
import com.veyq.api.generated.model.UpdateMemoryShareRequest
import com.veyq.api.generated.model.UpdatePublicShareMemoryRequest
import com.veyq.api.library.LibraryScopeKey
import com.veyq.api.library.LibraryScopes
import com.veyq.api.library.media.MediaConfig
import com.veyq.api.library.media.MediaService
import com.veyq.api.library.playlist.MemoryPlaylistService
import com.veyq.api.library.track.MemoryTrackService
import com.veyq.api.security.RateLimitService
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.ws.rs.core.NewCookie
import jakarta.ws.rs.core.Response
import java.nio.file.Path
import java.nio.file.Files
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.Comparator

@ApplicationScoped
class MemoryShareService @Inject constructor(
    private val shareRepo: MemoryShareRepo,
    private val shareIdentityRepo: MemoryShareIdentityRepo,
    private val shareAccessTokenRepo: MemoryShareAccessTokenRepo,
    private val shareRefreshTokenRepo: MemoryShareRefreshTokenRepo,
    private val memoryRepo: MemoryRepo,
    private val mediaRepo: MediaRepo,
    private val tenantMemberRepo: TenantMemberRepo,
    private val groupMemberRepo: TenantGroupMemberRepo,
    private val passwordService: PasswordService,
    private val mediaService: MediaService,
    private val trackService: MemoryTrackService,
    private val playlistService: MemoryPlaylistService,
    private val memoryJournalService: MemoryJournalService,
    private val mediaConfig: MediaConfig,
    private val requestContext: RequestContext,
    private val routingContext: RoutingContext,
    private val cookieConfig: CookieConfig,
    private val tokenConfig: TokenConfig,
    private val rateLimitService: RateLimitService,
    private val noticeMarkdown: NoticeMarkdown,
) {
    private val random = SecureRandom()

    @Inject
    lateinit var self: Instance<MemoryShareService>

    fun listMemoryShares(memoryId: String): List<MemoryShareSummary> {
        val memory = resolveManagedMemory(memoryId)
        return shareRepo.listByMemory(memory).map(::toManagedSummary)
    }

    @Transactional
    fun createMemoryShare(memoryId: String, request: CreateMemoryShareRequest): MemoryShareSummary {
        val memory = resolveManagedMemory(memoryId)

        val share = MemoryShare().also {
            it.name = optionalShareName(request.name)
            it.tenant = tenant()
            it.rootMemory = memory
            it.role = request.role.toDomain()
            it.passwordHash = optionalPasswordHash(request.password)
            val notice = noticeMarkdown.normalize(request.noticeMarkdownByLocale)
            it.noticeMarkdownByLocale = noticeMarkdown.serialize(notice)
            it.noticeVersion = if (notice.isEmpty()) 0 else 1
            it.enabled = true
            it.createdBy = user()
            shareRepo.persist(it)
        }
        shareRepo.flush()
        return toManagedSummary(share)
    }

    @Transactional
    fun updateMemoryShare(shareId: String, request: UpdateMemoryShareRequest): MemoryShareSummary {
        val share = resolveManagedShare(shareId)
        if (updateFieldPresent("name")) {
            share.name = optionalShareName(request.name)
        }
        request.role?.let { share.role = it.toDomain() }
        request.enabled?.let { enabled ->
            share.enabled = enabled
            if (!enabled) revokeShareIdentities(share)
        }
        if (updateFieldPresent("password")) {
            share.passwordHash = optionalPasswordHash(request.password)
            revokeShareIdentities(share)
        }
        if (updateFieldPresent("noticeMarkdownByLocale")) {
            val notice = noticeMarkdown.normalize(request.noticeMarkdownByLocale)
            if (noticeMarkdown.changed(share.noticeMarkdownByLocale, notice)) {
                share.noticeMarkdownByLocale = noticeMarkdown.serialize(notice)
                share.noticeVersion += 1
            }
        }
        return toManagedSummary(share)
    }

    fun getPublicShare(shareId: String): PublicShareSummary {
        val share = resolveEnabledShare(shareId)
        return toPublicSummary(currentAccess(share) ?: ShareAccess(share, null, null, false, false, false, false))
    }

    @Transactional
    fun loginPublicShare(shareId: String, request: PublicShareLoginRequest): ShareTokenResponse {
        val share = resolveEnabledShare(shareId)
        rateLimitService.checkShareLoginAttempt(share.publicId)
        share.passwordHash?.let { passwordHash ->
            if (!passwordService.matches(request.password ?: "", passwordHash)) {
                rateLimitService.recordShareLoginFailure(share.publicId)
                throw UnauthorizedException("Invalid share password")
            }
        }

        val displayName = shareDisplayName(share, request.displayName)
        val identity = MemoryShareIdentity().also {
            it.share = share
            it.displayName = displayName
            shareIdentityRepo.persist(it)
        }
        shareIdentityRepo.flush()
        rateLimitService.recordShareLoginSuccess(share.publicId)

        val tokens = createTokens(identity, oldRefreshToken = null)
        val access = ShareAccess(
            share = share,
            shareIdentity = identity,
            authenticatedUser = null,
            canViewGallery = canViewFull(share.role),
            canUpload = canUpload(share.role),
            canEdit = canEdit(share.role),
            canDelete = canDelete(share.role),
        )
        return ShareTokenResponse(toPublicSummary(access), accessCookie(tokens.accessToken), refreshCookie(tokens.refreshToken))
    }

    @Transactional
    fun refreshPublicShare(shareId: String): ShareTokenResponse {
        val share = resolveEnabledShare(shareId)
        val refreshTokenValue = routingContext.request().getCookie(SHARE_REFRESH_COOKIE)?.value
            ?: throw UnauthorizedException("Share refresh token required")
        val refreshToken = shareRefreshTokenRepo.findByTokenHash(HashingService.sha256(refreshTokenValue))
            ?: throw UnauthorizedException("Invalid share refresh token")
        val now = Instant.now()
        if (refreshToken.revokedAt != null) {
            self.get().invalidateRefreshTokenRecursive(refreshToken.tokenHash, now)
            throw UnauthorizedException("Invalid share session")
        }
        if (refreshToken.expiresAt <= now) throw UnauthorizedException("Invalid share refresh token")

        val identity = refreshToken.shareIdentity
        if (identity.revokedAt != null || identity.share.id != share.id) {
            throw UnauthorizedException("Invalid share session")
        }

        val tokens = createTokens(identity, oldRefreshToken = refreshToken)
        invalidateRefreshToken(refreshToken, now)
        val access = ShareAccess(
            share = share,
            shareIdentity = identity,
            authenticatedUser = null,
            canViewGallery = canViewFull(share.role),
            canUpload = canUpload(share.role),
            canEdit = canEdit(share.role),
            canDelete = canDelete(share.role),
        )
        return ShareTokenResponse(toPublicSummary(access), accessCookie(tokens.accessToken), refreshCookie(tokens.refreshToken))
    }

    @Transactional
    fun logoutPublicShare(shareId: String): Pair<NewCookie, NewCookie> {
        val share = resolveShare(shareId)
        val now = Instant.now()
        currentAccessToken(share, now)?.revokedAt = now
        currentRefreshToken(share, now)?.let { invalidateRefreshTokenRecursive(it.tokenHash, now) }
        return accessCookie("", maxAge = 0) to refreshCookie("", maxAge = 0)
    }

    fun getPublicShareTree(shareId: String): PublicShareTreeResponse {
        val access = requireAccess(resolveEnabledShare(shareId))
        if (!access.canViewGallery) throw ForbiddenException("Share does not allow full memory access")
        val memories = subtree(access.share.rootMemory)
        return PublicShareTreeResponse(
            root = toPublicMemorySummary(access.share.rootMemory, access.share),
            memories = memories.drop(1).map { toPublicMemorySummary(it, access.share) },
        )
    }

    fun getPublicShareMemoryByPath(shareId: String, path: String): PublicShareMemorySummary {
        val access = requireFullAccess(resolveEnabledShare(shareId))
        return toPublicMemorySummary(resolveShareMemoryByPath(access, path), access.share)
    }

    fun getPublicShareMemoryHeader(shareId: String, memoryId: String): PublicShareMemoryHeaderSummary {
        val access = requireFullAccess(resolveEnabledShare(shareId))
        return toPublicMemoryHeader(resolveShareMemory(access, memoryId), access.share)
    }

    fun getPublicShareMemoryJournal(shareId: String, memoryId: String): com.veyq.api.generated.model.MemoryJournalSummary {
        val access = requireFullAccess(resolveEnabledShare(shareId))
        return memoryJournalService.getJournal(resolveShareMemory(access, memoryId))
    }

    @Transactional
    fun updatePublicShareMemoryJournal(
        shareId: String,
        memoryId: String,
        request: UpdateMemoryJournalRequest,
    ): com.veyq.api.generated.model.MemoryJournalSummary {
        val access = requireJournalEditAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        return memoryJournalService.updateJournal(
            memory,
            request.markdown,
            MemoryJournalService.JournalValidationContext(
                memory = memory,
                shareRoot = access.share.rootMemory,
                allowShareSubtreeMedia = true,
            ),
        )
    }

    fun listPublicShareChildMemories(shareId: String, memoryId: String): List<PublicShareMemorySummary> {
        val access = requireFullAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        return memoryRepo.listChildren(access.share.tenant, memory)
            .filter { isDescendantOrSelf(it, access.share.rootMemory) }
            .map { toPublicMemorySummary(it, access.share) }
    }

    @Transactional
    fun createPublicShareChildMemory(
        shareId: String,
        memoryId: String,
        request: CreatePublicShareMemoryRequest,
    ): PublicShareMemorySummary {
        val access = requireEditAccess(resolveEnabledShare(shareId))
        val parent = resolveShareMemory(access, memoryId)
        val name = normalizedName(request.name)
        val dateRange = requiredDateRange(request.startDate, request.endDate)
        validateNoChildCollision(parent, name)
        val memory = Memory().also {
            it.tenant = access.share.tenant
            it.parentMemory = parent
            it.parentFolder = null
            it.owner = access.authenticatedUser ?: access.share.createdBy
            LibraryScopes.apply(it, LibraryScopes.scope(parent))
            it.name = name
            it.displayName = requiredDisplayName(request.displayName)
            it.startDate = dateRange.first
            it.endDate = dateRange.second
            memoryRepo.persist(it)
        }
        memoryRepo.flush()
        return toPublicMemorySummary(memory, access.share)
    }

    @Transactional
    fun updatePublicShareMemory(
        shareId: String,
        memoryId: String,
        request: UpdatePublicShareMemoryRequest,
    ): PublicShareMemorySummary {
        val access = requireEditAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        request.name?.let {
            val name = normalizedName(it)
            if (name != memory.name) validateNoChildCollision(memory.parentMemory, name)
            memory.name = name
        }
        request.displayName?.let { memory.displayName = requiredDisplayName(it) }
        if (request.startDate != null || request.endDate != null) {
            val dateRange = requiredDateRange(request.startDate ?: memory.startDate, request.endDate ?: memory.endDate)
            memory.startDate = dateRange.first
            memory.endDate = dateRange.second
        }
        return toPublicMemorySummary(memory, access.share)
    }

    @Transactional
    fun deletePublicShareMemory(shareId: String, memoryId: String) {
        val access = requireDeleteAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        if (memory.id == access.share.rootMemory.id) throw ForbiddenException("Share root cannot be deleted")
        deleteMemoryTree(memory)
    }

    fun listPublicShareMemoryMedia(shareId: String, memoryId: String): List<com.veyq.api.generated.model.PublicShareMediaSummary> {
        val access = requireFullAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        return mediaRepo.listByMemory(memory)
            .filter { isPublicMediaVisible(access, it) }
            .map { mediaService.publicShareSummary(it, access.share.publicId) }
    }

    fun downloadPublicShareMemoryMedia(shareId: String, memoryId: String, includeChildren: Boolean, locale: String?): Response {
        val access = requireFullAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        val memories = if (includeChildren) {
            subtree(memory).filter { isDescendantOrSelf(it, access.share.rootMemory) }
        } else {
            listOf(memory)
        }
        return mediaService.archiveResponse(
            memoryDirectories = mediaService.archiveMemoryDirectories(memory, memories),
            archiveName = publicShareArchiveFilename(memory, includeChildren, locale),
            locale = locale,
        )
    }

    fun listPublicShareMemoryTracks(shareId: String, memoryId: String): List<com.veyq.api.generated.model.MemoryTrackSummary> {
        val access = requireFullAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        return trackService.listPublicShareMemoryTracks(access.share.publicId, memory)
    }

    @Transactional
    fun uploadPublicShareMemoryTrack(
        shareId: String,
        memoryId: String,
        uploadPath: Path,
        originalFilename: String?,
        title: String?,
        activityType: com.veyq.api.generated.model.MemoryTrackActivityType?,
    ): com.veyq.api.generated.model.MemoryTrackSummary {
        val access = requireTrackEditAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        return trackService.uploadPublicShareMemoryTrack(
            shareId = access.share.publicId,
            memory = memory,
            uploadPath = uploadPath,
            originalFilename = originalFilename,
            title = title,
            activityType = activityType,
        )
    }

    @Transactional
    fun updatePublicShareMemoryTrack(
        shareId: String,
        memoryId: String,
        trackId: String,
        request: com.veyq.api.generated.model.UpdateMemoryTrackRequest,
    ): com.veyq.api.generated.model.MemoryTrackSummary {
        val access = requireTrackEditAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        return trackService.updatePublicShareMemoryTrack(access.share.publicId, memory, trackId, request)
    }

    @Transactional
    fun deletePublicShareMemoryTrack(shareId: String, memoryId: String, trackId: String) {
        val access = requireTrackEditAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        trackService.deletePublicShareMemoryTrack(memory, trackId)
    }

    fun publicShareMemoryTrackContent(shareId: String, memoryId: String, trackId: String): Response {
        val access = requireFullAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        return trackService.publicShareMemoryTrackContent(memory, trackId)
    }

    fun listPublicShareMemoryPlaylists(shareId: String, memoryId: String): List<com.veyq.api.generated.model.MemoryPlaylistSummary> {
        val access = requireFullAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        return playlistService.listPublicShareMemoryPlaylists(memory)
    }

    @Transactional
    fun createPublicShareMemoryPlaylist(
        shareId: String,
        memoryId: String,
        request: com.veyq.api.generated.model.CreateMemoryPlaylistRequest,
    ): com.veyq.api.generated.model.MemoryPlaylistSummary {
        val access = requirePlaylistEditAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        return playlistService.createPublicShareMemoryPlaylist(
            memory = memory,
            request = request,
            authenticatedUser = access.authenticatedUser,
            shareIdentity = access.shareIdentity,
        )
    }

    @Transactional
    fun updatePublicShareMemoryPlaylist(
        shareId: String,
        memoryId: String,
        playlistId: String,
        request: com.veyq.api.generated.model.UpdateMemoryPlaylistRequest,
    ): com.veyq.api.generated.model.MemoryPlaylistSummary {
        val access = requirePlaylistEditAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        return playlistService.updatePublicShareMemoryPlaylist(memory, playlistId, request)
    }

    @Transactional
    fun deletePublicShareMemoryPlaylist(shareId: String, memoryId: String, playlistId: String) {
        val access = requirePlaylistEditAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        playlistService.deletePublicShareMemoryPlaylist(memory, playlistId)
    }

    fun listPublicShareMedia(shareId: String): List<com.veyq.api.generated.model.PublicShareMediaSummary> {
        val access = requireAccess(resolveEnabledShare(shareId))
        if (!access.canViewGallery) {
            return ownIdentityMedia(access).map { mediaService.publicShareSummary(it, access.share.publicId) }
        }
        return subtree(access.share.rootMemory)
            .flatMap { mediaRepo.listByMemory(it) }
            .filter { isPublicMediaVisible(access, it) }
            .distinctBy { it.id }
            .sortedWith(compareBy<Media> { it.capturedAt ?: it.createdAt }.thenBy { it.createdAt })
            .map { mediaService.publicShareSummary(it, access.share.publicId) }
    }

    @Transactional
    fun uploadPublicShareMedia(
        shareId: String,
        uploadPath: Path,
        originalFilename: String?,
        title: String?,
    ): com.veyq.api.generated.model.PublicShareMediaSummary {
        val access = requireAccess(resolveEnabledShare(shareId))
        if (!access.canUpload) throw ForbiddenException("Share does not allow uploads")

        return mediaService.uploadPublicShareMedia(
            shareId = access.share.publicId,
            memory = access.share.rootMemory,
            uploadPath = uploadPath,
            originalFilename = originalFilename,
            title = title,
            uploaderUser = access.authenticatedUser,
            shareIdentity = access.shareIdentity,
        )
    }

    @Transactional
    fun uploadPublicShareMemoryMedia(
        shareId: String,
        memoryId: String,
        uploadPath: Path,
        originalFilename: String?,
        title: String?,
    ): com.veyq.api.generated.model.PublicShareMediaSummary {
        val access = requireUploadAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        return uploadPublicShareMedia(access, memory, uploadPath, originalFilename, title)
    }

    fun createPublicShareMediaUpload(
        shareId: String,
        request: CreateMemoryMediaUploadRequest,
    ): MemoryMediaUploadSession {
        val access = requireAccess(resolveEnabledShare(shareId))
        if (!access.canUpload) throw ForbiddenException("Share does not allow uploads")
        return mediaService.createPublicShareMediaUpload(
            shareId = access.share.publicId,
            memory = access.share.rootMemory,
            request = request,
            uploaderUser = access.authenticatedUser,
            shareIdentity = access.shareIdentity,
        )
    }

    fun createPublicShareMemoryMediaUpload(
        shareId: String,
        memoryId: String,
        request: CreateMemoryMediaUploadRequest,
    ): MemoryMediaUploadSession {
        val access = requireUploadAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        return mediaService.createPublicShareMediaUpload(
            shareId = access.share.publicId,
            memory = memory,
            request = request,
            uploaderUser = access.authenticatedUser,
            shareIdentity = access.shareIdentity,
        )
    }

    fun publicShareMediaUploadStatus(shareId: String, uploadId: String): PendingMemoryMediaUploadSession {
        val access = requireAccess(resolveEnabledShare(shareId))
        if (!access.canUpload) throw ForbiddenException("Share does not allow uploads")
        return mediaService.publicShareMediaUploadStatus(
            shareId = access.share.publicId,
            memory = access.share.rootMemory,
            uploadId = uploadId,
            uploaderUser = access.authenticatedUser,
            shareIdentity = access.shareIdentity,
        )
    }

    fun publicShareMemoryMediaUploadStatus(
        shareId: String,
        memoryId: String,
        uploadId: String,
    ): PendingMemoryMediaUploadSession {
        val access = requireUploadAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        return mediaService.publicShareMediaUploadStatus(
            shareId = access.share.publicId,
            memory = memory,
            uploadId = uploadId,
            uploaderUser = access.authenticatedUser,
            shareIdentity = access.shareIdentity,
        )
    }

    fun uploadPublicShareMediaChunk(shareId: String, uploadId: String, chunkIndex: Int, chunk: Path) {
        val access = requireAccess(resolveEnabledShare(shareId))
        if (!access.canUpload) throw ForbiddenException("Share does not allow uploads")
        mediaService.uploadPublicShareMediaChunk(
            shareId = access.share.publicId,
            memory = access.share.rootMemory,
            uploadId = uploadId,
            chunkIndex = chunkIndex,
            chunk = chunk,
            uploaderUser = access.authenticatedUser,
            shareIdentity = access.shareIdentity,
        )
    }

    fun uploadPublicShareMemoryMediaChunk(
        shareId: String,
        memoryId: String,
        uploadId: String,
        chunkIndex: Int,
        chunk: Path,
    ) {
        val access = requireUploadAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        mediaService.uploadPublicShareMediaChunk(
            shareId = access.share.publicId,
            memory = memory,
            uploadId = uploadId,
            chunkIndex = chunkIndex,
            chunk = chunk,
            uploaderUser = access.authenticatedUser,
            shareIdentity = access.shareIdentity,
        )
    }

    fun completePublicShareMediaUpload(
        shareId: String,
        uploadId: String,
    ): com.veyq.api.generated.model.PublicShareMediaSummary {
        val access = requireAccess(resolveEnabledShare(shareId))
        if (!access.canUpload) throw ForbiddenException("Share does not allow uploads")
        return mediaService.completePublicShareMediaUpload(
            shareId = access.share.publicId,
            memory = access.share.rootMemory,
            uploadId = uploadId,
            uploaderUser = access.authenticatedUser,
            shareIdentity = access.shareIdentity,
        )
    }

    fun completePublicShareMemoryMediaUpload(
        shareId: String,
        memoryId: String,
        uploadId: String,
    ): com.veyq.api.generated.model.PublicShareMediaSummary {
        val access = requireUploadAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        return mediaService.completePublicShareMediaUpload(
            shareId = access.share.publicId,
            memory = memory,
            uploadId = uploadId,
            uploaderUser = access.authenticatedUser,
            shareIdentity = access.shareIdentity,
        )
    }

    fun abortPublicShareMediaUpload(shareId: String, uploadId: String) {
        val access = requireAccess(resolveEnabledShare(shareId))
        if (!access.canUpload) throw ForbiddenException("Share does not allow uploads")
        mediaService.abortPublicShareMediaUpload(
            shareId = access.share.publicId,
            memory = access.share.rootMemory,
            uploadId = uploadId,
            uploaderUser = access.authenticatedUser,
            shareIdentity = access.shareIdentity,
        )
    }

    fun abortPublicShareMemoryMediaUpload(shareId: String, memoryId: String, uploadId: String) {
        val access = requireUploadAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        mediaService.abortPublicShareMediaUpload(
            shareId = access.share.publicId,
            memory = memory,
            uploadId = uploadId,
            uploaderUser = access.authenticatedUser,
            shareIdentity = access.shareIdentity,
        )
    }

    @Transactional
    fun setPublicShareMemoryCover(
        shareId: String,
        memoryId: String,
        uploadPath: Path?,
        originalFilename: String?,
        mediaId: String?,
        cropX: Double?,
        cropY: Double?,
        cropWidth: Double?,
        cropHeight: Double?,
    ): PublicShareMemorySummary {
        val access = requireEditAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        val media = if (uploadPath != null) {
            val uploaded = uploadPublicShareMedia(access, memory, uploadPath, originalFilename, originalFilename)
            mediaRepo.findByTenantAndPublicId(access.share.tenant, uploaded.id)
                ?: throw NotFoundException("Media not found")
        } else {
            val normalizedMediaId = mediaId?.trim()?.takeIf { it.isNotBlank() }
                ?: throw BadRequestException("Cover source is required", "MEMORY_COVER_SOURCE_REQUIRED")
            mediaRepo.findByTenantAndPublicId(access.share.tenant, normalizedMediaId)
                ?: throw NotFoundException("Media not found")
        }
        if (media.memory?.id != memory.id || !media.blob.mimeType.startsWith("image/")) {
            throw NotFoundException("Media not found")
        }
        memory.coverStorageKey?.let { deleteRecursively(coverDirectory(it)) }
        memory.coverStorageKey = null
        memory.coverMimeType = null
        memory.coverOriginalFilename = null
        memory.coverMedia = media
        val crop = requiredCoverCrop(cropX, cropY, cropWidth, cropHeight)
        memory.coverCropX = crop.x
        memory.coverCropY = crop.y
        memory.coverCropWidth = crop.width
        memory.coverCropHeight = crop.height
        return toPublicMemorySummary(memory, access.share)
    }

    @Transactional
    fun removePublicShareMemoryCover(shareId: String, memoryId: String): PublicShareMemorySummary {
        val access = requireEditAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        memory.coverStorageKey?.let { deleteRecursively(coverDirectory(it)) }
        memory.coverStorageKey = null
        memory.coverMimeType = null
        memory.coverOriginalFilename = null
        memory.coverMedia = null
        memory.coverCropX = null
        memory.coverCropY = null
        memory.coverCropWidth = null
        memory.coverCropHeight = null
        return toPublicMemorySummary(memory, access.share)
    }

    fun publicShareMemoryCoverContent(shareId: String, memoryId: String): Response {
        val access = requireFullAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        val storageKey = memory.coverStorageKey ?: throw NotFoundException("Cover not found")
        val path = storagePath(storageKey)
        if (!Files.isRegularFile(path)) throw NotFoundException("Cover not found")
        return Response.ok(path.toFile(), memory.coverMimeType ?: "application/octet-stream")
            .header("Cache-Control", "private, max-age=31536000, immutable")
            .header("Content-Length", Files.size(path))
            .header("X-Content-Type-Options", "nosniff")
            .build()
    }

    @Transactional
    fun updatePublicShareMediaMetadata(
        shareId: String,
        memoryId: String,
        mediaId: String,
        request: UpdateMemoryMediaMetadataRequest,
    ): com.veyq.api.generated.model.PublicShareMediaSummary {
        val access = requireEditAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        val media = resolvePublicMedia(access, mediaId)
        if (media.memory?.id != memory.id) throw NotFoundException("Media not found")
        media.capturedAt = request.capturedAt?.toInstant()
        media.capturedAtOverride = true
        return mediaService.publicShareSummary(media, access.share.publicId)
    }

    @Transactional
    fun deletePublicShareMedia(shareId: String, memoryId: String, mediaId: String) {
        val access = requireDeleteAccess(resolveEnabledShare(shareId))
        val memory = resolveShareMemory(access, memoryId)
        val media = resolvePublicMedia(access, mediaId)
        if (media.memory?.id != memory.id) throw NotFoundException("Media not found")
        deleteMedia(media)
    }

    fun publicShareMediaContent(shareId: String, mediaId: String, range: String?): Response {
        val media = resolvePublicMedia(shareId, mediaId)
        return mediaService.streamOriginal(media, range)
    }

    fun publicShareMediaThumbnail(shareId: String, mediaId: String): Response {
        val media = resolvePublicMedia(shareId, mediaId)
        return mediaService.streamThumbnail(media)
    }

    fun publicShareMediaPreview(shareId: String, mediaId: String): Response {
        val media = resolvePublicMedia(shareId, mediaId)
        return mediaService.streamPreview(media)
    }

    private fun resolvePublicMedia(shareId: String, mediaId: String): Media {
        val access = requireAccess(resolveEnabledShare(shareId))
        return resolvePublicMedia(access, mediaId)
    }

    private fun resolvePublicMedia(access: ShareAccess, mediaId: String): Media {
        val media = mediaRepo.findByTenantAndPublicId(access.share.tenant, mediaId)
            ?: throw NotFoundException("Media not found")
        if (!isPublicMediaVisible(access, media)) throw NotFoundException("Media not found")
        return media
    }

    private fun isPublicMediaVisible(access: ShareAccess, media: Media): Boolean {
        val memory = media.memory ?: return false
        if (!isDescendantOrSelf(memory, access.share.rootMemory)) return false
        if (access.canViewGallery) return true
        return access.shareIdentity != null && media.shareIdentity?.id == access.shareIdentity.id
    }

    private fun ownIdentityMedia(access: ShareAccess): List<Media> =
        access.shareIdentity?.let { identity ->
            mediaRepo.list("shareIdentity", identity)
        } ?: emptyList()

    private fun requireAccess(share: MemoryShare): ShareAccess =
        currentAccess(share) ?: throw UnauthorizedException("Share authentication required")

    private fun requireFullAccess(share: MemoryShare): ShareAccess =
        requireAccess(share).also {
            if (!it.canViewGallery) throw ForbiddenException("Share does not allow full memory access")
        }

    private fun requireUploadAccess(share: MemoryShare): ShareAccess =
        requireAccess(share).also {
            if (!it.canUpload) throw ForbiddenException("Share does not allow uploads")
        }

    private fun requireEditAccess(share: MemoryShare): ShareAccess =
        requireFullAccess(share).also {
            if (!it.canEdit) throw ForbiddenException("Share does not allow editing")
        }

    private fun requireTrackEditAccess(share: MemoryShare): ShareAccess =
        requireFullAccess(share).also {
            if (!it.canEdit) throw ForbiddenException("Share does not allow track editing")
        }

    private fun requirePlaylistEditAccess(share: MemoryShare): ShareAccess =
        requireFullAccess(share).also {
            if (!it.canEdit) throw ForbiddenException("Share does not allow playlist editing")
        }

    private fun requireDeleteAccess(share: MemoryShare): ShareAccess =
        requireFullAccess(share).also {
            if (!it.canDelete) throw ForbiddenException("Share does not allow deleting")
        }

    private fun requireJournalEditAccess(share: MemoryShare): ShareAccess =
        requireFullAccess(share).also {
            if (!it.canDelete) throw ForbiddenException("Share does not allow journal editing")
        }

    private fun currentAccess(share: MemoryShare): ShareAccess? {
        val authenticatedUser = requestContext.user?.takeIf { canUserSeeRootMemory(share, it) }
        if (authenticatedUser != null) {
            return ShareAccess(
                share = share,
                shareIdentity = null,
                authenticatedUser = authenticatedUser,
                canViewGallery = canViewFull(share.role),
                canUpload = canUpload(share.role),
                canEdit = canEdit(share.role),
                canDelete = canDelete(share.role),
            )
        }

        if (share.passwordHash == null && share.role == MemoryShareRole.VIEWER) {
            return ShareAccess(
                share = share,
                shareIdentity = null,
                authenticatedUser = null,
                canViewGallery = true,
                canUpload = false,
                canEdit = false,
                canDelete = false,
            )
        }

        val accessToken = currentAccessToken(share, Instant.now()) ?: return null
        val identity = accessToken.shareIdentity
        return ShareAccess(
            share = share,
            shareIdentity = identity,
            authenticatedUser = null,
            canViewGallery = canViewFull(share.role),
            canUpload = canUpload(share.role),
            canEdit = canEdit(share.role),
            canDelete = canDelete(share.role),
        )
    }

    private fun currentAccessToken(share: MemoryShare, now: Instant): MemoryShareAccessToken? {
        val token = routingContext.request().getCookie(SHARE_ACCESS_COOKIE)?.value ?: return null
        val accessToken = shareAccessTokenRepo.findActiveByTokenHash(HashingService.sha256(token), now) ?: return null
        if (accessToken.shareIdentity.revokedAt != null) return null
        if (accessToken.shareIdentity.share.id != share.id) return null
        return accessToken
    }

    private fun currentRefreshToken(share: MemoryShare, now: Instant): MemoryShareRefreshToken? {
        val token = routingContext.request().getCookie(SHARE_REFRESH_COOKIE)?.value ?: return null
        val refreshToken = shareRefreshTokenRepo.findActiveByTokenHash(HashingService.sha256(token), now) ?: return null
        if (refreshToken.shareIdentity.revokedAt != null) return null
        if (refreshToken.shareIdentity.share.id != share.id) return null
        return refreshToken
    }

    private fun canUserSeeRootMemory(share: MemoryShare, user: User): Boolean {
        if (tenantMemberRepo.findByTenantAndUser(share.tenant, user) == null) return false
        return when (val scope = LibraryScopes.scope(share.rootMemory)) {
            LibraryScopeKey.Tenant -> true
            is LibraryScopeKey.Group -> groupMemberRepo.findByGroupAndUser(scope.group, user) != null
            is LibraryScopeKey.User -> scope.user.id == user.id
        }
    }

    private fun resolveManagedMemory(publicId: String): Memory {
        val memory = memoryRepo.findByTenantAndPublicId(tenant(), publicId)
            ?: throw NotFoundException("Memory not found")
        if (!canUserSeeRootMemory(memoryShareShell(memory), user())) throw NotFoundException("Memory not found")
        return memory
    }

    private fun resolveManagedShare(publicId: String): MemoryShare {
        val share = shareRepo.findByTenantAndPublicId(tenant(), publicId)
            ?: throw NotFoundException("Share not found")
        if (!canUserSeeRootMemory(share, user())) throw NotFoundException("Share not found")
        return share
    }

    private fun resolveEnabledShare(publicId: String): MemoryShare {
        val share = resolveShare(publicId)
        if (!share.enabled) throw NotFoundException("Share not found")
        return share
    }

    private fun resolveShare(publicId: String): MemoryShare =
        shareRepo.findByPublicId(publicId) ?: throw NotFoundException("Share not found")

    private fun revokeShareIdentities(share: MemoryShare) {
        val now = Instant.now()
        shareIdentityRepo.list("share = ?1 and revokedAt is null", share).forEach { identity ->
            identity.revokedAt = now
            shareRefreshTokenRepo.list("shareIdentity", identity).forEach { token ->
                invalidateRefreshTokenRecursive(token.tokenHash, now)
            }
        }
    }

    private fun createTokens(identity: MemoryShareIdentity, oldRefreshToken: MemoryShareRefreshToken?): ShareTokens {
        val accessToken = newToken()
        val refreshToken = newToken()
        val now = Instant.now()

        val refreshEntity = MemoryShareRefreshToken().also {
            it.tokenHash = HashingService.sha256(refreshToken)
            it.shareIdentity = identity
            it.createdAt = now
            it.lastAccessedAt = now
            it.expiresAt = now.plus(Duration.ofMinutes(tokenConfig.refreshTokenTtlMinutes))
            shareRefreshTokenRepo.persist(it)
        }
        MemoryShareAccessToken().also {
            it.tokenHash = HashingService.sha256(accessToken)
            it.shareIdentity = identity
            it.refreshToken = refreshEntity
            it.createdAt = now
            it.lastAccessedAt = now
            it.expiresAt = now.plus(Duration.ofMinutes(tokenConfig.accessTokenTtlMinutes))
            shareAccessTokenRepo.persist(it)
        }
        oldRefreshToken?.rotatedTo = refreshEntity

        return ShareTokens(accessToken, refreshToken)
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun invalidateRefreshTokenRecursive(tokenHash: String?, revokedAt: Instant) {
        if (tokenHash == null) return
        val refreshToken = shareRefreshTokenRepo.findByTokenHash(tokenHash)?.also { token ->
            token.revokedAt = revokedAt
        }
        refreshToken?.id?.let { refreshTokenId ->
            shareAccessTokenRepo.list("refreshToken.id", refreshTokenId).forEach { accessToken ->
                accessToken.revokedAt = revokedAt
            }
        }
        invalidateRefreshTokenRecursive(refreshToken?.rotatedTo?.tokenHash, revokedAt)
    }

    private fun invalidateRefreshToken(refreshToken: MemoryShareRefreshToken, revokedAt: Instant) {
        refreshToken.revokedAt = revokedAt
        refreshToken.id?.let { refreshTokenId ->
            shareAccessTokenRepo.list("refreshToken.id", refreshTokenId).forEach { accessToken ->
                accessToken.revokedAt = revokedAt
            }
        }
    }

    private fun subtree(root: Memory): List<Memory> =
        listOf(root) + memoryRepo.list("tenant", root.tenant)
            .filter { candidate -> generateSequence(candidate.parentMemory) { it.parentMemory }.any { it.id == root.id } }

    private fun isDescendantOrSelf(candidate: Memory, root: Memory): Boolean =
        candidate.id == root.id || generateSequence(candidate.parentMemory) { it.parentMemory }.any { it.id == root.id }

    private fun resolveShareMemory(access: ShareAccess, memoryId: String): Memory {
        val memory = memoryRepo.findByTenantAndPublicId(access.share.tenant, memoryId)
            ?: throw NotFoundException("Memory not found")
        if (!isDescendantOrSelf(memory, access.share.rootMemory)) throw NotFoundException("Memory not found")
        return memory
    }

    private fun resolveShareMemoryByPath(access: ShareAccess, path: String): Memory {
        val segments = path.trim().trim('/').split('/').filter { it.isNotBlank() }
        var memory = access.share.rootMemory
        for (segment in segments) {
            memory = memoryRepo.listChildren(access.share.tenant, memory).firstOrNull { it.name == segment }
                ?: throw NotFoundException("Memory not found")
        }
        return memory
    }

    private fun uploadPublicShareMedia(
        access: ShareAccess,
        memory: Memory,
        uploadPath: Path,
        originalFilename: String?,
        title: String?,
    ): com.veyq.api.generated.model.PublicShareMediaSummary =
        mediaService.uploadPublicShareMedia(
            shareId = access.share.publicId,
            memory = memory,
            uploadPath = uploadPath,
            originalFilename = originalFilename,
            title = title,
            uploaderUser = access.authenticatedUser,
            shareIdentity = access.shareIdentity,
        )

    private fun validateNoChildCollision(parent: Memory?, name: String) {
        if (parent == null) return
        if (memoryRepo.listChildren(parent.tenant, parent).any { it.name == name }) {
            throw com.veyq.api.auth.ConflictException("Path name already exists", "MEMORY_NAME_CONFLICT")
        }
    }

    private fun normalizedName(value: String?): String {
        val name = value?.trim()?.lowercase() ?: throw BadRequestException("name is required", "MEMORY_NAME_REQUIRED")
        if (name.length !in 1..120 || !name.matches(Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$"))) {
            throw BadRequestException("Invalid name", "MEMORY_NAME_INVALID")
        }
        return name
    }

    private fun requiredDisplayName(value: String?): String =
        value?.trim()?.takeIf { it.isNotBlank() } ?: throw BadRequestException("displayName is required", "MEMORY_DISPLAY_NAME_REQUIRED")

    private fun requiredShareDisplayName(value: String?): String {
        val displayName = requiredDisplayName(value)
        if (displayName.length > 80) throw BadRequestException("displayName is too long")
        return displayName
    }

    private fun shareDisplayName(share: MemoryShare, value: String?): String {
        if (requiresShareDisplayName(share)) return requiredShareDisplayName(value)
        return value?.trim()?.takeIf { it.isNotBlank() }?.also {
            if (it.length > 80) throw BadRequestException("displayName is too long")
        } ?: "Viewer"
    }

    private fun requiredDateRange(startDate: LocalDate?, endDate: LocalDate?): Pair<LocalDate, LocalDate> {
        val start = startDate ?: throw BadRequestException("startDate is required", "MEMORY_DATE_RANGE_REQUIRED")
        val end = endDate ?: throw BadRequestException("endDate is required", "MEMORY_DATE_RANGE_REQUIRED")
        if (end.isBefore(start)) throw BadRequestException("endDate must not be before startDate", "MEMORY_DATE_RANGE_INVALID")
        return start to end
    }

    private fun publicShareArchiveFilename(memory: Memory, includeChildren: Boolean, locale: String?): String {
        val suffix = when (mediaService.normalizedLocale(locale)) {
            "en" -> if (includeChildren) "-with-chapters" else ""
            else -> if (includeChildren) "-mit-kapiteln" else ""
        }
        val name = memory.displayName
            .replace("\\", "-")
            .replace("/", "-")
            .replace(Regex("[\\x00-\\x1F\\x7F:*?\"<>|]+"), "-")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .takeIf { it.isNotBlank() }
            ?: "memory"
        return "$name$suffix.zip"
    }

    private fun requiredCoverCrop(
        x: Double?,
        y: Double?,
        width: Double?,
        height: Double?,
    ): PublicShareCoverCrop {
        val crop = PublicShareCoverCrop(
            x ?: throw BadRequestException("cropX is required", "MEMORY_COVER_CROP_REQUIRED"),
            y ?: throw BadRequestException("cropY is required", "MEMORY_COVER_CROP_REQUIRED"),
            width ?: throw BadRequestException("cropWidth is required", "MEMORY_COVER_CROP_REQUIRED"),
            height ?: throw BadRequestException("cropHeight is required", "MEMORY_COVER_CROP_REQUIRED"),
        )
        val values = listOf(crop.x, crop.y, crop.width, crop.height)
        if (values.any { !it.isFinite() || it < 0.0 || it > 1.0 }) {
            throw BadRequestException("Cover crop values must be between 0 and 1", "MEMORY_COVER_CROP_INVALID")
        }
        if (crop.width <= 0.0 || crop.height <= 0.0 || crop.x + crop.width > 1.000001 || crop.y + crop.height > 1.000001) {
            throw BadRequestException("Cover crop must fit inside the source image", "MEMORY_COVER_CROP_INVALID")
        }
        return crop
    }

    private fun deleteMemoryTree(memory: Memory) {
        val memories = subtree(memory)
        val media = memories.flatMap { mediaRepo.listByMemory(it) }
        memories.forEach { candidate ->
            if (candidate.coverMedia?.let { cover -> media.any { it.id == cover.id } } == true) {
                candidate.coverMedia = null
            }
        }
        memoryRepo.update("coverMedia = null where coverMedia in ?1", media)
        memoryRepo.flush()
        val blobs = media.map { it.blob }.distinctBy { it.id }
        media.forEach { it.delete() }
        mediaRepo.flush()
        blobs.forEach(mediaService::deleteBlobIfUnreferenced)
        memories.sortedByDescending { descendantDepth(it) }.forEach { it.delete() }
    }

    private fun deleteMedia(media: Media) {
        memoryRepo.list("coverMedia = ?1", media).forEach { it.coverMedia = null }
        val blob = media.blob
        media.delete()
        mediaRepo.flush()
        mediaService.deleteBlobIfUnreferenced(blob)
    }

    private fun descendantDepth(memory: Memory): Int =
        generateSequence(memory.parentMemory) { it.parentMemory }.count()

    private fun coverDirectory(storageKey: String): Path {
        val coverPath = storagePath(storageKey)
        return coverPath.parent ?: coverPath
    }

    private fun storagePath(storageKey: String): Path {
        val root = mediaConfig.storageDir.toAbsolutePath().normalize()
        val path = root.resolve(storageKey).normalize()
        if (!path.startsWith(root)) throw BadRequestException("Invalid media storage key", "MEDIA_STORAGE_KEY_INVALID")
        return path
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun canViewFull(role: MemoryShareRole): Boolean =
        role != MemoryShareRole.UPLOADER

    private fun canUpload(role: MemoryShareRole): Boolean =
        role != MemoryShareRole.VIEWER

    private fun canEdit(role: MemoryShareRole): Boolean =
        role == MemoryShareRole.CONTRIBUTOR || role == MemoryShareRole.MANAGER

    private fun canDelete(role: MemoryShareRole): Boolean =
        role == MemoryShareRole.MANAGER

    private fun requiresShareDisplayName(share: MemoryShare): Boolean =
        share.role != MemoryShareRole.VIEWER

    private fun toManagedSummary(share: MemoryShare): MemoryShareSummary =
        MemoryShareSummary(
            id = share.publicId,
            memoryId = share.rootMemory.publicId,
            name = share.name,
            role = share.role.toApi(),
            enabled = share.enabled,
            shareUrl = "/s/${share.publicId}",
            hasPassword = share.passwordHash != null,
            noticeVersion = share.noticeVersion,
            noticeMarkdownByLocale = noticeMarkdown.deserialize(share.noticeMarkdownByLocale).takeIf { it.isNotEmpty() },
            createdAt = share.createdAt.toApiOffsetDateTime(),
            updatedAt = share.updatedAt.toApiOffsetDateTime(),
        )

    private fun toPublicSummary(access: ShareAccess): PublicShareSummary =
        PublicShareSummary(
            id = access.share.publicId,
            role = access.share.role.toApi(),
            authenticated = access.authenticatedUser != null || access.shareIdentity != null,
            passwordRequired = access.share.passwordHash != null,
            displayNameRequired = requiresShareDisplayName(access.share) && access.shareIdentity == null && access.authenticatedUser == null,
            canViewGallery = access.canViewGallery,
            canUpload = access.canUpload,
            canEdit = access.canEdit,
            canDelete = access.canDelete,
            notices = publicShareNotices(access.share),
            rootMemory = if (canExposePublicRootMemory(access)) toPublicMemorySummary(access.share.rootMemory, access.share) else null,
            redirectUrl = access.authenticatedUser?.let { "/${access.share.tenant.name}${access.share.rootMemory.pathRelativeToTenant()}" },
        )

    private fun publicShareNotices(share: MemoryShare): List<PublicShareNotice> =
        listOfNotNull(
            noticeMarkdown.deserialize(share.tenant.termsMarkdownByLocale)
                .takeIf { it.isNotEmpty() }
                ?.let {
                    PublicShareNotice(
                        scope = PublicShareNoticeScope.TENANT,
                        tenantName = share.tenant.name,
                        tenantDomain = share.tenant.domain,
                        version = share.tenant.termsVersion,
                        markdownByLocale = it,
                    )
                },
            noticeMarkdown.deserialize(share.noticeMarkdownByLocale)
                .takeIf { it.isNotEmpty() }
                ?.let {
                    PublicShareNotice(
                        scope = PublicShareNoticeScope.SHARE,
                        tenantName = share.tenant.name,
                        tenantDomain = share.tenant.domain,
                        version = share.noticeVersion,
                        markdownByLocale = it,
                    )
                },
        )

    private fun toPublicMemorySummary(memory: Memory, share: MemoryShare): PublicShareMemorySummary =
        PublicShareMemorySummary(
            id = memory.publicId,
            name = memory.name,
            displayName = memory.displayName,
            path = memory.sharePath(share.rootMemory),
            parentId = memory.parentMemory?.publicId ?: "",
            childCount = memoryRepo.listChildren(memory.tenant, memory).size,
            mediaCount = mediaRepo.listByMemory(memory).size,
            coverUrl = publicCoverUrl(memory, share.publicId),
            coverMediaId = memory.coverMedia?.publicId,
            coverCrop = memory.coverCrop(),
            startDate = memory.startDate,
            endDate = memory.endDate,
        )

    private fun toPublicMemoryHeader(memory: Memory, share: MemoryShare): PublicShareMemoryHeaderSummary {
        val descendants = subtree(memory).drop(1)
        return PublicShareMemoryHeaderSummary(
            id = memory.publicId,
            displayName = memory.displayName,
            path = memory.sharePath(share.rootMemory),
            coverUrl = publicCoverUrl(memory, share.publicId),
            coverCrop = memory.coverCrop(),
            startDate = memory.startDate,
            endDate = memory.endDate,
            directMediaCount = mediaRepo.listByMemory(memory).size,
            descendantMediaCount = descendants.sumOf { mediaRepo.listByMemory(it).size },
            childMemoryCount = memoryRepo.listChildren(memory.tenant, memory).size,
        )
    }

    private fun publicCoverUrl(memory: Memory, shareId: String): String? =
        memory.coverStorageKey?.let { "/api/public-shares/$shareId/memories/${memory.publicId}/cover/content" }
            ?: memory.coverMedia?.publicId?.let { "/api/public-shares/$shareId/media/$it/content" }

    private fun Memory.coverCrop(): PublicShareCoverCrop? {
        val x = coverCropX ?: return null
        val y = coverCropY ?: return null
        val width = coverCropWidth ?: return null
        val height = coverCropHeight ?: return null
        return PublicShareCoverCrop(x, y, width, height)
    }

    private fun Memory.sharePath(root: Memory): String {
        val names = generateSequence(this) { it.parentMemory }
            .toList()
            .asReversed()
            .dropWhile { it.id == root.id }
            .map { it.name }
        return if (names.isEmpty()) "/" else "/" + names.joinToString("/")
    }

    private fun Memory.pathRelativeToTenant(): String =
        (parentMemory?.pathRelativeToTenant()?.trimEnd('/')
            ?: parentFolder?.pathRelativeToTenant()?.trimEnd('/')
            ?: LibraryScopes.pathPrefix(LibraryScopes.scope(this)).trimEnd('/')) + "/" + name

    private fun TenantFolder.pathRelativeToTenant(): String =
        (parentFolder?.pathRelativeToTenant()?.trimEnd('/')
            ?: LibraryScopes.pathPrefix(LibraryScopes.scope(this)).trimEnd('/')) + "/" + name

    private fun accessCookie(value: String, maxAge: Int = Duration.ofMinutes(tokenConfig.accessTokenTtlMinutes).seconds.toInt()): NewCookie =
        shareCookie(SHARE_ACCESS_COOKIE, value, maxAge)

    private fun refreshCookie(value: String, maxAge: Int = Duration.ofMinutes(tokenConfig.refreshTokenTtlMinutes).seconds.toInt()): NewCookie =
        shareCookie(SHARE_REFRESH_COOKIE, value, maxAge)

    private fun shareCookie(name: String, value: String, maxAge: Int): NewCookie =
        NewCookie.Builder(name)
            .value(value)
            .path("/api/public-shares")
            .maxAge(maxAge)
            .secure(cookieConfig.secure)
            .httpOnly(true)
            .sameSite(NewCookie.SameSite.STRICT)
            .build()

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun tenant(): Tenant =
        requestContext.tenant ?: throw NotFoundException("Tenant not found")

    private fun user(): User =
        requestContext.user ?: throw UnauthorizedException("Authentication required")

    private fun memoryShareShell(memory: Memory): MemoryShare =
        MemoryShare().also {
            it.tenant = memory.tenant
            it.rootMemory = memory
            it.role = MemoryShareRole.VIEWER
            it.passwordHash = null
            it.createdBy = memory.owner
        }

    private fun optionalShareName(value: String?): String? =
        value?.trim()?.takeIf { it.isNotBlank() }?.also {
            if (it.length > 120) throw BadRequestException("share name is too long")
        }

    private fun optionalPasswordHash(value: String?): String? {
        val password = value?.trim().orEmpty()
        if (password.isBlank()) return null
        if (password.length < 12) throw BadRequestException("password does not meet password rules")
        return passwordService.hash(password)
    }

    private fun updateFieldPresent(field: String): Boolean =
        routingContext.get<Set<String>>(UpdateMemoryShareRequestReader.PRESENT_FIELDS_KEY)?.contains(field) == true

    private fun canExposePublicRootMemory(access: ShareAccess): Boolean =
        access.share.role != MemoryShareRole.UPLOADER &&
            (access.authenticatedUser != null || access.shareIdentity != null || access.canViewGallery)

    private fun com.veyq.api.generated.model.MemoryShareRole.toDomain(): MemoryShareRole =
        MemoryShareRole.valueOf(value)

    private fun MemoryShareRole.toApi(): com.veyq.api.generated.model.MemoryShareRole =
        com.veyq.api.generated.model.MemoryShareRole.decode(name)

    private fun Instant.toApiOffsetDateTime(): OffsetDateTime =
        OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    data class ShareTokenResponse(
        val summary: PublicShareSummary,
        val accessCookie: NewCookie,
        val refreshCookie: NewCookie,
    )

    private data class ShareTokens(
        val accessToken: String,
        val refreshToken: String,
    )

    private data class ShareAccess(
        val share: MemoryShare,
        val shareIdentity: MemoryShareIdentity?,
        val authenticatedUser: User?,
        val canViewGallery: Boolean,
        val canUpload: Boolean,
        val canEdit: Boolean,
        val canDelete: Boolean,
    )

    companion object {
        const val SHARE_ACCESS_COOKIE = "VEYQ_SHARE_ACCESS_TOKEN"
        const val SHARE_REFRESH_COOKIE = "VEYQ_SHARE_REFRESH_TOKEN"
    }
}
