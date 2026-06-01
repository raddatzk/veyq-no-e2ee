import Foundation
import UIKit

@MainActor
final class VeyqStore: ObservableObject {
    @Published private(set) var connectedInstanceURL: URL?
    @Published private(set) var instances: [StoredNativeSession] = []
    @Published private(set) var user: VeyqUser?
    @Published private(set) var tenants: [VeyqTenant] = []
    @Published private(set) var selectedTenant: VeyqTenant?
    @Published private(set) var spaces: [VeyqSpace] = []
    @Published private(set) var selectedSpace: VeyqSpace?
    @Published private(set) var memories: [Memory] = []
    @Published private(set) var memoryDetails: [String: LoadedMemoryDetail] = [:]
    @Published private(set) var memoryDetailLoadingIDs: Set<String> = []
    @Published private(set) var memoryDetailMediaErrors: [String: String] = [:]
    @Published private(set) var uploadItems: [UploadQueueItem]
    @Published private(set) var loading = false
    @Published private(set) var errorMessage: String?

    private var apiClient: VeyqAPIClient?
    private let instanceRepository: InstanceRepository
    private let sessionStore: NativeSessionStoring
    private let authenticator: MobileAuthenticating
    private let apiSession: URLSession
    private let mockMode: Bool
    private var uploadTasks: [String: Task<Void, Never>] = [:]
    private var activeCapabilities = Set<String>()

    var isConnected: Bool {
        connectedInstanceURL != nil
    }

    var visibleMemories: [Memory] {
        memories
    }

    init(
        instanceRepository: InstanceRepository = DefaultInstanceRepository(),
        sessionStore: NativeSessionStoring = KeychainNativeSessionStore(),
        authenticator: MobileAuthenticating? = nil,
        apiSession: URLSession = .shared,
        mockMode: Bool = false
    ) {
        self.instanceRepository = instanceRepository
        self.sessionStore = sessionStore
        self.authenticator = authenticator ?? SystemMobileAuthenticator()
        self.apiSession = apiSession
        self.mockMode = mockMode
        self.uploadItems = mockMode ? UploadQueueItem.samples : UploadQueuePersistence.loadQueue()
    }

    func restoreSession() async {
        guard !mockMode else { return }
        do {
            instances = try sessionStore.loadAll()
            guard let session = try sessionStore.loadActive() else { return }
            try await activateRestoredSession(session)
        } catch {
            clearActiveState()
            errorMessage = error.localizedDescription
        }
    }

    @discardableResult
    func connect(to rawValue: String) async throws -> URL {
        let normalized = try instanceRepository.normalizeInstanceURL(rawValue)
        if mockMode {
            connectMock(to: normalized)
            return normalized
        }

        loading = true
        errorMessage = nil
        defer { loading = false }

        let discoveryClient = VeyqAPIClient(instanceURL: normalized, session: apiSession)
        let discovery = try await discoveryClient.discover()
        let capabilities = try await discoveryClient.clientCapabilities(from: discovery.clientCapabilitiesUrl)
        try validateRequiredCapabilities(capabilities)

        let state = UUID().uuidString
        let loginURL = mobileLoginURL(discovery.mobileLoginUrl, state: state)
        let callbackURL = try await authenticator.authenticate(loginURL: loginURL, callbackScheme: discovery.mobileCallbackScheme)
        let code = try mobileCode(from: callbackURL, expectedState: state)
        let sessionClient = VeyqAPIClient(instanceURL: normalized, apiBaseURL: discovery.apiBaseUrl, session: apiSession)
        let nativeSession = try await sessionClient.exchangeMobileSession(code: code)
        let storedSession = StoredNativeSession(
            instanceURL: normalized,
            apiBaseURL: discovery.apiBaseUrl,
            accessToken: nativeSession.accessToken,
            accessTokenExpiresAt: nativeSession.accessTokenExpiresAt,
            refreshToken: nativeSession.refreshToken,
            refreshTokenExpiresAt: nativeSession.refreshTokenExpiresAt,
            user: nativeSession.user,
            capabilities: capabilities.capabilities
        )
        try sessionStore.save(storedSession)

        instances = try sessionStore.loadAll()
        try await activate(storedSession)
        return normalized
    }

    func disconnect() {
        if !mockMode, let client = apiClient {
            Task { try? await client.logoutMobileSession() }
            try? sessionStore.delete()
        }
        cancelRunningUploadTasks()
        connectedInstanceURL = nil
        instances = []
        user = nil
        tenants = []
        selectedTenant = nil
        spaces = []
        selectedSpace = nil
        memories = []
        memoryDetails = [:]
        apiClient = nil
        activeCapabilities = []
        errorMessage = nil
        uploadItems = mockMode ? UploadQueueItem.samples : UploadQueuePersistence.loadQueue()
    }

    func switchInstance(_ session: StoredNativeSession) async {
        do {
            try sessionStore.save(session)
            instances = try sessionStore.loadAll()
            try await activate(session)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func deleteInstance(_ session: StoredNativeSession) async {
        do {
            if !mockMode, connectedInstanceURL == session.instanceURL {
                try? await VeyqAPIClient(instanceURL: session.instanceURL, apiBaseURL: session.apiBaseURL, accessToken: session.accessToken, session: apiSession).logoutMobileSession()
            }
            try sessionStore.delete(instanceURL: session.instanceURL)
            instances = try sessionStore.loadAll()
            if connectedInstanceURL == session.instanceURL {
                if let next = try sessionStore.loadActive() {
                    try await activate(next)
                } else {
                    clearActiveState()
                }
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func selectTenant(_ tenant: VeyqTenant) {
        guard tenants.contains(where: { $0.id == tenant.id }) else { return }
        selectedTenant = tenant
        selectedSpace = nil
        spaces = []
        memories = []
        memoryDetails = [:]
        if !mockMode {
            Task { await reloadMemories() }
        } else {
            connectMock(to: connectedInstanceURL ?? URL(string: "https://memories.example.test")!)
        }
    }

    func selectSpace(_ space: VeyqSpace) {
        guard spaces.contains(space) else { return }
        selectedSpace = space
        if !mockMode {
            Task { await reloadMemories() }
        } else {
            memories = mockMemories(for: space)
        }
    }

    func searchMemories(matching query: String) -> [Memory] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }
        let searchSource = mockMode ? Memory.samples : memories
        return searchSource.filter { $0.displayName.localizedCaseInsensitiveContains(trimmed) }
    }

    func spaceLabel(for path: String) -> String {
        spaces.first { path.hasPrefix($0.path) }?.label ?? selectedTenant?.displayName ?? L10n.string("Unknown Space")
    }

    func reportError(_ message: String) {
        errorMessage = message
    }

    func clearError() {
        errorMessage = nil
    }

    func supportsCapability(_ capabilityId: String) -> Bool {
        mockMode || activeCapabilities.contains(capabilityId)
    }

    func refreshCurrentMemories() async {
        if mockMode {
            if let selectedSpace {
                memories = mockMemories(for: selectedSpace)
            }
            return
        }

        await reloadMemories()
    }

    func loadDetail(for memory: Memory, force: Bool = false) async {
        guard force || memoryDetails[memory.id] == nil else { return }
        memoryDetailLoadingIDs.insert(memory.id)
        memoryDetailMediaErrors[memory.id] = nil
        defer {
            memoryDetailLoadingIDs.remove(memory.id)
        }

        if mockMode {
            let loadedChapters = mockChapters(for: memory)
            memoryDetails[memory.id] = LoadedMemoryDetail(
                header: MemoryHeader(id: memory.id, displayName: memory.displayName, path: memory.path, coverUrl: memory.coverUrl, coverCrop: memory.coverCrop, startDate: memory.startDate, endDate: memory.endDate, directMediaCount: memory.mediaCount, descendantMediaCount: 0, chapterCount: loadedChapters.count, favorite: false),
                journal: MemoryJournal(memoryId: memory.id, markdown: "# Trail notes\n\nA quiet Memory kept from the native app shell.\n\n::media:media-summit Summit view\n\n::media:media-summit,media-trail Trail carousel", updatedAt: nil),
                chapters: loadedChapters,
                media: MemoryMedia.chronologicallySorted(
                    MemoryMedia.samples.filter { $0.memoryId == memory.id || memory.id == "summer-south-tyrol" }
                )
            )
            if let tenantName = selectedTenant?.name {
                await persistShareImportTargets(tenantName: tenantName)
            }
            return
        }
        guard let tenantName = selectedTenant?.name, let apiClient else {
            memoryDetailMediaErrors[memory.id] = L10n.string("No active tenant session.")
            return
        }
        do {
            async let header = apiClient.memoryHeader(tenantName: tenantName, memoryId: memory.id)
            async let chapters = apiClient.listMemoryChapters(tenantName: tenantName, memoryId: memory.id)

            let loadedHeader = try await header
            let loadedChapters = try await chapters
            let loadedJournal = supportsCapability("memory.journal-markdown")
                ? try await apiClient.memoryJournal(tenantName: tenantName, memoryId: memory.id)
                : MemoryJournal(memoryId: memory.id, markdown: "", updatedAt: nil)
            let loadedMedia: [MemoryMedia]
            if supportsCapability("memory.media") {
                do {
                    loadedMedia = try await apiClient.memoryMedia(tenantName: tenantName, memoryId: memory.id, includeChildren: true)
                } catch {
                    loadedMedia = []
                    memoryDetailMediaErrors[memory.id] = error.localizedDescription
                }
            } else {
                loadedMedia = []
            }
            memoryDetails[memory.id] = LoadedMemoryDetail(
                header: loadedHeader,
                journal: loadedJournal,
                chapters: loadedChapters,
                media: MemoryMedia.chronologicallySorted(loadedMedia)
            )
            await persistShareImportTargets(tenantName: tenantName)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func loadMediaImage(from urlString: String) async throws -> UIImage {
        guard !mockMode else {
            throw VeyqAPIError.invalidDiscovery
        }
        guard let apiClient else { throw VeyqAPIError.missingSession }
        let data = try await apiClient.mediaData(from: urlString)
        guard let image = UIImage(data: data) else { throw VeyqAPIError.invalidDiscovery }
        return image
    }

    func loadMediaFile(from urlString: String, preferredFilename: String) async throws -> URL {
        guard !mockMode else {
            throw VeyqAPIError.invalidDiscovery
        }
        guard let apiClient else { throw VeyqAPIError.missingSession }
        let data = try await apiClient.mediaData(from: urlString)
        let directory = FileManager.default.temporaryDirectory.appending(path: "veyq-media-viewer", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let fileURL = directory.appending(path: sanitizedFilename(preferredFilename))
        try data.write(to: fileURL, options: .atomic)
        return fileURL
    }

    func enqueueUploads(fileURLs: [URL], targetMemory memory: Memory, kind: VeyqUploadFileKind) {
        guard supportsCapability("memory.media-resumable-upload") else {
            errorMessage = L10n.string("This instance does not support media uploads.")
            return
        }
        guard let tenantName = selectedTenant?.name else {
            errorMessage = L10n.string("No active tenant is selected.")
            return
        }
        let target = UploadTargetMemory(tenantName: tenantName, memoryId: memory.id, memoryTitle: memory.title)
        var newItems: [UploadQueueItem] = []

        for fileURL in fileURLs {
            let filename = fileURL.lastPathComponent.isEmpty ? L10n.string("Upload") : fileURL.lastPathComponent
            let attributes = try? FileManager.default.attributesOfItem(atPath: fileURL.path)
            let sizeBytes = (attributes?[.size] as? NSNumber)?.int64Value ?? 0
            newItems.append(UploadQueueItem(
                id: UUID().uuidString,
                localFileURL: fileURL,
                filename: filename,
                mimeType: mimeType(for: filename, kind: kind),
                sizeBytes: sizeBytes,
                target: target,
                progress: 0,
                state: .queued
            ))
        }

        guard !newItems.isEmpty else { return }
        uploadItems.append(contentsOf: newItems)
        persistUploads()
        runNextUploadIfNeeded()
    }

    func enqueuePendingImport(_ batch: PendingImportBatch, targetMemory memory: Memory) {
        guard supportsCapability("memory.media-resumable-upload") else {
            errorMessage = L10n.string("This instance does not support media uploads.")
            return
        }
        guard let tenantName = selectedTenant?.name else {
            errorMessage = L10n.string("No active tenant is selected.")
            return
        }
        enqueuePendingImport(
            batch,
            target: UploadTargetMemory(tenantName: tenantName, memoryId: memory.id, memoryTitle: memory.title)
        )
    }

    func enqueuePendingImport(_ batch: PendingImportBatch, target: UploadTargetMemory) {
        let imageURLs = batch.fileURLs.filter { inferredUploadKind(for: $0) == .image }
        let videoURLs = batch.fileURLs.filter { inferredUploadKind(for: $0) == .video }
        enqueuePendingFiles(fileURLs: imageURLs, target: target, kind: .image)
        enqueuePendingFiles(fileURLs: videoURLs, target: target, kind: .video)
        UploadQueuePersistence.removePendingImportBatch(id: batch.id)
    }

    func retryUpload(_ item: UploadQueueItem) {
        guard let index = uploadItems.firstIndex(where: { $0.id == item.id }) else { return }
        guard uploadItems[index].retryable else { return }
        uploadItems[index].state = .queued
        uploadItems[index].errorMessage = nil
        uploadItems[index].retryable = true
        uploadItems[index].progressUpdatedAt = Date()
        persistUploads()
        runNextUploadIfNeeded()
    }

    func cancelUpload(_ item: UploadQueueItem) {
        guard let index = uploadItems.firstIndex(where: { $0.id == item.id }) else { return }
        uploadTasks[item.id]?.cancel()
        uploadTasks[item.id] = nil
        let uploadId = uploadItems[index].uploadId
        let target = uploadItems[index].target
        uploadItems[index].state = .cancelled
        uploadItems[index].progress = 0
        uploadItems[index].uploadedBytes = 0
        uploadItems[index].startedAt = nil
        uploadItems[index].progressUpdatedAt = Date()
        persistUploads()

        if let uploadId, let apiClient {
            Task {
                try? await apiClient.abortMemoryMediaUpload(
                    tenantName: target.tenantName,
                    memoryId: target.memoryId,
                    uploadId: uploadId
                )
            }
        }
        runNextUploadIfNeeded()
    }

    func cancelAllUploads() {
        uploadItems.forEach(cancelUpload)
    }

    func clearFinishedUploads() {
        uploadItems.removeAll { $0.state == .complete || $0.state == .cancelled }
        persistUploads()
    }

    private func loadBootstrap() async throws {
        guard let apiClient else { throw VeyqAPIError.missingSession }
        let bootstrap = try await apiClient.bootstrap()
        user = bootstrap.user
        tenants = bootstrap.tenants
        if let currentTenant = selectedTenant, bootstrap.tenants.contains(where: { $0.id == currentTenant.id }) {
            selectedTenant = currentTenant
        } else {
            selectedTenant = bootstrap.tenants.first
        }
        selectedSpace = nil
        await reloadMemories()
    }

    private func activate(_ session: StoredNativeSession) async throws {
        connectedInstanceURL = session.instanceURL
        user = session.user
        var session = session
        activeCapabilities = Set(session.capabilities.map(\.id))
        if shouldRefreshAccessToken(session) {
            session = try await refresh(session)
            try sessionStore.save(session)
            instances = try sessionStore.loadAll()
        }

        let client = VeyqAPIClient(instanceURL: session.instanceURL, apiBaseURL: session.apiBaseURL, accessToken: session.accessToken, session: apiSession)
        apiClient = client
        let capabilitySnapshot = try await client.clientCapabilities()
        try validateRequiredCapabilities(capabilitySnapshot, requiringMobileHandoff: false)
        activeCapabilities = Set(capabilitySnapshot.capabilities.map(\.id))
        let capabilitySession = session.withCapabilities(capabilitySnapshot.capabilities)
        try sessionStore.save(capabilitySession)
        instances = try sessionStore.loadAll()
        tenants = []
        selectedTenant = nil
        spaces = []
        selectedSpace = nil
        memories = []
        memoryDetails = [:]
        try await loadBootstrap()
        runNextUploadIfNeeded()
    }

    private func activateRestoredSession(_ session: StoredNativeSession) async throws {
        do {
            try await activate(session)
        } catch VeyqAPIError.unauthorized {
            let refreshed = try await refresh(session)
            try sessionStore.save(refreshed)
            instances = try sessionStore.loadAll()
            try await activate(refreshed)
        }
    }

    private func refresh(_ session: StoredNativeSession) async throws -> StoredNativeSession {
        let client = VeyqAPIClient(instanceURL: session.instanceURL, apiBaseURL: session.apiBaseURL, session: apiSession)
        let nativeSession = try await client.refreshMobileSession(refreshToken: session.refreshToken)
        return StoredNativeSession(
            instanceURL: session.instanceURL,
            apiBaseURL: session.apiBaseURL,
            accessToken: nativeSession.accessToken,
            accessTokenExpiresAt: nativeSession.accessTokenExpiresAt,
            refreshToken: nativeSession.refreshToken,
            refreshTokenExpiresAt: nativeSession.refreshTokenExpiresAt,
            user: nativeSession.user,
            capabilities: session.capabilities
        )
    }

    private func shouldRefreshAccessToken(_ session: StoredNativeSession, now: Date = Date()) -> Bool {
        session.accessTokenExpiresAt <= now.addingTimeInterval(60)
    }

    private func validateRequiredCapabilities(
        _ capabilities: VeyqClientCapabilities,
        requiringMobileHandoff: Bool = true
    ) throws {
        if requiringMobileHandoff, !capabilities.contains("auth.mobile-handoff") {
            throw VeyqAPIError.missingCapability("auth.mobile-handoff")
        }
        guard capabilities.contains("memory.core") else {
            throw VeyqAPIError.missingCapability("memory.core")
        }
    }

    private func clearActiveState() {
        cancelRunningUploadTasks()
        connectedInstanceURL = nil
        user = nil
        tenants = []
        selectedTenant = nil
        spaces = []
        selectedSpace = nil
        memories = []
        memoryDetails = [:]
        apiClient = nil
        activeCapabilities = []
        errorMessage = nil
    }

    private func runNextUploadIfNeeded() {
        guard !mockMode, apiClient != nil else { return }
        guard uploadTasks.isEmpty else { return }
        guard let item = uploadItems.first(where: { $0.state == .queued }) else { return }

        let task = Task<Void, Never> { [weak self] in
            guard let self else { return }
            await self.performUpload(itemId: item.id)
        }
        uploadTasks[item.id] = task
    }

    private func performUpload(itemId: String) async {
        guard let index = uploadItems.firstIndex(where: { $0.id == itemId }), let apiClient else {
            uploadTasks[itemId] = nil
            return
        }
        guard FileManager.default.isReadableFile(atPath: uploadItems[index].localFileURL.path) else {
            uploadItems[index].state = .failed
            uploadItems[index].errorMessage = L10n.string("The local file is no longer available. Add it again to upload.")
            uploadItems[index].retryable = false
            uploadItems[index].progressUpdatedAt = Date()
            persistUploads()
            uploadTasks[itemId] = nil
            runNextUploadIfNeeded()
            return
        }
        uploadItems[index].state = .uploading
        uploadItems[index].errorMessage = nil
        uploadItems[index].retryable = true
        if uploadItems[index].startedAt == nil {
            uploadItems[index].startedAt = Date()
        }
        uploadItems[index].progressUpdatedAt = Date()
        persistUploads()

        let item = uploadItems[index]
        do {
            let media = try await apiClient.uploadMemoryMediaFile(
                tenantName: item.target.tenantName,
                memoryId: item.target.memoryId,
                fileURL: item.localFileURL,
                filename: item.filename,
                mimeType: item.mimeType,
                title: item.filename,
                existingUploadId: item.uploadId,
                progress: { [weak self] uploadId, loadedBytes, totalBytes in
                    await self?.updateUploadProgress(itemId: itemId, uploadId: uploadId, loadedBytes: loadedBytes, totalBytes: totalBytes)
                }
            )
            if let completedIndex = uploadItems.firstIndex(where: { $0.id == itemId }) {
                uploadItems[completedIndex].state = .complete
                uploadItems[completedIndex].progress = 1
                uploadItems[completedIndex].uploadedBytes = uploadItems[completedIndex].sizeBytes
                uploadItems[completedIndex].progressUpdatedAt = Date()
                uploadItems[completedIndex].errorMessage = nil
                persistUploads()
            }
            mergeUploadedMedia(media)
            try? FileManager.default.removeItem(at: item.localFileURL)
        } catch is CancellationError {
            if let cancelledIndex = uploadItems.firstIndex(where: { $0.id == itemId }) {
                uploadItems[cancelledIndex].state = .cancelled
                uploadItems[cancelledIndex].progress = 0
                uploadItems[cancelledIndex].uploadedBytes = 0
                uploadItems[cancelledIndex].startedAt = nil
                uploadItems[cancelledIndex].progressUpdatedAt = Date()
                persistUploads()
            }
        } catch {
            if let failedIndex = uploadItems.firstIndex(where: { $0.id == itemId }) {
                uploadItems[failedIndex].state = .failed
                uploadItems[failedIndex].errorMessage = error.localizedDescription
                uploadItems[failedIndex].retryable = true
                uploadItems[failedIndex].progressUpdatedAt = Date()
                persistUploads()
            }
        }

        uploadTasks[itemId] = nil
        runNextUploadIfNeeded()
    }

    private func updateUploadProgress(itemId: String, uploadId: String, loadedBytes: Int64, totalBytes: Int64) {
        guard let index = uploadItems.firstIndex(where: { $0.id == itemId }) else { return }
        uploadItems[index].uploadId = uploadId
        uploadItems[index].uploadedBytes = max(uploadItems[index].uploadedBytes, loadedBytes)
        uploadItems[index].progressUpdatedAt = Date()
        if totalBytes > 0 {
            uploadItems[index].progress = min(1, max(0, Double(loadedBytes) / Double(totalBytes)))
        }
        persistUploads()
    }

    func mergeUploadedMedia(_ media: MemoryMedia) {
        guard let detail = memoryDetails[media.memoryId] else { return }
        if !detail.media.contains(where: { $0.id == media.id }) {
            memoryDetails[media.memoryId] = LoadedMemoryDetail(
                header: detail.header,
                journal: detail.journal,
                chapters: detail.chapters,
                media: MemoryMedia.chronologicallySorted(detail.media + [media])
            )
        }
    }

    private func persistUploads() {
        if !mockMode {
            UploadQueuePersistence.saveQueue(uploadItems)
        }
    }

    private func cancelRunningUploadTasks() {
        uploadTasks.values.forEach { $0.cancel() }
        uploadTasks = [:]
    }

    private func reloadMemories() async {
        guard let tenant = selectedTenant, let apiClient else { return }
        do {
            let navigation = try await apiClient.navigation(tenantName: tenant.name, path: selectedSpace?.path ?? "/")
            spaces = spaces(from: tenant, navigation: navigation)
            if selectedSpace == nil {
                selectedSpace = spaces.first
            }
            memories = try await apiClient.memoriesByFolder(tenantName: tenant.name, folderPath: selectedSpace?.path ?? "/")
            await persistShareImportTargets(tenantName: tenant.name)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func enqueueUploads(fileURLs: [URL], target: UploadTargetMemory, kind: VeyqUploadFileKind) {
        var newItems: [UploadQueueItem] = []

        for fileURL in fileURLs {
            let filename = fileURL.lastPathComponent.isEmpty ? L10n.string("Upload") : fileURL.lastPathComponent
            let attributes = try? FileManager.default.attributesOfItem(atPath: fileURL.path)
            let sizeBytes = (attributes?[.size] as? NSNumber)?.int64Value ?? 0
            newItems.append(UploadQueueItem(
                id: UUID().uuidString,
                localFileURL: fileURL,
                filename: filename,
                mimeType: mimeType(for: filename, kind: kind),
                sizeBytes: sizeBytes,
                target: target,
                progress: 0,
                state: .queued
            ))
        }

        guard !newItems.isEmpty else { return }
        uploadItems.append(contentsOf: newItems)
        persistUploads()
        runNextUploadIfNeeded()
    }

    private func enqueuePendingFiles(fileURLs: [URL], target: UploadTargetMemory, kind: VeyqUploadFileKind) {
        var storedURLs: [URL] = []
        var failedCount = 0

        for fileURL in fileURLs {
            do {
                let filename = fileURL.lastPathComponent.isEmpty ? L10n.string("Upload") : fileURL.lastPathComponent
                let storedURL = try UploadQueuePersistence.copyIntoUploadStore(
                    sourceURL: fileURL,
                    preferredFilename: filename,
                    kind: kind
                )
                storedURLs.append(storedURL)
            } catch {
                failedCount += 1
            }
        }

        if failedCount > 0 {
            errorMessage = L10n.format("%d shared files could not be prepared for upload.", failedCount)
        }
        enqueueUploads(fileURLs: storedURLs, target: target, kind: kind)
    }

    private func persistShareImportTargets(tenantName: String) async {
        let targets = await shareImportTargets(tenantName: tenantName)
        UploadQueuePersistence.removeUnreferencedShareImportCovers(
            keeping: Set(targets.compactMap(\.coverThumbnailFilename))
        )
        UploadQueuePersistence.saveShareImportTargets(
            ShareImportTargetSnapshot(
                targets: targets,
                lastUsedMemoryId: nil,
                updatedAt: Date(),
                includesOnlyKnownChapters: true
            )
        )
    }

    func shareImportTargets(tenantName: String) async -> [ShareImportMemoryTarget] {
        var targets: [ShareImportMemoryTarget] = []
        targets.reserveCapacity(memories.count)

        for memory in memories {
            targets.append(await shareImportTarget(for: memory, tenantName: tenantName))

            let chapters = await shareImportChapters(for: memory, tenantName: tenantName)
            for chapter in chapters {
                targets.append(await shareImportTarget(
                    for: chapter,
                    tenantName: tenantName,
                    parentMemoryTitle: memory.title,
                    depth: 1
                ))
            }
        }

        return targets
    }

    private func shareImportChapters(for memory: Memory, tenantName: String) async -> [Memory] {
        if let detail = memoryDetails[memory.id] {
            return detail.chapters
        }
        return []
    }

    private func shareImportTarget(
        for memory: Memory,
        tenantName: String,
        parentMemoryTitle: String? = nil,
        depth: Int = 0
    ) async -> ShareImportMemoryTarget {
        let thumbnailFilename = await shareImportCoverThumbnailFilename(for: memory)
        return ShareImportMemoryTarget(
            id: memory.id,
            title: memory.title,
            dateRange: memory.dateRange,
            tenantName: tenantName,
            coverUrl: memory.coverUrl,
            coverThumbnailFilename: thumbnailFilename,
            parentMemoryTitle: parentMemoryTitle,
            depth: depth
        )
    }

    private func shareImportCoverThumbnailFilename(for memory: Memory) async -> String? {
        guard !mockMode,
              let coverUrl = memory.coverUrl,
              !coverUrl.isEmpty
        else {
            return nil
        }
        guard let image = try? await loadMediaImage(from: coverUrl) else { return nil }
        let thumbnail = image.preparingThumbnail(of: CGSize(width: 72, height: 72)) ?? image
        guard let data = thumbnail.jpegData(compressionQuality: 0.82) else { return nil }
        let filename = "\(memory.id)-cover.jpg"
        let destination = UploadQueuePersistence.shareImportCoverURL(filename: filename)
        do {
            try data.write(to: destination, options: [.atomic])
            return filename
        } catch {
            return nil
        }
    }

    private func spaces(from tenant: VeyqTenant, navigation: MemoryNavigationResponse) -> [VeyqSpace] {
        let tenantSpace = VeyqSpace(id: tenant.name, kind: .tenant, label: L10n.string("All Memories"), path: "/", groupName: nil)
        let scoped = navigation.scopeRoots.map { root in
            let kind: VeyqSpaceKind = root.kind == "GROUP" ? .group : .personal
            let label = kind == .group ? "@\(root.displayName)" : root.displayName
            return VeyqSpace(id: "\(root.kind.lowercased())-\(root.name)", kind: kind, label: label, path: root.path, groupName: kind == .group ? root.name : nil)
        }
        return [tenantSpace] + scoped
    }

    private func mobileLoginURL(_ url: URL, state: String) -> URL {
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        var queryItems = components.queryItems ?? []
        queryItems.append(URLQueryItem(name: "redirect_uri", value: "veyq://auth/callback"))
        queryItems.append(URLQueryItem(name: "state", value: state))
        components.queryItems = queryItems
        return components.url ?? url
    }

    private func mobileCode(from callbackURL: URL, expectedState: String) throws -> String {
        guard callbackURL.scheme == "veyq",
              callbackURL.host == "auth",
              callbackURL.path == "/callback",
              let components = URLComponents(url: callbackURL, resolvingAgainstBaseURL: false),
              let code = components.queryItems?.first(where: { $0.name == "code" })?.value,
              components.queryItems?.first(where: { $0.name == "state" })?.value == expectedState
        else {
            throw VeyqAPIError.invalidCallback
        }
        return code
    }

    private func connectMock(to url: URL) {
        connectedInstanceURL = url
        user = VeyqUser(userId: 1, email: "admin@example.test", displayName: "Administrator", permalink: "administrator", serverRole: "ServerOwner", passkeyCount: 0, hasPassword: true, avatarUrl: nil)
        instances = [
            StoredNativeSession(
                instanceURL: url,
                apiBaseURL: url.appending(path: "api"),
                accessToken: "mock-access",
                accessTokenExpiresAt: Date(timeIntervalSince1970: 1_800_000_000),
                refreshToken: "mock-refresh",
                refreshTokenExpiresAt: Date(timeIntervalSince1970: 1_800_003_600),
                user: VeyqUser(userId: 1, email: "admin@example.test", displayName: "Administrator", permalink: "administrator", serverRole: "ServerOwner", passkeyCount: 0, hasPassword: true, avatarUrl: nil)
            )
        ]
        tenants = [.sample]
        selectedTenant = .sample
        spaces = [
            VeyqSpace(id: "family", kind: .tenant, label: L10n.string("All Memories"), path: "/", groupName: nil),
            VeyqSpace(id: "group-family", kind: .group, label: "@Family", path: "/@family", groupName: "family"),
            VeyqSpace(id: "personal", kind: .personal, label: L10n.string("Personal"), path: "/~me", groupName: nil)
        ]
        selectedSpace = spaces[0]
        memories = mockMemories(for: spaces[0])
    }

    private func mockMemories(for space: VeyqSpace) -> [Memory] {
        switch space.id {
        case "group-family":
            [Memory.samples[1]]
        case "personal":
            [Memory.samples[2]]
        default:
            [Memory.samples[0]]
        }
    }

    private func mockChapters(for memory: Memory) -> [Memory] {
        guard memory.id == "summer-south-tyrol" else { return [] }
        return [
            Memory(id: "summer-south-tyrol-day-1", name: "day-1", displayName: "Day 1 - Arrival", path: "\(memory.path)/day-1", parentId: memory.id, coverMediaId: nil, coverUrl: nil, coverCrop: nil, startDate: "2026-07-12", endDate: "2026-07-12", mediaCount: 24, childCount: 0),
            Memory(id: "summer-south-tyrol-day-2", name: "day-2", displayName: "Day 2 - Mountain Trail", path: "\(memory.path)/day-2", parentId: memory.id, coverMediaId: nil, coverUrl: nil, coverCrop: nil, startDate: "2026-07-13", endDate: "2026-07-13", mediaCount: 31, childCount: 0)
        ]
    }

    private func inferredUploadKind(for url: URL) -> VeyqUploadFileKind {
        let lower = url.pathExtension.lowercased()
        if ["mov", "mp4", "m4v"].contains(lower) {
            return .video
        }
        return .image
    }

    private func sanitizedFilename(_ value: String) -> String {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._- ")
        let scalars = value.unicodeScalars.map { allowed.contains($0) ? Character($0) : "-" }
        let sanitized = String(scalars).trimmingCharacters(in: .whitespacesAndNewlines)
        return sanitized.isEmpty ? "veyq-media" : sanitized
    }
}

struct LoadedMemoryDetail: Equatable {
    let header: MemoryHeader
    let journal: MemoryJournal
    let chapters: [Memory]
    let media: [MemoryMedia]
}
