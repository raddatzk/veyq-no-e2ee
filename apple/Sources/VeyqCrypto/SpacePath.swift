import Foundation

/// The space-selector + slug logic for clean paths (`/~me/...`, `/@group/...`, `/tenant/...`). Shared here in
/// VeyqCrypto so the slug stays byte-compatible with the web `homeRoute` (group-name prefixes must round-trip)
/// and is unit-tested. The URL/deep-link extraction itself stays per-platform (URLComponents / Uri).
public enum SpacePath {
    public enum Kind { case personal, tenant, group }
    public struct Parsed {
        public let kind: Kind
        public let groupSlug: String?
        public let segments: [String]
        public init(kind: Kind, groupSlug: String?, segments: [String]) {
            self.kind = kind; self.groupSlug = groupSlug; self.segments = segments
        }
    }

    /// Split a path into its space selector + the remaining folder/memory slug segments.
    public static func parsePath(_ path: String) -> Parsed {
        let raw = path.split(separator: "/").map(String.init).filter { !$0.isEmpty }
        guard let first = raw.first else { return Parsed(kind: .tenant, groupSlug: nil, segments: []) }
        if first == "~me" { return Parsed(kind: .personal, groupSlug: nil, segments: Array(raw.dropFirst())) }
        if first.hasPrefix("@") { return Parsed(kind: .group, groupSlug: String(first.dropFirst()), segments: Array(raw.dropFirst())) }
        return Parsed(kind: .tenant, groupSlug: nil, segments: raw)
    }

    /// A URL-safe slug from a name — must match the web `slugify` so group-name prefixes round-trip.
    public static func slugify(_ name: String) -> String {
        let allowed = Set("abcdefghijklmnopqrstuvwxyz0123456789")
        let folded = name.folding(options: .diacriticInsensitive, locale: nil).lowercased()
        let mapped = String(folded.map { allowed.contains($0) ? $0 : "-" })
        let collapsed = mapped.split(separator: "-").joined(separator: "-")
        return collapsed.isEmpty ? "item" : collapsed
    }
}
