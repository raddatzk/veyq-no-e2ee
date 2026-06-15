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
    @Published var groupNames: [String: String] = [:] // groupScopeId → decrypted name (sealed under the group key)

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
            await resolveGroupNames()
            if let personal = spaces.first(where: { $0.kind == "PERSONAL" }) ?? spaces.first {
                await switchSpace(personal)
            }
        } catch {
            self.error = "Konnte Bereiche nicht laden."
        }
    }

    /// Decrypt the sealed name of each group so the sidebar can show it (opens the group scope + content key).
    func resolveGroupNames() async {
        for entry in spaces where entry.kind == "GROUP" {
            guard groupNames[entry.scopeId] == nil, let sealed = entry.sealedName else { continue }
            if let opened = try? await SwitchActiveSpace.openSpace(apiBase: apiBase, session: session, entry: entry),
               let ck = try? await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: session, scope: opened),
               let name = try? Groups.openSealedName(contentKey: ck, sealedB64: sealed.bytes) {
                groupNames[entry.scopeId] = name
            }
        }
    }

    /// The label to show for a space: its plaintext name, or the decrypted group name, else a lock.
    func displayName(for entry: SwitchActiveSpace.SpaceEntry) -> String {
        if let n = entry.displayName, !n.isEmpty { return n }
        return groupNames[entry.scopeId] ?? "🔒"
    }

    /// Create a group within a tenant (the caller becomes its GroupOwner), refresh, and switch into it.
    func createGroupSpace(tenantId: String, name: String) async -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        do {
            let group = try await Groups.createGroup(apiBase: apiBase, session: session, tenantId: tenantId, name: trimmed)
            spaces = try await SwitchActiveSpace.listMySpaces(apiBase: apiBase, session: session)
            await resolveGroupNames()
            if let entry = spaces.first(where: { $0.scopeId == group.scopeId }) { await switchSpace(entry) }
            return true
        } catch {
            self.error = T("groups.createFailed")
            return false
        }
    }

    /// Look up an invite's tenant + role for a confirmation preview (no secret needed — public metadata).
    func inviteInfo(_ inviteId: String) async -> (name: String, role: String)? {
        guard let meta = try? await RegisterViaInvitation.getInviteMetadata(apiBase: apiBase, inviteId: inviteId) else { return nil }
        return (meta.tenant.displayName, meta.role)
    }

    /// Accept an invite onto this (logged-in) account, then refresh the space list so the group appears.
    func joinGroup(_ parts: InviteLink.Parts) async -> Bool {
        do {
            _ = try await InviteMembers.acceptMemberInvite(apiBase: apiBase, session: session, inviteId: parts.inviteId, bootstrapSecret: parts.bootstrapSecret, tempPrivateKeyB64: parts.tempPrivateKey)
            if let refreshed = try? await SwitchActiveSpace.listMySpaces(apiBase: apiBase, session: session) { spaces = refreshed }
            return true
        } catch {
            self.error = T("accept.failed")
            return false
        }
    }

    /// Create a shared group the caller owns, refresh the space list, and switch into it.
    func createGroup(displayName: String) async -> Bool {
        let trimmed = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        do {
            let tenant = try await Tenant.createGroup(apiBase: apiBase, token: session.sessionToken, identityKemPublicKey: session.kemPublicKey, displayName: trimmed)
            spaces = try await SwitchActiveSpace.listMySpaces(apiBase: apiBase, session: session)
            if let entry = spaces.first(where: { $0.kind == "TENANT" && $0.tenantId == tenant.id }) { await switchSpace(entry) }
            return true
        } catch {
            self.error = T("groups.createFailed")
            return false
        }
    }

    func refreshSpaces() async {
        if let s = try? await SwitchActiveSpace.listMySpaces(apiBase: apiBase, session: session) {
            spaces = s; groupNames = [:]; await resolveGroupNames()
        }
    }

    /// After a space (group/tenant) is deleted: drop it if active, refresh the roster, and land somewhere.
    func afterSpaceDeleted(scopeId: String) async {
        let wasActive = activeScopeId == scopeId
        await refreshSpaces()
        if wasActive {
            scope = nil; contentKey = nil; activeScopeId = nil; folders = []; memories = []
            if let first = spaces.first(where: { $0.kind == "PERSONAL" }) ?? spaces.first { await switchSpace(first) }
        }
    }

    func deleteNode(_ nodeId: String) async {
        guard let scope else { return }
        do {
            try await Groups.deleteNode(apiBase: apiBase, session: session, scopeId: scope.scopeId, nodeId: nodeId)
            allNodes = nil
            await openLevel()
        } catch { self.error = "Konnte nicht löschen." }
    }

    func deleteGroup(_ entry: SwitchActiveSpace.SpaceEntry) async {
        guard let tid = entry.tenantId else { return }
        do {
            try await Groups.deleteGroup(apiBase: apiBase, session: session, tenantId: tid, scopeId: entry.scopeId)
            await afterSpaceDeleted(scopeId: entry.scopeId)
        } catch { self.error = "Konnte Gruppe nicht löschen." }
    }

    func deleteTenant(_ entry: SwitchActiveSpace.SpaceEntry) async {
        guard let tid = entry.tenantId else { return }
        do {
            try await Groups.deleteTenant(apiBase: apiBase, session: session, tenantId: tid)
            await afterSpaceDeleted(scopeId: entry.scopeId)
        } catch { self.error = "Konnte Tenant nicht löschen." }
    }

    func switchSpace(_ entry: SwitchActiveSpace.SpaceEntry) async {
        do {
            let opened = try await SwitchActiveSpace.openSpace(apiBase: apiBase, session: session, entry: entry)
            let key = try await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: session, scope: opened)
            scope = opened
            contentKey = key
            activeScopeId = entry.scopeId
            allNodes = nil
            crumbs = [Crumb(id: "root", nodeId: nil, name: displayName(for: entry))]
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
            let chosen = (m.node.coverMediaId.flatMap { cid in media.first { $0.id == cid } }) ?? media.first
            guard let item = chosen else { return }
            let ref = item.derivatives.first(where: { $0.kind == "THUMBNAIL" })?.blob ?? item.original
            let bytes = try await Media.downloadBlob(apiBase: apiBase, session: session, scopeId: scope.scopeId, contentKey: contentKey, ref: ref)
            if let img = UIImage(data: bytes) { covers[m.node.id] = img }
        } catch {
            // no cover — leave the placeholder
        }
    }

    /// Valid move targets: the root + every folder not in the node's own subtree.
    func moveDestinations(_ node: MemoryTree.MemoryNode) async -> [(id: String, name: String)] {
        guard let scope, let contentKey else { return [] }
        guard let all = try? await MemoryTree.listNodes(apiBase: apiBase, session: session, scopeId: scope.scopeId) else { return [] }
        let root = scope.scopeId
        func childrenOf(_ id: String) -> [MemoryTree.MemoryNode] { all.filter { ($0.parentId ?? root) == id } }
        var banned: Set<String> = [node.id]
        var stack = [node.id]
        while let cur = stack.popLast() { for c in childrenOf(cur) where !banned.contains(c.id) { banned.insert(c.id); stack.append(c.id) } }
        var dests: [(id: String, name: String)] = [(id: "root", name: T("move.toRoot"))]
        for n in all where n.type == "FOLDER" && !banned.contains(n.id) {
            dests.append((id: n.id, name: (try? MemoryTree.decodeNode(contentKey: contentKey, node: n).name) ?? "—"))
        }
        return dests
    }

    func move(_ entry: Entry, to destId: String) async {
        guard let scope, let contentKey else { return }
        allNodes = nil
        do {
            _ = try await Curate.moveNode(apiBase: apiBase, session: session, scopeId: scope.scopeId, contentKey: contentKey, node: entry.node, newParentId: destId)
            await openLevel()
        } catch {
            self.error = "Konnte nicht verschieben."
        }
    }

    func rename(_ entry: Entry, name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let scope, let contentKey, !trimmed.isEmpty else { return }
        allNodes = nil
        do {
            _ = try await Curate.renameNode(apiBase: apiBase, session: session, scopeId: scope.scopeId, contentKey: contentKey, node: entry.node, newName: trimmed)
            await openLevel()
        } catch {
            self.error = "Konnte nicht umbenennen."
        }
    }
}
