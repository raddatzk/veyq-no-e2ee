package com.veyq.api.memory

import com.veyq.api.generated.api.MemoriesApi
import com.veyq.api.generated.model.CreateMemoryRequest
import com.veyq.api.generated.model.MoveMemoryRequest
import com.veyq.api.generated.model.UpdateMemoryJournalRequest
import com.veyq.api.generated.model.UpdateMemoryRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response
import java.io.InputStream
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@ApplicationScoped
class MemoryResource @Inject constructor(
    private val memoryService: MemoryService,
    private val memoryNavigationContextService: MemoryNavigationContextService,
    private val memoryJournalService: MemoryJournalService,
) : MemoriesApi {
    override fun listMemories(tenantName: String): Response =
        Response.ok(memoryService.listRootMemories()).build()

    override fun getMemoryNavigationContext(tenantName: String, path: String): Response =
        Response.ok(memoryNavigationContextService.navigationContext(path)).build()

    override fun createMemory(tenantName: String, request: CreateMemoryRequest): Response =
        Response.ok(memoryService.createMemory(request)).build()

    override fun getMemory(tenantName: String, memoryId: String): Response =
        Response.ok(memoryService.getMemory(memoryId)).build()

    override fun getMemoryHeader(tenantName: String, memoryId: String): Response =
        Response.ok(memoryService.getMemoryHeader(memoryId)).build()

    override fun getMemoryJournal(tenantName: String, memoryId: String): Response {
        val memory = memoryService.resolveVisibleMemory(memoryId)
        return Response.ok(memoryJournalService.getJournal(memory)).build()
    }

    override fun createMemoryJournalLiveSession(tenantName: String, memoryId: String): Response {
        val memory = memoryService.resolveVisibleMemory(memoryId)
        return Response.ok(memoryJournalService.createLiveSession(memory)).build()
    }

    override fun getMemoryByPath(tenantName: String, path: String): Response =
        Response.ok(memoryService.getMemoryByPath(path)).build()

    override fun updateMemory(tenantName: String, memoryId: String, request: UpdateMemoryRequest): Response =
        Response.ok(memoryService.updateMemory(memoryId, request)).build()

    override fun moveMemory(tenantName: String, memoryId: String, request: MoveMemoryRequest): Response =
        Response.ok(memoryService.moveMemory(memoryId, request)).build()

    override fun updateMemoryJournal(tenantName: String, memoryId: String, updateMemoryJournalRequest: UpdateMemoryJournalRequest): Response {
        val memory = memoryService.resolveVisibleMemory(memoryId)
        return Response.ok(
            memoryJournalService.updateJournal(
                memory,
                updateMemoryJournalRequest.markdown,
                MemoryJournalService.JournalValidationContext(memory),
            )
        ).build()
    }

    override fun deleteMemory(tenantName: String, memoryId: String): Response {
        memoryService.deleteMemory(memoryId)
        return Response.noContent().build()
    }

    override fun favoriteMemory(tenantName: String, memoryId: String): Response =
        Response.ok(memoryService.favoriteMemory(memoryId)).build()

    override fun unfavoriteMemory(tenantName: String, memoryId: String): Response =
        Response.ok(memoryService.unfavoriteMemory(memoryId)).build()

    override fun listChildMemories(tenantName: String, memoryId: String): Response =
        Response.ok(memoryService.listChildren(memoryId)).build()

    override fun setMemoryCover(
        tenantName: String,
        memoryId: String,
        cropX: Double?,
        cropY: Double?,
        cropWidth: Double?,
        cropHeight: Double?,
        fileInputStream: InputStream?,
        mediaId: String?,
    ): Response {
        val temp = fileInputStream?.let { Files.createTempFile("veyq-memory-cover-", ".tmp") }
        return try {
            if (fileInputStream != null && temp != null) {
                fileInputStream.use { input -> Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING) }
            }
            val upload = temp?.let { GeneratedCoverUpload(it) }
            Response.ok(
                memoryService.setCover(
                    memoryId,
                    upload?.path,
                    upload?.fileName(),
                    mediaId,
                    cropX,
                    cropY,
                    cropWidth,
                    cropHeight,
                    upload?.size(),
                    upload?.contentType(),
                )
            ).build()
        } finally {
            if (temp != null) Files.deleteIfExists(temp)
        }
    }

    override fun memoryCoverPreview(tenantName: String, memoryId: String): Response =
        memoryService.coverPreview(memoryId)

    override fun removeMemoryCover(tenantName: String, memoryId: String): Response =
        Response.ok(memoryService.clearCover(memoryId)).build()

    private class GeneratedCoverUpload(val path: Path) {
        fun fileName(): String = "cover"
        fun size(): Long = Files.size(path)
        fun contentType(): String? =
            detectContentType(path)
                ?: Files.newInputStream(path).use { input -> URLConnection.guessContentTypeFromStream(input) }
                ?: Files.probeContentType(path)

        private fun detectContentType(path: Path): String? {
            val header = ByteArray(16)
            val read = Files.newInputStream(path).use { input -> input.read(header) }
            if (read >= 3 &&
                header[0] == 0xFF.toByte() &&
                header[1] == 0xD8.toByte() &&
                header[2] == 0xFF.toByte()
            ) {
                return "image/jpeg"
            }
            if (read >= 8 &&
                header[0] == 0x89.toByte() &&
                header[1] == 'P'.code.toByte() &&
                header[2] == 'N'.code.toByte() &&
                header[3] == 'G'.code.toByte()
            ) {
                return "image/png"
            }
            return null
        }
    }
}
