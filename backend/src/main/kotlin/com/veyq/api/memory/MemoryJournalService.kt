package com.veyq.api.memory

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.domain.MediaRepo
import com.veyq.api.domain.Memory
import com.veyq.api.domain.MemoryJournal
import com.veyq.api.domain.MemoryJournalRepo
import com.veyq.api.domain.MemoryPlaylistRepo
import com.veyq.api.domain.MemoryRepo
import com.veyq.api.domain.MemoryTrackRepo
import com.veyq.api.generated.model.MemoryJournalSummary
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Singleton
class MemoryJournalService @Inject constructor(
    private val journalRepo: MemoryJournalRepo,
    private val mediaRepo: MediaRepo,
    private val memoryRepo: MemoryRepo,
    private val trackRepo: MemoryTrackRepo,
    private val playlistRepo: MemoryPlaylistRepo,
) {
    fun getJournal(memory: Memory): MemoryJournalSummary =
        journalRepo.findByMemory(memory)?.let(::toSummary) ?: emptySummary(memory)

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
        VEYQ_LINK.findAll(markdown).forEach { match ->
            val rawTarget = match.groupValues[1]
            val uri = runCatching { URI(rawTarget) }.getOrNull()
                ?: throw BadRequestException("Invalid veyq journal embed", "MEMORY_JOURNAL_EMBED_INVALID")
            when (uri.schemeSpecificPart.substringBefore("?").substringBefore("/")) {
                "media" -> validateMediaEmbed(uri, context)
                "memory" -> validateMemoryEmbed(uri, context)
                "memory-path" -> validateMemoryPathEmbed(uri, context)
                "playlist" -> validatePlaylistEmbed(uri, context)
                "map" -> validateMapEmbed(uri)
                "track" -> validateTrackEmbed(uri, context)
                else -> throw BadRequestException("Unsupported veyq journal embed", "MEMORY_JOURNAL_EMBED_UNSUPPORTED")
            }
        }
    }

    private fun validateMediaEmbed(uri: URI, context: JournalValidationContext) {
        val mediaIds = pathValue(uri, "media").split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (mediaIds.isEmpty()) {
            throw BadRequestException("Invalid veyq journal embed", "MEMORY_JOURNAL_EMBED_INVALID")
        }
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

    private fun validateTrackEmbed(uri: URI, context: JournalValidationContext) {
        val trackId = pathValue(uri, "track")
        val track = trackRepo.findByTenantAndPublicId(context.memory.tenant, trackId)
            ?: throw NotFoundException("Track not found")
        val allowed = if (context.allowShareSubtreeMedia) {
            context.shareRoot != null && isDescendantOrSelf(track.memory, context.shareRoot)
        } else {
            track.memory.id == context.memory.id
        }
        if (!allowed) throw NotFoundException("Track not found")
    }

    private fun validatePlaylistEmbed(uri: URI, context: JournalValidationContext) {
        val target = uri.schemeSpecificPart.substringBefore("?")
        if (target == "playlist") {
            validateRequiredQuery(uri, "url")
            return
        }
        val playlistId = pathValue(uri, "playlist")
        val playlist = playlistRepo.findByTenantAndPublicId(context.memory.tenant, playlistId)
            ?: throw NotFoundException("Playlist not found")
        val allowed = if (context.allowShareSubtreeMedia) {
            context.shareRoot != null && isDescendantOrSelf(playlist.memory, context.shareRoot)
        } else {
            playlist.memory.id == context.memory.id
        }
        if (!allowed) throw NotFoundException("Playlist not found")
    }

    private fun validateMemoryEmbed(uri: URI, context: JournalValidationContext) {
        val memoryId = pathValue(uri, "memory")
        val target = memoryRepo.findByTenantAndPublicId(context.memory.tenant, memoryId)
            ?: throw NotFoundException("Memory not found")
        if (!isAllowedMemoryTarget(target, context)) throw NotFoundException("Memory not found")
    }

    private fun validateMemoryPathEmbed(uri: URI, context: JournalValidationContext) {
        val path = URLDecoder.decode(pathValue(uri, "memory-path"), StandardCharsets.UTF_8)
            .trim()
            .trim('/')
        var target = context.shareRoot ?: context.memory
        if (path.isNotBlank()) {
            path.split('/').filter { it.isNotBlank() }.forEach { segment ->
                target = memoryRepo.listChildren(context.memory.tenant, target).firstOrNull { it.name == segment }
                    ?: throw NotFoundException("Memory not found")
            }
        }
        if (!isAllowedMemoryTarget(target, context)) throw NotFoundException("Memory not found")
    }

    private fun isAllowedMemoryTarget(target: Memory, context: JournalValidationContext): Boolean =
        if (context.shareRoot != null) {
            isDescendantOrSelf(target, context.shareRoot)
        } else {
            target.id != context.memory.id && isDescendantOrSelf(target, context.memory)
        }

    private fun validateRequiredQuery(uri: URI, key: String) {
        if (query(uri)[key].isNullOrBlank()) {
            throw BadRequestException("Invalid veyq journal embed", "MEMORY_JOURNAL_EMBED_INVALID")
        }
    }

    private fun validateMapEmbed(uri: URI) {
        val params = query(uri)
        params["lat"]?.toDoubleOrNull() ?: throw BadRequestException("Invalid veyq journal embed", "MEMORY_JOURNAL_EMBED_INVALID")
        params["lng"]?.toDoubleOrNull() ?: throw BadRequestException("Invalid veyq journal embed", "MEMORY_JOURNAL_EMBED_INVALID")
        params["zoom"]?.toDoubleOrNull()
    }

    private fun pathValue(uri: URI, type: String): String {
        val prefix = "$type/"
        val value = uri.schemeSpecificPart.substringBefore("?")
        if (!value.startsWith(prefix) || value.length <= prefix.length) {
            throw BadRequestException("Invalid veyq journal embed", "MEMORY_JOURNAL_EMBED_INVALID")
        }
        return value.removePrefix(prefix).trim()
    }

    private fun query(uri: URI): Map<String, String> =
        uri.rawSchemeSpecificPart.substringAfter("?", "")
            .split('&')
            .filter { it.isNotBlank() && it.contains('=') }
            .associate {
                val key = it.substringBefore('=')
                val value = URLDecoder.decode(it.substringAfter('='), StandardCharsets.UTF_8)
                key to value
            }

    private fun isDescendantOrSelf(candidate: Memory, root: Memory): Boolean =
        candidate.id == root.id || generateSequence(candidate.parentMemory) { it.parentMemory }.any { it.id == root.id }

    private fun emptySummary(memory: Memory): MemoryJournalSummary =
        MemoryJournalSummary(memory.publicId, "", OffsetDateTime.ofInstant(memory.updatedAt, ZoneOffset.UTC))

    private fun toSummary(journal: MemoryJournal): MemoryJournalSummary =
        MemoryJournalSummary(journal.memory.publicId, journal.markdown, OffsetDateTime.ofInstant(journal.updatedAt, ZoneOffset.UTC))

    data class JournalValidationContext(
        val memory: Memory,
        val shareRoot: Memory? = null,
        val allowShareSubtreeMedia: Boolean = false,
    )

    companion object {
        private const val MAX_MARKDOWN_LENGTH = 200_000
        private val VEYQ_LINK = Regex("""!?\[[^\]]*]\((veyq:[^)]+)\)""")
    }
}
