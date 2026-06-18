package app.veyq.android.ui

import app.veyq.crypto.DayBuckets
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Localized gallery day labels. The day-key derivation + consecutive grouping live in core's DayBuckets
 * (shared + unit-tested); the labels here are locale-formatted via the active app locale.
 */
object DateBuckets {
    fun dayKey(takenAt: String): String? = DayBuckets.dayKey(takenAt)
    fun <T> groupByDay(items: List<T>, takenAt: (T) -> String): List<Pair<String, List<T>>> =
        DayBuckets.groupByDay(items, takenAt)

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
