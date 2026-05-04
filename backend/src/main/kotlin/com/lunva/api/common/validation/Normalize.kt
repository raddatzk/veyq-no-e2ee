package com.lunva.api.common.validation

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import java.util.Locale

@JsonDeserialize(using = NormalizerDeserializer::class)
annotation class Normalize()

class NormalizerDeserializer : JsonDeserializer<String>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): String =
        p.valueAsString.trim().lowercase(Locale.ROOT)
}