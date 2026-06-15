import Foundation

/// Parse a pasted veyq invite link (or bare fragment) into its parts. The bootstrap secret + one-time temp
/// private key ride the URL *fragment* (`#invite=…&s=…&k=…`) so they never reach a server.
enum InviteLink {
    struct Parts { let inviteId, bootstrapSecret, tempPrivateKey: String }

    static func parse(_ text: String) -> Parts? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let frag = trimmed.firstIndex(of: "#").map { String(trimmed[trimmed.index(after: $0)...]) } ?? trimmed
        var dict: [String: String] = [:]
        for pair in frag.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1)
            if kv.count == 2 { dict[String(kv[0])] = String(kv[1]).removingPercentEncoding ?? String(kv[1]) }
        }
        guard let id = dict["invite"], let s = dict["s"], let k = dict["k"] else { return nil }
        return Parts(inviteId: id, bootstrapSecret: s, tempPrivateKey: k)
    }
}
