package com.veyq.api.common

import jakarta.ws.rs.ext.ParamConverter
import jakarta.ws.rs.ext.ParamConverterProvider
import jakarta.ws.rs.ext.Provider
import java.lang.reflect.Type
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
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
        val converter = when (rawType) {
            Date::class.java -> IsoDateParamConverter
            OffsetDateTime::class.java -> IsoOffsetDateTimeParamConverter
            else -> return null
        }
        @Suppress("UNCHECKED_CAST")
        return converter as ParamConverter<T>
    }

    private object IsoDateParamConverter : ParamConverter<Date> {
        override fun fromString(value: String?): Date? =
            value?.trim()?.takeIf { it.isNotEmpty() }?.let { Date.from(parseInstant(it)) }

        override fun toString(value: Date?): String? =
            value?.toInstant()?.toString()
    }

    private object IsoOffsetDateTimeParamConverter : ParamConverter<OffsetDateTime> {
        override fun fromString(value: String?): OffsetDateTime? =
            value?.trim()?.takeIf { it.isNotEmpty() }?.let(::parseOffsetDateTime)

        override fun toString(value: OffsetDateTime?): String? =
            value?.toInstant()?.toString()
    }

    private companion object {
        fun parseInstant(value: String): Instant =
            try {
                Instant.parse(value)
            } catch (_: DateTimeParseException) {
                parseOffsetDateTime(value).toInstant()
            }

        fun parseOffsetDateTime(value: String): OffsetDateTime =
            try {
                Instant.parse(value).atOffset(ZoneOffset.UTC)
            } catch (_: DateTimeParseException) {
                try {
                    OffsetDateTime.parse(value)
                } catch (_: DateTimeParseException) {
                    OffsetDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                }
            }
    }
}
