package com.veyq.api.library.media

import java.time.Instant
import java.util.Properties

data class UploadSession(
    val uploadId: String,
    val tenantId: Long,
    val tenantName: String,
    val ownerUserId: Long,
    val albumPath: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val title: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

object MediaUploadSessions {
    private val validUploadId = Regex("^[0-9a-fA-F-]{36}$")

    fun hasValidUploadId(uploadId: String): Boolean =
        uploadId.matches(validUploadId)

    fun toProperties(session: UploadSession): Properties =
        Properties().also {
            it["uploadId"] = session.uploadId
            it["tenantId"] = session.tenantId.toString()
            it["tenantName"] = session.tenantName
            it["ownerUserId"] = session.ownerUserId.toString()
            it["albumPath"] = session.albumPath
            it["filename"] = session.filename
            it["mimeType"] = session.mimeType
            it["sizeBytes"] = session.sizeBytes.toString()
            it["createdAt"] = session.createdAt.toString()
            it["updatedAt"] = session.updatedAt.toString()
            session.title?.let { title -> it["title"] = title }
        }

    fun fromProperties(
        properties: Properties,
        updatedAtFallback: Instant,
        createdAtFallback: Instant = updatedAtFallback,
        tenantNameFallback: (Long) -> String?,
    ): UploadSession {
        val tenantId = properties.getProperty("tenantId").toLong()
        val updatedAt = properties.getProperty("updatedAt")?.let { Instant.parse(it) } ?: updatedAtFallback
        val createdAt = properties.getProperty("createdAt")?.let { Instant.parse(it) } ?: createdAtFallback
        return UploadSession(
            uploadId = properties.getProperty("uploadId"),
            tenantId = tenantId,
            tenantName = properties.getProperty("tenantName") ?: tenantNameFallback(tenantId)
                ?: throw IllegalArgumentException("tenantName is missing"),
            ownerUserId = properties.getProperty("ownerUserId").toLong(),
            albumPath = properties.getProperty("albumPath"),
            filename = properties.getProperty("filename"),
            mimeType = properties.getProperty("mimeType"),
            sizeBytes = properties.getProperty("sizeBytes").toLong(),
            title = properties.getProperty("title"),
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}
