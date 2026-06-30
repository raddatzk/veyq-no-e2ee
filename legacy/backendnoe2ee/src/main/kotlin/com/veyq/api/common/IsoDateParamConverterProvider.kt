package com.veyq.api.common

import jakarta.ws.rs.ext.ParamConverter
import jakarta.ws.rs.ext.ParamConverterProvider
import jakarta.ws.rs.ext.Provider
import java.lang.reflect.Type
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Date

@Provider
class IsoDateParamConverterProvider : ParamConverterProvider {
    override fun <T : Any?> getConverter(
        rawType: Class<T>,
        genericType: Type?,
        annotations: Array<out Annotation>?,
    ): ParamConverter<T>? {
        if (rawType != Date::class.java) return null
        @Suppress("UNCHECKED_CAST")
        return IsoDateParamConverter as ParamConverter<T>
    }

    private object IsoDateParamConverter : ParamConverter<Date> {
        override fun fromString(value: String?): Date? {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return Date.from(parseInstant(trimmed))
        }

        override fun toString(value: Date?): String? =
            value?.toInstant()?.toString()

        private fun parseInstant(value: String): Instant =
            try {
                Instant.parse(value)
            } catch (_: DateTimeParseException) {
                try {
                    OffsetDateTime.parse(value).toInstant()
                } catch (_: DateTimeParseException) {
                    OffsetDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                }
            }
    }
}
