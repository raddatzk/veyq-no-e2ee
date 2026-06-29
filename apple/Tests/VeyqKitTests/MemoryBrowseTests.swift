import XCTest
import ContentAPI
@testable import VeyqKit

/// Client-side level + breadcrumb derivation (cr-0034) — the operator-blind server computes no navigation,
/// so this logic (parity of web `browse.test.ts`) is what assembles the tree from the decrypted rows.
final class MemoryBrowseTests: XCTestCase {
    private func node(_ id: String, _ kind: ContentAPI.Components.Schemas.NodeKind, _ name: String,
                      parent: String? = nil, order: Int = 0) -> VeyqClient.TreeNode {
        .init(id: id, parentId: parent, kind: kind, orderIndex: order, name: name, wrappedContentKey: nil)
    }

    // root: folder "Trips" + memory "Solo"; "Trips" holds memory "Italy" + folder "2025".
    private var tree: [VeyqClient.TreeNode] {
        [
            node("trips", .FOLDER, "Trips", order: 1),
            node("solo", .MEMORY, "Solo", order: 0),
            node("italy", .MEMORY, "Italy", parent: "trips"),
            node("2025", .FOLDER, "2025", parent: "trips"),
        ]
    }

    func testChildrenSplitsFoldersAndMemoriesOrdered() {
        let root = MemoryBrowse.children(of: nil, in: tree)
        XCTAssertEqual(root.folders.map(\.name), ["Trips"])
        XCTAssertEqual(root.memories.map(\.name), ["Solo"])

        let inTrips = MemoryBrowse.children(of: "trips", in: tree)
        XCTAssertEqual(inTrips.folders.map(\.name), ["2025"])
        XCTAssertEqual(inTrips.memories.map(\.name), ["Italy"])
    }

    func testChildrenEmptyForALeaf() {
        let level = MemoryBrowse.children(of: "solo", in: tree)
        XCTAssertTrue(level.folders.isEmpty && level.memories.isEmpty)
    }

    func testBreadcrumbWalksRootToNode() {
        let trail = MemoryBrowse.breadcrumb(to: "2025", in: tree, rootName: "Personal")
        XCTAssertEqual(trail.map(\.name), ["Personal", "Trips", "2025"])
        XCTAssertEqual(trail.map(\.nodeId), [nil, "trips", "2025"])
    }

    func testBreadcrumbIsJustTheRootAtTheTopLevel() {
        let trail = MemoryBrowse.breadcrumb(to: nil, in: tree, rootName: "Personal")
        XCTAssertEqual(trail.map(\.name), ["Personal"])
    }

    func testNodeLookup() {
        XCTAssertEqual(MemoryBrowse.node("italy", in: tree)?.name, "Italy")
        XCTAssertNil(MemoryBrowse.node(nil, in: tree))
        XCTAssertNil(MemoryBrowse.node("nope", in: tree))
    }

    // A drill path Trips → 2025 (the shell's `HomeModel.path` stack).
    private var deepPath: [VeyqClient.TreeNode] {
        [node("trips", .FOLDER, "Trips", order: 1), node("2025", .FOLDER, "2025", parent: "trips")]
    }

    func testNavigatingToRootClearsThePath() {
        XCTAssertEqual(MemoryBrowse.path(deepPath, navigatingTo: nil).map(\.id), [])
    }

    func testNavigatingToACrumbTruncatesToIt() {
        XCTAssertEqual(MemoryBrowse.path(deepPath, navigatingTo: "trips").map(\.id), ["trips"])
        // tapping the deepest crumb is a no-op (whole path kept)
        XCTAssertEqual(MemoryBrowse.path(deepPath, navigatingTo: "2025").map(\.id), ["trips", "2025"])
    }

    func testNavigatingToANodeNotOnThePathLeavesItUnchanged() {
        XCTAssertEqual(MemoryBrowse.path(deepPath, navigatingTo: "elsewhere").map(\.id), ["trips", "2025"])
    }

    func testDeletingAnAncestorStepsBackToItsParent() {
        // deleting "trips" while inside Trips → 2025 drops it and everything below
        XCTAssertEqual(MemoryBrowse.path(deepPath, afterDeleting: "trips").map(\.id), [])
        // deleting the open leaf "2025" steps back to "trips"
        XCTAssertEqual(MemoryBrowse.path(deepPath, afterDeleting: "2025").map(\.id), ["trips"])
    }

    func testDeletingASiblingOutsideThePathLeavesItUnchanged() {
        XCTAssertEqual(MemoryBrowse.path(deepPath, afterDeleting: "solo").map(\.id), ["trips", "2025"])
    }
}
