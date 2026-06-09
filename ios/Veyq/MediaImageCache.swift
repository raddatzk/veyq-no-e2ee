import Foundation
import UIKit

final class MediaImageCache {
    struct Scope: Hashable {
        let instanceURL: URL
        let tenantName: String
        let userId: Int
    }

    static let shared = MediaImageCache()
    static let defaultTimeToLive: TimeInterval = 14 * 24 * 60 * 60

    private final class CachedImage {
        let image: UIImage
        let storedAt: Date

        init(image: UIImage, storedAt: Date) {
            self.image = image
            self.storedAt = storedAt
        }
    }

    private let memoryCache = NSCache<NSString, CachedImage>()
    private let rootDirectory: URL
    private let timeToLive: TimeInterval
    private let fileManager: FileManager
    private let now: () -> Date

    init(
        rootDirectory: URL? = nil,
        timeToLive: TimeInterval = MediaImageCache.defaultTimeToLive,
        fileManager: FileManager = .default,
        now: @escaping () -> Date = Date.init
    ) {
        self.rootDirectory = rootDirectory ?? fileManager.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("VeyqMediaImageCache", isDirectory: true)
        self.timeToLive = timeToLive
        self.fileManager = fileManager
        self.now = now
        memoryCache.countLimit = 240
        memoryCache.totalCostLimit = 64 * 1024 * 1024
    }

    func image(for urlString: String, scope: Scope) -> UIImage? {
        guard isCacheableVariant(urlString) else { return nil }
        let key = cacheKey(for: urlString, scope: scope)
        if let cached = memoryCache.object(forKey: key as NSString) {
            guard !isExpired(cached.storedAt) else {
                memoryCache.removeObject(forKey: key as NSString)
                removeDiskEntry(for: urlString, scope: scope)
                return nil
            }
            return cached.image
        }

        let fileURL = diskURL(for: urlString, scope: scope)
        guard let storedAt = modificationDate(for: fileURL), !isExpired(storedAt) else {
            removeDiskEntry(for: urlString, scope: scope)
            return nil
        }
        guard let data = try? Data(contentsOf: fileURL), let image = UIImage(data: data) else {
            removeDiskEntry(for: urlString, scope: scope)
            return nil
        }
        storeInMemory(image, key: key, storedAt: storedAt)
        return image
    }

    func store(_ image: UIImage, data: Data, for urlString: String, scope: Scope) {
        guard isCacheableVariant(urlString) else { return }
        let key = cacheKey(for: urlString, scope: scope)
        let storedAt = now()
        storeInMemory(image, key: key, storedAt: storedAt)

        let fileURL = diskURL(for: urlString, scope: scope)
        try? fileManager.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: fileURL, options: [.atomic])
        try? fileManager.setAttributes([.modificationDate: storedAt], ofItemAtPath: fileURL.path)
    }

    func clearMemory() {
        memoryCache.removeAllObjects()
    }

    func removeDiskCache(for scope: Scope) {
        clearMemory()
        try? fileManager.removeItem(at: scopeDirectory(for: scope))
    }

    func removeDiskCache(forInstanceURL instanceURL: URL) {
        clearMemory()
        guard let directories = try? fileManager.contentsOfDirectory(
            at: rootDirectory,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        ) else {
            return
        }
        let prefix = "\(stableHash(instanceURL.absoluteString))-"
        directories
            .filter { $0.lastPathComponent.hasPrefix(prefix) }
            .forEach { try? fileManager.removeItem(at: $0) }
    }

    func removeAllDiskCache() {
        clearMemory()
        try? fileManager.removeItem(at: rootDirectory)
    }

    static func isCacheableVariant(_ urlString: String) -> Bool {
        guard let path = URL(string: urlString)?.path ?? URLComponents(string: urlString)?.path else {
            return false
        }
        let component = (path as NSString).lastPathComponent.lowercased()
        return component == "thumbnail" || component == "preview" || component == "poster"
    }

    private func isCacheableVariant(_ urlString: String) -> Bool {
        Self.isCacheableVariant(urlString)
    }

    private func isExpired(_ storedAt: Date) -> Bool {
        now().timeIntervalSince(storedAt) > timeToLive
    }

    private func storeInMemory(_ image: UIImage, key: String, storedAt: Date) {
        memoryCache.setObject(CachedImage(image: image, storedAt: storedAt), forKey: key as NSString, cost: imageCacheCost(image))
    }

    private func imageCacheCost(_ image: UIImage) -> Int {
        let scale = max(image.scale, 1)
        let pixels = image.size.width * scale * image.size.height * scale
        return max(Int(pixels) * 4, 1)
    }

    private func cacheKey(for urlString: String, scope: Scope) -> String {
        "\(scopeDirectoryName(for: scope))/\(stableHash(urlString))"
    }

    private func diskURL(for urlString: String, scope: Scope) -> URL {
        scopeDirectory(for: scope).appendingPathComponent("\(stableHash(urlString)).img")
    }

    private func scopeDirectory(for scope: Scope) -> URL {
        rootDirectory.appendingPathComponent(scopeDirectoryName(for: scope), isDirectory: true)
    }

    private func scopeDirectoryName(for scope: Scope) -> String {
        "\(stableHash(scope.instanceURL.absoluteString))-\(stableHash(scope.tenantName))-\(stableHash(String(scope.userId)))"
    }

    private func modificationDate(for fileURL: URL) -> Date? {
        (try? fileManager.attributesOfItem(atPath: fileURL.path)[.modificationDate]) as? Date
    }

    private func removeDiskEntry(for urlString: String, scope: Scope) {
        try? fileManager.removeItem(at: diskURL(for: urlString, scope: scope))
    }

    private func stableHash(_ value: String) -> String {
        var hash: UInt64 = 14_695_981_039_346_656_037
        for byte in value.utf8 {
            hash ^= UInt64(byte)
            hash &*= 1_099_511_628_211
        }
        return String(hash, radix: 16)
    }
}
