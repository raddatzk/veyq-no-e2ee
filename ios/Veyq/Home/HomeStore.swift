import SwiftUI
import UIKit
import VeyqCrypto

/// MemoryHome state: the spaces the user can enter, the active space's tree level (folders + memories with
/// decrypted names), a breadcrumb, and lazily-resolved memory covers. Built on the existing slices; names
/// and covers are decrypted client-side.
@MainActor
final class HomeStore: ObservableObject {
    struct Entry: Identifiable {
        let node: MemoryTree.MemoryNode
        let name: String
        var id: String { node.id }
    }
    struct Crumb: Identifiable {
        let id: String
        let nodeId: String?
        let name: String
    }

    let apiBase: String
    let session: Login.UnlockedSession

    @Published var spaces: [SwitchActiveSpace.SpaceEntry] = []
    @Published var activeScopeId: String?
    @Published var folders: [Entry] = []
    @Published var memories: [Entry] = []
    @Published var covers: [String: UIImage] = [:]
    @Published var crumbs: [Crumb] = []
    @Published var error: String?
    @Published var searchText = ""
    @Published var searchHits: [Entry] = []

    private(set) var scope: ScopeContent.OpenScope?
    private(set) var contentKey: Data?
    private var allNodes: [MemoryTree.MemoryNode]?

    init(apiBase: String, session: Login.UnlockedSession) {
        self.apiBase = apiBase
        self.session = session
    }

    func start() async {
        do {
            spaces = try await SwitchActiveSpace.listMySpaces(apiBase: apiBase, session: session)
            if let personal = spaces.first(where: { $0.kind == "PERSONAL" }) ?? spaces.first {
                await switchSpace(personal)
            }
        } catch {
            self.error = "Konnte Bereiche nicht laden."
        }
    }

    func switchSpace(_ entry: SwitchActiveSpace.SpaceEntry) async {
        do {
            let opened = try await SwitchActiveSpace.openSpace(apiBase: apiBase, session: session, entry: entry)
            let key = try await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: session, scope: opened)
            scope = opened
            contentKey = key
            activeScopeId = entry.scopeId
            allNodes = nil
            crumbs = [Crumb(id: "root", nodeId: nil, name: entry.displayName)]
            await openLevel()
        } catch {
            self.error = "Konnte den Bereich nicht öffnen."
        }
    }

    func openFolder(_ entry: Entry) async {
        crumbs.append(Crumb(id: entry.node.id, nodeId: entry.node.id, name: entry.name))
        await openLevel()
    }

    /// Create a folder/memory at the current level. Name is sealed; the slug retries on collision.
    func create(type: String, name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let scope, let contentKey, !trimmed.isEmpty else { return }
        allNodes = nil
        let parentId = crumbs.last?.nodeId
        let orderIndex = folders.count + memories.count
        let base = slugify(trimmed)
        for attempt in 0 ..< 25 {
            let slug = attempt == 0 ? base : "\(base)-\(attempt + 1)"
            do {
                _ = try await MemoryTree.createNode(apiBase: apiBase, session: session, scopeId: scope.scopeId, contentKey: contentKey, type: type, name: trimmed, slug: slug, orderIndex: orderIndex, parentId: parentId)
                await openLevel()
                return
            } catch {
                if !"\(error)".contains("SLUG_EXISTS") && !"\(error)".contains("409") {
                    self.error = "Konnte nicht anlegen."
                    return
                }
            }
        }
    }

    private func slugify(_ name: String) -> String {
        let allowed = Set("abcdefghijklmnopqrstuvwxyz0123456789")
        let folded = name.folding(options: .diacriticInsensitive, locale: nil).lowercased()
        let mapped = String(folded.map { allowed.contains($0) ? $0 : "-" })
        let collapsed = mapped.split(separator: "-").joined(separator: "-")
        return collapsed.isEmpty ? "item" : collapsed
    }

    /// Client-side search over the active space's decrypted node names (loads the graph once, caches it).
    func search() async {
        let q = searchText.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty, let scope, let contentKey else { searchHits = []; return }
        if allNodes == nil {
            allNodes = try? await MemoryTree.listNodes(apiBase: apiBase, session: session, scopeId: scope.scopeId)
        }
        searchHits = (allNodes ?? []).compactMap { node in
            let name = (try? MemoryTree.decodeNode(contentKey: contentKey, node: node).name) ?? ""
            return name.lowercased().contains(q) ? Entry(node: node, name: name) : nil
        }
    }

    func gotoCrumb(_ crumb: Crumb) async {
        guard let idx = crumbs.firstIndex(where: { $0.id == crumb.id }), idx < crumbs.count - 1 else { return }
        crumbs = Array(crumbs.prefix(idx + 1))
        await openLevel()
    }

    private func openLevel() async {
        guard let scope, let contentKey else { return }
        do {
            let nodes = try await Browse.listChildren(apiBase: apiBase, session: session, scopeId: scope.scopeId, parentId: crumbs.last?.nodeId)
            let entries = nodes.map { node in
                Entry(node: node, name: (try? MemoryTree.decodeNode(contentKey: contentKey, node: node).name) ?? "—")
            }
            folders = entries.filter { $0.node.type == "FOLDER" }
            memories = entries.filter { $0.node.type == "MEMORY" }
            covers = [:]
            for m in memories { await loadCover(m, scope: scope, contentKey: contentKey) }
        } catch {
            self.error = "Konnte Inhalte nicht laden."
        }
    }

    private func loadCover(_ m: Entry, scope: ScopeContent.OpenScope, contentKey: Data) async {
        do {
            let media = try await Media.listMedia(apiBase: apiBase, session: session, scopeId: scope.scopeId, nodeId: m.node.id)
            guard let first = media.first else { return }
            let ref = first.derivatives.first(where: { $0.kind == "THUMBNAIL" })?.blob ?? first.original
            let bytes = try await Media.downloadBlob(apiBase: apiBase, session: session, scopeId: scope.scopeId, contentKey: contentKey, ref: ref)
            if let img = UIImage(data: bytes) { covers[m.node.id] = img }
        } catch {
            // no cover — leave the placeholder
        }
    }
}
