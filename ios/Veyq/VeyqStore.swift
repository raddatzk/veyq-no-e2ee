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
    @Published private(set) var navigationLocation: MemoryNavigationLocation?
    @Published private(set) var folders: [MemoryFolder] = []
    @Published private(set) var memories: [Memory] = []
    @Published private(set) var searchResults: [SearchResultSummary] = []
    @Published private(set) var searchLoading = false
    @Published private(set) var searchErrorMessage: String?
    @Published private(set) var memoryDetails: [String: LoadedMemoryDetail] = [:]
    @Published private(set) var memoryDetailLoadingIDs: Set<String> = []
    @Published private(set) var memoryDetailMediaErrors: [String: String] = [:]
    @Published private(set) var uploadItems: [UploadQueueItem]
    @Published private(set) var loading = false
    @Published private(set) var memoryNavigationLoading = false
    @Published private(set) var errorMessage: String?

    private var apiClient: VeyqAPIClient?
    private let instanceRepository: InstanceRepository
    private let sessionStore: NativeSessionStoring
    private let authenticator: MobileAuthenticating
    private let apiSession: URLSession
    private let mockMode: Bool
    private var uploadTasks: [String: Task<Void, Never>] = [:]
    private var activeCapabilities = Set<String>()
    private var memoryNavigationCache: [MemoryNavigationCacheKey: MemoryNavigationSnapshot] = [:]
    private var memoryNavigationLoadSequence = 0
    private var searchSequence = 0

    private struct MemoryNavigationCacheKey: Hashable {
        let tenantName: String
        let path: String
    }

    private struct MemoryNavigationSnapshot {
        let spaces: [VeyqSpace]
        let selectedSpace: VeyqSpace?
        let navigationLocation: MemoryNavigationLocation
        let folders: [MemoryFolder]
        let memories: [Memory]
    }

    var isConnected: Bool {
        connectedInstanceURL != nil
    }

    var visibleMemories: [Memory] {
        memories
    }

    var visibleFolders: [MemoryFolder] {
        folders
    }

    var currentFolderPath: String {
        navigationLocation?.path ?? selectedSpace?.path ?? "/"
    }

    var canNavigateUpFolder: Bool {
        let current = currentFolderPath.trimmedFolderPath
        let root = (selectedSpace?.path ?? "/").trimmedFolderPath
        return current != root && current != "/"
    }

    var canNavigateToTenantScope: Bool {
        selectedSpace?.kind != nil && selectedSpace?.kind != .tenant
    }

    var groupSpaces: [VeyqSpace] {
        spaces.filter { $0.kind == .group }
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
            persistSharedAccounts()
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
        searchResults = []
        searchLoading = false
        searchErrorMessage = nil
        memoryDetails = [:]
        memoryNavigationCache = [:]
        searchSequence = 0
        memoryNavigationLoading = false
        apiClient = nil
        activeCapabilities = []
        errorMessage = nil
        uploadItems = mockMode ? UploadQueueItem.samples : UploadQueuePersistence.loadQueue()
        persistSharedAccounts()
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
                    persistSharedAccounts()
                }
            }
            persistSharedAccounts()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func selectTenant(_ tenant: VeyqTenant) {
        guard tenants.contains(where: { $0.id == tenant.id }) else { return }
        selectedTenant = tenant
        persistSelectedTenantName(tenant.name)
        selectedSpace = nil
        navigationLocation = nil
        folders = []
        spaces = []
        memories = []
        searchResults = []
        searchLoading = false
        searchErrorMessage = nil
        memoryDetails = [:]
        memoryNavigationCache = [:]
        memoryNavigationLoading = false
        searchSequence = 0
        if !mockMode {
            Task { await reloadMemories() }
        } else {
            connectMock(to: connectedInstanceURL ?? URL(string: "https://memories.example.test")!)
        }
    }

    func selectSpace(_ space: VeyqSpace) {
        guard spaces.contains(space) else { return }
        if mockMode {
            selectedSpace = space
            navigationLocation = nil
            folders = []
            loadMockFolder(path: space.path)
        } else {
            Task { await loadMemories(path: space.path, selectedSpaceOverride: space) }
        }
    }

    func navigateToTenantScope() {
        guard let tenantSpace = spaces.first(where: { $0.kind == .tenant }) else { return }
        selectSpace(tenantSpace)
    }

    func searchMemories(matching query: String) -> [SearchResultSummary] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }
        return Memory.samples
            .filter { $0.displayName.localizedCaseInsensitiveContains(trimmed) }
            .map { SearchResultSummary(memory: $0) }
    }

    func updateSearch(matching query: String) async {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        searchSequence += 1
        let currentSearchSequence = searchSequence
        searchErrorMessage = nil
        guard trimmed.count >= 2 else {
            searchResults = []
            searchLoading = false
            return
        }
        if mockMode {
            searchResults = searchMemories(matching: trimmed)
            searchLoading = false
            return
        }
        guard let tenantName = selectedTenant?.name, let apiClient else {
            searchResults = []
            searchLoading = false
            return
        }
        searchLoading = true
        do {
            let results = try await apiClient.searchTenant(tenantName: tenantName, query: trimmed)
            guard searchSequence == currentSearchSequence else { return }
            searchResults = results
            searchLoading = false
        } catch {
            guard searchSequence == currentSearchSequence else { return }
            searchResults = []
            searchLoading = false
            searchErrorMessage = error.localizedDescription
        }
    }

    func openSearchResult(_ result: SearchResultSummary) async -> Memory? {
        switch result.kind {
        case .memory:
            return result.memory
        case .folder:
            await navigateToSearchResultPath(result.path)
            return nil
        }
    }

    func navigateToSearchResultPath(_ path: String) async {
        let normalizedPath = path.trimmedFolderPath
        let targetSpace = bestMatchingSpace(for: normalizedPath)
        if mockMode {
            selectedSpace = targetSpace ?? selectedSpace
            loadMockFolder(path: normalizedPath, fallbackSpace: targetSpace ?? selectedSpace)
        } else {
            await loadMemories(path: normalizedPath, selectedSpaceOverride: targetSpace ?? selectedSpace)
        }
    }

    func spaceLabel(for path: String) -> String {
        bestMatchingSpace(for: path)?.label ?? selectedTenant?.displayName ?? L10n.string("Unknown Space")
    }

    private func bestMatchingSpace(for path: String) -> VeyqSpace? {
        let normalizedPath = path.trimmedFolderPath
        return spaces
            .filter { space in
                let spacePath = space.path.trimmedFolderPath
                return normalizedPath == spacePath || normalizedPath.hasPrefix("\(spacePath)/") || spacePath == "/"
            }
            .sorted { lhs, rhs in
                lhs.path.trimmedFolderPath.count > rhs.path.trimmedFolderPath.count
            }
            .first
    }

    func reportError(_ message: String) {
        errorMessage = message
    }

    func clearError() {
        errorMessage = nil
    }

    func reloadMySettings() async {
        guard !mockMode else { return }
        do {
            guard let apiClient else { throw VeyqAPIError.missingSession }
            let nextUser = try await apiClient.mySettings()
            try persistUpdatedUser(nextUser)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    @discardableResult
    func updateMySettings(displayName: String, permalink: String, email: String) async throws -> VeyqUser {
        if mockMode {
            let nextUser = VeyqUser(
                userId: user?.userId ?? 1,
                email: email,
                displayName: displayName,
                permalink: permalink,
                serverRole: user?.serverRole ?? "ServerOwner",
                passkeyCount: user?.passkeyCount ?? 0,
                hasPassword: user?.hasPassword ?? true,
                avatarUrl: user?.avatarUrl,
                pendingEmail: nil,
                totpEnabled: user?.totpEnabled
            )
            user = nextUser
            return nextUser
        }

        guard let apiClient else { throw VeyqAPIError.missingSession }
        let nextUser = try await apiClient.updateMySettings(
            displayName: displayName,
            permalink: permalink,
            email: email
        )
        try persistUpdatedUser(nextUser)
        return nextUser
    }

    func supportsCapability(_ capabilityId: String) -> Bool {
        mockMode || activeCapabilities.contains(capabilityId)
    }

    func refreshCurrentMemories() async {
        if mockMode {
            if let selectedSpace {
                loadMockFolder(path: currentFolderPath, fallbackSpace: selectedSpace)
            }
            return
        }

        await reloadMemories()
    }

    func openFolder(_ folder: MemoryFolder) {
        if mockMode {
            navigationLocation = MemoryNavigationLocation(kind: "FOLDER", path: folder.path, displayName: folder.displayName)
            loadMockFolder(path: folder.path)
        } else {
            Task { await loadMemories(path: folder.path, selectedSpaceOverride: selectedSpace) }
        }
    }

    func navigateUpFolder() {
        guard canNavigateUpFolder else { return }
        let parentPath = parentFolderPath(for: currentFolderPath, rootPath: selectedSpace?.path ?? "/")
        if mockMode {
            navigationLocation = MemoryNavigationLocation(kind: parentPath == selectedSpace?.path ? "SCOPE" : "FOLDER", path: parentPath, displayName: selectedSpace?.label ?? L10n.string("Memories"))
            loadMockFolder(path: parentPath)
        } else {
            Task { await loadMemories(path: parentPath, selectedSpaceOverride: selectedSpace) }
        }
    }

    @discardableResult
    func createGroup(displayName: String, name: String) async throws -> VeyqSpace {
        let trimmedDisplayName = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedDisplayName.isEmpty else {
            throw VeyqAPIError.serverError(status: 400, response: VeyqErrorResponse(code: "GROUP_DISPLAY_NAME_REQUIRED", message: L10n.string("Enter a group name.")))
        }
        guard !trimmedName.isEmpty else {
            throw VeyqAPIError.serverError(status: 400, response: VeyqErrorResponse(code: "GROUP_NAME_REQUIRED", message: L10n.string("Enter a group path name.")))
        }

        let group: GroupSummary
        if mockMode {
            group = GroupSummary(id: 10_000 + spaces.count, name: trimmedName, displayName: trimmedDisplayName, members: [])
        } else {
            guard let tenantName = selectedTenant?.name, let apiClient else {
                throw VeyqAPIError.missingSession
            }
            group = try await apiClient.createGroup(
                tenantName: tenantName,
                request: CreateGroupRequest(name: trimmedName, displayName: trimmedDisplayName)
            )
        }

        let createdSpace = space(from: group)
        upsertGroupSpace(createdSpace)
        selectSpace(createdSpace)
        return createdSpace
    }

    @discardableResult
    func createFolder(displayName: String, name: String) async throws -> MemoryFolder {
        guard supportsCapability("memory.core") else {
            throw VeyqAPIError.missingCapability("memory.core")
        }
        let trimmedDisplayName = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedDisplayName.isEmpty else {
            throw VeyqAPIError.serverError(status: 400, response: VeyqErrorResponse(code: "FOLDER_DISPLAY_NAME_REQUIRED", message: L10n.string("Enter a folder name.")))
        }
        guard !trimmedName.isEmpty else {
            throw VeyqAPIError.serverError(status: 400, response: VeyqErrorResponse(code: "FOLDER_NAME_REQUIRED", message: L10n.string("Enter a folder path name.")))
        }

        let created: MemoryFolder
        if mockMode {
            let parentPath = currentFolderPath.trimmedFolderPath
            created = MemoryFolder(
                id: trimmedName,
                name: trimmedName,
                displayName: trimmedDisplayName,
                path: parentPath == "/" ? "/\(trimmedName)" : "\(parentPath)/\(trimmedName)"
            )
        } else {
            guard let tenantName = selectedTenant?.name, let apiClient else {
                throw VeyqAPIError.missingSession
            }
            created = try await apiClient.createFolder(
                tenantName: tenantName,
                request: CreateFolderRequest(
                    parentPath: currentFolderPath,
                    name: trimmedName,
                    displayName: trimmedDisplayName,
                    scope: currentFolderPath.trimmedFolderPath == (selectedSpace?.path ?? "/").trimmedFolderPath ? selectedScopeRequest : nil
                )
            )
        }

        mergeCreatedFolder(created)
        return created
    }

    @discardableResult
    func createMemory(displayName: String, name: String, startDate: String, endDate: String, parentMemory: Memory? = nil) async throws -> Memory {
        guard supportsCapability("memory.core") else {
            throw VeyqAPIError.missingCapability("memory.core")
        }
        let trimmedDisplayName = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedDisplayName.isEmpty else {
            throw VeyqAPIError.serverError(status: 400, response: VeyqErrorResponse(code: "MEMORY_DISPLAY_NAME_REQUIRED", message: ""))
        }
        guard !trimmedName.isEmpty else {
            throw VeyqAPIError.serverError(status: 400, response: VeyqErrorResponse(code: "MEMORY_NAME_REQUIRED", message: ""))
        }
        guard endDate >= startDate else {
            throw VeyqAPIError.serverError(status: 400, response: VeyqErrorResponse(code: "MEMORY_DATE_RANGE_INVALID", message: ""))
        }

        let created: Memory
        if mockMode {
            created = mockCreatedMemory(
                displayName: trimmedDisplayName,
                name: trimmedName,
                startDate: startDate,
                endDate: endDate,
                parentMemory: parentMemory
            )
        } else {
            guard let tenantName = selectedTenant?.name, let apiClient else {
                throw VeyqAPIError.missingSession
            }
            created = try await apiClient.createMemory(
                tenantName: tenantName,
                request: CreateMemoryRequest(
                    parentId: parentMemory?.id,
                    folderPath: parentMemory == nil ? selectedSpace?.path ?? "/" : nil,
                    name: trimmedName,
                    displayName: trimmedDisplayName,
                    startDate: startDate,
                    endDate: endDate,
                    scope: parentMemory == nil ? selectedScopeRequest : nil
                )
            )
        }

        mergeCreatedMemory(created, parentMemory: parentMemory)
        if let tenantName = selectedTenant?.name {
            await persistShareImportTargets(tenantName: tenantName)
        }
        return created
    }

    @discardableResult
    func updateMemory(_ memory: Memory, displayName: String, name: String, startDate: String, endDate: String) async throws -> Memory {
        guard supportsCapability("memory.core") else {
            throw VeyqAPIError.missingCapability("memory.core")
        }
        let trimmedDisplayName = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedDisplayName.isEmpty else {
            throw VeyqAPIError.serverError(status: 400, response: VeyqErrorResponse(code: "MEMORY_DISPLAY_NAME_REQUIRED", message: ""))
        }
        guard !trimmedName.isEmpty else {
            throw VeyqAPIError.serverError(status: 400, response: VeyqErrorResponse(code: "MEMORY_NAME_REQUIRED", message: ""))
        }
        guard endDate >= startDate else {
            throw VeyqAPIError.serverError(status: 400, response: VeyqErrorResponse(code: "MEMORY_DATE_RANGE_INVALID", message: ""))
        }

        let updated: Memory
        if mockMode {
            updated = memory.withDisplayMetadata(displayName: trimmedDisplayName, name: trimmedName, startDate: startDate, endDate: endDate)
        } else {
            guard let tenantName = selectedTenant?.name, let apiClient else {
                throw VeyqAPIError.missingSession
            }
            updated = try await apiClient.updateMemory(
                tenantName: tenantName,
                memoryId: memory.id,
                request: UpdateMemoryRequest(
                    parentId: memory.parentId,
                    name: trimmedName,
                    displayName: trimmedDisplayName,
                    startDate: startDate,
                    endDate: endDate
                )
            )
        }

        mergeUpdatedMemory(updated)
        if let tenantName = selectedTenant?.name {
            await persistShareImportTargets(tenantName: tenantName)
        }
        return updated
    }

    func deleteMemory(_ memory: Memory) async throws {
        guard supportsCapability("memory.core") else {
            throw VeyqAPIError.missingCapability("memory.core")
        }
        if mockMode {
            removeDeletedMemory(memory)
        } else {
            guard let tenantName = selectedTenant?.name, let apiClient else {
                throw VeyqAPIError.missingSession
            }
            try await apiClient.deleteMemory(tenantName: tenantName, memoryId: memory.id)
            removeDeletedMemory(memory)
        }
        if let tenantName = selectedTenant?.name {
            await persistShareImportTargets(tenantName: tenantName)
        }
    }

    @discardableResult
    func setMemoryCover(for memory: Memory, media: MemoryMedia) async throws -> Memory {
        guard supportsCapability("memory.core") else {
            throw VeyqAPIError.missingCapability("memory.core")
        }
        let crop = CoverCrop.defaultCoverCrop
        let updated: Memory
        if mockMode {
            updated = memory.withCover(mediaId: media.id, coverUrl: media.contentUrl, coverCrop: crop)
        } else {
            guard let tenantName = selectedTenant?.name, let apiClient else {
                throw VeyqAPIError.missingSession
            }
            updated = try await apiClient.setMemoryCoverFromMedia(
                tenantName: tenantName,
                memoryId: memory.id,
                mediaId: media.id,
                crop: crop
            )
        }
        mergeUpdatedMemory(updated)
        if let tenantName = selectedTenant?.name {
            await persistShareImportTargets(tenantName: tenantName)
        }
        return updated
    }

    @discardableResult
    func removeMemoryCover(for memory: Memory) async throws -> Memory {
        guard supportsCapability("memory.core") else {
            throw VeyqAPIError.missingCapability("memory.core")
        }
        let updated: Memory
        if mockMode {
            updated = memory.withCover(mediaId: nil, coverUrl: nil, coverCrop: nil)
        } else {
            guard let tenantName = selectedTenant?.name, let apiClient else {
                throw VeyqAPIError.missingSession
            }
            updated = try await apiClient.removeMemoryCover(tenantName: tenantName, memoryId: memory.id)
        }
        mergeUpdatedMemory(updated)
        if let tenantName = selectedTenant?.name {
            await persistShareImportTargets(tenantName: tenantName)
        }
        return updated
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
                ),
                tracks: MemoryTrack.samples.filter { $0.memoryId == memory.id }
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
            let loadedTracks = supportsCapability("memory.tracks")
                ? try await apiClient.memoryTracks(tenantName: tenantName, memoryId: memory.id)
                : []
            memoryDetails[memory.id] = LoadedMemoryDetail(
                header: loadedHeader,
                journal: loadedJournal,
                chapters: loadedChapters,
                media: MemoryMedia.chronologicallySorted(loadedMedia),
                tracks: loadedTracks
            )
            await persistShareImportTargets(tenantName: tenantName)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func watchMemoryMediaUpdates(for memory: Memory) async {
        guard !mockMode,
              supportsCapability("memory.media"),
              supportsCapability("memory.media-live-updates"),
              let tenantName = selectedTenant?.name,
              let apiClient
        else {
            return
        }

        while !Task.isCancelled {
            guard let detail = memoryDetails[memory.id] else {
                try? await Task.sleep(nanoseconds: 500_000_000)
                continue
            }

            let cursor = MemoryMedia.newestUpdateCursor(in: detail.media)
            let knownMediaSetHash = MemoryMedia.mediaSetHash(detail.media.map(\.id))
            do {
                let updates = try await apiClient.waitForMemoryMediaUpdates(
                    tenantName: tenantName,
                    memoryId: memory.id,
                    includeChildren: true,
                    afterUpdatedAt: cursor?.updatedAt,
                    afterMediaId: cursor?.mediaId,
                    knownMediaSetHash: knownMediaSetHash
                )
                guard !Task.isCancelled else { return }
                if mergeMemoryMediaUpdates(for: memory.id, updates: updates) {
                    await refreshMemoryHeader(tenantName: tenantName, memoryId: memory.id)
                }
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                memoryDetailMediaErrors[memory.id] = error.localizedDescription
                try? await Task.sleep(nanoseconds: 5_000_000_000)
            }
        }
    }

    @discardableResult
    func mergeMemoryMediaUpdates(for memoryId: String, updates: MemoryMediaUpdatesResponse) -> Bool {
        guard let detail = memoryDetails[memoryId] else { return false }

        var mediaById = Dictionary(uniqueKeysWithValues: detail.media.map { ($0.id, $0) })
        for item in updates.media {
            mediaById[item.id] = item
        }

        var merged = Array(mediaById.values)
        if let currentMediaIds = updates.currentMediaIds {
            let currentIdSet = Set(currentMediaIds)
            merged = merged.filter { currentIdSet.contains($0.id) }
        }

        let sorted = MemoryMedia.chronologicallySorted(merged)
        guard sorted != detail.media else { return false }

        memoryDetails[memoryId] = LoadedMemoryDetail(
            header: detail.header,
            journal: detail.journal,
            chapters: detail.chapters,
            media: sorted,
            tracks: detail.tracks
        )
        memoryDetailMediaErrors[memoryId] = nil
        return true
    }

    func saveMemoryJournal(for memory: Memory, markdown: String) async throws -> MemoryJournal {
        let savedJournal: MemoryJournal
        if mockMode {
            savedJournal = MemoryJournal(memoryId: memory.id, markdown: markdown, updatedAt: nil)
        } else {
            guard supportsCapability("memory.journal-markdown") else {
                throw VeyqAPIError.missingCapability("memory.journal-markdown")
            }
            guard let tenantName = selectedTenant?.name else {
                throw VeyqAPIError.missingSession
            }
            guard let apiClient else {
                throw VeyqAPIError.missingSession
            }
            savedJournal = try await apiClient.updateMemoryJournal(
                tenantName: tenantName,
                memoryId: memory.id,
                markdown: markdown
            )
        }

        if let detail = memoryDetails[memory.id] {
            memoryDetails[memory.id] = LoadedMemoryDetail(
                header: detail.header,
                journal: savedJournal,
                chapters: detail.chapters,
                media: detail.media,
                tracks: detail.tracks
            )
        }
        return savedJournal
    }

    @discardableResult
    func updateMediaCapturedAt(_ media: MemoryMedia, capturedAt: Date?) async throws -> MemoryMedia {
        guard supportsCapability("memory.media") else {
            throw VeyqAPIError.missingCapability("memory.media")
        }

        let updated: MemoryMedia
        if mockMode {
            updated = media.withCapturedAt(capturedAt, updatedAt: Date())
        } else {
            guard let tenantName = selectedTenant?.name, let apiClient else {
                throw VeyqAPIError.missingSession
            }
            updated = try await apiClient.updateMemoryMediaCapturedAt(
                tenantName: tenantName,
                memoryId: media.memoryId,
                mediaId: media.id,
                capturedAt: capturedAt
            )
        }

        mergeUpdatedMedia(updated)
        return updated
    }

    @discardableResult
    func updateMediaHighlight(_ media: MemoryMedia, highlight: Bool) async throws -> MemoryMedia {
        guard supportsCapability("memory.media") else {
            throw VeyqAPIError.missingCapability("memory.media")
        }

        let updated: MemoryMedia
        if mockMode {
            updated = media.withHighlight(highlight, updatedAt: Date())
        } else {
            guard let tenantName = selectedTenant?.name, let apiClient else {
                throw VeyqAPIError.missingSession
            }
            updated = try await apiClient.updateMemoryMediaHighlight(
                tenantName: tenantName,
                memoryId: media.memoryId,
                mediaId: media.id,
                highlight: highlight
            )
        }

        mergeUpdatedMedia(updated)
        return updated
    }

    func deleteMedia(_ media: MemoryMedia) async throws {
        guard supportsCapability("memory.media") else {
            throw VeyqAPIError.missingCapability("memory.media")
        }

        if !mockMode {
            guard let tenantName = selectedTenant?.name, let apiClient else {
                throw VeyqAPIError.missingSession
            }
            try await apiClient.deleteMemoryMedia(
                tenantName: tenantName,
                memoryId: media.memoryId,
                mediaId: media.id
            )
        }

        removeDeletedMedia(media)
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
        let hasMediaFiles = batch.fileURLs.contains { inferredPendingImportKind(for: $0) != .track }
        let hasTrackFiles = batch.fileURLs.contains { inferredPendingImportKind(for: $0) == .track }
        if hasMediaFiles && !supportsCapability("memory.media-resumable-upload") {
            errorMessage = L10n.string("This instance does not support media uploads.")
            return
        }
        if hasTrackFiles && !supportsCapability("memory.tracks") {
            errorMessage = L10n.string("This instance does not support GPX tracks.")
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
        let hasMediaFiles = batch.fileURLs.contains { inferredPendingImportKind(for: $0) != .track }
        let hasTrackFiles = batch.fileURLs.contains { inferredPendingImportKind(for: $0) == .track }
        if hasMediaFiles && !supportsCapability("memory.media-resumable-upload") {
            errorMessage = L10n.string("This instance does not support media uploads.")
            return
        }
        if hasTrackFiles && !supportsCapability("memory.tracks") {
            errorMessage = L10n.string("This instance does not support GPX tracks.")
            return
        }
        let imageURLs = batch.fileURLs.filter { inferredPendingImportKind(for: $0) == .image }
        let videoURLs = batch.fileURLs.filter { inferredPendingImportKind(for: $0) == .video }
        let trackURLs = batch.fileURLs.filter { inferredPendingImportKind(for: $0) == .track }
        enqueuePendingFiles(fileURLs: imageURLs, target: target, kind: .image)
        enqueuePendingFiles(fileURLs: videoURLs, target: target, kind: .video)
        guard !trackURLs.isEmpty else {
            UploadQueuePersistence.removePendingImportBatch(id: batch.id)
            return
        }
        Task {
            await uploadPendingImportTracks(fileURLs: trackURLs, target: target)
            UploadQueuePersistence.removePendingImportBatch(id: batch.id)
        }
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

    private func loadBootstrap(preferredTenantName: String?) async throws {
        guard let apiClient else { throw VeyqAPIError.missingSession }
        let bootstrap = try await apiClient.bootstrap()
        user = bootstrap.user
        tenants = bootstrap.tenants
        if let currentTenant = selectedTenant, bootstrap.tenants.contains(where: { $0.id == currentTenant.id }) {
            selectedTenant = currentTenant
        } else if let preferredTenant = bootstrap.tenants.first(where: { $0.name == preferredTenantName }) {
            selectedTenant = preferredTenant
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
        persistSharedAccounts()
        tenants = []
        selectedTenant = nil
        spaces = []
        selectedSpace = nil
        navigationLocation = nil
        folders = []
        memories = []
        memoryDetails = [:]
        try await loadBootstrap(preferredTenantName: session.selectedTenantName)
        runNextUploadIfNeeded()
    }

    private func activateRestoredSession(_ session: StoredNativeSession) async throws {
        do {
            try await activate(session)
        } catch VeyqAPIError.unauthorized {
            if let recoveredSession = try loadRotatedStoredSession(replacing: session) {
                try await activate(recoveredSession)
                return
            }
            let refreshed = try await refresh(session)
            try sessionStore.save(refreshed)
            instances = try sessionStore.loadAll()
            try await activate(refreshed)
        }
    }

    private func loadRotatedStoredSession(replacing session: StoredNativeSession) throws -> StoredNativeSession? {
        guard let storedSession = try sessionStore.loadActive(),
              storedSession.instanceURL == session.instanceURL,
              storedSession.hasDifferentTokens(than: session)
        else {
            return nil
        }
        return storedSession
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
            capabilities: session.capabilities,
            selectedTenantName: session.selectedTenantName
        )
    }

    private func persistSelectedTenantName(_ tenantName: String?) {
        guard !mockMode else { return }
        do {
            guard let activeSession = try sessionStore.loadActive() else { return }
            try sessionStore.save(activeSession.withSelectedTenantName(tenantName))
            instances = try sessionStore.loadAll()
            persistSharedAccounts()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func persistUpdatedUser(_ nextUser: VeyqUser) throws {
        user = nextUser
        guard let activeSession = try sessionStore.loadActive() else { return }
        try sessionStore.save(activeSession.withUser(nextUser))
        instances = try sessionStore.loadAll()
        persistSharedAccounts()
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
        navigationLocation = nil
        folders = []
        memories = []
        memoryDetails = [:]
        memoryNavigationCache = [:]
        memoryNavigationLoading = false
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
                media: MemoryMedia.chronologicallySorted(detail.media + [media]),
                tracks: detail.tracks
            )
        }
    }

    private func mergeUpdatedMedia(_ media: MemoryMedia) {
        for (memoryId, detail) in memoryDetails where detail.media.contains(where: { $0.id == media.id }) {
            memoryDetails[memoryId] = LoadedMemoryDetail(
                header: detail.header,
                journal: detail.journal,
                chapters: detail.chapters,
                media: MemoryMedia.chronologicallySorted(detail.media.map { $0.id == media.id ? media : $0 }),
                tracks: detail.tracks
            )
        }
    }

    private func removeDeletedMedia(_ media: MemoryMedia) {
        for (memoryId, detail) in memoryDetails where detail.media.contains(where: { $0.id == media.id }) {
            let nextHeader = memoryId == media.memoryId
                ? detail.header.withDirectMediaCount(max(0, detail.header.directMediaCount - 1))
                : detail.header
            memoryDetails[memoryId] = LoadedMemoryDetail(
                header: nextHeader,
                journal: detail.journal,
                chapters: detail.chapters,
                media: detail.media.filter { $0.id != media.id },
                tracks: detail.tracks
            )
        }
    }

    private func refreshMemoryHeader(tenantName: String, memoryId: String) async {
        guard let apiClient, let detail = memoryDetails[memoryId] else { return }
        do {
            let header = try await apiClient.memoryHeader(tenantName: tenantName, memoryId: memoryId)
            guard let currentDetail = memoryDetails[memoryId] else { return }
            memoryDetails[memoryId] = LoadedMemoryDetail(
                header: header,
                journal: currentDetail.journal,
                chapters: currentDetail.chapters,
                media: currentDetail.media,
                tracks: currentDetail.tracks
            )
        } catch {
            memoryDetails[memoryId] = detail
        }
    }

    private func refreshMemoryTracks(tenantName: String, memoryId: String) async {
        guard supportsCapability("memory.tracks"),
              let apiClient,
              let detail = memoryDetails[memoryId]
        else {
            return
        }
        do {
            let tracks = try await apiClient.memoryTracks(tenantName: tenantName, memoryId: memoryId)
            guard let currentDetail = memoryDetails[memoryId] else { return }
            memoryDetails[memoryId] = LoadedMemoryDetail(
                header: currentDetail.header,
                journal: currentDetail.journal,
                chapters: currentDetail.chapters,
                media: currentDetail.media,
                tracks: tracks
            )
        } catch {
            memoryDetails[memoryId] = detail
        }
    }

    private func persistUploads() {
        if !mockMode {
            UploadQueuePersistence.saveQueue(uploadItems)
        }
    }

    private func persistSharedAccounts() {
        guard !mockMode else { return }
        if instances.isEmpty {
            SharedAccountPersistence.clear()
        } else {
            SharedAccountPersistence.save(sessions: instances, activeInstanceURL: connectedInstanceURL)
        }
    }

    private func cancelRunningUploadTasks() {
        uploadTasks.values.forEach { $0.cancel() }
        uploadTasks = [:]
    }

    private func reloadMemories() async {
        await loadMemories(path: navigationLocation?.path ?? selectedSpace?.path ?? "/", selectedSpaceOverride: selectedSpace)
    }

    private func loadMemories(path: String, selectedSpaceOverride: VeyqSpace? = nil) async {
        guard let tenant = selectedTenant, let apiClient else { return }
        let cacheKey = MemoryNavigationCacheKey(tenantName: tenant.name, path: path.trimmedFolderPath)
        memoryNavigationLoadSequence += 1
        let loadSequence = memoryNavigationLoadSequence
        let hasCachedSnapshot = memoryNavigationCache[cacheKey] != nil
        if let snapshot = memoryNavigationCache[cacheKey] {
            applyMemoryNavigationSnapshot(snapshot)
        } else {
            memoryNavigationLoading = true
        }
        defer {
            if memoryNavigationLoadSequence == loadSequence {
                memoryNavigationLoading = false
            }
        }

        do {
            let navigationContext = try await apiClient.memoryNavigationContext(tenantName: tenant.name, path: path)
            var nextSpaces = spaces(from: tenant, navigationContext: navigationContext)
            if let selectedSpaceOverride, !nextSpaces.contains(where: { $0.id == selectedSpaceOverride.id }) {
                nextSpaces = insertingSpace(selectedSpaceOverride, into: nextSpaces)
            }
            let nextSelectedSpace: VeyqSpace?
            if let selectedSpaceOverride {
                nextSelectedSpace = nextSpaces.first { $0.id == selectedSpaceOverride.id } ?? selectedSpaceOverride
            } else if let selectedSpace {
                nextSelectedSpace = nextSpaces.first { $0.id == selectedSpace.id } ?? selectedSpace
            } else {
                nextSelectedSpace = nextSpaces.first
            }
            let snapshot = MemoryNavigationSnapshot(
                spaces: nextSpaces,
                selectedSpace: nextSelectedSpace,
                navigationLocation: navigationContext.location,
                folders: navigationContext.folders,
                memories: navigationContext.memories
            )
            memoryNavigationCache[cacheKey] = snapshot
            if !hasCachedSnapshot || memoryNavigationLoadSequence == loadSequence {
                applyMemoryNavigationSnapshot(snapshot)
            }
            await persistShareImportTargets(tenantName: tenant.name)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func applyMemoryNavigationSnapshot(_ snapshot: MemoryNavigationSnapshot) {
        spaces = snapshot.spaces
        selectedSpace = snapshot.selectedSpace
        navigationLocation = snapshot.navigationLocation
        folders = snapshot.folders
        memories = snapshot.memories
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

    private func uploadPendingImportTracks(fileURLs: [URL], target: UploadTargetMemory) async {
        if mockMode {
            return
        }
        guard supportsCapability("memory.tracks") else {
            errorMessage = L10n.string("This instance does not support GPX tracks.")
            return
        }
        guard let apiClient else {
            errorMessage = L10n.string("No native session is stored.")
            return
        }

        var failedCount = 0
        for fileURL in fileURLs {
            do {
                try await apiClient.uploadMemoryTrack(
                    tenantName: target.tenantName,
                    memoryId: target.memoryId,
                    fileURL: fileURL,
                    title: trackTitle(for: fileURL)
                )
            } catch {
                failedCount += 1
            }
        }

        if failedCount > 0 {
            errorMessage = L10n.format("%d GPX tracks could not be uploaded.", failedCount)
        }
        if failedCount < fileURLs.count {
            await refreshMemoryTracks(tenantName: target.tenantName, memoryId: target.memoryId)
        }
    }

    private func trackTitle(for fileURL: URL) -> String {
        let basename = fileURL.deletingPathExtension().lastPathComponent.trimmingCharacters(in: .whitespacesAndNewlines)
        return basename.isEmpty ? L10n.string("GPX Track") : basename
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

    private func spaces(from tenant: VeyqTenant, navigationContext: MemoryNavigationContextResponse) -> [VeyqSpace] {
        let tenantSpace = VeyqSpace(id: tenant.name, kind: .tenant, label: L10n.string("All Memories"), path: "/", groupName: nil)
        let scoped = navigationContext.scopeRoots.map { root in
            let kind: VeyqSpaceKind = root.kind == "GROUP" ? .group : .personal
            let label = scopedSpaceLabel(displayName: root.displayName, kind: kind)
            return VeyqSpace(id: "\(root.kind.lowercased())-\(root.name)", kind: kind, label: label, path: root.path, groupName: kind == .group ? root.name : nil)
        }
        return [tenantSpace] + scoped
    }

    private func scopedSpaceLabel(displayName: String, kind: VeyqSpaceKind) -> String {
        let trimmed = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        switch kind {
        case .tenant:
            return trimmed
        case .group:
            return trimmed.hasPrefix("@") ? trimmed : "@\(trimmed)"
        case .personal:
            return trimmed.hasPrefix("~") ? trimmed : "~\(trimmed)"
        }
    }

    private func space(from group: GroupSummary) -> VeyqSpace {
        VeyqSpace(
            id: "group-\(group.name)",
            kind: .group,
            label: "@\(group.displayName)",
            path: "/@\(group.name)",
            groupName: group.name
        )
    }

    private func upsertGroupSpace(_ space: VeyqSpace) {
        spaces = insertingSpace(space, into: spaces)
        memoryNavigationCache = memoryNavigationCache.mapValues { snapshot in
            MemoryNavigationSnapshot(
                spaces: insertingSpace(space, into: snapshot.spaces),
                selectedSpace: snapshot.selectedSpace,
                navigationLocation: snapshot.navigationLocation,
                folders: snapshot.folders,
                memories: snapshot.memories
            )
        }
    }

    private func insertingSpace(_ space: VeyqSpace, into existingSpaces: [VeyqSpace]) -> [VeyqSpace] {
        var nextSpaces = existingSpaces
        if let index = nextSpaces.firstIndex(where: { $0.id == space.id }) {
            nextSpaces[index] = space
        } else if let tenantIndex = nextSpaces.firstIndex(where: { $0.kind == .tenant }) {
            nextSpaces.insert(space, at: nextSpaces.index(after: tenantIndex))
        } else if let personalIndex = nextSpaces.firstIndex(where: { $0.kind == .personal }) {
            nextSpaces.insert(space, at: personalIndex)
        } else {
            nextSpaces.append(space)
        }
        return nextSpaces
    }

    private var selectedScopeRequest: MemoryScopeRequest {
        guard let selectedSpace else {
            return MemoryScopeRequest(type: "TENANT", groupName: nil)
        }
        switch selectedSpace.kind {
        case .tenant:
            return MemoryScopeRequest(type: "TENANT", groupName: nil)
        case .group:
            return MemoryScopeRequest(type: "GROUP", groupName: selectedSpace.groupName)
        case .personal:
            return MemoryScopeRequest(type: "USER", groupName: nil)
        }
    }

    private func mergeCreatedMemory(_ created: Memory, parentMemory: Memory?) {
        if let parentMemory {
            if let detail = memoryDetails[parentMemory.id] {
                memoryDetails[parentMemory.id] = LoadedMemoryDetail(
                    header: detail.header.withChapterCount(detail.header.chapterCount + 1),
                    journal: detail.journal,
                    chapters: detail.chapters.contains(where: { $0.id == created.id }) ? detail.chapters : detail.chapters + [created],
                    media: detail.media,
                    tracks: detail.tracks
                )
            }
            if let index = memories.firstIndex(where: { $0.id == parentMemory.id }) {
                memories[index] = memories[index].withChildCount(memories[index].childCount + 1)
            }
            return
        }

        if !memories.contains(where: { $0.id == created.id }) {
            memories.append(created)
            memories.sort { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
        }
    }

    private func mergeCreatedFolder(_ created: MemoryFolder) {
        guard !folders.contains(where: { $0.id == created.id || $0.path == created.path }) else { return }
        folders.append(created)
        folders.sort { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
    }

    private func mergeUpdatedMemory(_ updated: Memory) {
        if let index = memories.firstIndex(where: { $0.id == updated.id }) {
            memories[index] = updated
        }
        for (memoryId, detail) in memoryDetails {
            let updatedChapters = detail.chapters.map { $0.id == updated.id ? updated : $0 }
            let updatedHeader = memoryId == updated.id ? detail.header.withMemory(updated) : detail.header
            memoryDetails[memoryId] = LoadedMemoryDetail(
                header: updatedHeader,
                journal: detail.journal,
                chapters: updatedChapters,
                media: detail.media,
                tracks: detail.tracks
            )
        }
    }

    private func removeDeletedMemory(_ memory: Memory) {
        memories.removeAll { $0.id == memory.id || $0.path.hasPrefix("\(memory.path)/") }
        memoryDetails.removeValue(forKey: memory.id)
        memoryDetailLoadingIDs.remove(memory.id)
        memoryDetailMediaErrors.removeValue(forKey: memory.id)

        for (memoryId, detail) in memoryDetails {
            let remainingChapters = detail.chapters.filter { $0.id != memory.id && !$0.path.hasPrefix("\(memory.path)/") }
            if remainingChapters.count != detail.chapters.count {
                memoryDetails[memoryId] = LoadedMemoryDetail(
                    header: detail.header.withChapterCount(remainingChapters.count),
                    journal: detail.journal,
                    chapters: remainingChapters,
                    media: detail.media,
                    tracks: detail.tracks
                )
            }
        }
    }

    private func mockCreatedMemory(displayName: String, name: String, startDate: String, endDate: String, parentMemory: Memory?) -> Memory {
        let parentPath = parentMemory?.path ?? selectedSpace?.path ?? "/"
        let path = parentPath == "/" ? "/\(name)" : "\(parentPath)/\(name)"
        return Memory(
            id: name,
            name: name,
            displayName: displayName,
            path: path,
            parentId: parentMemory?.id,
            coverMediaId: nil,
            coverUrl: nil,
            coverCrop: nil,
            startDate: startDate,
            endDate: endDate,
            mediaCount: 0,
            childCount: 0
        )
    }

    private func parentFolderPath(for path: String, rootPath: String) -> String {
        let normalized = path.trimmedFolderPath
        let root = rootPath.trimmedFolderPath
        guard normalized != root else { return root }
        let parts = normalized.split(separator: "/").map(String.init)
        guard parts.count > 1 else { return root }
        let parent = "/" + parts.dropLast().joined(separator: "/")
        if root == "/" {
            return parent.isEmpty ? "/" : parent
        }
        return parent.hasPrefix(root) ? parent : root
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
            VeyqSpace(id: "personal", kind: .personal, label: "~\(user?.displayName ?? L10n.string("Personal"))", path: "/~me", groupName: nil)
        ]
        selectedSpace = spaces[0]
        loadMockFolder(path: spaces[0].path)
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

    private func mockFolders(for path: String) -> [MemoryFolder] {
        switch path.trimmedFolderPath {
        case "/":
            [MemoryFolder(id: "folder-travel", name: "travel", displayName: "Travel", path: "/travel")]
        case "/@family":
            [MemoryFolder(id: "folder-family-events", name: "events", displayName: "Events", path: "/@family/events")]
        default:
            []
        }
    }

    private func loadMockFolder(path: String, fallbackSpace: VeyqSpace? = nil) {
        let normalizedPath = path.trimmedFolderPath
        let space = fallbackSpace ?? selectedSpace
        navigationLocation = MemoryNavigationLocation(
            kind: normalizedPath == space?.path.trimmedFolderPath ? "SCOPE" : "FOLDER",
            path: normalizedPath,
            displayName: space?.label ?? L10n.string("Memories")
        )
        folders = mockFolders(for: normalizedPath)
        if normalizedPath == space?.path.trimmedFolderPath, let space {
            memories = mockMemories(for: space)
        } else {
            memories = []
        }
    }

    private func mockChapters(for memory: Memory) -> [Memory] {
        guard memory.id == "summer-south-tyrol" else { return [] }
        return [
            Memory(id: "summer-south-tyrol-day-1", name: "day-1", displayName: "Day 1 - Arrival", path: "\(memory.path)/day-1", parentId: memory.id, coverMediaId: nil, coverUrl: nil, coverCrop: nil, startDate: "2026-07-12", endDate: "2026-07-12", mediaCount: 24, childCount: 0),
            Memory(id: "summer-south-tyrol-day-2", name: "day-2", displayName: "Day 2 - Mountain Trail", path: "\(memory.path)/day-2", parentId: memory.id, coverMediaId: nil, coverUrl: nil, coverCrop: nil, startDate: "2026-07-13", endDate: "2026-07-13", mediaCount: 31, childCount: 0)
        ]
    }

    private func inferredPendingImportKind(for url: URL) -> VeyqPendingImportFileKind {
        let lower = url.pathExtension.lowercased()
        if lower == "gpx" {
            return .track
        }
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
    let tracks: [MemoryTrack]
}

private extension Memory {
    func withDisplayMetadata(displayName: String, name: String, startDate: String, endDate: String) -> Memory {
        let pathPrefix = path.split(separator: "/").dropLast().joined(separator: "/")
        let updatedPath = pathPrefix.isEmpty ? "/\(name)" : "/\(pathPrefix)/\(name)"
        return Memory(
            id: id,
            name: name,
            displayName: displayName,
            path: updatedPath,
            parentId: parentId,
            coverMediaId: coverMediaId,
            coverUrl: coverUrl,
            coverCrop: coverCrop,
            startDate: startDate,
            endDate: endDate,
            mediaCount: mediaCount,
            childCount: childCount
        )
    }

    func withChildCount(_ childCount: Int) -> Memory {
        Memory(
            id: id,
            name: name,
            displayName: displayName,
            path: path,
            parentId: parentId,
            coverMediaId: coverMediaId,
            coverUrl: coverUrl,
            coverCrop: coverCrop,
            startDate: startDate,
            endDate: endDate,
            mediaCount: mediaCount,
            childCount: childCount
        )
    }

    func withCover(mediaId: String?, coverUrl: String?, coverCrop: CoverCrop?) -> Memory {
        Memory(
            id: id,
            name: name,
            displayName: displayName,
            path: path,
            parentId: parentId,
            coverMediaId: mediaId,
            coverUrl: coverUrl,
            coverCrop: coverCrop,
            startDate: startDate,
            endDate: endDate,
            mediaCount: mediaCount,
            childCount: childCount
        )
    }
}

private extension MemoryHeader {
    func withMemory(_ memory: Memory) -> MemoryHeader {
        MemoryHeader(
            id: id,
            displayName: memory.displayName,
            path: memory.path,
            coverUrl: memory.coverUrl,
            coverCrop: memory.coverCrop,
            startDate: memory.startDate,
            endDate: memory.endDate,
            directMediaCount: directMediaCount,
            descendantMediaCount: descendantMediaCount,
            chapterCount: chapterCount,
            favorite: favorite
        )
    }

    func withChapterCount(_ chapterCount: Int) -> MemoryHeader {
        MemoryHeader(
            id: id,
            displayName: displayName,
            path: path,
            coverUrl: coverUrl,
            coverCrop: coverCrop,
            startDate: startDate,
            endDate: endDate,
            directMediaCount: directMediaCount,
            descendantMediaCount: descendantMediaCount,
            chapterCount: chapterCount,
            favorite: favorite
        )
    }

    func withDirectMediaCount(_ directMediaCount: Int) -> MemoryHeader {
        MemoryHeader(
            id: id,
            displayName: displayName,
            path: path,
            coverUrl: coverUrl,
            coverCrop: coverCrop,
            startDate: startDate,
            endDate: endDate,
            directMediaCount: directMediaCount,
            descendantMediaCount: descendantMediaCount,
            chapterCount: chapterCount,
            favorite: favorite
        )
    }
}

private extension MemoryMedia {
    func withCapturedAt(_ capturedAt: Date?, updatedAt: Date) -> MemoryMedia {
        MemoryMedia(
            id: id,
            memoryId: memoryId,
            bucketId: bucketId,
            title: title,
            uploader: uploader,
            createdAt: createdAt,
            updatedAt: updatedAt,
            capturedAt: capturedAt,
            capturedAtOverride: true,
            mimeType: mimeType,
            sizeBytes: sizeBytes,
            contentUrl: contentUrl,
            highlight: highlight,
            thumbnailUrl: thumbnailUrl,
            previewUrl: previewUrl,
            posterUrl: posterUrl,
            cameraMake: cameraMake,
            cameraModel: cameraModel,
            lensModel: lensModel,
            imageWidth: imageWidth,
            imageHeight: imageHeight,
            gpsLatitude: gpsLatitude,
            gpsLongitude: gpsLongitude
        )
    }

    func withHighlight(_ highlight: Bool, updatedAt: Date) -> MemoryMedia {
        MemoryMedia(
            id: id,
            memoryId: memoryId,
            bucketId: bucketId,
            title: title,
            uploader: uploader,
            createdAt: createdAt,
            updatedAt: updatedAt,
            capturedAt: capturedAt,
            capturedAtOverride: capturedAtOverride,
            mimeType: mimeType,
            sizeBytes: sizeBytes,
            contentUrl: contentUrl,
            highlight: highlight,
            thumbnailUrl: thumbnailUrl,
            previewUrl: previewUrl,
            posterUrl: posterUrl,
            cameraMake: cameraMake,
            cameraModel: cameraModel,
            lensModel: lensModel,
            imageWidth: imageWidth,
            imageHeight: imageHeight,
            gpsLatitude: gpsLatitude,
            gpsLongitude: gpsLongitude
        )
    }
}

extension CoverCrop {
    static let defaultCoverCrop = CoverCrop(x: 0, y: 0, width: 1, height: 0.5625)
}

private extension String {
    var trimmedFolderPath: String {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "/" }
        var normalized = trimmed.hasPrefix("/") ? trimmed : "/\(trimmed)"
        while normalized.count > 1 && normalized.hasSuffix("/") {
            normalized.removeLast()
        }
        return normalized
    }
}
