import Foundation

/// Client-side name search over already-decrypted node names. The server stays blind: callers decode names
/// locally, then pass them through this pure filter.
public enum Search {
    public static func matchByName<T>(_ items: [T], query: String, limit: Int = 25, name: (T) -> String) -> [T] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !q.isEmpty else { return [] }
        return Array(items.filter { name($0).lowercased().contains(q) }.prefix(limit))
    }
}
