package com.veyq.client

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Emergency Kit import — the Android port of web `emergencyKit.ts`'s parse path (and Apple's `Kit.swift`).
// The kit is machine-readable: setup/web's `kitPayload` writes `veyqkit:<base64url(JSON{v:1,email,sk})>`
// as a QR **and** uncompressed in the kit PDF's bytes. Recovering it is purely on-device (operator-blind):
// the email + Secret Key fill local fields, nothing is sent. The kit carries **no password**.

/** The data an Emergency Kit carries: the login email + the recovery Secret Key (never the password). */
data class KitData(val email: String, val secretKey: String)

private val TOKEN = Regex("veyqkit:([A-Za-z0-9_-]+)")

/**
 * Parse a kit token (`veyqkit:<base64url(JSON{v:1,email,sk})>`) out of any text — a scanned QR string or
 * the kit PDF read as text. Mirrors web/Apple `parseKit`: find the token, base64url-decode, validate
 * `v == 1` and non-empty email + sk. Returns null if no valid kit token is present.
 */
fun parseKit(text: String): KitData? {
    val token = TOKEN.find(text)?.groupValues?.get(1) ?: return null
    val json = decodeBase64url(token) ?: return null
    return try {
        val obj = Json.parseToJsonElement(String(json, Charsets.UTF_8)).jsonObject
        val v = obj["v"]?.jsonPrimitive?.intOrNull
        val email = obj["email"]?.jsonPrimitive?.contentOrNull
        val sk = obj["sk"]?.jsonPrimitive?.contentOrNull
        if (v == 1 && !email.isNullOrEmpty() && !sk.isNullOrEmpty()) KitData(email, sk) else null
    } catch (e: Exception) {
        null
    }
}

/**
 * Recover a kit from a file's raw bytes read as UTF-8 text (the kit PDF embeds the token uncompressed, or
 * any token-carrying text). No PDF library — exactly like web/Apple. Returns null if no kit token is found.
 */
fun importKitFromBytes(bytes: ByteArray): KitData? = parseKit(String(bytes, Charsets.UTF_8))

/** base64url → bytes; the URL decoder handles `-`/`_` and tolerates the missing padding web's `b64url` strips. */
private fun decodeBase64url(s: String): ByteArray? =
    try {
        Base64.getUrlDecoder().decode(s)
    } catch (e: IllegalArgumentException) {
        null
    }
