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

    private(set) var scope: ScopeContent.OpenScope?
    private(set) var contentKey: Data?

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
