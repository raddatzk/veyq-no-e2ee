package app.veyq.android.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Groups gallery media into day buckets by capture date (takenAt), mirroring the web `dateBuckets` logic,
 * so the gallery reads as a dated timeline. Labels are localized via the active app locale.
 */
object DateBuckets {
    private fun localDate(takenAt: String): java.time.LocalDate? =
        runCatching { Instant.parse(takenAt).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()

    /** Local calendar-day key (YYYY-MM-DD), or null for unparseable timestamps. */
    fun dayKey(takenAt: String): String? = localDate(takenAt)?.toString()

    /** Split items (already ordered by takenAt) into consecutive day buckets, preserving order. */
    fun <T> groupByDay(items: List<T>, takenAt: (T) -> String): List<Pair<String, List<T>>> {
        val buckets = mutableListOf<Pair<String, MutableList<T>>>()
        for (item in items) {
            val key = dayKey(takenAt(item)) ?: "—"
            val last = buckets.lastOrNull()
            if (last != null && last.first == key) last.second.add(item)
            else buckets.add(key to mutableListOf(item))
        }
        return buckets.map { it.first to it.second.toList() }
    }

    /** A bucket's header label: the full localized date (e.g. "13. Juni 2026"). */
    fun dayLabel(key: String): String {
        if (key == "—") return "—"
        val date = runCatching { java.time.LocalDate.parse(key) }.getOrNull() ?: return key
        val locale = Locale.forLanguageTag(L10n.locale)
        return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
    }

    /** The detail subtitle: a single date, or a "start – end" range across all media. */
    fun <T> dateRange(items: List<T>, takenAt: (T) -> String): String {
        val keys = items.mapNotNull { dayKey(takenAt(it)) }.sorted()
        val first = keys.firstOrNull() ?: return ""
        val last = keys.last()
        return if (first == last) dayLabel(first) else "${dayLabel(first)} – ${dayLabel(last)}"
    }
}
