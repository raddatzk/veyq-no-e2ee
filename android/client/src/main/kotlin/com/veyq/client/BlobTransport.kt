package com.veyq.client

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The direct-to-store byte transport (cr-0028, ADR 0013): the client PUT/GETs **raw sealed ciphertext**
 * straight to a presigned object-store URL — never through the backend, never with credentials. Injectable so
 * the hermetic `:client:test` drives an in-memory store instead of a real MinIO.
 */
interface BlobTransport {
    /** PUT raw bytes to a presigned URL (no base64, no JSON). */
    fun put(url: String, body: ByteArray)
    /** GET raw bytes from a presigned URL. */
    fun get(url: String): ByteArray
}

/**
 * Default OkHttp-backed transport for presigned PUT/GET. Uses a **credential-free** client (no
 * [BearerInterceptor]) — a presigned URL is self-authenticating (SigV4) and an extra Authorization header
 * would break the signature / leak the session token to the store.
 */
class OkHttpBlobTransport(private val client: OkHttpClient = OkHttpClient()) : BlobTransport {

    override fun put(url: String, body: ByteArray) {
        val request = Request.Builder().url(url).put(body.toRequestBody()).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw VeyqError.BlobTransferFailed
        }
    }

    override fun get(url: String): ByteArray {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw VeyqError.BlobTransferFailed
            return response.body?.bytes() ?: throw VeyqError.BlobTransferFailed
        }
    }
}
