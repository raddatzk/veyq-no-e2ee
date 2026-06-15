import Foundation

/// Groups gallery media into day buckets by capture date (takenAt), mirroring the web `dateBuckets` logic,
/// so the gallery reads as a dated timeline. Locale-aware labels via the active app locale.
enum DateBuckets {
    static func parse(_ takenAt: String) -> Date? {
        let withFraction = ISO8601DateFormatter()
        withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return withFraction.date(from: takenAt) ?? ISO8601DateFormatter().date(from: takenAt)
    }

    /// Local calendar-day key (YYYY-MM-DD), or nil for unparseable timestamps.
    static func dayKey(_ takenAt: String) -> String? {
        guard let date = parse(takenAt) else { return nil }
        let c = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    /// Split items (already ordered by takenAt) into consecutive day buckets, preserving order.
    static func groupByDay<T>(_ items: [T], takenAt: (T) -> String) -> [(key: String, items: [T])] {
        var buckets: [(key: String, items: [T])] = []
        for item in items {
            let key = dayKey(takenAt(item)) ?? "—"
            if buckets.last?.key == key { buckets[buckets.count - 1].items.append(item) }
            else { buckets.append((key: key, items: [item])) }
        }
        return buckets
    }

    private static func localeFormatter() -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: UserDefaults.standard.string(forKey: "veyq.locale") ?? "de")
        f.setLocalizedDateFormatFromTemplate("d MMMM y")
        return f
    }

    /// A bucket's header label: the full localized date (e.g. "13. Juni 2026").
    static func dayLabel(_ key: String) -> String {
        guard key != "—" else { return "—" }
        let parser = DateFormatter(); parser.dateFormat = "yyyy-MM-dd"; parser.locale = Locale(identifier: "en_US_POSIX")
        guard let date = parser.date(from: key) else { return key }
        return localeFormatter().string(from: date)
    }

    /// The detail subtitle: a single date, or a "start – end" range across all media.
    static func dateRange<T>(_ items: [T], takenAt: (T) -> String) -> String {
        let keys = items.compactMap { dayKey(takenAt($0)) }.sorted()
        guard let first = keys.first, let last = keys.last else { return "" }
        return first == last ? dayLabel(first) : "\(dayLabel(first)) – \(dayLabel(last))"
    }
}
