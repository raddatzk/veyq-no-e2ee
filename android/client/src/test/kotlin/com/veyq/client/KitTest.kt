package com.veyq.client

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// uc.account.unlock — Emergency Kit import (android, cr-0018). Proves the parse path: a `veyqkit:` token
// (built here the way setup/web's `kitPayload` does) round-trips through `parseKit` to the exact email +
// Secret Key, including when embedded in PDF-shaped bytes; a non-kit blob yields null. On-device only.
class KitTest {

    private val email = "kevin@veyq.test"
    private val secretKey = "QEp7TgT/fdk7MhHigYQTS9o47PK7TSKjB/dpVq+bs14="

    /** Build a kit token exactly like web `kitPayload`: `veyqkit:` + base64url(JSON{v:1,email,sk}). */
    private fun makeToken(email: String, sk: String, v: Int = 1): String {
        // Hand-roll the JSON so the key order matches and stays a plain object.
        val json = """{"v":$v,"email":"$email","sk":"$sk"}"""
        val b64url = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
        return "veyqkit:$b64url"
    }

    @Test
    fun parseKitRoundTrips() {
        assertEquals(KitData(email, secretKey), parseKit(makeToken(email, secretKey)))
    }

    @Test
    fun importFromPdfShapedBytes() {
        // The token is embedded uncompressed amongst binary PDF noise, as the kit PDF stores it.
        val token = makeToken(email, secretKey)
        val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37, 0x00, 0xFF.toByte()) +
            token.toByteArray(Charsets.UTF_8) +
            byteArrayOf(0x00, 0xFE.toByte(), 0x0A, 0x25, 0x25, 0x45, 0x4F, 0x46)
        assertEquals(KitData(email, secretKey), importKitFromBytes(bytes))
    }

    @Test
    fun nonKitIsNull() {
        assertNull(importKitFromBytes("just some text, no kit here".toByteArray()))
        assertNull(parseKit("veyqkit:not-valid-base64-json!!!"))
        assertNull(parseKit(makeToken(email, secretKey, v = 2))) // wrong version
    }
}
