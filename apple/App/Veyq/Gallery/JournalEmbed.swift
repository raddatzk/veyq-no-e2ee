import Foundation

// Journal rich-embed tokens — `::media:id1,id2 label`, `::track:id1,id2 label`, `::chapter:id label`. Mirrors
// the web `journalEmbed.ts` wire format so journals render identically across platforms. A token ends at a
// newline, end-of-text, or the next token, so several can sit back-to-back.

enum JournalEmbedKind: String { case media, track, chapter }

struct JournalEmbed: Identifiable {
    let id: Int
    let kind: JournalEmbedKind
    let ids: [String]
    let label: String?
}

enum JournalBlock: Identifiable {
    case markdown(String, id: Int)
    case embed(JournalEmbed)
    var id: Int {
        switch self {
        case let .markdown(_, id): return id
        case let .embed(e): return e.id
        }
    }
}

enum JournalEmbedParser {
    static let maxTrackIds = 20
    private static let regex = try! NSRegularExpression(
        pattern: "::(media|track|chapter):([^\\s\\n]+)(?:[ \\t]+([^\\n]*?))?(?=\\n|$|::(?:media|track|chapter):)")

    /// Split the journal into an ordered run of Markdown segments and embed tokens for block-based rendering.
    static func splitBlocks(_ markdown: String) -> [JournalBlock] {
        let ns = markdown as NSString
        var blocks: [JournalBlock] = []
        var cursor = 0
        var counter = 0
        func nextId() -> Int { counter += 1; return counter }
        for m in regex.matches(in: markdown, range: NSRange(location: 0, length: ns.length)) {
            let full = m.range
            if full.location > cursor {
                blocks.append(.markdown(ns.substring(with: NSRange(location: cursor, length: full.location - cursor)), id: nextId()))
            }
            cursor = full.location + full.length
            guard let kind = JournalEmbedKind(rawValue: ns.substring(with: m.range(at: 1))) else { continue }
            let rawIds = ns.substring(with: m.range(at: 2)).split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            let ids = kind == .track ? normalizeTrackIds(rawIds) : (kind == .chapter ? Array(rawIds.prefix(1)) : rawIds)
            if ids.isEmpty { continue }
            let lr = m.range(at: 3)
            let label = lr.location != NSNotFound ? ns.substring(with: lr).trimmingCharacters(in: .whitespaces) : nil
            blocks.append(.embed(JournalEmbed(id: nextId(), kind: kind, ids: ids, label: (label?.isEmpty ?? true) ? nil : label)))
        }
        if cursor < ns.length {
            blocks.append(.markdown(ns.substring(from: cursor), id: nextId()))
        }
        return blocks
    }

    /// Dedupe + trim track ids and cap them (one embed can carry several tracks onto one map).
    static func normalizeTrackIds(_ ids: [String]) -> [String] {
        var seen = Set<String>(); var out: [String] = []
        for raw in ids {
            let t = raw.trimmingCharacters(in: .whitespaces)
            if !t.isEmpty, seen.insert(t).inserted { out.append(t) }
        }
        return Array(out.prefix(maxTrackIds))
    }

    /// Serialize an embed back to its token form (used by the editor pickers when inserting/editing).
    static func embedMarkdown(_ kind: JournalEmbedKind, _ ids: [String], label: String?) -> String {
        let norm = kind == .track ? normalizeTrackIds(ids) : (kind == .chapter ? Array(ids.prefix(1)) : ids)
        let trimmed = label?.trimmingCharacters(in: .whitespaces)
        let suffix = (trimmed?.isEmpty ?? true) ? "" : " \(trimmed!)"
        return "::\(kind.rawValue):\(norm.joined(separator: ","))\(suffix)"
    }
}
