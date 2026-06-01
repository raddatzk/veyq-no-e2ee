package com.veyq.api.memory

import com.veyq.api.common.apiPathSegment as url
import com.veyq.api.domain.MediaRepo
import com.veyq.api.domain.Memory
import com.veyq.api.domain.MemoryRepo
import com.veyq.api.domain.MemoryShare
import com.veyq.api.generated.model.PublicShareCoverCrop
import com.veyq.api.generated.model.PublicShareMemoryHeaderSummary
import com.veyq.api.generated.model.PublicShareMemorySummary
import jakarta.inject.Singleton

@Singleton
class PublicShareMemoryMapper(
    private val memoryRepo: MemoryRepo,
    private val mediaRepo: MediaRepo,
) {
    fun toSummary(memory: Memory, share: MemoryShare): PublicShareMemorySummary =
        PublicShareMemorySummary(
            id = memory.publicId,
            name = memory.name,
            displayName = memory.displayName,
            path = sharePath(memory, share.rootMemory),
            parentId = memory.parentMemory?.publicId ?: "",
            childCount = memoryRepo.listChildren(memory.tenant, memory).size,
            mediaCount = mediaRepo.listByMemory(memory).size,
            coverUrl = publicCoverUrl(memory, share.publicId),
            coverMediaId = memory.coverMedia?.publicId,
            coverCrop = coverCrop(memory),
            startDate = memory.startDate,
            endDate = memory.endDate,
        )

    fun toHeader(memory: Memory, share: MemoryShare, descendants: List<Memory>): PublicShareMemoryHeaderSummary =
        PublicShareMemoryHeaderSummary(
            id = memory.publicId,
            displayName = memory.displayName,
            path = sharePath(memory, share.rootMemory),
            coverUrl = publicCoverUrl(memory, share.publicId),
            coverCrop = coverCrop(memory),
            startDate = memory.startDate,
            endDate = memory.endDate,
            directMediaCount = mediaRepo.listByMemory(memory).size,
            descendantMediaCount = descendants.sumOf { mediaRepo.listByMemory(it).size },
            childMemoryCount = memoryRepo.listChildren(memory.tenant, memory).size,
        )

    private fun publicCoverUrl(memory: Memory, shareId: String): String? =
        memory.coverStorageKey?.let { "/api/public-shares/${url(shareId)}/memories/${url(memory.publicId)}/cover/content" }
            ?: memory.coverMedia?.publicId?.let { "/api/public-shares/${url(shareId)}/media/${url(it)}/content" }

    private fun coverCrop(memory: Memory): PublicShareCoverCrop? {
        val x = memory.coverCropX ?: return null
        val y = memory.coverCropY ?: return null
        val width = memory.coverCropWidth ?: return null
        val height = memory.coverCropHeight ?: return null
        return PublicShareCoverCrop(x, y, width, height)
    }

    private fun sharePath(memory: Memory, root: Memory): String {
        val names = generateSequence(memory) { it.parentMemory }
            .toList()
            .asReversed()
            .dropWhile { it.id == root.id }
            .map { it.name }
        return if (names.isEmpty()) "/" else "/" + names.joinToString("/")
    }
}
