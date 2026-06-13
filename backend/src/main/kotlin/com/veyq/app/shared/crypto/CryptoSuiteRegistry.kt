package com.veyq.app.shared.crypto

import jakarta.enterprise.context.ApplicationScoped

/**
 * The server's only crypto-awareness: which suite ids it accepts. It never performs content crypto —
 * it stores opaque blobs and rejects writes with an unknown suite id.
 */
@ApplicationScoped
class CryptoSuiteRegistry {
    private val supported = linkedSetOf("v1")

    fun supported(): List<String> = supported.toList()

    fun isKnown(suiteId: String): Boolean = suiteId in supported
}
