import Foundation

/// Group gallery media into consecutive calendar-day buckets by capture date (takenAt), mirroring the web
/// `dateBuckets` grouping so the gallery reads as a dated timeline. Shared here in VeyqCrypto so the day-key
/// derivation and the consecutive-run grouping are unit-tested; the localized labels stay per-platform.
public enum DayBuckets {
    static func parse(_ takenAt: String) -> Date? {
        let withFraction = ISO8601DateFormatter()
        withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return withFraction.date(from: takenAt) ?? ISO8601DateFormatter().date(from: takenAt)
    }

    /// Local calendar-day key (YYYY-MM-DD), or nil for unparseable timestamps.
    public static func dayKey(_ takenAt: String) -> String? {
        guard let date = parse(takenAt) else { return nil }
        let c = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    /// Split items (already ordered by takenAt) into consecutive day buckets, preserving order.
    public static func groupByDay<T>(_ items: [T], takenAt: (T) -> String) -> [(key: String, items: [T])] {
        var buckets: [(key: String, items: [T])] = []
        for item in items {
            let key = dayKey(takenAt(item)) ?? "—"
            if buckets.last?.key == key { buckets[buckets.count - 1].items.append(item) }
            else { buckets.append((key: key, items: [item])) }
        }
        return buckets
    }
}
