package com.veyq.api.library.media

data class ContentRange(val start: Long, val end: Long) {
    val length: Long = end - start + 1
}

object MediaRanges {
    fun parse(rangeHeader: String?, size: Long): ContentRange? {
        if (rangeHeader == null) return null
        if (!rangeHeader.startsWith("bytes=") || size <= 0) return null
        val range = rangeHeader.removePrefix("bytes=").substringBefore(',')
        val startText = range.substringBefore('-', "")
        val endText = range.substringAfter('-', "")
        if (startText.isBlank() && endText.isBlank()) return null

        val start: Long
        val end: Long
        if (startText.isBlank()) {
            val suffixLength = endText.toLongOrNull() ?: return null
            if (suffixLength <= 0) return null
            start = (size - suffixLength).coerceAtLeast(0)
            end = size - 1
        } else {
            start = startText.toLongOrNull() ?: return null
            end = if (endText.isBlank()) size - 1 else endText.toLongOrNull() ?: return null
        }

        if (start < 0 || end < start || start >= size) return null
        return ContentRange(start, end.coerceAtMost(size - 1))
    }
}
