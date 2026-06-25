package com.veyq.app.shared.content

import com.veyq.app.platform.error.ApiException
import java.util.Base64

/**
 * Decode a base64url sealed-content payload (node name, item, journal, track) with a hard size cap, BEFORE
 * allocating the decoded array — so an over-large payload is rejected without the OOM-sized allocation. Mirrors
 * the blob-upload frame cap; large media never reaches here (it goes through the chunked blob path).
 */
object SealedContent {
    fun decodeCapped(b64: String, maxBytes: Int, dec: Base64.Decoder): ByteArray {
        // base64 is ~4 chars per 3 bytes, so the encoded length bounds the decoded size — check it first.
        val maxChars = ((maxBytes + 2) / 3) * 4
        if (b64.length > maxChars) throw ApiException(400, "CONTENT_TOO_LARGE", "Sealed content exceeds the size limit")
        val bytes = dec.decode(b64)
        if (bytes.size > maxBytes) throw ApiException(400, "CONTENT_TOO_LARGE", "Sealed content exceeds the size limit")
        return bytes
    }
}
