package com.lunva.api.library.media

import com.lunva.api.auth.BadRequestException
import com.lunva.api.auth.NotFoundException
import com.lunva.api.domain.TenantRepo
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Comparator
import java.util.Properties
import kotlin.io.use
import kotlin.use

@ApplicationScoped
class MediaCleanup @Inject constructor(
    private val mediaConfig: MediaConfig,
    private val tenantRepo: TenantRepo
) {
    private val log: Logger = Logger.getLogger(MediaCleanup::class.java)

    @Scheduled(cron = "{lunva.media.upload-session-cleanup-cron}")
    fun cleanupUploadSessions() {
        val expiresBefore = Instant.now().minus(mediaConfig.uploadSessionTtlHours, ChronoUnit.HOURS)
        uploadSessions()
            .filter { it.updatedAt.isBefore(expiresBefore) }
            .forEach { session ->
                try {
                    deleteRecursively(uploadDirectory(session.uploadId))
                } catch (e: Exception) {
                    log.warn("Failed to cleanup upload session ${session.uploadId}", e)
                }
            }
    }

    private fun uploadSessions(): List<UploadSession> {
        val root = storagePath("_uploads")
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .map { it.resolve("session.properties") }
                .filter { Files.isRegularFile(it) }
                .toList()
                .mapNotNull { path ->
                    try {
                        loadUploadSession(path)
                    } catch (e: Exception) {
                        log.warn("Ignoring invalid upload session $path", e)
                        null
                    }
                }
        }
    }

    private fun storagePath(storageKey: String): Path {
        val root = mediaConfig.storageDir.toAbsolutePath().normalize()
        val path = root.resolve(storageKey).normalize()
        if (!path.startsWith(root)) throw BadRequestException("Invalid media storage key")
        return path
    }

    private fun loadUploadSession(path: Path): UploadSession {
        val properties = Properties()
        Files.newInputStream(path).use { input -> properties.load(input) }
        val updatedAt = properties.getProperty("updatedAt")?.let { Instant.parse(it) }
            ?: Files.getLastModifiedTime(path).toInstant()
        val createdAt = properties.getProperty("createdAt")?.let { Instant.parse(it) }
            ?: updatedAt
        return try {
            MediaUploadSessions.fromProperties(
                properties = properties,
                updatedAtFallback = updatedAt,
                createdAtFallback = createdAt,
                tenantNameFallback = { tenantId -> tenantRepo.findById(tenantId)?.name },
            )
        } catch (e: Exception) {
            throw NotFoundException("Upload not found")
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun uploadDirectory(uploadId: String): Path =
        storagePath("_uploads/$uploadId")

}
