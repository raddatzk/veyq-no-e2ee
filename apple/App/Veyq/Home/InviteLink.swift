import Foundation

/// Parse a pasted veyq invite link (or bare fragment) into its parts. The bootstrap secret + one-time temp
/// private key ride the URL *fragment* (`#invite=…&s=…&k=…`) so they never reach a server.
enum InviteLink {
    struct Parts { let inviteId, bootstrapSecret, tempPrivateKey: String }
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
        guard let id = d["invite"], let s = d["s"], let k = d["k"] else { return nil }
        return Parts(inviteId: id, bootstrapSecret: s, tempPrivateKey: k)
    }

    /// Parse a veyq://invite#invite=…&s=…&k=…&api=<origin> deep link (the banner carries the instance in `api`).
    static func parseDeepLink(_ uri: String) -> DeepLink? {
        guard let parts = parse(uri), var api = dict(uri)["api"], !api.isEmpty else { return nil }
        while api.hasSuffix("/") { api.removeLast() }
        return DeepLink(apiBase: api, parts: parts)
    }
}
