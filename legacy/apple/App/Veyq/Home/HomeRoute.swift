import Foundation
import VeyqCrypto

/// Parse a member deep link `veyq://open?u=<path>&api=<origin>` (from the web "open in app" banner).
/// The path's space selector + slug logic lives in VeyqCrypto.SpacePath (shared + unit-tested).
enum HomeRoute {
    typealias Kind = SpacePath.Kind
    typealias Parsed = SpacePath.Parsed

    /// Extract (apiBase, path) from a veyq://open deep link, or nil if it isn't one.
    static func parseDeepLink(_ uri: String) -> (apiBase: String, path: String)? {
        guard let comps = URLComponents(string: uri), comps.scheme == "veyq", comps.host == "open" else { return nil }
        let items = comps.queryItems ?? []
        guard var api = items.first(where: { $0.name == "api" })?.value, !api.isEmpty else { return nil }
        while api.hasSuffix("/") { api.removeLast() }
        let path = items.first(where: { $0.name == "u" })?.value ?? "/"
        return (api, path)
    }

    static func parsePath(_ path: String) -> Parsed { SpacePath.parsePath(path) }
    static func slugify(_ name: String) -> String { SpacePath.slugify(name) }
}
