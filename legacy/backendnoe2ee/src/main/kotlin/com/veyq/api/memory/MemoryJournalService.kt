package com.veyq.api.memory

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ConflictException
import com.veyq.api.auth.ForbiddenException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.auth.UnauthorizedException
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.domain.MediaRepo
import com.veyq.api.domain.Memory
import com.veyq.api.domain.MemoryJournal
import com.veyq.api.domain.MemoryJournalRepo
import com.veyq.api.domain.MemoryPlaylistRepo
import com.veyq.api.domain.MemoryRepo
import com.veyq.api.domain.MemoryTrackRepo
import com.veyq.api.domain.TenantRepo
import com.veyq.api.domain.User
import com.veyq.api.generated.model.MemoryJournalLiveSession
import com.veyq.api.generated.model.MemoryJournalSummary
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Singleton
class MemoryJournalService @Inject constructor(
    private val journalRepo: MemoryJournalRepo,
    private val mediaRepo: MediaRepo,
    private val memoryRepo: MemoryRepo,
    private val tenantRepo: TenantRepo,
    private val trackRepo: MemoryTrackRepo,
    private val playlistRepo: MemoryPlaylistRepo,
    private val requestContext: RequestContext,
    private val objectMapper: ObjectMapper,
    private val journalLiveConfig: JournalLiveConfig,
) {
    fun getJournal(memory: Memory): MemoryJournalSummary =
        journalRepo.findByMemory(memory)?.let(::toSummary) ?: emptySummary(memory)

    fun requireNoJournalReference(memory: Memory, kind: JournalReferenceKind, targetId: String) {
        val managedMemory = memoryRepo.findByTenantAndPublicId(memory.tenant, memory.publicId)
            ?: throw NotFoundException("Memory not found")
        val markdown = journalRepo.findByMemory(managedMemory)?.markdown ?: return
        val referenced = journalReferences(markdown)
            .any { reference -> reference.kind == kind && targetId in reference.targetIds }
        if (referenced) {
            throw ConflictException(
                "Cannot delete ${kind.displayName} while it is referenced by the memory journal",
                "MEMORY_JOURNAL_REFERENCE_CONFLICT",
            )
        }
    }

    fun createLiveSession(memory: Memory): MemoryJournalLiveSession {
        val user = requestContext.user ?: throw UnauthorizedException("Invalid session")
        val managedMemory = memoryRepo.findByTenantAndPublicId(memory.tenant, memory.publicId)
            ?: throw NotFoundException("Memory not found")
        val expiresAt = Instant.now().plus(Duration.ofMinutes(journalLiveConfig.tokenTtlMinutes))
        val documentId = internalDocumentId(managedMemory)
        val token = signToken(
            JournalLiveTokenPayload(
                documentId = documentId,
                tenantName = managedMemory.tenant.name,
                memoryId = managedMemory.publicId,
                shareId = null,
                shareRootMemoryId = null,
                displayName = user.displayName,
                color = presenceColor(user.permalink),
                canEdit = true,
                expiresAt = expiresAt.epochSecond,
            )
        )
        return liveSession(managedMemory, documentId, token, expiresAt, user.displayName, presenceColor(user.permalink))
    }

    fun createShareLiveSession(
        memory: Memory,
        shareId: String,
        shareRoot: Memory,
        displayName: String,
    ): MemoryJournalLiveSession {
        val managedMemory = memoryRepo.findByTenantAndPublicId(memory.tenant, memory.publicId)
            ?: throw NotFoundException("Memory not found")
        val managedShareRoot = memoryRepo.findByTenantAndPublicId(shareRoot.tenant, shareRoot.publicId)
            ?: throw NotFoundException("Memory not found")
        val expiresAt = Instant.now().plus(Duration.ofMinutes(journalLiveConfig.tokenTtlMinutes))
        val documentId = shareDocumentId(shareId, managedMemory)
        val colorSeed = "$shareId:$displayName"
        val token = signToken(
            JournalLiveTokenPayload(
                documentId = documentId,
                tenantName = managedMemory.tenant.name,
                memoryId = managedMemory.publicId,
                shareId = shareId,
                shareRootMemoryId = managedShareRoot.publicId,
                displayName = displayName,
                color = presenceColor(colorSeed),
                canEdit = true,
                expiresAt = expiresAt.epochSecond,
            )
        )
        return liveSession(managedMemory, documentId, token, expiresAt, displayName, presenceColor(colorSeed))
    }

    fun validateLiveSession(token: String, documentId: String): JournalLiveSessionDocument =
        validateLiveSession(token, documentId, allowExpiredToken = false)

    private fun validateLiveSession(token: String, documentId: String, allowExpiredToken: Boolean): JournalLiveSessionDocument {
        val payload = verifyToken(token, allowExpiredToken)
        if (payload.documentId != documentId) throw ForbiddenException("Journal live session document mismatch")
        if (!payload.canEdit) throw ForbiddenException("Journal live session is read-only")
        val tenant = tenantRepo.findByName(payload.tenantName) ?: throw NotFoundException("Memory not found")
        val memory = memoryRepo.findByTenantAndPublicId(tenant, payload.memoryId)
            ?: throw NotFoundException("Memory not found")
        return JournalLiveSessionDocument(
            memory = memory,
            context = validationContext(payload, memory),
            documentId = payload.documentId,
            markdown = getJournal(memory).markdown,
            displayName = payload.displayName,
            color = payload.color,
            expiresAt = OffsetDateTime.ofInstant(Instant.ofEpochSecond(payload.expiresAt), ZoneOffset.UTC),
            canEdit = payload.canEdit,
        )
    }

    @Transactional
    fun persistLiveSnapshot(
        token: String,
        documentId: String,
        markdown: String?,
        allowExpiredToken: Boolean = false,
    ): MemoryJournalSummary {
        val document = validateLiveSession(token, documentId, allowExpiredToken)
        return updateJournal(document.memory, markdown, document.context)
    }

    @Transactional
    fun updateJournal(memory: Memory, markdown: String?, context: JournalValidationContext): MemoryJournalSummary {
        val managedMemory = memoryRepo.findByTenantAndPublicId(memory.tenant, memory.publicId)
            ?: throw NotFoundException("Memory not found")
        val managedShareRoot = context.shareRoot?.let { root ->
            memoryRepo.findByTenantAndPublicId(root.tenant, root.publicId) ?: throw NotFoundException("Memory not found")
        }
        val managedContext = context.copy(memory = managedMemory, shareRoot = managedShareRoot)
        val normalized = markdown ?: throw BadRequestException("markdown is required", "MEMORY_JOURNAL_MARKDOWN_REQUIRED")
        if (normalized.length > MAX_MARKDOWN_LENGTH) {
            throw BadRequestException("markdown is too long", "MEMORY_JOURNAL_MARKDOWN_TOO_LONG")
        }
        validateEmbeds(normalized, managedContext)
        val journal = journalRepo.findByMemory(managedMemory) ?: MemoryJournal().also {
            it.memory = managedMemory
            it.markdown = ""
            journalRepo.persist(it)
        }
        journal.markdown = normalized
        journalRepo.flush()
        return toSummary(journal)
    }

    private fun validateEmbeds(markdown: String, context: JournalValidationContext) {
        VEYQ_EMBED.findAll(markdown).forEach { match ->
            val kind = match.groupValues[1]
            val targetIds = targetIds(match.groupValues[2])
            if (targetIds.isEmpty()) {
                throw BadRequestException("Invalid veyq journal embed", "MEMORY_JOURNAL_EMBED_INVALID")
            }
            when (kind) {
                "media" -> validateMediaEmbed(targetIds, context)
                "chapter" -> validateMemoryEmbed(singleTarget(targetIds), context)
                "playlist" -> validatePlaylistEmbed(singleTarget(targetIds), context)
                "track" -> validateTrackEmbed(targetIds, context)
                else -> throw BadRequestException("Unsupported veyq journal embed", "MEMORY_JOURNAL_EMBED_UNSUPPORTED")
            }
        }
    }

    private fun validateMediaEmbed(mediaIds: List<String>, context: JournalValidationContext) {
        mediaIds.forEach { mediaId ->
            validateSingleMediaEmbed(mediaId, context)
        }
    }

    private fun validateSingleMediaEmbed(mediaId: String, context: JournalValidationContext) {
        val media = mediaRepo.findByTenantAndPublicId(context.memory.tenant, mediaId)
            ?: throw NotFoundException("Media not found")
        val ownerMemory = media.memory ?: throw NotFoundException("Media not found")
        val allowed = if (context.allowShareSubtreeMedia) {
            context.shareRoot != null && isDescendantOrSelf(ownerMemory, context.shareRoot)
        } else {
            ownerMemory.id == context.memory.id
        }
        if (!allowed) throw NotFoundException("Media not found")
    }

    private fun validateTrackEmbed(trackIds: List<String>, context: JournalValidationContext) {
        trackIds.forEach { trackId ->
            validateSingleTrackEmbed(trackId, context)
        }
    }

    private fun validateSingleTrackEmbed(trackId: String, context: JournalValidationContext) {
        val track = trackRepo.findByTenantAndPublicId(context.memory.tenant, trackId)
            ?: throw NotFoundException("Track not found")
        val allowed = if (context.allowShareSubtreeMedia) {
            context.shareRoot != null && isDescendantOrSelf(track.memory, context.shareRoot)
        } else {
            track.memory.id == context.memory.id
        }
        if (!allowed) throw NotFoundException("Track not found")
    }

    private fun validatePlaylistEmbed(playlistId: String, context: JournalValidationContext) {
        val playlist = playlistRepo.findByTenantAndPublicId(context.memory.tenant, playlistId)
            ?: throw NotFoundException("Playlist not found")
        val allowed = if (context.allowShareSubtreeMedia) {
            context.shareRoot != null && isDescendantOrSelf(playlist.memory, context.shareRoot)
        } else {
            playlist.memory.id == context.memory.id
        }
        if (!allowed) throw NotFoundException("Playlist not found")
    }

    private fun validateMemoryEmbed(memoryId: String, context: JournalValidationContext) {
        val target = memoryRepo.findByTenantAndPublicId(context.memory.tenant, memoryId)
            ?: throw NotFoundException("Memory not found")
        if (!isAllowedMemoryTarget(target, context)) throw NotFoundException("Memory not found")
    }

    private fun isAllowedMemoryTarget(target: Memory, context: JournalValidationContext): Boolean =
        if (context.shareRoot != null) {
            isDescendantOrSelf(target, context.shareRoot)
        } else {
            target.id != context.memory.id && isDescendantOrSelf(target, context.memory)
        }

    private fun isDescendantOrSelf(candidate: Memory, root: Memory): Boolean =
        candidate.id == root.id || generateSequence(candidate.parentMemory) { it.parentMemory }.any { it.id == root.id }

    private fun journalReferences(markdown: String): List<JournalReference> =
        VEYQ_EMBED.findAll(markdown)
            .mapNotNull { match ->
                val kind = JournalReferenceKind.fromToken(match.groupValues[1]) ?: return@mapNotNull null
                val targetIds = targetIds(match.groupValues[2])
                if (targetIds.isEmpty()) null else JournalReference(kind, targetIds)
            }
            .toList()

    private fun targetIds(target: String): List<String> =
        target.trim()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun singleTarget(targetIds: List<String>): String {
        if (targetIds.size != 1) {
            throw BadRequestException("Invalid veyq journal embed", "MEMORY_JOURNAL_EMBED_INVALID")
        }
        return targetIds.single()
    }

    private fun emptySummary(memory: Memory): MemoryJournalSummary =
        MemoryJournalSummary(memory.publicId, "", OffsetDateTime.ofInstant(memory.updatedAt, ZoneOffset.UTC))

    private fun toSummary(journal: MemoryJournal): MemoryJournalSummary =
        MemoryJournalSummary(journal.memory.publicId, journal.markdown, OffsetDateTime.ofInstant(journal.updatedAt, ZoneOffset.UTC))

    private fun liveSession(
        memory: Memory,
        documentId: String,
        token: String,
        expiresAt: Instant,
        displayName: String,
        color: String,
    ): MemoryJournalLiveSession =
        MemoryJournalLiveSession(
            memoryId = memory.publicId,
            documentId = documentId,
            websocketUrl = journalLiveConfig.websocketUrl,
            token = token,
            expiresAt = OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC),
            displayName = displayName,
            color = color,
            canEdit = true,
        )

    private fun validationContext(payload: JournalLiveTokenPayload, memory: Memory): JournalValidationContext {
        val shareRoot = payload.shareRootMemoryId?.let { rootId ->
            memoryRepo.findByTenantAndPublicId(memory.tenant, rootId) ?: throw NotFoundException("Memory not found")
        }
        return JournalValidationContext(
            memory = memory,
            shareRoot = shareRoot,
            allowShareSubtreeMedia = shareRoot != null,
        )
    }

    private fun signToken(payload: JournalLiveTokenPayload): String {
        requireConfiguredLiveTokenSecret()
        val encodedPayload = base64Url(objectMapper.writeValueAsBytes(payload))
        val signature = hmac(encodedPayload)
        return "$encodedPayload.$signature"
    }

    private fun requireConfiguredLiveTokenSecret() {
        if (
            journalLiveConfig.tokenSecret.isBlank() ||
            journalLiveConfig.tokenSecret == DEFAULT_LIVE_TOKEN_SECRET ||
            journalLiveConfig.sidecarSecret.orElse("").isBlank()
        ) {
            throw ForbiddenException("Journal live sync is not configured", "MEMORY_JOURNAL_LIVE_NOT_CONFIGURED")
        }
    }

    private fun verifyToken(token: String, allowExpiredToken: Boolean = false): JournalLiveTokenPayload {
        val payload = token.substringBefore('.', "")
        val signature = token.substringAfter('.', "")
        if (payload.isBlank() || signature.isBlank() || !constantTimeEquals(hmac(payload), signature)) {
            throw UnauthorizedException("Invalid journal live session")
        }
        val decoded = runCatching {
            objectMapper.readValue(Base64.getUrlDecoder().decode(payload), JournalLiveTokenPayload::class.java)
        }.getOrElse {
            throw UnauthorizedException("Invalid journal live session")
        }
        if (!allowExpiredToken && Instant.now().epochSecond >= decoded.expiresAt) {
            throw UnauthorizedException("Journal live session expired", "MEMORY_JOURNAL_LIVE_SESSION_EXPIRED")
        }
        return decoded
    }

    private fun hmac(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(journalLiveConfig.tokenSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return base64Url(mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun base64Url(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(StandardCharsets.UTF_8), right.toByteArray(StandardCharsets.UTF_8))

    private fun internalDocumentId(memory: Memory): String =
        "tenant/${memory.tenant.name}/memory/${memory.publicId}/journal"

    private fun shareDocumentId(shareId: String, memory: Memory): String =
        "share/$shareId/memory/${memory.publicId}/journal"

    private fun presenceColor(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(StandardCharsets.UTF_8))
        val hue = digest[0].toInt().and(0xff) * 360 / 255
        return hslToHex(hue.toDouble(), 0.62, 0.48)
    }

    private fun hslToHex(hue: Double, saturation: Double, lightness: Double): String {
        val chroma = (1 - kotlin.math.abs(2 * lightness - 1)) * saturation
        val x = chroma * (1 - kotlin.math.abs((hue / 60) % 2 - 1))
        val m = lightness - chroma / 2
        val (r1, g1, b1) = when {
            hue < 60 -> Triple(chroma, x, 0.0)
            hue < 120 -> Triple(x, chroma, 0.0)
            hue < 180 -> Triple(0.0, chroma, x)
            hue < 240 -> Triple(0.0, x, chroma)
            hue < 300 -> Triple(x, 0.0, chroma)
            else -> Triple(chroma, 0.0, x)
        }
        return "#%02x%02x%02x".format(((r1 + m) * 255).toInt(), ((g1 + m) * 255).toInt(), ((b1 + m) * 255).toInt())
    }

    data class JournalValidationContext(
        val memory: Memory,
        val shareRoot: Memory? = null,
        val allowShareSubtreeMedia: Boolean = false,
    )

    data class JournalLiveSessionDocument(
        val memory: Memory,
        val context: JournalValidationContext,
        val documentId: String,
        val markdown: String,
        val displayName: String,
        val color: String,
        val expiresAt: OffsetDateTime,
        val canEdit: Boolean,
    )

    data class JournalLiveTokenPayload(
        val documentId: String = "",
        val tenantName: String = "",
        val memoryId: String = "",
        val shareId: String? = null,
        val shareRootMemoryId: String? = null,
        val displayName: String = "",
        val color: String = "#5b6ee1",
        val canEdit: Boolean = false,
        val expiresAt: Long = 0,
    )

    enum class JournalReferenceKind(val token: String, val displayName: String) {
        Media("media", "media"),
        Track("track", "track"),
        Playlist("playlist", "playlist");

        companion object {
            fun fromToken(token: String): JournalReferenceKind? =
                entries.firstOrNull { it.token == token }
        }
    }

    private data class JournalReference(
        val kind: JournalReferenceKind,
        val targetIds: List<String>,
    )

    companion object {
        private const val MAX_MARKDOWN_LENGTH = 200_000
        private const val DEFAULT_LIVE_TOKEN_SECRET = "dev-only-change-me"
        private val VEYQ_EMBED = Regex("""(?<!:)::([A-Za-z][A-Za-z0-9-]*):([^\s\n]+)(?:[ \t]+[^\n]*?)?(?=\n|$|::(?:media|chapter|playlist|track):)""")
    }
}
