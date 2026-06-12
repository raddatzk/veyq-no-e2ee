package com.veyq.api.common

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class NoticeMarkdown(
    private val objectMapper: ObjectMapper,
) {
    private val mapType = object : TypeReference<Map<String, String>>() {}

    fun normalize(value: Map<String, String>?): Map<String, String> =
        value.orEmpty()
            .mapNotNull { (locale, markdown) ->
                val normalizedLocale = locale.trim().lowercase()
                val normalizedMarkdown = markdown.trim()
                if (normalizedLocale.isBlank() || normalizedMarkdown.isBlank()) null
                else normalizedLocale to normalizedMarkdown
            }
            .toMap()
            .toSortedMap()

    fun serialize(value: Map<String, String>): String? =
        value.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) }

    fun deserialize(value: String?): Map<String, String> =
        value?.takeIf { it.isNotBlank() }?.let { objectMapper.readValue(it, mapType) }.orEmpty()

    fun changed(currentJson: String?, next: Map<String, String>): Boolean =
        deserialize(currentJson) != next
}
