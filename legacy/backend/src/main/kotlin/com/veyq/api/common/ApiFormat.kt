package com.veyq.api.common

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

fun apiPathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

fun Instant.toApiOffsetDateTime(): OffsetDateTime =
    OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
