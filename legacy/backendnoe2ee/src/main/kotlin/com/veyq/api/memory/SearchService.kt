package com.veyq.api.memory

import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.common.apiPathSegment as url
import com.veyq.api.domain.Memory
import com.veyq.api.domain.MemoryJournalRepo
import com.veyq.api.domain.MemoryRepo
import com.veyq.api.domain.TenantFolder
import com.veyq.api.domain.TenantFolderRepo
import com.veyq.api.domain.TenantGroupMemberRepo
import com.veyq.api.domain.TenantGroupRepo
import com.veyq.api.generated.model.CoverCrop
import com.veyq.api.generated.model.MemoryScope
import com.veyq.api.generated.model.SearchResultSummary
import com.veyq.api.library.LibraryScopeKey
import com.veyq.api.library.LibraryScopes
import com.veyq.api.library.LibraryService
import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class SearchService @Inject constructor(
    private val memoryRepo: MemoryRepo,
    private val folderRepo: TenantFolderRepo,
    private val groupRepo: TenantGroupRepo,
    private val groupMemberRepo: TenantGroupMemberRepo,
    private val memoryJournalRepo: MemoryJournalRepo,
    requestContext: RequestContext,
) : LibraryService(folderRepo, groupRepo, groupMemberRepo, requestContext) {
    fun search(query: String, requestedLimit: Int?): List<SearchResultSummary> {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.length < MIN_QUERY_LENGTH) return emptyList()
        val limit = requestedLimit?.coerceIn(1, MAX_RESULTS) ?: DEFAULT_RESULTS

        val memoryResults = memoryRepo.list("tenant", tenant())
            .filter(::isMemoryVisible)
            .mapNotNull { memory -> memoryResult(memory, normalizedQuery) }
        val folderResults = folderRepo.listByTenant(tenant())
            .filter(::isFolderVisible)
            .mapNotNull { folder -> folderResult(folder, normalizedQuery) }

        return (memoryResults + folderResults)
            .sortedWith(
                compareBy<ScoredSearchResult> { it.rank }
                    .thenBy { it.summary.displayName.lowercase() }
                    .thenBy { it.summary.path.lowercase() }
            )
            .take(limit)
            .map { it.summary }
    }

    private fun memoryResult(memory: Memory, query: String): ScoredSearchResult? {
        val path = memory.path()
        val titleMatches = titleMatches(memory.displayName, memory.name, query)
        val pathMatches = contains(path, query)
        val journalMarkdown = memoryJournalRepo.findByMemory(memory)?.markdown.orEmpty()
        val journalMatches = contains(journalMarkdown, query)
        if (!titleMatches.any && !pathMatches && !journalMatches) return null

        val matchTypes = linkedSetOf<SearchResultSummary.MatchTypes>()
        if (titleMatches.any) matchTypes += SearchResultSummary.MatchTypes.TITLE
        if (pathMatches) matchTypes += SearchResultSummary.MatchTypes.PATH
        if (journalMatches) matchTypes += SearchResultSummary.MatchTypes.JOURNAL

        return ScoredSearchResult(
            summary = SearchResultSummary(
                kind = SearchResultSummary.Kind.MEMORY,
                id = memory.publicId,
                displayName = memory.displayName,
                path = path,
                scope = memory.scope().toApiScope(),
                matchTypes = matchTypes,
                coverUrl = memory.coverUrl(),
                coverCrop = memory.coverCrop(),
                journalSnippet = if (journalMatches) journalSnippet(journalMarkdown, query) else null,
            ),
            rank = rank(titleMatches, pathMatches, journalMatches),
        )
    }

    private fun folderResult(folder: TenantFolder, query: String): ScoredSearchResult? {
        val path = folder.path()
        val titleMatches = titleMatches(folder.displayName, folder.name, query)
        val pathMatches = contains(path, query)
        if (!titleMatches.any && !pathMatches) return null

        val matchTypes = linkedSetOf<SearchResultSummary.MatchTypes>()
        if (titleMatches.any) matchTypes += SearchResultSummary.MatchTypes.TITLE
        if (pathMatches) matchTypes += SearchResultSummary.MatchTypes.PATH

        return ScoredSearchResult(
            summary = SearchResultSummary(
                kind = SearchResultSummary.Kind.FOLDER,
                id = folder.publicId,
                displayName = folder.displayName,
                path = path,
                scope = folder.scope().toApiScope(),
                matchTypes = matchTypes,
            ),
            rank = rank(titleMatches, pathMatches, journalMatches = false),
        )
    }

    private fun titleMatches(displayName: String, name: String, query: String): TitleMatch {
        val display = displayName.lowercase()
        val slug = name.lowercase()
        return TitleMatch(
            exact = display == query || slug == query,
            prefix = display.startsWith(query) || slug.startsWith(query),
            any = display.contains(query) || slug.contains(query),
        )
    }

    private fun rank(title: TitleMatch, pathMatches: Boolean, journalMatches: Boolean): Int =
        when {
            title.exact -> 0
            title.prefix -> 1
            title.any || pathMatches -> 2
            journalMatches -> 3
            else -> 4
        }

    private fun contains(value: String, query: String): Boolean =
        value.lowercase().contains(query)

    private fun isMemoryVisible(memory: Memory): Boolean =
        memory.tenant.id == tenant().id && isScopeVisible(memory.scope())

    private fun Memory.path(): String =
        (parentMemory?.path()?.trimEnd('/')
            ?: parentFolder?.path()?.trimEnd('/')
            ?: LibraryScopes.pathPrefix(scope()).trimEnd('/')) + "/" + name

    private fun Memory.coverUrl(): String? =
        coverStorageKey?.let { storageKey ->
            "/api/${url(tenant().name)}/memories/${url(publicId)}/cover/preview?fp=${coverFingerprint(storageKey)}"
        }
            ?: coverMedia?.let { media -> "/api/${url(tenant().name)}/memory-media/${url(media.publicId)}/preview" }

    private fun coverFingerprint(storageKey: String): String =
        Integer.toUnsignedString(storageKey.hashCode(), 36)

    private fun Memory.coverCrop(): CoverCrop? {
        val x = coverCropX ?: return null
        val y = coverCropY ?: return null
        val width = coverCropWidth ?: return null
        val height = coverCropHeight ?: return null
        return CoverCrop(x, y, width, height)
    }

    private fun LibraryScopeKey.toApiScope(): MemoryScope =
        when (this) {
            LibraryScopeKey.Tenant -> MemoryScope(MemoryScope.Type.TENANT, null)
            is LibraryScopeKey.Group -> MemoryScope(MemoryScope.Type.GROUP, group.name)
            is LibraryScopeKey.User -> MemoryScope(MemoryScope.Type.USER, null)
        }

    private fun journalSnippet(markdown: String, query: String): String {
        val text = markdown
            .replace(Regex("""[`*_>#\[\]()]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (text.isBlank()) return ""
        val index = text.lowercase().indexOf(query).takeIf { it >= 0 } ?: return text.take(SNIPPET_LENGTH)
        val start = (index - SNIPPET_CONTEXT).coerceAtLeast(0)
        val end = (index + query.length + SNIPPET_CONTEXT).coerceAtMost(text.length)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < text.length) "..." else ""
        return prefix + text.substring(start, end).trim() + suffix
    }

    private data class TitleMatch(
        val exact: Boolean,
        val prefix: Boolean,
        val any: Boolean,
    )

    private data class ScoredSearchResult(
        val summary: SearchResultSummary,
        val rank: Int,
    )

    private companion object {
        private const val MIN_QUERY_LENGTH = 2
        private const val DEFAULT_RESULTS = 20
        private const val MAX_RESULTS = 50
        private const val SNIPPET_LENGTH = 120
        private const val SNIPPET_CONTEXT = 48
    }
}
