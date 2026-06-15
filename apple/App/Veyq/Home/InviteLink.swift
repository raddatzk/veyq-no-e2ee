import Foundation

/// Parse / build a veyq invite link. The bootstrap secret + the one-time temp key ride the URL *fragment*
/// (`#invite=…&s=…&…`) so they never reach a server. Two modes share the plumbing:
///  - pragmatic  → `k=<tempPrivKey>` (the temp key in the clear)
///  - passphrase → `ek=<encTempKey>&ps=<salt>` (the temp key encrypted under an out-of-band passphrase)
enum InviteLink {
    struct Parts {
        let inviteId, bootstrapSecret: String
        let tempPrivateKey: String?       // pragmatic
        let encTempKey, pwSalt: String?   // passphrase-protected
        var isProtected: Bool { encTempKey != nil && pwSalt != nil }
    }
    /// A deep link from the browser banner: the invite parts plus the instance it points at.
    struct DeepLink { let apiBase: String; let parts: Parts }

    private static func dict(_ text: String) -> [String: String] {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let frag = trimmed.firstIndex(of: "#").map { String(trimmed[trimmed.index(after: $0)...]) } ?? trimmed
        var d: [String: String] = [:]
        for pair in frag.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1)
            if kv.count == 2 { d[String(kv[0])] = String(kv[1]).removingPercentEncoding ?? String(kv[1]) }
        }
        return d
    }

    static func parse(_ text: String) -> Parts? {
        let d = dict(text)
        guard let id = d["invite"], let s = d["s"] else { return nil }
        if let k = d["k"] { return Parts(inviteId: id, bootstrapSecret: s, tempPrivateKey: k, encTempKey: nil, pwSalt: nil) }
        if let ek = d["ek"], let ps = d["ps"] { return Parts(inviteId: id, bootstrapSecret: s, tempPrivateKey: nil, encTempKey: ek, pwSalt: ps) }
        return nil
    }

    /// Parse a veyq://invite#invite=…&s=…&(k|ek+ps)…&api=<origin> deep link (the banner carries the instance).
    static func parseDeepLink(_ uri: String) -> DeepLink? {
        guard let parts = parse(uri), var api = dict(uri)["api"], !api.isEmpty else { return nil }
        while api.hasSuffix("/") { api.removeLast() }
        return DeepLink(apiBase: api, parts: parts)
    }

    // The fragment values are base64url (chars [A-Za-z0-9-_]) — already URL-fragment-safe, so plain joins.
    static func build(origin: String, inviteId: String, bootstrapSecret: String, tempPrivateKey: String) -> String {
        "\(trimSlash(origin))/#invite=\(inviteId)&s=\(bootstrapSecret)&k=\(tempPrivateKey)"
    }
    static func buildProtected(origin: String, inviteId: String, bootstrapSecret: String, encTempKey: String, pwSalt: String) -> String {
        "\(trimSlash(origin))/#invite=\(inviteId)&s=\(bootstrapSecret)&ek=\(encTempKey)&ps=\(pwSalt)"
    }

    private static func trimSlash(_ s: String) -> String { var o = s; while o.hasSuffix("/") { o.removeLast() }; return o }
}
