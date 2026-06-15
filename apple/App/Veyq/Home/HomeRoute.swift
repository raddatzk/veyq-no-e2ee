import Foundation

/// Parse a member deep link `veyq://open?u=<path>&api=<origin>` (from the web "open in app" banner) and the
/// path's space selector. Mirrors the web homeRoute scheme:
///   tenant   → /urlaube/italien            (no prefix)
///   group    → /@reisegruppe/urlaube/italien
///   personal → /~me/urlaube/italien
enum HomeRoute {
    enum Kind { case personal, tenant, group }
    struct Parsed { let kind: Kind; let groupSlug: String?; let segments: [String] }

    /// Extract (apiBase, path) from a veyq://open deep link, or nil if it isn't one.
    static func parseDeepLink(_ uri: String) -> (apiBase: String, path: String)? {
        guard let comps = URLComponents(string: uri), comps.scheme == "veyq", comps.host == "open" else { return nil }
        let items = comps.queryItems ?? []
        guard var api = items.first(where: { $0.name == "api" })?.value, !api.isEmpty else { return nil }
        while api.hasSuffix("/") { api.removeLast() }
        let path = items.first(where: { $0.name == "u" })?.value ?? "/"
        return (api, path)
    }

    /// Split a path into its space selector + the remaining folder/memory slug segments.
    static func parsePath(_ path: String) -> Parsed {
        let raw = path.split(separator: "/").map(String.init).filter { !$0.isEmpty }
        guard let first = raw.first else { return Parsed(kind: .tenant, groupSlug: nil, segments: []) }
        if first == "~me" { return Parsed(kind: .personal, groupSlug: nil, segments: Array(raw.dropFirst())) }
        if first.hasPrefix("@") { return Parsed(kind: .group, groupSlug: String(first.dropFirst()), segments: Array(raw.dropFirst())) }
        return Parsed(kind: .tenant, groupSlug: nil, segments: raw)
    }

    /// A URL-safe slug from a name — must match the web `slugify` so group-name prefixes round-trip.
    static func slugify(_ name: String) -> String {
        let allowed = Set("abcdefghijklmnopqrstuvwxyz0123456789")
        let folded = name.folding(options: .diacriticInsensitive, locale: nil).lowercased()
        let mapped = String(folded.map { allowed.contains($0) ? $0 : "-" })
        let collapsed = mapped.split(separator: "-").joined(separator: "-")
        return collapsed.isEmpty ? "item" : collapsed
    }
}
