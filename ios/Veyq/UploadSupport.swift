import Foundation

enum UploadQueueState: String, Codable, Equatable {
    case queued
    case uploading
    case failed
    case complete
    case cancelled

    var label: String {
        switch self {
        case .queued: L10n.string("Queued")
        case .uploading: L10n.string("Uploading")
        case .failed: L10n.string("Failed")
        case .complete: L10n.string("Complete")
        case .cancelled: L10n.string("Cancelled")
        }
    }

    var isActive: Bool {
        self == .queued || self == .uploading
    }
}

struct UploadTargetMemory: Codable, Equatable {
    let tenantName: String
    let memoryId: String
    let memoryTitle: String
    let accountId: String?
    let instanceURL: URL?
    let apiBaseURL: URL?
    let userId: Int?

    init(
        tenantName: String,
        memoryId: String,
        memoryTitle: String,
        accountId: String? = nil,
        instanceURL: URL? = nil,
        apiBaseURL: URL? = nil,
        userId: Int? = nil
    ) {
        self.tenantName = tenantName
        self.memoryId = memoryId
        self.memoryTitle = memoryTitle
        self.accountId = accountId
        self.instanceURL = instanceURL
        self.apiBaseURL = apiBaseURL
        self.userId = userId
    }

    /// A target coming from the share extension carries the account it was browsed
    /// with. Targets created inside the host app (already on the active account)
    /// stay unbound.
    var isAccountBound: Bool {
        accountId != nil && instanceURL != nil && apiBaseURL != nil && userId != nil
    }

    func matches(session: StoredNativeSession) -> Bool {
        guard let accountId, let instanceURL, let apiBaseURL, let userId else { return false }
        return accountId == session.instanceURL.absoluteString
            && instanceURL == session.instanceURL
            && apiBaseURL == session.apiBaseURL
            && userId == session.user.userId
    }
}

struct UploadQueueItem: Identifiable, Codable, Equatable {
    let id: String
    let localFileURL: URL
    let filename: String
    let mimeType: String
    let sizeBytes: Int64
    let target: UploadTargetMemory
    var progress: Double
    var state: UploadQueueState
    var uploadId: String?
    var errorMessage: String?
    var uploadedBytes: Int64
    var startedAt: Date?
    var progressUpdatedAt: Date?
    var retryable: Bool

    init(
        id: String,
        localFileURL: URL,
        filename: String,
        mimeType: String,
        sizeBytes: Int64,
        target: UploadTargetMemory,
        progress: Double,
        state: UploadQueueState,
        uploadId: String? = nil,
        errorMessage: String? = nil,
        uploadedBytes: Int64? = nil,
        startedAt: Date? = nil,
        progressUpdatedAt: Date? = nil,
        retryable: Bool = true
    ) {
        self.id = id
        self.localFileURL = localFileURL
        self.filename = filename
        self.mimeType = mimeType
        self.sizeBytes = sizeBytes
        self.target = target
        self.progress = progress
        self.state = state
        self.uploadId = uploadId
        self.errorMessage = errorMessage
        self.uploadedBytes = uploadedBytes ?? Int64((Double(sizeBytes) * progress).rounded())
        self.startedAt = startedAt
        self.progressUpdatedAt = progressUpdatedAt
        self.retryable = retryable
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case localFileURL
        case filename
        case mimeType
        case sizeBytes
        case target
        case progress
        case state
        case uploadId
        case errorMessage
        case uploadedBytes
        case startedAt
        case progressUpdatedAt
        case retryable
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        localFileURL = try container.decode(URL.self, forKey: .localFileURL)
        filename = try container.decode(String.self, forKey: .filename)
        mimeType = try container.decode(String.self, forKey: .mimeType)
        sizeBytes = try container.decode(Int64.self, forKey: .sizeBytes)
        target = try container.decode(UploadTargetMemory.self, forKey: .target)
        progress = try container.decode(Double.self, forKey: .progress)
        state = try container.decode(UploadQueueState.self, forKey: .state)
        uploadId = try container.decodeIfPresent(String.self, forKey: .uploadId)
        errorMessage = try container.decodeIfPresent(String.self, forKey: .errorMessage)
        uploadedBytes = try container.decodeIfPresent(Int64.self, forKey: .uploadedBytes)
            ?? Int64((Double(sizeBytes) * progress).rounded())
        startedAt = try container.decodeIfPresent(Date.self, forKey: .startedAt)
        progressUpdatedAt = try container.decodeIfPresent(Date.self, forKey: .progressUpdatedAt)
        retryable = try container.decodeIfPresent(Bool.self, forKey: .retryable) ?? true
    }

    var restoredForLaunch: UploadQueueItem {
        guard state == .uploading else { return self }
        var restored = self
        restored.state = .queued
        restored.errorMessage = nil
        restored.startedAt = nil
        restored.retryable = true
        return restored
    }

    static let samples: [UploadQueueItem] = [
        UploadQueueItem(
            id: "queued",
            localFileURL: URL(fileURLWithPath: "/tmp/IMG_2042.HEIC"),
            filename: "IMG_2042.HEIC",
            mimeType: "image/heic",
            sizeBytes: 2_048_000,
            target: UploadTargetMemory(tenantName: "family", memoryId: "summer-south-tyrol", memoryTitle: "Summer in South Tyrol"),
            progress: 0,
            state: .queued
        ),
        UploadQueueItem(
            id: "uploading",
            localFileURL: URL(fileURLWithPath: "/tmp/clip-trail.mov"),
            filename: "clip-trail.mov",
            mimeType: "video/quicktime",
            sizeBytes: 16_000_000,
            target: UploadTargetMemory(tenantName: "family", memoryId: "summer-south-tyrol", memoryTitle: "Summer in South Tyrol"),
            progress: 0.62,
            state: .uploading,
            uploadedBytes: 9_920_000,
            startedAt: Date(timeIntervalSince1970: 1_800_000_000),
            progressUpdatedAt: Date(timeIntervalSince1970: 1_800_000_010)
        ),
        UploadQueueItem(
            id: "failed",
            localFileURL: URL(fileURLWithPath: "/tmp/family-table.jpg"),
            filename: "family-table.jpg",
            mimeType: "image/jpeg",
            sizeBytes: 1_600_000,
            target: UploadTargetMemory(tenantName: "family", memoryId: "birthday-weekend", memoryTitle: "Birthday Weekend"),
            progress: 0.38,
            state: .failed,
            errorMessage: "The upload was interrupted.",
            uploadedBytes: 608_000
        ),
        UploadQueueItem(
            id: "complete",
            localFileURL: URL(fileURLWithPath: "/tmp/sunset.jpg"),
            filename: "sunset.jpg",
            mimeType: "image/jpeg",
            sizeBytes: 1_200_000,
            target: UploadTargetMemory(tenantName: "family", memoryId: "birthday-weekend", memoryTitle: "Birthday Weekend"),
            progress: 1,
            state: .complete
        )
    ]
}

struct PendingImportBatch: Identifiable, Codable, Equatable {
    let id: String
    let fileURLs: [URL]
    let createdAt: Date
    let targetMemory: UploadTargetMemory?

    init(id: String, fileURLs: [URL], createdAt: Date, targetMemory: UploadTargetMemory? = nil) {
        self.id = id
        self.fileURLs = fileURLs
        self.createdAt = createdAt
        self.targetMemory = targetMemory
    }
}

enum PendingImportPresentationAction: Equatable {
    case enqueueTargeted([PendingImportBatch])
    case none
}

enum PendingImportPresentationPlanner {
    static func action(
        for batches: [PendingImportBatch],
        pendingSheetBatchId: String?,
        activeHandoffBatchIds: Set<String>
    ) -> PendingImportPresentationAction {
        guard pendingSheetBatchId == nil else { return .none }

        let importableBatches = batches
            .filter { !$0.fileURLs.isEmpty }
            .filter { !activeHandoffBatchIds.contains($0.id) }
            .sorted { $0.createdAt > $1.createdAt }

        let targetedBatches = importableBatches.filter { $0.targetMemory != nil }
        if !targetedBatches.isEmpty {
            return .enqueueTargeted(targetedBatches)
        }

        return .none
    }
}

struct ShareImportMemoryTarget: Identifiable, Codable, Equatable {
    let id: String
    let title: String
    let dateRange: String
    let tenantName: String
    let coverUrl: String?
    let coverCrop: CoverCrop?
    let coverThumbnailFilename: String?
    let parentMemoryTitle: String?
    let depth: Int?
    let mediaCount: Int?
    let childCount: Int?

    init(
        id: String,
        title: String,
        dateRange: String,
        tenantName: String,
        coverUrl: String?,
        coverCrop: CoverCrop? = nil,
        coverThumbnailFilename: String?,
        parentMemoryTitle: String? = nil,
        depth: Int = 0,
        mediaCount: Int? = nil,
        childCount: Int? = nil
    ) {
        self.id = id
        self.title = title
        self.dateRange = dateRange
        self.tenantName = tenantName
        self.coverUrl = coverUrl
        self.coverCrop = coverCrop
        self.coverThumbnailFilename = coverThumbnailFilename
        self.parentMemoryTitle = parentMemoryTitle
        self.depth = depth
        self.mediaCount = mediaCount
        self.childCount = childCount
    }
}

struct ShareImportTargetSnapshot: Codable, Equatable {
    let targets: [ShareImportMemoryTarget]
    let lastUsedMemoryId: String?
    let updatedAt: Date
    let includesOnlyKnownChapters: Bool?

    init(
        targets: [ShareImportMemoryTarget],
        lastUsedMemoryId: String?,
        updatedAt: Date,
        includesOnlyKnownChapters: Bool? = nil
    ) {
        self.targets = targets
        self.lastUsedMemoryId = lastUsedMemoryId
        self.updatedAt = updatedAt
        self.includesOnlyKnownChapters = includesOnlyKnownChapters
    }
}

struct SharedVeyqAccount: Identifiable, Codable, Equatable {
    let id: String
    let instanceURL: URL
    let apiBaseURL: URL
    let userId: Int
    let displayName: String
    let email: String
    let active: Bool

    init(session: StoredNativeSession, active: Bool) {
        self.id = session.instanceURL.absoluteString
        self.instanceURL = session.instanceURL
        self.apiBaseURL = session.apiBaseURL
        self.userId = session.user.userId
        self.displayName = session.user.displayName
        self.email = session.user.email
        self.active = active
    }
}

struct SharedVeyqAccountSnapshot: Codable, Equatable {
    let accounts: [SharedVeyqAccount]
    let activeAccountId: String?
    let updatedAt: Date
}

enum SharedAccountPersistence {
    static func save(sessions: [StoredNativeSession], activeInstanceURL: URL?) {
        let activeId = activeInstanceURL?.absoluteString
        let accounts = sessions.map { session in
            SharedVeyqAccount(session: session, active: session.instanceURL.absoluteString == activeId)
        }
        save(
            SharedVeyqAccountSnapshot(
                accounts: accounts,
                activeAccountId: activeId,
                updatedAt: Date()
            )
        )
    }

    static func save(_ snapshot: SharedVeyqAccountSnapshot) {
        guard let data = try? JSONEncoder.veyq.encode(snapshot) else { return }
        try? data.write(to: fileURL, options: [.atomic])
    }

    static func load() -> SharedVeyqAccountSnapshot? {
        guard let data = try? Data(contentsOf: fileURL) else { return nil }
        return try? JSONDecoder.veyq.decode(SharedVeyqAccountSnapshot.self, from: data)
    }

    static func clear() {
        try? FileManager.default.removeItem(at: fileURL)
    }

    private static var fileURL: URL {
        UploadQueuePersistence.sharedContainerURL().appendingPathComponent("shared-accounts.json")
    }
}

enum VeyqUploadFileKind {
    case image
    case video
    case track
}

enum VeyqPendingImportFileKind {
    case image
    case video
    case track
}

enum UploadQueuePersistence {
    static let appGroupIdentifier = "group.app.veyq.ios"

    static var uploadDirectory: URL {
        let root = sharedContainerURL()
        let directory = root.appendingPathComponent("Uploads", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    static var pendingImportDirectory: URL {
        let root = sharedContainerURL()
        let directory = root.appendingPathComponent("PendingImports", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    static var shareImportCoverDirectory: URL {
        let root = sharedContainerURL()
        let directory = root.appendingPathComponent("ShareImportCovers", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    static func loadQueue() -> [UploadQueueItem] {
        guard let data = try? Data(contentsOf: queueFileURL) else { return [] }
        return ((try? JSONDecoder.veyq.decode([UploadQueueItem].self, from: data)) ?? [])
            .map(\.restoredForLaunch)
    }

    static func saveQueue(_ items: [UploadQueueItem]) {
        guard let data = try? JSONEncoder.veyq.encode(items) else { return }
        try? data.write(to: queueFileURL, options: [.atomic])
    }

    static func saveShareImportTargets(_ snapshot: ShareImportTargetSnapshot) {
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        try? data.write(to: shareImportTargetsFileURL, options: [.atomic])
    }

    static func loadShareImportTargets() -> ShareImportTargetSnapshot? {
        guard let data = try? Data(contentsOf: shareImportTargetsFileURL) else { return nil }
        return try? JSONDecoder().decode(ShareImportTargetSnapshot.self, from: data)
    }

    static func shareImportCoverURL(filename: String) -> URL {
        shareImportCoverDirectory.appendingPathComponent((filename as NSString).lastPathComponent)
    }

    static func removeUnreferencedShareImportCovers(keeping filenames: Set<String>) {
        removeUnreferencedShareImportCovers(in: shareImportCoverDirectory, keeping: filenames)
    }

    static func removeUnreferencedShareImportCovers(in directory: URL, keeping filenames: Set<String>) {
        guard let coverFiles = try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        ) else {
            return
        }
        coverFiles
            .filter { !filenames.contains($0.lastPathComponent) }
            .forEach { try? FileManager.default.removeItem(at: $0) }
    }

    static func loadPendingImportBatch(id: String) -> PendingImportBatch? {
        let url = pendingImportDirectory
            .appendingPathComponent(id, isDirectory: true)
            .appendingPathComponent("batch.json")
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(PendingImportBatch.self, from: data)
    }

    static func loadLatestPendingImportBatch() -> PendingImportBatch? {
        loadPendingImportBatches()
            .filter { !$0.fileURLs.isEmpty }
            .sorted { $0.createdAt > $1.createdAt }
            .first
    }

    static func loadPendingImportBatches() -> [PendingImportBatch] {
        guard let batchDirectories = try? FileManager.default.contentsOfDirectory(
            at: pendingImportDirectory,
            includingPropertiesForKeys: [.contentModificationDateKey],
            options: [.skipsHiddenFiles]
        ) else {
            return []
        }

        return batchDirectories
            .compactMap { directory -> PendingImportBatch? in
                let batchURL = directory.appendingPathComponent("batch.json")
                guard let data = try? Data(contentsOf: batchURL) else { return nil }
                return try? JSONDecoder().decode(PendingImportBatch.self, from: data)
            }
    }

    static func removePendingImportBatch(id: String) {
        let directory = pendingImportDirectory.appendingPathComponent(id, isDirectory: true)
        try? FileManager.default.removeItem(at: directory)
    }

    static func removeUntargetedPendingImportBatches(_ batches: [PendingImportBatch]) {
        batches
            .filter { $0.targetMemory == nil }
            .forEach { removePendingImportBatch(id: $0.id) }
    }

    static func copyIntoUploadStore(sourceURL: URL, preferredFilename: String, kind: VeyqUploadFileKind) throws -> URL {
        let safeFilename = sanitizedFilename(preferredFilename, fallbackExtension: fallbackExtension(for: kind))
        let destination = uploadDirectory.appendingPathComponent("\(UUID().uuidString)-\(safeFilename)")
        if FileManager.default.fileExists(atPath: destination.path) {
            try FileManager.default.removeItem(at: destination)
        }
        try FileManager.default.copyItem(at: sourceURL, to: destination)
        return destination
    }

    static func writeIntoUploadStore(data: Data, preferredFilename: String, kind: VeyqUploadFileKind) throws -> URL {
        let safeFilename = sanitizedFilename(preferredFilename, fallbackExtension: fallbackExtension(for: kind))
        let destination = uploadDirectory.appendingPathComponent("\(UUID().uuidString)-\(safeFilename)")
        try data.write(to: destination, options: [.atomic])
        return destination
    }

    private static func fallbackExtension(for kind: VeyqUploadFileKind) -> String {
        return switch kind {
        case .image: "jpg"
        case .video: "mov"
        case .track: "gpx"
        }
    }

    static func sharedContainerURL() -> URL {
        if let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier) {
            return container
        }
        return FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    private static var queueFileURL: URL {
        uploadDirectory.appendingPathComponent("queue.json")
    }

    private static var shareImportTargetsFileURL: URL {
        sharedContainerURL().appendingPathComponent("share-import-targets.json")
    }

    private static func sanitizedFilename(_ value: String, fallbackExtension: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = trimmed.isEmpty ? "Upload.\(fallbackExtension)" : trimmed
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "._- "))
        let scalars = name.unicodeScalars.map { allowed.contains($0) ? Character($0) : "-" }
        return String(scalars)
    }
}

func mimeType(for filename: String, kind: VeyqUploadFileKind) -> String {
    let lower = filename.lowercased()
    if lower.hasSuffix(".heic") || lower.hasSuffix(".heif") { return "image/heic" }
    if lower.hasSuffix(".png") { return "image/png" }
    if lower.hasSuffix(".webp") { return "image/webp" }
    if lower.hasSuffix(".mp4") { return "video/mp4" }
    if lower.hasSuffix(".m4v") { return "video/mp4" }
    if lower.hasSuffix(".mov") { return "video/quicktime" }
    return switch kind {
    case .image: "image/jpeg"
    case .video: "video/quicktime"
    case .track: "application/gpx+xml"
    }
}

func isGPXFilename(_ filename: String) -> Bool {
    filename.lowercased().hasSuffix(".gpx")
}

struct CreateMemoryMediaUploadBody: Encodable {
    let filename: String
    let mimeType: String
    let sizeBytes: Int64
    let title: String?
    let bucketId: String?
}

struct MemoryMediaUploadSessionResponse: Codable, Equatable {
    let uploadId: String
    let chunkSizeBytes: Int64?
}

struct PendingMemoryMediaUploadSessionResponse: Codable, Equatable {
    let uploadId: String
    let filename: String?
    let mimeType: String?
    let sizeBytes: Int64
    let chunkSizeBytes: Int64
    let uploadedChunks: [Int]
    let uploadedBytes: Int64
}

enum UploadChunkPlanner {
    static func ranges(sizeBytes: Int64, chunkSizeBytes: Int64) -> [Range<Int64>] {
        guard sizeBytes > 0, chunkSizeBytes > 0 else { return [] }
        var ranges: [Range<Int64>] = []
        var start: Int64 = 0
        while start < sizeBytes {
            let end = min(start + chunkSizeBytes, sizeBytes)
            ranges.append(start..<end)
            start = end
        }
        return ranges
    }
}

enum UploadQueueProgressPresenter {
    static let stalledInterval: TimeInterval = 30

    static func detailText(for item: UploadQueueItem, now: Date = Date()) -> String? {
        if isStalled(item, now: now) {
            return L10n.string("Upload appears stalled. It will keep waiting for network progress.")
        }

        let percent = Int((item.progress * 100).rounded())
        switch item.state {
        case .queued where item.uploadedBytes > 0:
            return L10n.format("%d%% uploaded. Will resume when next in queue.", percent)
        case .uploading:
            if let remaining = remainingText(for: item, now: now) {
                return L10n.format("%d%% uploaded • %@", percent, remaining)
            }
            return L10n.format("%d%% uploaded", percent)
        case .failed where !item.retryable:
            return L10n.string("The local file is no longer available. Add it again to upload.")
        case .failed where item.uploadedBytes > 0:
            return L10n.format("%d%% uploaded. Retry will resume where possible.", percent)
        default:
            return nil
        }
    }

    static func isStalled(_ item: UploadQueueItem, now: Date = Date()) -> Bool {
        guard item.state == .uploading,
              item.progress < 1,
              let progressUpdatedAt = item.progressUpdatedAt
        else {
            return false
        }
        return now.timeIntervalSince(progressUpdatedAt) >= stalledInterval
    }

    static func remainingText(for item: UploadQueueItem, now: Date = Date()) -> String? {
        guard item.state == .uploading,
              item.uploadedBytes > 0,
              item.sizeBytes > item.uploadedBytes,
              let startedAt = item.startedAt
        else {
            return nil
        }

        let elapsed = now.timeIntervalSince(startedAt)
        guard elapsed > 0 else { return nil }
        let bytesPerSecond = Double(item.uploadedBytes) / elapsed
        guard bytesPerSecond > 0 else { return nil }
        let remainingSeconds = Double(item.sizeBytes - item.uploadedBytes) / bytesPerSecond
        if remainingSeconds < 60 {
            return L10n.string("less than a minute remaining")
        }

        let minutes = max(1, Int((remainingSeconds / 60).rounded()))
        if minutes < 60 {
            return L10n.format("%d min remaining", minutes)
        }

        let hours = minutes / 60
        let remainder = minutes % 60
        if remainder == 0 {
            return L10n.format("%d hr remaining", hours)
        }
        return L10n.format("%d hr %d min remaining", hours, remainder)
    }
}
