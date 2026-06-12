package com.veyq.api.library.media

import com.veyq.api.auth.BadRequestException
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator

@Singleton
class MediaStorageService @Inject constructor(
    private val mediaConfig: MediaConfig,
) {
    fun path(
        storageKey: String,
        invalidMessage: String = "Invalid media storage key",
        invalidCode: String = "MEDIA_STORAGE_KEY_INVALID",
    ): Path {
        val root = mediaConfig.storageDir.toAbsolutePath().normalize()
        val path = root.resolve(storageKey).normalize()
        if (!path.startsWith(root)) throw BadRequestException(invalidMessage, invalidCode)
        return path
    }

    fun writeAtomic(source: Path, storageKey: String, tempPrefix: String) {
        val target = path(storageKey)
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, tempPrefix, ".tmp")
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            throw e
        }
    }

    fun deleteIfExists(storageKey: String) {
        Files.deleteIfExists(path(storageKey))
    }

    fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
