import Foundation
import ContentAPI

/// Client-side tree navigation (cr-0034, parity of web `browse.ts`). The operator-blind server computes no
/// navigation, so the current level + the breadcrumb are derived here from the locally-decrypted tree
/// (`VeyqClient.listTree`). Pure + Sendable — the same logic the web client runs.
public enum MemoryBrowse {
    public typealias Node = VeyqClient.TreeNode

    /// A breadcrumb crumb: `nodeId == nil` is the scope root.
    public struct Crumb: Identifiable, Equatable, Sendable {
        public let nodeId: String?
        public let name: String
        public var id: String { nodeId ?? "" }
        public init(nodeId: String?, name: String) {
            self.nodeId = nodeId
            self.name = name
        }
    }

    /// The children of `parentId` (nil = the scope root), split into folders + Memories, each ordered.
    public static func children(of parentId: String?, in nodes: [Node]) -> (folders: [Node], memories: [Node]) {
        let kids = nodes.filter { $0.parentId == parentId }
            .sorted { ($0.orderIndex, $0.name) < ($1.orderIndex, $1.name) }
        return (kids.filter { $0.kind == .FOLDER }, kids.filter { $0.kind == .MEMORY })
    }

    /// The breadcrumb from the root down to `nodeId` (nil = root): `[root, …ancestors, node]`.
    public static func breadcrumb(to nodeId: String?, in nodes: [Node], rootName: String) -> [Crumb] {
        var byId: [String: Node] = [:]
        for n in nodes { byId[n.id] = n }
        var trail: [Crumb] = []
        var cur = nodeId.flatMap { byId[$0] }
        while let node = cur {
            trail.insert(Crumb(nodeId: node.id, name: node.name), at: 0)
            cur = node.parentId.flatMap { byId[$0] }
        }
        trail.insert(Crumb(nodeId: nil, name: rootName), at: 0)
        return trail
    }

    /// A node by id (nil id → nil), e.g. to title the folder currently open.
    public static func node(_ nodeId: String?, in nodes: [Node]) -> Node? {
        guard let nodeId else { return nil }
        return nodes.first { $0.id == nodeId }
    }
}
