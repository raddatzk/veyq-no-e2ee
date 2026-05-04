package com.lunva.api.common

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object HashingService {
    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(value.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}