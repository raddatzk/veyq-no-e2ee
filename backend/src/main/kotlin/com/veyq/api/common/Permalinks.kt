package com.veyq.api.common

import java.text.Normalizer

object Permalinks {
    private val valid = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$")

    fun normalize(value: String): String =
        Normalizer.normalize(
            value.trim()
                .lowercase()
                .replace("ä", "ae")
                .replace("ö", "oe")
                .replace("ü", "ue")
                .replace("ß", "ss"),
            Normalizer.Form.NFKD,
        )
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .replace(Regex("-{2,}"), "-")
            .take(120)
            .trim('-')

    fun isValid(value: String): Boolean =
        value.length in 1..120 && valid.matches(value)
}
