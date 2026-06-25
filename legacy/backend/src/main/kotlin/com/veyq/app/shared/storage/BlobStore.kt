package com.veyq.app.shared.storage

import com.veyq.app.shared.config.BlobStoreConfig
import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.GetPresignedObjectUrlArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import io.minio.StatObjectArgs
import io.minio.errors.ErrorResponseException
import io.minio.http.Method
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * The blind blob store: opaque (already-encrypted) bytes live here, NOT in the relational DB — so the DB stays
 * lean (metadata + manifest only) and the server is decoupled from storage. The store never interprets the
 * bytes (they are AEAD ciphertext / sealed frames).
 *
 * The server does NOT store blobs locally: the only production backend is `s3` — an external S3-compatible
 * object store, OR the bundled MinIO/Garage container (which is itself just an S3 endpoint). `memory` is an
 * ephemeral in-process backend for dev/test only (never a deployment). Chosen by veyq.blob-store.backend.
 */
interface BlobStore {
    fun put(key: String, bytes: ByteArray, contentType: String? = null)
    fun get(key: String): ByteArray?
    fun delete(key: String)

    /**
     * A short-lived presigned URL letting a client transfer one object DIRECTLY to/from the store, bypassing
     * the backend for the bytes — without ever seeing the store credentials (ADR 0021). This is the ONLY
     * transport path; returns null only for the in-memory dev/test store, which therefore has no media-transport
     * path at all (there is no proxied fallback — dev/test that exercises media needs a real S3/MinIO).
     */
    fun presignPut(key: String, ttlSeconds: Int): String? = null
    fun presignGet(key: String, ttlSeconds: Int): String? = null

    /** Whether this store issues presigned URLs — drives direct-to-store transport + resume detection. */
    fun supportsPresign(): Boolean = false

    /** The stored object's size in bytes, or null if absent — used to verify a directly-uploaded frame. */
    fun stat(key: String): Long?
}

/** Picks the configured backend. The unused impl is never constructed (so no S3 client opens under `memory`). */
@ApplicationScoped
class BlobStoreProducer(private val config: BlobStoreConfig) {
    @Produces
    @ApplicationScoped
    fun blobStore(): BlobStore = when (config.backend().lowercase()) {
        "s3" -> S3BlobStore(config)
        "memory" -> InMemoryBlobStore()
        else -> throw IllegalStateException("veyq.blob-store.backend must be 's3' (production) or 'memory' (dev/test); got '${config.backend()}'")
    }
}

/**
 * S3-compatible blob store (AWS S3, MinIO, Garage, …) — the only production backend. Each blob frame is one
 * object keyed by the server-generated key; the bytes are already AEAD ciphertext, so the object store learns
 * nothing. The bucket is created on first use (handy for the bundled MinIO; external buckets are pre-provisioned).
 */
class S3BlobStore(config: BlobStoreConfig) : BlobStore {
    private val bucket = config.s3Bucket()
    private val endpoint = config.s3Endpoint().orElseThrow { IllegalStateException("veyq.blob-store.s3-endpoint is required when backend=s3") }
    private val accessKey = config.s3AccessKey().orElseThrow { IllegalStateException("veyq.blob-store.s3-access-key is required when backend=s3") }
    private val secretKey = config.s3SecretKey().orElseThrow { IllegalStateException("veyq.blob-store.s3-secret-key is required when backend=s3") }
    private val region = config.s3Region()

    private fun clientFor(ep: String): MinioClient =
        MinioClient.builder().endpoint(ep).credentials(accessKey, secretKey).region(region).build()

    // Backend ↔ store IO (put/get/stat/delete) uses the internal endpoint (a private/compose hostname is fine).
    private val client: MinioClient = clientFor(endpoint)
    // Presigned URLs are SIGNED for the public endpoint the CLIENT can reach (ADR 0021) — which may differ from
    // the internal one (bundled MinIO behind a reverse proxy). Presigning is local HMAC, so this client never
    // connects. Defaults to the internal client when no separate public endpoint is configured.
    private val presignClient: MinioClient =
        config.s3PublicEndpoint().map { it.trim() }.filter { it.isNotEmpty() && it != endpoint }
            .map { clientFor(it) }.orElse(client)

    init {
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
        }
    }

    override fun put(key: String, bytes: ByteArray, contentType: String?) {
        client.putObject(
            PutObjectArgs.builder().bucket(bucket).`object`(key)
                .stream(ByteArrayInputStream(bytes), bytes.size.toLong(), -1)
                .contentType(contentType ?: "application/octet-stream")
                .build(),
        )
    }

    override fun get(key: String): ByteArray? =
        try {
            client.getObject(GetObjectArgs.builder().bucket(bucket).`object`(key).build()).use { it.readBytes() }
        } catch (e: ErrorResponseException) {
            if (e.errorResponse().code() == "NoSuchKey") null else throw e
        }

    override fun delete(key: String) {
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).`object`(key).build())
    }

    override fun presignPut(key: String, ttlSeconds: Int): String =
        presignClient.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder().method(Method.PUT).bucket(bucket).`object`(key).expiry(ttlSeconds).build(),
        )

    override fun presignGet(key: String, ttlSeconds: Int): String =
        presignClient.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder().method(Method.GET).bucket(bucket).`object`(key).expiry(ttlSeconds).build(),
        )

    override fun supportsPresign(): Boolean = true

    override fun stat(key: String): Long? =
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).`object`(key).build()).size()
        } catch (e: ErrorResponseException) {
            if (e.errorResponse().code() == "NoSuchKey") null else throw e
        }
}

/** Ephemeral in-process store for dev/test ONLY (no persistence, no deployment). Keeps frames in a map. */
class InMemoryBlobStore : BlobStore {
    private val objects = ConcurrentHashMap<String, ByteArray>()
    override fun put(key: String, bytes: ByteArray, contentType: String?) { objects[key] = bytes }
    override fun get(key: String): ByteArray? = objects[key]
    override fun delete(key: String) { objects.remove(key) }
    // No HTTP endpoint to sign against → presign unsupported (callers fall back to the proxied chunk path).
    override fun stat(key: String): Long? = objects[key]?.size?.toLong()
}
