import Foundation
import VeyqCrypto

/// Build a zip of a memory's decrypted originals, fully client-side (the server holds only ciphertext).
/// "Mit Kapiteln" walks the memory's CHAPTER descendants. Zipping uses NSFileCoordinator's .forUploading,
/// so no hand-rolled zip writer.
enum DownloadService {
    struct Item { let name: String; let data: Data }

    static func buildZip(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, nodeId: String, name: String, withChapters: Bool) async throws -> URL {
        var files: [Item] = []
        try await gather(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: nodeId, prefix: "", withChapters: withChapters, into: &files)
        return try zipToTemp(files, name: sanitize(name))
    }

    private static func gather(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, nodeId: String, prefix: String, withChapters: Bool, into files: inout [Item]) async throws {
        let media = try await Media.listMedia(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId)
        for (i, m) in media.enumerated() {
            let data = try await Media.downloadBlob(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, ref: m.original)
            files.append(Item(name: "\(prefix)\(String(format: "%03d", i + 1)).\(ext(m.mimeType))", data: data))
        }
        if withChapters {
            let children = try await Browse.listChildren(apiBase: apiBase, session: session, scopeId: scopeId, parentId: nodeId)
            for c in children where c.type == "CHAPTER" {
                let cname = (try? MemoryTree.decodeNode(contentKey: contentKey, node: c).name) ?? "Kapitel"
                try await gather(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: c.id, prefix: "\(prefix)\(sanitize(cname))/", withChapters: true, into: &files)
            }
        }
    }

    private static func zipToTemp(_ files: [Item], name: String) throws -> URL {
        let fm = FileManager.default
        let parent = fm.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let dir = parent.appendingPathComponent(name, isDirectory: true)
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        for f in files {
            let url = dir.appendingPathComponent(f.name)
            try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try f.data.write(to: url)
        }
        var result: URL?
        var coordError: NSError?
        NSFileCoordinator().coordinate(readingItemAt: dir, options: .forUploading, error: &coordError) { zipped in
            let dest = fm.temporaryDirectory.appendingPathComponent("\(name).zip")
            try? fm.removeItem(at: dest)
            try? fm.copyItem(at: zipped, to: dest)
            result = dest
        }
        if let coordError { throw coordError }
        guard let result else { throw NSError(domain: "veyq.zip", code: 1) }
        return result
    }

    private static func ext(_ mime: String) -> String {
        ["image/jpeg": "jpg", "image/png": "png", "image/gif": "gif", "image/webp": "webp", "video/mp4": "mp4", "video/quicktime": "mov"][mime] ?? "bin"
    }
    private static func sanitize(_ s: String) -> String { s.replacingOccurrences(of: "/", with: "_") }
}
