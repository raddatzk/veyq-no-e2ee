import Foundation
import SwiftUI
import VeyqKit

/// The Memory shell's state (cr-0034) — the Apple parity of the web `treeStore` + `activeSpace` + browse
/// derivation. Opens the personal scope, loads the decrypted tree once, and derives the current level +
/// breadcrumb **client-side** (the operator-blind server computes no navigation). Drill is a path stack, like
/// the web client. `ObservableObject` (not `@Observable`) to match the app's macOS 13 / iOS 16 deployment.
@MainActor
final class HomeModel: ObservableObject {
    @Published var nodes: [VeyqClient.TreeNode] = []
    /// The drill path from the scope root to the folder currently open (empty = root).
    @Published var path: [VeyqClient.TreeNode] = []
    @Published var error: String?
    @Published var loading = true

    private var client: VeyqClient?
    private var scopeId: String?
    private var scopeKey: Data?
    let rootName = "Personal"

    var currentParentId: String? { path.last?.id }
    var level: (folders: [VeyqClient.TreeNode], memories: [VeyqClient.TreeNode]) {
        MemoryBrowse.children(of: currentParentId, in: nodes)
    }
    var crumbs: [MemoryBrowse.Crumb] { MemoryBrowse.breadcrumb(to: currentParentId, in: nodes, rootName: rootName) }

    /// Open the personal scope (auto-birth if absent) + load the tree. Idempotent across re-entry.
    func start(client: VeyqClient?) async {
        guard scopeId == nil else { return } // already opened
        guard let client else { error = "Not connected."; loading = false; return }
        self.client = client
        loading = true
        error = nil
        do {
            _ = try await client.ensurePersonalScope()
            guard let personal = try await client.listMyScopes().first(where: { $0.kind == .PERSONAL }),
                  let key = try await client.openPersonalScope()
            else { error = "Couldn’t open your space."; loading = false; return }
            scopeId = personal.id
            scopeKey = key
            try await reload()
        } catch {
            self.error = "Couldn’t open your space."
        }
        loading = false
    }

    func reload() async throws {
        guard let client, let scopeId, let scopeKey else { return }
        nodes = try await client.listTree(scopeId: scopeId, scopeKey: scopeKey)
    }

    func openFolder(_ node: VeyqClient.TreeNode) { path.append(node) }

    func goto(_ crumb: MemoryBrowse.Crumb) {
        guard let target = crumb.nodeId else { path.removeAll(); return }
        if let idx = path.firstIndex(where: { $0.id == target }) { path = Array(path.prefix(idx + 1)) }
    }

    func createFolder(_ name: String) async { await mutate { c, id, key in
        _ = try await c.createFolder(scopeId: id, scopeKey: key, name: name, parentId: self.currentParentId)
    } }

    func createMemory(_ name: String) async { await mutate { c, id, key in
        _ = try await c.createMemory(scopeId: id, scopeKey: key, name: name, parentId: self.currentParentId)
    } }

    func rename(_ node: VeyqClient.TreeNode, to name: String) async { await mutate { c, id, key in
        try await c.renameNode(scopeId: id, scopeKey: key, node: node, newName: name)
    } }

    func delete(_ node: VeyqClient.TreeNode) async {
        // If we're inside the node (or its subtree) being deleted, step back to its parent first.
        if path.contains(where: { $0.id == node.id }) {
            if let idx = path.firstIndex(where: { $0.id == node.id }) { path = Array(path.prefix(idx)) }
        }
        await mutate { c, id, _ in try await c.deleteNode(scopeId: id, nodeId: node.id) }
    }

    /// Run a mutation against the open scope, then reload the tree; surface a generic error on failure.
    private func mutate(_ op: @escaping (VeyqClient, String, Data) async throws -> Void) async {
        guard let client, let scopeId, let scopeKey else { return }
        let trimmed = scopeId
        do {
            try await op(client, trimmed, scopeKey)
            try await reload()
        } catch {
            self.error = "That didn’t work — please try again."
        }
    }
}
