package com.veyq.app.shared.storage

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The blind blob store: opaque (already-encrypted) bytes live here, NOT in the relational DB — so the DB
 * stays lean (metadata + manifest only) and the storage backend is swappable. The store never interprets
 * the bytes (they are AEAD ciphertext / sealed frames). Today a local filesystem (a directory, which can be
 * a mounted volume); a future S3/MinIO impl (and per-tenant buckets for SaaS) drops in behind this interface.
 */
interface BlobStore {
    fun put(key: String, bytes: ByteArray)
    fun get(key: String): ByteArray?
    fun delete(key: String)
}

/**
 * Filesystem-backed blob store: each blob is a file under the configured root (`<root>/<key>`). Keys are
 * server-generated (uploadId/index), so no path-traversal — but we still reject `..` defensively.
 */
@ApplicationScoped
class FilesystemBlobStore(
    @ConfigProperty(name = "veyq.blob-store.dir", defaultValue = "build/veyq-blobs") private val rootDir: String,
) : BlobStore {
    private val root: Path = Path.of(rootDir).toAbsolutePath().normalize()

    private fun resolve(key: String): Path {
        require(!key.contains("..")) { "invalid blob key" }
        val p = root.resolve(key).normalize()
        require(p.startsWith(root)) { "blob key escapes the store root" }
        return p
    }

    override fun put(key: String, bytes: ByteArray) {
        val path = resolve(key)
        Files.createDirectories(path.parent)
        val tmp = Files.createTempFile(path.parent, ".upload", ".tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) // idempotent overwrite
    }

    override fun get(key: String): ByteArray? {
        val path = resolve(key)
        return if (Files.exists(path)) Files.readAllBytes(path) else null
    }

    override fun delete(key: String) {
        Files.deleteIfExists(resolve(key))
    }
}
