import Foundation
import VeyqCrypto

/// Localized gallery day labels. The day-key derivation + consecutive grouping live in VeyqCrypto.DayBuckets
/// (shared + unit-tested); the labels here are locale-formatted via the active app locale.
enum DateBuckets {
    static func dayKey(_ takenAt: String) -> String? { DayBuckets.dayKey(takenAt) }
    static func groupByDay<T>(_ items: [T], takenAt: (T) -> String) -> [(key: String, items: [T])] {
        DayBuckets.groupByDay(items, takenAt: takenAt)
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
