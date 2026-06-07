import MapKit
import XCTest
@testable import Veyq

final class VeyqTests: XCTestCase {
    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: AppThemePreference.storageKey)
        UserDefaults.standard.removeObject(forKey: AppLanguagePreference.storageKey)
        super.tearDown()
    }

    func testNormalizesHttpsInstanceURL() throws {
        let url = try InstanceURLValidator.normalize(" memories.example.test/ ")
        XCTAssertEqual(url.absoluteString, "https://memories.example.test")
    }

    func testAllowsHttpInstanceURL() throws {
        let url = try InstanceURLValidator.normalize("http://memories.example.test")
        XCTAssertEqual(url.absoluteString, "http://memories.example.test")
    }

    func testKeepsInstancePathPrefix() throws {
        let url = try InstanceURLValidator.normalize("https://memories.example.test/veyq/")
        XCTAssertEqual(url.absoluteString, "https://memories.example.test/veyq")
    }

    @MainActor
    func testConnectMovesStoreToHomeState() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        let url = try await store.connect(to: "https://memories.example.test")

        XCTAssertTrue(store.isConnected)
        XCTAssertEqual(url.absoluteString, "https://memories.example.test")
        XCTAssertEqual(store.connectedInstanceURL, url)
    }

    @MainActor
    func testRestoreSessionMarksInitialSessionCheckComplete() async {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore())

        XCTAssertFalse(store.hasCompletedSessionRestore)
        await store.restoreSession()

        XCTAssertTrue(store.hasCompletedSessionRestore)
        XCTAssertFalse(store.isConnected)
    }

    @MainActor
    func testMockMemoryDetailDataIsAvailable() async {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        try? await store.connect(to: "https://memories.example.test")
        let memory = Memory.samples[0]
        await store.loadDetail(for: memory)

        XCTAssertEqual(store.memoryDetails[memory.id]?.header.displayName, "Summer in South Tyrol")
        XCTAssertEqual(store.memoryDetails[memory.id]?.header.coverUrl, "/api/family/memories/summer-south-tyrol/cover/content")
        XCTAssertEqual(store.memoryDetails[memory.id]?.header.coverCrop?.width, 0.8)
        XCTAssertEqual(store.memoryDetails[memory.id]?.journal.memoryId, "summer-south-tyrol")
    }

    @MainActor
    func testMockMemoryDetailIncludesNavigableChapters() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        try await store.connect(to: "https://memories.example.test")
        let memory = Memory.samples[0]
        await store.loadDetail(for: memory)

        let chapters = try XCTUnwrap(store.memoryDetails[memory.id]?.chapters)
        XCTAssertEqual(chapters.map(\.id), ["summer-south-tyrol-day-1", "summer-south-tyrol-day-2"])
        XCTAssertEqual(chapters.first?.parentId, memory.id)
    }

    @MainActor
    func testShareImportTargetsIncludeChapters() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        try await store.connect(to: "https://memories.example.test")
        await store.loadDetail(for: Memory.samples[0])

        let targets = await store.shareImportTargets(tenantName: "family")

        XCTAssertEqual(targets.map(\.id), [
            "summer-south-tyrol",
            "summer-south-tyrol-day-1",
            "summer-south-tyrol-day-2"
        ])
        XCTAssertEqual(targets[1].parentMemoryTitle, "Summer in South Tyrol")
        XCTAssertEqual(targets[1].depth, 1)
    }

    @MainActor
    func testShareImportTargetsDoNotFetchUnknownChapters() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        try await store.connect(to: "https://memories.example.test")

        let targets = await store.shareImportTargets(tenantName: "family")

        XCTAssertEqual(targets.map(\.id), ["summer-south-tyrol"])
    }

    @MainActor
    func testConnectedStateExposesTabShellData() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        _ = try await store.connect(to: "https://memories.example.test")

        XCTAssertEqual(store.selectedTenant?.displayName, "Family Memories")
        XCTAssertFalse(store.memories.isEmpty)
        XCTAssertFalse(store.uploadItems.isEmpty)
    }

    @MainActor
    func testStoreUpdatesMockUserSettings() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        try await store.connect(to: "https://memories.example.test")

        let updated = try await store.updateMySettings(
            displayName: "Kevin Raddatz",
            permalink: "kevin",
            email: "kevin@example.test"
        )

        XCTAssertEqual(updated.displayName, "Kevin Raddatz")
        XCTAssertEqual(store.user?.displayName, "Kevin Raddatz")
        XCTAssertEqual(store.user?.permalink, "kevin")
        XCTAssertEqual(store.user?.email, "kevin@example.test")
    }

    @MainActor
    func testUploadQueueContainsAllPlannedStates() {
        let states = Set(VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true).uploadItems.map(\.state))

        XCTAssertEqual(states, [.queued, .uploading, .failed, .complete])
    }

    func testUploadChunkPlannerSplitsFileIntoStableRanges() {
        let ranges = UploadChunkPlanner.ranges(sizeBytes: 10, chunkSizeBytes: 4)

        XCTAssertEqual(ranges.map { "\($0.lowerBound)..<\($0.upperBound)" }, ["0..<4", "4..<8", "8..<10"])
    }

    func testUploadQueueItemDecodesLegacyQueueWithoutProgressMetadata() throws {
        let data = """
        {
          "id": "legacy",
          "localFileURL": "file:///tmp/photo.jpg",
          "filename": "photo.jpg",
          "mimeType": "image/jpeg",
          "sizeBytes": 1000,
          "target": {
            "tenantName": "family",
            "memoryId": "summer",
            "memoryTitle": "Summer"
          },
          "progress": 0.25,
          "state": "failed",
          "uploadId": "upl_123",
          "errorMessage": "Interrupted"
        }
        """.data(using: .utf8)!

        let item = try JSONDecoder.veyq.decode(UploadQueueItem.self, from: data)

        XCTAssertEqual(item.uploadedBytes, 250)
        XCTAssertEqual(item.uploadId, "upl_123")
        XCTAssertTrue(item.retryable)
        XCTAssertNil(item.startedAt)
        XCTAssertNil(item.progressUpdatedAt)
    }

    func testUploadingQueueItemRestoresAsQueuedForLaunchResume() {
        let item = UploadQueueItem(
            id: "resume",
            localFileURL: URL(fileURLWithPath: "/tmp/video.mov"),
            filename: "video.mov",
            mimeType: "video/quicktime",
            sizeBytes: 1000,
            target: makeUploadTarget(),
            progress: 0.5,
            state: .uploading,
            uploadId: "upl_123",
            errorMessage: "old",
            uploadedBytes: 500,
            startedAt: Date(timeIntervalSince1970: 1_800_000_000),
            progressUpdatedAt: Date(timeIntervalSince1970: 1_800_000_010)
        )

        let restored = item.restoredForLaunch

        XCTAssertEqual(restored.state, .queued)
        XCTAssertEqual(restored.uploadId, "upl_123")
        XCTAssertEqual(restored.uploadedBytes, 500)
        XCTAssertNil(restored.errorMessage)
        XCTAssertNil(restored.startedAt)
        XCTAssertTrue(restored.retryable)
    }

    func testUploadQueuePresenterShowsMissingLocalFileAsNonRetryable() {
        UserDefaults.standard.set(AppLanguagePreference.en.rawValue, forKey: AppLanguagePreference.storageKey)
        let item = UploadQueueItem(
            id: "missing-local-file",
            localFileURL: URL(fileURLWithPath: "/tmp/missing.jpg"),
            filename: "missing.jpg",
            mimeType: "image/jpeg",
            sizeBytes: 600,
            target: makeUploadTarget(),
            progress: 0,
            state: .failed,
            errorMessage: "Missing",
            retryable: false
        )

        XCTAssertEqual(
            UploadQueueProgressPresenter.detailText(for: item),
            "The local file is no longer available. Add it again to upload."
        )
    }

    func testUploadQueuePresenterShowsEtaForActiveUpload() {
        UserDefaults.standard.set(AppLanguagePreference.en.rawValue, forKey: AppLanguagePreference.storageKey)
        let item = UploadQueueItem(
            id: "eta",
            localFileURL: URL(fileURLWithPath: "/tmp/video.mov"),
            filename: "video.mov",
            mimeType: "video/quicktime",
            sizeBytes: 600,
            target: makeUploadTarget(),
            progress: 0.5,
            state: .uploading,
            uploadedBytes: 300,
            startedAt: Date(timeIntervalSince1970: 1_800_000_000),
            progressUpdatedAt: Date(timeIntervalSince1970: 1_800_000_010)
        )

        let detail = UploadQueueProgressPresenter.detailText(
            for: item,
            now: Date(timeIntervalSince1970: 1_800_000_030)
        )

        XCTAssertEqual(detail, "50% uploaded • less than a minute remaining")
    }

    func testUploadQueuePresenterDetectsStalledUpload() {
        UserDefaults.standard.set(AppLanguagePreference.en.rawValue, forKey: AppLanguagePreference.storageKey)
        let item = UploadQueueItem(
            id: "stalled",
            localFileURL: URL(fileURLWithPath: "/tmp/video.mov"),
            filename: "video.mov",
            mimeType: "video/quicktime",
            sizeBytes: 600,
            target: makeUploadTarget(),
            progress: 0.5,
            state: .uploading,
            uploadedBytes: 300,
            startedAt: Date(timeIntervalSince1970: 1_800_000_000),
            progressUpdatedAt: Date(timeIntervalSince1970: 1_800_000_010)
        )

        XCTAssertTrue(UploadQueueProgressPresenter.isStalled(
            item,
            now: Date(timeIntervalSince1970: 1_800_000_041)
        ))
        XCTAssertEqual(
            UploadQueueProgressPresenter.detailText(for: item, now: Date(timeIntervalSince1970: 1_800_000_041)),
            "Upload appears stalled. It will keep waiting for network progress."
        )
    }

    func testPendingImportPlannerEnqueuesTargetedBatchWithoutSheet() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let targeted = makePendingImportBatch(id: "targeted", createdAt: now, targetMemory: makeUploadTarget())

        let action = PendingImportPresentationPlanner.action(
            for: [targeted],
            pendingSheetBatchId: nil,
            activeHandoffBatchIds: [],
            now: now
        )

        XCTAssertEqual(action, .enqueueTargeted([targeted]))
    }

    func testPendingImportPlannerPresentsOlderUntargetedBatch() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let untargeted = makePendingImportBatch(
            id: "untargeted",
            createdAt: now.addingTimeInterval(-PendingImportPresentationPlanner.untargetedBatchGraceInterval - 1)
        )

        let action = PendingImportPresentationPlanner.action(
            for: [untargeted],
            pendingSheetBatchId: nil,
            activeHandoffBatchIds: [],
            now: now
        )

        XCTAssertEqual(action, .presentUntargeted(untargeted))
    }

    func testPendingImportPlannerDoesNotPresentFreshUntargetedBatchDuringHandoffRace() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let freshUntargeted = makePendingImportBatch(id: "fresh-untargeted", createdAt: now)

        let action = PendingImportPresentationPlanner.action(
            for: [freshUntargeted],
            pendingSheetBatchId: nil,
            activeHandoffBatchIds: [],
            now: now
        )

        XCTAssertEqual(action, .none)
    }

    func testPendingImportPlannerPrioritizesTargetedBatchOverUntargetedBatch() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let oldUntargeted = makePendingImportBatch(id: "old-untargeted", createdAt: now.addingTimeInterval(-60))
        let targeted = makePendingImportBatch(id: "targeted", createdAt: now.addingTimeInterval(-1), targetMemory: makeUploadTarget())

        let action = PendingImportPresentationPlanner.action(
            for: [oldUntargeted, targeted],
            pendingSheetBatchId: nil,
            activeHandoffBatchIds: [],
            now: now
        )

        XCTAssertEqual(action, .enqueueTargeted([targeted]))
    }

    @MainActor
    func testRetryFailedUploadReturnsItToQueuedState() {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        let failed = store.uploadItems.first { $0.state == .failed }!

        store.retryUpload(failed)

        XCTAssertEqual(store.uploadItems.first { $0.id == failed.id }?.state, .queued)
        XCTAssertNil(store.uploadItems.first { $0.id == failed.id }?.errorMessage)
    }

    @MainActor
    func testDefaultSelectedSpaceIsTenantScope() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        _ = try await store.connect(to: "https://memories.example.test")

        XCTAssertEqual(store.selectedSpace?.kind, .tenant)
        XCTAssertEqual(store.selectedSpace?.path, "/")
    }

    @MainActor
    func testSwitchingSelectedSpaceChangesVisibleMemories() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        _ = try await store.connect(to: "https://memories.example.test")
        let tenantMemoryIds = store.visibleMemories.map(\.id)

        let groupSpace = try XCTUnwrap(store.spaces.first { $0.kind == .group })
        store.selectSpace(groupSpace)

        XCTAssertNotEqual(tenantMemoryIds, store.visibleMemories.map(\.id))
        XCTAssertEqual(store.visibleMemories.map(\.id), ["birthday-weekend"])
    }

    @MainActor
    func testRefreshingCurrentMemoriesKeepsSelectedSpace() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        _ = try await store.connect(to: "https://memories.example.test")
        let groupSpace = try XCTUnwrap(store.spaces.first { $0.kind == .group })
        store.selectSpace(groupSpace)

        await store.refreshCurrentMemories()

        XCTAssertEqual(store.selectedSpace, groupSpace)
        XCTAssertEqual(store.visibleMemories.map(\.id), ["birthday-weekend"])
    }

    @MainActor
    func testMockSpacesIncludeTenantGroupAndPersonalKinds() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        _ = try await store.connect(to: "https://memories.example.test")
        let kinds = Set(store.spaces.map(\.kind))

        XCTAssertEqual(kinds, Set(VeyqSpaceKind.allCases))
    }

    @MainActor
    func testMockStoreShowsAndOpensOrganizationalFolders() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        _ = try await store.connect(to: "https://memories.example.test")

        XCTAssertEqual(store.visibleFolders.map(\.id), ["folder-travel"])

        let folder = try XCTUnwrap(store.visibleFolders.first)
        store.openFolder(folder)

        XCTAssertEqual(store.currentFolderPath, "/travel")
        XCTAssertTrue(store.canNavigateUpFolder)
        XCTAssertTrue(store.visibleMemories.isEmpty)

        store.navigateUpFolder()

        XCTAssertEqual(store.currentFolderPath, "/")
        XCTAssertFalse(store.canNavigateUpFolder)
    }

    @MainActor
    func testMockStoreCreatesFolderInCurrentFolder() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        _ = try await store.connect(to: "https://memories.example.test")

        let created = try await store.createFolder(displayName: "Road Trips", name: "road-trips")

        XCTAssertEqual(created.path, "/road-trips")
        XCTAssertTrue(store.visibleFolders.contains { $0.id == "road-trips" })
    }

    @MainActor
    func testGroupSpacesExposeVisibleGroupScopes() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        _ = try await store.connect(to: "https://memories.example.test")

        XCTAssertEqual(store.groupSpaces.map(\.id), ["group-family"])
        XCTAssertEqual(store.groupSpaces.first?.label, "@Family")
    }

    @MainActor
    func testMockStoreCreatesGroupSpaceAndSelectsIt() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        _ = try await store.connect(to: "https://memories.example.test")

        let created = try await store.createGroup(displayName: "Road Crew", name: "road-crew")

        XCTAssertEqual(created.id, "group-road-crew")
        XCTAssertEqual(created.label, "@Road Crew")
        XCTAssertEqual(created.path, "/@road-crew")
        XCTAssertTrue(store.groupSpaces.contains(created))
        XCTAssertEqual(store.selectedSpace, created)
    }

    @MainActor
    func testSearchFindsMemoryByTitle() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        _ = try await store.connect(to: "https://memories.example.test")
        let personalSpace = try XCTUnwrap(store.spaces.first { $0.kind == .personal })
        store.selectSpace(personalSpace)
        let results = store.searchMemories(matching: "garden")

        XCTAssertEqual(results.map(\.id), ["morning-garden"])
    }

    @MainActor
    func testSearchCoversTenantGroupsAndPersonalScopes() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        _ = try await store.connect(to: "https://memories.example.test")
        let personalSpace = try XCTUnwrap(store.spaces.first { $0.kind == .personal })
        store.selectSpace(personalSpace)

        await store.updateSearch(matching: "Birthday")

        XCTAssertEqual(store.visibleMemories.map(\.id), ["morning-garden"])
        XCTAssertEqual(store.searchResults.map(\.id), ["birthday-weekend"])
        XCTAssertEqual(store.searchResults.first?.path, "/@family/birthday-weekend")
    }

    func testAppPreferenceResolversFallbackToSystem() {
        XCTAssertEqual(AppThemePreference.resolve("not-a-theme"), .system)
        XCTAssertEqual(AppLanguagePreference.resolve("not-a-language"), .system)
    }

    func testL10nUsesExplicitLanguagePreference() {
        XCTAssertEqual(L10n.string("Settings", languagePreference: .de), "Einstellungen")
        XCTAssertEqual(L10n.string("Settings", languagePreference: .en), "Settings")
    }

    func testDiscoveryParsing() throws {
        let data = """
        {
          "product": "veyq",
          "apiBaseUrl": "https://memories.example.test/api",
          "clientCapabilitiesUrl": "https://memories.example.test/api/client-capabilities",
          "mobileLoginUrl": "https://memories.example.test/login?handoff=mobile",
          "mobileCallbackScheme": "veyq"
        }
        """.data(using: .utf8)!

        let discovery = try JSONDecoder.veyq.decode(VeyqInstanceDiscovery.self, from: data)

        XCTAssertEqual(discovery.product, "veyq")
        XCTAssertEqual(discovery.mobileCallbackScheme, "veyq")
    }

    func testCreateGroupRequestUsesGroupsEndpoint() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.responseData = """
        {
          "id": 99,
          "name": "road-crew",
          "displayName": "Road Crew",
          "members": []
        }
        """.data(using: .utf8)!
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            apiBaseURL: URL(string: "https://memories.example.test/api")!,
            accessToken: "access",
            session: URLSession(configuration: .stubbed)
        )

        let created = try await client.createGroup(
            tenantName: "friends",
            request: CreateGroupRequest(name: "road-crew", displayName: "Road Crew")
        )

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        let body = String(data: try XCTUnwrap(URLProtocolStub.lastRequestBody), encoding: .utf8) ?? ""
        XCTAssertEqual(created.name, "road-crew")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.absoluteString, "https://memories.example.test/api/friends/groups")
        XCTAssertTrue(body.contains(#""name":"road-crew""#))
        XCTAssertTrue(body.contains(#""displayName":"Road Crew""#))
    }

    @MainActor
    func testConnectPersistsClientCapabilitySnapshotWithNativeSession() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.queuedResponses = [
            URLProtocolStub.Response(statusCode: 200, data: discoveryJSON().data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: capabilitiesJSON(["auth.mobile-handoff", "memory.core", "memory.media"]).data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: nativeSessionJSON(accessToken: "access", refreshToken: "refresh").data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: capabilitiesJSON(["auth.mobile-handoff", "memory.core", "memory.media"]).data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: bootstrapJSON().data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: navigationJSON().data(using: .utf8)!)
        ]

        let sessionStore = InMemoryNativeSessionStore()
        let store = VeyqStore(
            sessionStore: sessionStore,
            authenticator: StubMobileAuthenticator(),
            apiSession: URLSession(configuration: .stubbed)
        )

        try await store.connect(to: "https://memories.example.test")

        let saved = try XCTUnwrap(sessionStore.loadActive())
        XCTAssertEqual(saved.capabilities.map(\.id), ["auth.mobile-handoff", "memory.core", "memory.media"])
        XCTAssertTrue(store.supportsCapability("memory.media"))
    }

    @MainActor
    func testConnectFormatsScopedSpaceLabels() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.queuedResponses = [
            URLProtocolStub.Response(statusCode: 200, data: discoveryJSON().data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: capabilitiesJSON(["auth.mobile-handoff", "memory.core"]).data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: nativeSessionJSON(accessToken: "access", refreshToken: "refresh").data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: capabilitiesJSON(["auth.mobile-handoff", "memory.core"]).data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: bootstrapJSON().data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: scopedNavigationJSON().data(using: .utf8)!)
        ]

        let store = VeyqStore(
            sessionStore: InMemoryNativeSessionStore(),
            authenticator: StubMobileAuthenticator(),
            apiSession: URLSession(configuration: .stubbed)
        )

        try await store.connect(to: "https://memories.example.test")

        XCTAssertEqual(store.spaces.first { $0.kind == .group }?.label, "@Family")
        XCTAssertEqual(store.spaces.first { $0.kind == .personal }?.label, "~Admin")
    }

    @MainActor
    func testConnectRejectsInstancesMissingRequiredCapabilities() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.queuedResponses = [
            URLProtocolStub.Response(statusCode: 200, data: discoveryJSON().data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: capabilitiesJSON(["auth.mobile-handoff"]).data(using: .utf8)!)
        ]

        let store = VeyqStore(
            sessionStore: InMemoryNativeSessionStore(),
            authenticator: StubMobileAuthenticator(),
            apiSession: URLSession(configuration: .stubbed)
        )

        do {
            try await store.connect(to: "https://memories.example.test")
            XCTFail("Expected missing capability error")
        } catch VeyqAPIError.missingCapability(let capability) {
            XCTAssertEqual(capability, "memory.core")
        }
    }

    @MainActor
    func testRestoreRefreshesExpiredNativeSessionBeforeBootstrap() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.queuedResponses = [
            URLProtocolStub.Response(statusCode: 200, data: nativeSessionJSON(accessToken: "new-access", refreshToken: "new-refresh").data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: capabilitiesJSON(["auth.mobile-handoff", "memory.core"]).data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: bootstrapJSON().data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: navigationJSON().data(using: .utf8)!)
        ]

        let sessionStore = InMemoryNativeSessionStore()
        try sessionStore.save(StoredNativeSession(
            instanceURL: URL(string: "https://memories.example.test")!,
            apiBaseURL: URL(string: "https://memories.example.test/api")!,
            accessToken: "expired-access",
            accessTokenExpiresAt: Date(timeIntervalSince1970: 1_700_000_000),
            refreshToken: "old-refresh",
            refreshTokenExpiresAt: Date(timeIntervalSince1970: 1_900_000_000),
            user: testUser(),
            capabilities: [VeyqCapability(id: "memory.core", introducedInVersion: "1.0", audience: "ALL_USERS")]
        ))
        let store = VeyqStore(
            sessionStore: sessionStore,
            apiSession: URLSession(configuration: .stubbed)
        )

        await store.restoreSession()

        let requestedURLs = URLProtocolStub.requestHistory.compactMap { $0.url?.absoluteString }
        let saved = try XCTUnwrap(sessionStore.loadActive())
        XCTAssertEqual(requestedURLs.first, "https://memories.example.test/api/auth/mobile/session/refresh")
        XCTAssertEqual(URLProtocolStub.requestHistory.first?.value(forHTTPHeaderField: "Authorization"), nil)
        XCTAssertEqual(saved.accessToken, "new-access")
        XCTAssertTrue(store.isConnected)
    }

    @MainActor
    func testRestoreRecoversWhenStoredNativeSessionWasAlreadyRotated() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.queuedResponses = [
            URLProtocolStub.Response(statusCode: 401, data: #"{"code":"UNAUTHORIZED","message":"Unauthorized"}"#.data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: capabilitiesJSON(["auth.mobile-handoff", "memory.core"]).data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: bootstrapJSON().data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: navigationJSON().data(using: .utf8)!)
        ]

        let staleSession = StoredNativeSession(
            instanceURL: URL(string: "https://memories.example.test")!,
            apiBaseURL: URL(string: "https://memories.example.test/api")!,
            accessToken: "expired-access",
            accessTokenExpiresAt: Date(timeIntervalSince1970: 1_700_000_000),
            refreshToken: "old-refresh",
            refreshTokenExpiresAt: Date(timeIntervalSince1970: 1_900_000_000),
            user: testUser(),
            capabilities: [VeyqCapability(id: "memory.core", introducedInVersion: "1.0", audience: "ALL_USERS")]
        )
        let rotatedSession = StoredNativeSession(
            instanceURL: URL(string: "https://memories.example.test")!,
            apiBaseURL: URL(string: "https://memories.example.test/api")!,
            accessToken: "rotated-access",
            accessTokenExpiresAt: Date(timeIntervalSince1970: 1_900_000_000),
            refreshToken: "rotated-refresh",
            refreshTokenExpiresAt: Date(timeIntervalSince1970: 1_900_003_600),
            user: testUser(),
            capabilities: [VeyqCapability(id: "memory.core", introducedInVersion: "1.0", audience: "ALL_USERS")]
        )
        let sessionStore = RotatedNativeSessionStore(staleSession: staleSession, rotatedSession: rotatedSession)
        let store = VeyqStore(
            sessionStore: sessionStore,
            apiSession: URLSession(configuration: .stubbed)
        )

        await store.restoreSession()

        let requestedURLs = URLProtocolStub.requestHistory.compactMap { $0.url?.absoluteString }
        XCTAssertEqual(requestedURLs.first, "https://memories.example.test/api/auth/mobile/session/refresh")
        XCTAssertEqual(requestedURLs.dropFirst().first, "https://memories.example.test/api/client-capabilities")
        XCTAssertEqual(URLProtocolStub.requestHistory.dropFirst().first?.value(forHTTPHeaderField: "Authorization"), "Bearer rotated-access")
        XCTAssertNil(store.errorMessage)
        XCTAssertTrue(store.isConnected)
    }

    @MainActor
    func testRestoreKeepsLastSelectedTenant() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.queuedResponses = [
            URLProtocolStub.Response(statusCode: 200, data: capabilitiesJSON(["auth.mobile-handoff", "memory.core"]).data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: multiTenantBootstrapJSON().data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: navigationJSON().data(using: .utf8)!)
        ]

        let sessionStore = InMemoryNativeSessionStore()
        try sessionStore.save(StoredNativeSession(
            instanceURL: URL(string: "https://memories.example.test")!,
            apiBaseURL: URL(string: "https://memories.example.test/api")!,
            accessToken: "access",
            accessTokenExpiresAt: Date(timeIntervalSince1970: 1_900_000_000),
            refreshToken: "refresh",
            refreshTokenExpiresAt: Date(timeIntervalSince1970: 1_900_003_600),
            user: testUser(),
            capabilities: [VeyqCapability(id: "memory.core", introducedInVersion: "1.0", audience: "ALL_USERS")],
            selectedTenantName: "friends"
        ))
        let store = VeyqStore(
            sessionStore: sessionStore,
            apiSession: URLSession(configuration: .stubbed)
        )

        await store.restoreSession()

        let requestedURLs = URLProtocolStub.requestHistory.compactMap { $0.url?.absoluteString }
        XCTAssertEqual(store.selectedTenant?.name, "friends")
        XCTAssertTrue(requestedURLs.contains("https://memories.example.test/api/friends/memory-navigation-context?path=/"))
    }

    @MainActor
    func testSelectingTenantPersistsItInActiveSession() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.queuedResponses = [
            URLProtocolStub.Response(statusCode: 200, data: capabilitiesJSON(["auth.mobile-handoff", "memory.core"]).data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: multiTenantBootstrapJSON().data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: navigationJSON().data(using: .utf8)!)
        ]

        let sessionStore = InMemoryNativeSessionStore()
        try sessionStore.save(StoredNativeSession(
            instanceURL: URL(string: "https://memories.example.test")!,
            apiBaseURL: URL(string: "https://memories.example.test/api")!,
            accessToken: "access",
            accessTokenExpiresAt: Date(timeIntervalSince1970: 1_900_000_000),
            refreshToken: "refresh",
            refreshTokenExpiresAt: Date(timeIntervalSince1970: 1_900_003_600),
            user: testUser(),
            capabilities: [VeyqCapability(id: "memory.core", introducedInVersion: "1.0", audience: "ALL_USERS")]
        ))
        let store = VeyqStore(
            sessionStore: sessionStore,
            apiSession: URLSession(configuration: .stubbed)
        )
        await store.restoreSession()

        let friends = try XCTUnwrap(store.tenants.first { $0.name == "friends" })
        URLProtocolStub.queuedResponses = [
            URLProtocolStub.Response(statusCode: 200, data: navigationJSON().data(using: .utf8)!)
        ]
        store.selectTenant(friends)

        XCTAssertEqual(try sessionStore.loadActive()?.selectedTenantName, "friends")
    }

    func testMemoryCoverDecoding() throws {
        let data = """
        {
          "id": "mem_abc123",
          "name": "albania-2024",
          "displayName": "Albania 2024",
          "path": "/albania-2024",
          "parentId": null,
          "coverMediaId": "med_abc123",
          "coverUrl": "/api/friends/memory-media/med_abc123/content",
          "coverCrop": {
            "x": 0.1,
            "y": 0.2,
            "width": 0.8,
            "height": 0.45
          },
          "startDate": "2026-06-01",
          "endDate": "2026-06-07",
          "mediaCount": 42,
          "childCount": 3,
          "favorite": true
        }
        """.data(using: .utf8)!

        let memory = try JSONDecoder.veyq.decode(Memory.self, from: data)

        XCTAssertEqual(memory.coverUrl, "/api/friends/memory-media/med_abc123/content")
        XCTAssertEqual(memory.coverCrop?.x, 0.1)
        XCTAssertEqual(memory.coverCrop?.height, 0.45)
    }

    func testMemoryHeaderCoverDecoding() throws {
        let data = """
        {
          "id": "mem_abc123",
          "displayName": "Albania 2024",
          "path": "/albania-2024",
          "coverUrl": "/api/friends/memories/mem_abc123/cover/content",
          "coverCrop": {
            "x": 0,
            "y": 0,
            "width": 1,
            "height": 0.5625
          },
          "startDate": "2026-06-01",
          "endDate": "2026-06-07",
          "directMediaCount": 42,
          "descendantMediaCount": 7,
          "childMemoryCount": 3,
          "favorite": false
        }
        """.data(using: .utf8)!

        let header = try JSONDecoder.veyq.decode(MemoryHeader.self, from: data)

        XCTAssertEqual(header.coverUrl, "/api/friends/memories/mem_abc123/cover/content")
        XCTAssertEqual(header.coverCrop?.width, 1)
        XCTAssertEqual(header.coverCrop?.height, 0.5625)
        XCTAssertEqual(header.chapterCount, 3)
    }

    func testMemoryDetailUploadIconUsesMediaImportSymbol() {
        XCTAssertEqual(MemoryDetailToolbarSymbols.upload, "photo.badge.plus")
    }

    func testMemoryNavigationContextDecodesFolders() throws {
        let data = """
        {
          "location": {
            "kind": "TENANT",
            "path": "/",
            "displayName": "Family Memories"
          },
          "breadcrumbs": [],
          "scopeRoots": [],
          "favoriteMemories": [],
          "folders": [
            {
              "id": "fol_123",
              "name": "travel",
              "displayName": "Travel",
              "path": "/travel"
            }
          ],
          "memories": []
        }
        """.data(using: .utf8)!

        let navigation = try JSONDecoder.veyq.decode(MemoryNavigationContextResponse.self, from: data)

        XCTAssertEqual(navigation.folders.map(\.id), ["fol_123"])
        XCTAssertEqual(navigation.folders.first?.path, "/travel")
    }

    func testMemoryNameFormatterCreatesStablePathSlug() {
        XCTAssertEqual(MemoryNameFormatter.slug(from: "  Südtirol Day 1! "), "sudtirol-day-1")
        XCTAssertEqual(MemoryNameFormatter.slug(from: "Family__Trip"), "family__trip")
    }

    @MainActor
    func testMockStoreCreatesRootMemoryInSelectedScope() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        _ = try await store.connect(to: "https://memories.example.test")

        let created = try await store.createMemory(
            displayName: "Weekend Lake",
            name: "weekend-lake",
            startDate: "2026-08-01",
            endDate: "2026-08-02"
        )

        XCTAssertEqual(created.path, "/weekend-lake")
        XCTAssertTrue(store.visibleMemories.contains { $0.id == "weekend-lake" })
    }

    @MainActor
    func testMockStoreCreatesChapterInsideLoadedMemory() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        _ = try await store.connect(to: "https://memories.example.test")
        let parent = Memory.samples[0]
        await store.loadDetail(for: parent)

        let created = try await store.createMemory(
            displayName: "Day 3",
            name: "day-3",
            startDate: "2026-07-14",
            endDate: "2026-07-14",
            parentMemory: parent
        )

        XCTAssertEqual(created.parentId, parent.id)
        XCTAssertEqual(created.path, "/summer-south-tyrol/day-3")
        XCTAssertTrue(store.memoryDetails[parent.id]?.chapters.contains { $0.id == "day-3" } == true)
        XCTAssertEqual(store.memoryDetails[parent.id]?.header.chapterCount, 3)
    }

    @MainActor
    func testMockStoreUpdatesLoadedMemoryDisplayMetadata() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        _ = try await store.connect(to: "https://memories.example.test")
        let memory = Memory.samples[0]
        await store.loadDetail(for: memory)

        let updated = try await store.updateMemory(
            memory,
            displayName: "South Tyrol Summer",
            name: "south-tyrol-summer",
            startDate: "2026-07-11",
            endDate: "2026-07-18"
        )

        XCTAssertEqual(updated.displayName, "South Tyrol Summer")
        XCTAssertEqual(store.memoryDetails[memory.id]?.header.displayName, "South Tyrol Summer")
        XCTAssertEqual(store.memoryDetails[memory.id]?.header.startDate, "2026-07-11")
    }

    @MainActor
    func testMockStoreDeletesMemoryFromVisibleList() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        _ = try await store.connect(to: "https://memories.example.test")
        let memory = Memory.samples[0]
        await store.loadDetail(for: memory)

        try await store.deleteMemory(memory)

        XCTAssertFalse(store.visibleMemories.contains { $0.id == memory.id })
        XCTAssertNil(store.memoryDetails[memory.id])
    }

    @MainActor
    func testMockStoreSetsMemoryCoverFromMedia() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        _ = try await store.connect(to: "https://memories.example.test")
        let memory = Memory.samples[0]
        await store.loadDetail(for: memory)
        let media = try XCTUnwrap(store.memoryDetails[memory.id]?.media.first { $0.isImage })

        let updated = try await store.setMemoryCover(for: memory, media: media)

        XCTAssertEqual(updated.coverMediaId, media.id)
        XCTAssertEqual(updated.coverUrl, media.contentUrl)
        XCTAssertEqual(updated.coverCrop, .defaultCoverCrop)
        XCTAssertEqual(store.memoryDetails[memory.id]?.header.coverUrl, media.contentUrl)
        XCTAssertEqual(store.memoryDetails[memory.id]?.header.coverCrop, .defaultCoverCrop)
    }

    @MainActor
    func testMockStoreRemovesMemoryCover() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        _ = try await store.connect(to: "https://memories.example.test")
        let memory = Memory.samples[0]
        await store.loadDetail(for: memory)

        let updated = try await store.removeMemoryCover(for: memory)

        XCTAssertNil(updated.coverMediaId)
        XCTAssertNil(updated.coverUrl)
        XCTAssertNil(updated.coverCrop)
        XCTAssertNil(store.memoryDetails[memory.id]?.header.coverUrl)
        XCTAssertNil(store.memoryDetails[memory.id]?.header.coverCrop)
    }

    func testInternalMemoryShareLinksUseSectionHashes() throws {
        let instanceURL = try XCTUnwrap(URL(string: "https://memories.example.test/base/"))

        XCTAssertEqual(
            MemoryInternalShareLink.url(instanceURL: instanceURL, memoryPath: "/summer-trip", destination: .gallery)?.absoluteString,
            "https://memories.example.test/base/summer-trip"
        )
        XCTAssertEqual(
            MemoryInternalShareLink.url(instanceURL: instanceURL, memoryPath: "/summer-trip", destination: .journal)?.absoluteString,
            "https://memories.example.test/base/summer-trip#journal"
        )
        XCTAssertEqual(
            MemoryInternalShareLink.url(instanceURL: instanceURL, memoryPath: "/summer-trip", destination: .chapters)?.absoluteString,
            "https://memories.example.test/base/summer-trip#children"
        )
    }

    func testMemoryCoverDecodingAllowsMissingCrop() throws {
        let data = """
        {
          "id": "mem_abc123",
          "name": "albania-2024",
          "displayName": "Albania 2024",
          "path": "/albania-2024",
          "parentId": null,
          "coverMediaId": null,
          "coverUrl": null,
          "startDate": "2026-06-01",
          "endDate": "2026-06-07",
          "mediaCount": 42,
          "childCount": 3
        }
        """.data(using: .utf8)!

        let memory = try JSONDecoder.veyq.decode(Memory.self, from: data)

        XCTAssertNil(memory.coverCrop)
    }

    func testShareImportMemoryTargetDecodesCoverThumbnailFilename() throws {
        let data = """
        {
          "id": "mem_abc123",
          "title": "Albania 2024",
          "dateRange": "2026-06-01 - 2026-06-07",
          "tenantName": "friends",
          "coverUrl": "/api/friends/memories/mem_abc123/cover/content",
          "coverThumbnailFilename": "mem_abc123-cover.jpg"
        }
        """.data(using: .utf8)!

        let target = try JSONDecoder().decode(ShareImportMemoryTarget.self, from: data)

        XCTAssertEqual(target.coverUrl, "/api/friends/memories/mem_abc123/cover/content")
        XCTAssertEqual(target.coverThumbnailFilename, "mem_abc123-cover.jpg")
    }

    func testShareImportMemoryTargetDecodesLegacySnapshotsWithoutCoverThumbnailFilename() throws {
        let data = """
        {
          "id": "mem_abc123",
          "title": "Albania 2024",
          "dateRange": "2026-06-01 - 2026-06-07",
          "tenantName": "friends",
          "coverUrl": "/api/friends/memories/mem_abc123/cover/content"
        }
        """.data(using: .utf8)!

        let target = try JSONDecoder().decode(ShareImportMemoryTarget.self, from: data)

        XCTAssertEqual(target.coverUrl, "/api/friends/memories/mem_abc123/cover/content")
        XCTAssertNil(target.coverThumbnailFilename)
        XCTAssertNil(target.parentMemoryTitle)
        XCTAssertNil(target.depth)
    }

    func testShareImportMemoryTargetDecodesChapterMetadata() throws {
        let data = """
        {
          "id": "mem_child",
          "title": "Day 1",
          "dateRange": "2026-06-01",
          "tenantName": "friends",
          "coverUrl": null,
          "coverThumbnailFilename": null,
          "parentMemoryTitle": "Albania 2024",
          "depth": 1
        }
        """.data(using: .utf8)!

        let target = try JSONDecoder().decode(ShareImportMemoryTarget.self, from: data)

        XCTAssertEqual(target.parentMemoryTitle, "Albania 2024")
        XCTAssertEqual(target.depth, 1)
    }

    func testShareImportTargetsPersistCoverThumbnailFilename() throws {
        let snapshot = ShareImportTargetSnapshot(
            targets: [
                ShareImportMemoryTarget(
                    id: "mem_abc123",
                    title: "Albania 2024",
                    dateRange: "2026-06-01 - 2026-06-07",
                    tenantName: "friends",
                    coverUrl: "/api/friends/memories/mem_abc123/cover/content",
                    coverThumbnailFilename: "mem_abc123-cover.jpg"
                )
            ],
            lastUsedMemoryId: "mem_abc123",
            updatedAt: Date(timeIntervalSince1970: 1_775_000_000)
        )

        UploadQueuePersistence.saveShareImportTargets(snapshot)
        let loaded = try XCTUnwrap(UploadQueuePersistence.loadShareImportTargets())

        XCTAssertEqual(loaded.targets.first?.coverThumbnailFilename, "mem_abc123-cover.jpg")
        XCTAssertNil(loaded.includesOnlyKnownChapters)
    }

    func testShareImportCoverCleanupRemovesStaleThumbnails() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("veyq-share-import-cover-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let current = directory.appendingPathComponent("current-cover.jpg")
        let stale = directory.appendingPathComponent("stale-cover.jpg")
        try Data([1]).write(to: current)
        try Data([2]).write(to: stale)

        UploadQueuePersistence.removeUnreferencedShareImportCovers(in: directory, keeping: ["current-cover.jpg"])

        XCTAssertTrue(FileManager.default.fileExists(atPath: current.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: stale.path))
    }

    func testMemoryMediaDecoding() throws {
        let data = """
        {
          "id": "med_abc123",
          "memoryId": "mem_abc123",
          "bucketId": null,
          "title": "Beach",
          "uploader": {
            "type": "USER",
            "displayName": "Kevin",
            "permalink": "kevin",
            "userId": 1
          },
          "createdAt": "2026-05-03T12:00:00.123Z",
          "updatedAt": "2026-05-03T12:03:00Z",
          "capturedAt": null,
          "capturedAtOverride": false,
          "mimeType": "image/jpeg",
          "sizeBytes": 2048000,
          "contentUrl": "/api/friends/memory-media/med_abc123/content",
          "highlight": true,
          "thumbnailUrl": null,
          "previewUrl": "/api/friends/memory-media/med_abc123/preview",
          "posterUrl": null,
          "cameraMake": "Fujifilm",
          "cameraModel": "X100VI",
          "lensModel": "23mm F2",
          "imageWidth": 6240,
          "imageHeight": 4160,
          "gpsLatitude": 54.1825,
          "gpsLongitude": 7.8853
        }
        """.data(using: .utf8)!

        let media = try JSONDecoder.veyq.decode(MemoryMedia.self, from: data)

        XCTAssertEqual(media.id, "med_abc123")
        XCTAssertEqual(media.previewUrl, "/api/friends/memory-media/med_abc123/preview")
        XCTAssertTrue(media.highlight)
        XCTAssertEqual(media.imageWidth, 6240)
        XCTAssertEqual(media.gpsLatitude, 54.1825)
        XCTAssertGreaterThan(media.updatedAt, media.createdAt)
    }

    func testMemoryMapPointsUseOnlyValidGpsMedia() {
        let valid = MemoryMedia.samples[0]
        let invalid = MemoryMedia(
            id: "invalid-gps",
            memoryId: valid.memoryId,
            bucketId: nil,
            title: "Invalid GPS",
            uploader: valid.uploader,
            createdAt: valid.createdAt,
            capturedAt: valid.capturedAt,
            capturedAtOverride: valid.capturedAtOverride,
            mimeType: valid.mimeType,
            sizeBytes: valid.sizeBytes,
            contentUrl: valid.contentUrl,
            highlight: false,
            thumbnailUrl: nil,
            previewUrl: nil,
            posterUrl: nil,
            cameraMake: nil,
            cameraModel: nil,
            lensModel: nil,
            imageWidth: nil,
            imageHeight: nil,
            gpsLatitude: 112,
            gpsLongitude: 11
        )
        let missing = MemoryMedia.samples[2]

        let points = MemoryMapPoint.points(from: [valid, invalid, missing])

        XCTAssertEqual(points, [
            MemoryMapPoint(
                mediaId: "media-summit",
                title: "Summit view",
                latitude: 46.4983,
                longitude: 11.3548
            )
        ])
    }

    func testMemoryMapRegionFitsMappedMedia() {
        let points = MemoryMapPoint.points(from: Array(MemoryMedia.samples.prefix(2)))

        let region = MemoryMapRegion.region(for: points)

        XCTAssertEqual(region.center.latitude, 46.5052, accuracy: 0.0001)
        XCTAssertEqual(region.center.longitude, 11.37225, accuracy: 0.0001)
        XCTAssertGreaterThanOrEqual(region.span.latitudeDelta, 0.02)
        XCTAssertGreaterThanOrEqual(region.span.longitudeDelta, 0.02)
    }

    func testMemoryTrackDecodingProvidesRenderableCoordinatesAndDuration() throws {
        let data = """
        {
          "id": "track-1",
          "memoryId": "mem-1",
          "title": "Summit Route",
          "activityType": "HIKING",
          "originalFilename": "summit.gpx",
          "distanceMeters": 12430,
          "ascentMeters": 820,
          "descentMeters": 610,
          "pointCount": 3,
          "startedAt": "2026-05-03T08:30:00Z",
          "endedAt": "2026-05-03T12:20:00Z",
          "bounds": {
            "minLatitude": 47.426,
            "minLongitude": 10.982,
            "maxLatitude": 47.451,
            "maxLongitude": 11.034
          },
          "geometry": {
            "type": "LineString",
            "coordinates": [[10.982, 47.426], [11.034, 47.451], [220, 47.5]]
          },
          "contentUrl": "/api/friends/memories/mem-1/tracks/track-1/content",
          "createdAt": "2026-05-03T12:30:00Z",
          "updatedAt": "2026-05-03T12:30:00Z"
        }
        """.data(using: .utf8)!

        let track = try JSONDecoder.veyq.decode(MemoryTrack.self, from: data)

        XCTAssertEqual(track.id, "track-1")
        XCTAssertEqual(track.coordinates.count, 2)
        XCTAssertEqual(track.coordinates.first?.latitude, 47.426)
        XCTAssertEqual(track.coordinates.first?.longitude, 10.982)
        XCTAssertEqual(track.durationSeconds, 13_800)
        XCTAssertTrue(track.hasRenderableGeometry)
    }

    func testMemoryMapRegionFitsTracksAndMappedMediaTogether() {
        let points = MemoryMapPoint.points(from: [MemoryMedia.samples[0]])
        let track = MemoryTrack.samples[0]

        let region = MemoryMapRegion.region(points: points, tracks: [track])

        XCTAssertEqual(region.center.latitude, 46.5092, accuracy: 0.0001)
        XCTAssertEqual(region.center.longitude, 11.37205, accuracy: 0.0001)
        XCTAssertGreaterThan(region.span.latitudeDelta, 0.04)
        XCTAssertGreaterThan(region.span.longitudeDelta, 0.06)
    }

    func testMemoryTrackFormatterShowsDistanceDurationAndElevation() {
        UserDefaults.standard.set(AppLanguagePreference.en.rawValue, forKey: AppLanguagePreference.storageKey)

        XCTAssertEqual(MemoryTrackFormatter.distance(12_430), "12.4 km")
        XCTAssertEqual(MemoryTrackFormatter.distance(860), "860 m")
        XCTAssertEqual(MemoryTrackFormatter.duration(13_800), "3 h 50 min")
        XCTAssertEqual(MemoryTrackFormatter.elevation(820), "820 m")
        XCTAssertEqual(MemoryTrackFormatter.elevation(nil), "n/a")
    }

    func testGalleryAspectRatioBoundsExtremeValues() {
        XCTAssertEqual(JustifiedMediaLayout.galleryDisplayAspectRatio(width: 16, height: 9), 4.0 / 3.0)
        XCTAssertEqual(JustifiedMediaLayout.galleryDisplayAspectRatio(width: 9, height: 16), 3.0 / 4.0)
        XCTAssertEqual(JustifiedMediaLayout.galleryDisplayAspectRatio(width: nil, height: 9), 4.0 / 3.0)
    }

    func testMemoryMediaSetHashMatchesServerCanonicalFormat() {
        XCTAssertEqual(
            MemoryMedia.mediaSetHash(["media-2", "media-1"]),
            "7185ce8e239cc3ff81c9e76430743ac5617fbd6166ae6b3b1b41d5f0f96a5159"
        )
    }

    func testJustifiedLayoutKeepsPortraitAndLandscapeShapes() {
        let layout = JustifiedMediaLayout.calculate(
            items: [
                MediaGridLayoutSource(id: "portrait", aspectRatio: 3.0 / 4.0),
                MediaGridLayoutSource(id: "landscape", aspectRatio: 16.0 / 9.0)
            ],
            containerWidth: 600,
            targetHeight: 200,
            gap: 8
        )

        let portrait = layout.tiles.first { $0.id == "portrait" }
        let landscape = layout.tiles.first { $0.id == "landscape" }

        XCTAssertNotNil(portrait)
        XCTAssertNotNil(landscape)
        XCTAssertLessThan(portrait!.renderedWidth, portrait!.renderedHeight)
        XCTAssertGreaterThan(landscape!.renderedWidth, landscape!.renderedHeight)
    }

    func testJustifiedLayoutReturnsEmptyForInvalidInput() {
        let layout = JustifiedMediaLayout.calculate(
            items: [MediaGridLayoutSource(id: "media", aspectRatio: 1)],
            containerWidth: 0,
            targetHeight: 200,
            gap: 8
        )

        XCTAssertTrue(layout.tiles.isEmpty)
        XCTAssertEqual(layout.height, 0)
    }

    func testJustifiedLayoutCanLimitRenderedRows() {
        let layout = JustifiedMediaLayout.calculate(
            items: (0..<12).map { MediaGridLayoutSource(id: "media-\($0)", aspectRatio: 1) },
            containerWidth: 300,
            targetHeight: 100,
            gap: 8,
            maxRows: 3
        )

        XCTAssertEqual(Set(layout.tiles.map(\.top)).count, 3)
        XCTAssertLessThan(layout.tiles.count, 12)
    }

    func testAPIClientListsMemoryMediaWithChildren() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.responseData = "[]".data(using: .utf8)!

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )

        _ = try await client.memoryMedia(tenantName: "friends", memoryId: "mem-1", includeChildren: true)

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        XCTAssertEqual(request.url?.absoluteString, "https://memories.example.test/api/friends/memories/mem-1/media?includeChildren=true")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
    }

    func testAPIClientSearchesTenantWithBearerHeader() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.responseData = """
        [
          {
            "kind": "MEMORY",
            "id": "mem-1",
            "displayName": "Birthday Weekend",
            "path": "/@family/birthday-weekend",
            "scope": {
              "type": "GROUP",
              "groupName": "family"
            },
            "coverUrl": null,
            "coverCrop": null,
            "matchTypes": ["TITLE"]
          }
        ]
        """.data(using: .utf8)!

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )

        let results = try await client.searchTenant(tenantName: "friends", query: "birthday", limit: 20)

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        let components = try XCTUnwrap(URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false))
        let queryItems = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value) })
        XCTAssertEqual(results.map(\.id), ["mem-1"])
        XCTAssertEqual(results.first?.scope.groupName, "family")
        XCTAssertEqual(components.scheme, "https")
        XCTAssertEqual(components.host, "memories.example.test")
        XCTAssertEqual(components.path, "/api/friends/search")
        XCTAssertEqual(queryItems["q"], "birthday")
        XCTAssertEqual(queryItems["limit"], "20")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
    }

    func testAPIClientCreatesFolderWithBearerHeader() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.responseData = """
        {
          "id": "fol_123",
          "name": "travel",
          "displayName": "Travel",
          "path": "/travel"
        }
        """.data(using: .utf8)!

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )

        let created = try await client.createFolder(
            tenantName: "friends",
            request: CreateFolderRequest(
                parentPath: "/",
                name: "travel",
                displayName: "Travel",
                scope: MemoryScopeRequest(type: "TENANT", groupName: nil)
            )
        )

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        let body = try XCTUnwrap(request.httpBodyString)
        XCTAssertEqual(created.id, "fol_123")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.absoluteString, "https://memories.example.test/api/friends/folders")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
        XCTAssertTrue(body.contains(#""displayName":"Travel""#))
        XCTAssertTrue(body.contains(#""type":"TENANT""#))
    }

    func testAPIClientUpdatesMySettingsWithBearerHeader() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.responseData = """
        {
          "userId": 1,
          "email": "new@example.test",
          "displayName": "New Name",
          "permalink": "new-name",
          "serverRole": "ServerOwner",
          "passkeyCount": 1,
          "hasPassword": true,
          "totpEnabled": true,
          "avatarUrl": null,
          "pendingEmail": "new@example.test"
        }
        """.data(using: .utf8)!

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )

        let updated = try await client.updateMySettings(
            displayName: "New Name",
            permalink: "new-name",
            email: "new@example.test"
        )

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        let body = try XCTUnwrap(request.httpBodyString)
        XCTAssertEqual(updated.displayName, "New Name")
        XCTAssertEqual(updated.pendingEmail, "new@example.test")
        XCTAssertEqual(updated.totpEnabled, true)
        XCTAssertEqual(request.httpMethod, "PUT")
        XCTAssertEqual(request.url?.absoluteString, "https://memories.example.test/api/users/me")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
        XCTAssertTrue(body.contains(#""displayName":"New Name""#))
        XCTAssertTrue(body.contains(#""permalink":"new-name""#))
        XCTAssertTrue(body.contains(#""email":"new@example.test""#))
    }

    func testAPIClientCreatesMemoryWithBearerHeader() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.responseData = """
        {
          "id": "mem-created",
          "name": "summer-trip",
          "displayName": "Summer Trip",
          "path": "/summer-trip",
          "parentId": null,
          "coverMediaId": null,
          "coverUrl": null,
          "coverCrop": null,
          "startDate": "2026-07-12",
          "endDate": "2026-07-18",
          "mediaCount": 0,
          "childCount": 0
        }
        """.data(using: .utf8)!

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )

        let created = try await client.createMemory(
            tenantName: "friends",
            request: CreateMemoryRequest(
                parentId: nil,
                folderPath: "/",
                name: "summer-trip",
                displayName: "Summer Trip",
                startDate: "2026-07-12",
                endDate: "2026-07-18",
                scope: MemoryScopeRequest(type: "TENANT", groupName: nil)
            )
        )

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        let body = try XCTUnwrap(request.httpBodyString)
        XCTAssertEqual(created.id, "mem-created")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.absoluteString, "https://memories.example.test/api/friends/memories")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
        XCTAssertTrue(body.contains(#""displayName":"Summer Trip""#))
        XCTAssertTrue(body.contains(#""type":"TENANT""#))
    }

    func testAPIClientUpdatesMemoryWithBearerHeader() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.responseData = """
        {
          "id": "mem-1",
          "name": "summer-trip-2026",
          "displayName": "Summer Trip 2026",
          "path": "/summer-trip-2026",
          "parentId": null,
          "coverMediaId": null,
          "coverUrl": null,
          "coverCrop": null,
          "startDate": "2026-07-12",
          "endDate": "2026-07-19",
          "mediaCount": 0,
          "childCount": 0
        }
        """.data(using: .utf8)!

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )

        let updated = try await client.updateMemory(
            tenantName: "friends",
            memoryId: "mem-1",
            request: UpdateMemoryRequest(
                parentId: nil,
                name: "summer-trip-2026",
                displayName: "Summer Trip 2026",
                startDate: "2026-07-12",
                endDate: "2026-07-19"
            )
        )

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        let body = try XCTUnwrap(request.httpBodyString)
        XCTAssertEqual(updated.displayName, "Summer Trip 2026")
        XCTAssertEqual(request.httpMethod, "PUT")
        XCTAssertEqual(request.url?.absoluteString, "https://memories.example.test/api/friends/memories/mem-1")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
        XCTAssertTrue(body.contains(#""name":"summer-trip-2026""#))
    }

    func testAPIClientDeletesMemoryWithBearerHeader() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.statusCode = 204
        URLProtocolStub.responseData = Data()

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )

        try await client.deleteMemory(tenantName: "friends", memoryId: "mem-1")

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        XCTAssertEqual(request.httpMethod, "DELETE")
        XCTAssertEqual(request.url?.absoluteString, "https://memories.example.test/api/friends/memories/mem-1")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
    }

    func testAPIClientSetsMemoryCoverFromMediaWithBearerHeader() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.responseData = """
        {
          "id": "mem-1",
          "name": "summer-trip",
          "displayName": "Summer Trip",
          "path": "/summer-trip",
          "parentId": null,
          "coverMediaId": "media-1",
          "coverUrl": "/api/friends/memory-media/media-1/content",
          "coverCrop": {
            "x": 0,
            "y": 0,
            "width": 1,
            "height": 0.5625
          },
          "startDate": "2026-07-12",
          "endDate": "2026-07-19",
          "mediaCount": 1,
          "childCount": 0
        }
        """.data(using: .utf8)!

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )

        let updated = try await client.setMemoryCoverFromMedia(
            tenantName: "friends",
            memoryId: "mem-1",
            mediaId: "media-1",
            crop: .defaultCoverCrop
        )

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        let body = String(data: try XCTUnwrap(URLProtocolStub.lastRequestBody), encoding: .utf8) ?? ""
        XCTAssertEqual(updated.coverMediaId, "media-1")
        XCTAssertEqual(request.httpMethod, "PUT")
        XCTAssertEqual(request.url?.absoluteString, "https://memories.example.test/api/friends/memories/mem-1/cover")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
        XCTAssertTrue(request.value(forHTTPHeaderField: "Content-Type")?.hasPrefix("multipart/form-data; boundary=veyq-ios-") ?? false)
        XCTAssertTrue(body.contains("name=\"mediaId\""))
        XCTAssertTrue(body.contains("media-1"))
        XCTAssertTrue(body.contains("name=\"cropWidth\""))
        XCTAssertTrue(body.contains("1.0"))
        XCTAssertTrue(body.contains("name=\"cropHeight\""))
        XCTAssertTrue(body.contains("0.5625"))
    }

    func testAPIClientRemovesMemoryCoverWithBearerHeader() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.responseData = """
        {
          "id": "mem-1",
          "name": "summer-trip",
          "displayName": "Summer Trip",
          "path": "/summer-trip",
          "parentId": null,
          "coverMediaId": null,
          "coverUrl": null,
          "coverCrop": null,
          "startDate": "2026-07-12",
          "endDate": "2026-07-19",
          "mediaCount": 1,
          "childCount": 0
        }
        """.data(using: .utf8)!

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )

        let updated = try await client.removeMemoryCover(tenantName: "friends", memoryId: "mem-1")

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        XCTAssertNil(updated.coverUrl)
        XCTAssertEqual(request.httpMethod, "DELETE")
        XCTAssertEqual(request.url?.absoluteString, "https://memories.example.test/api/friends/memories/mem-1/cover")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
    }

    func testAPIClientWaitsForMemoryMediaUpdatesWithCursorAndHash() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.responseData = #"{"media":[],"currentMediaIds":["media-1"]}"#.data(using: .utf8)!

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )
        let cursorDate = Date(timeIntervalSince1970: 1_778_000_000)

        let updates = try await client.waitForMemoryMediaUpdates(
            tenantName: "friends",
            memoryId: "mem-1",
            includeChildren: true,
            afterUpdatedAt: cursorDate,
            afterMediaId: "media-0",
            knownMediaSetHash: "known-hash",
            timeoutSeconds: 3
        )

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        let components = try XCTUnwrap(URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false))
        let query = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).compactMap { item in
            item.value.map { (item.name, $0) }
        })
        XCTAssertEqual(components.scheme, "https")
        XCTAssertEqual(components.host, "memories.example.test")
        XCTAssertEqual(components.path, "/api/friends/memories/mem-1/media/updates")
        XCTAssertEqual(query["includeChildren"], "true")
        XCTAssertNotNil(query["afterUpdatedAt"])
        XCTAssertEqual(query["afterMediaId"], "media-0")
        XCTAssertEqual(query["knownMediaSetHash"], "known-hash")
        XCTAssertEqual(query["timeoutSeconds"], "3")
        XCTAssertEqual(updates.currentMediaIds, ["media-1"])
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
    }

    func testAPIClientListsMemoryChaptersWithBearerHeader() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.responseData = "[]".data(using: .utf8)!

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )

        _ = try await client.listMemoryChapters(tenantName: "friends", memoryId: "mem-1")

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        XCTAssertEqual(request.url?.absoluteString, "https://memories.example.test/api/friends/memories/mem-1/children")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
    }

    func testAPIClientUpdatesMemoryJournalWithBearerHeader() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.responseData = ##"{"memoryId":"mem-1","markdown":"# Updated","updatedAt":"2026-06-06T08:15:00Z"}"##.data(using: .utf8)!

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )

        let journal = try await client.updateMemoryJournal(tenantName: "friends", memoryId: "mem-1", markdown: "# Updated")

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        let body = try XCTUnwrap(request.httpBodyString)
        XCTAssertEqual(request.httpMethod, "PUT")
        XCTAssertEqual(request.url?.absoluteString, "https://memories.example.test/api/friends/memories/mem-1/journal")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertEqual(body, ##"{"markdown":"# Updated"}"##)
        XCTAssertEqual(journal.markdown, "# Updated")
    }

    func testAPIClientUploadsMemoryTrackAsMultipartGPX() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.responseData = "{}".data(using: .utf8)!
        let fileURL = FileManager.default.temporaryDirectory.appending(path: "morning-walk.gpx")
        try """
        <gpx version="1.1">
          <trk><trkseg><trkpt lat="46.1" lon="11.1"/></trkseg></trk>
        </gpx>
        """.data(using: .utf8)!.write(to: fileURL, options: [.atomic])
        defer { try? FileManager.default.removeItem(at: fileURL) }

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )

        try await client.uploadMemoryTrack(
            tenantName: "friends",
            memoryId: "mem-1",
            fileURL: fileURL,
            title: "Morning Walk",
            activityType: "HIKING"
        )

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        let body = String(data: try XCTUnwrap(URLProtocolStub.lastRequestBody), encoding: .utf8) ?? ""
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.absoluteString, "https://memories.example.test/api/friends/memories/mem-1/tracks")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
        XCTAssertTrue(request.value(forHTTPHeaderField: "Content-Type")?.hasPrefix("multipart/form-data; boundary=veyq-ios-") ?? false)
        XCTAssertTrue(body.contains("name=\"title\""))
        XCTAssertTrue(body.contains("Morning Walk"))
        XCTAssertTrue(body.contains("name=\"activityType\""))
        XCTAssertTrue(body.contains("HIKING"))
        XCTAssertTrue(body.contains("name=\"file\"; filename=\"morning-walk.gpx\""))
        XCTAssertTrue(body.contains("Content-Type: application/gpx+xml"))
        XCTAssertTrue(body.contains("<gpx version=\"1.1\">"))
    }

    func testAPIClientListsMemoryTracksWithBearerHeader() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.responseData = """
        [
          {
            "id": "track-1",
            "memoryId": "mem-1",
            "title": "Summit Route",
            "activityType": "HIKING",
            "originalFilename": "summit.gpx",
            "distanceMeters": 12430,
            "ascentMeters": 820,
            "descentMeters": 610,
            "pointCount": 3,
            "startedAt": "2026-05-03T08:30:00Z",
            "endedAt": "2026-05-03T12:20:00Z",
            "bounds": {
              "minLatitude": 47.426,
              "minLongitude": 10.982,
              "maxLatitude": 47.451,
              "maxLongitude": 11.034
            },
            "geometry": {
              "type": "LineString",
              "coordinates": [[10.982, 47.426], [11.034, 47.451]]
            },
            "contentUrl": "/api/friends/memories/mem-1/tracks/track-1/content",
            "createdAt": "2026-05-03T12:30:00Z",
            "updatedAt": "2026-05-03T12:30:00Z"
          }
        ]
        """.data(using: .utf8)!

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )

        let tracks = try await client.memoryTracks(tenantName: "friends", memoryId: "mem-1")

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        XCTAssertEqual(request.url?.absoluteString, "https://memories.example.test/api/friends/memories/mem-1/tracks")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
        XCTAssertEqual(tracks.map(\.id), ["track-1"])
        XCTAssertEqual(tracks.first?.distanceMeters, 12_430)
        XCTAssertEqual(tracks.first?.coordinates.count, 2)
    }

    func testAPIClientLoadsMediaDataWithBearerHeader() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.responseData = Data([0x01, 0x02, 0x03])

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )

        let data = try await client.mediaData(from: "/api/friends/memory-media/med-1/preview")

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        XCTAssertEqual(data, Data([0x01, 0x02, 0x03]))
        XCTAssertEqual(request.url?.absoluteString, "https://memories.example.test/api/friends/memory-media/med-1/preview")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
    }

    func testAPIClientUpdatesMemoryMediaCapturedAt() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.responseData = """
        {
          "id": "media-1",
          "memoryId": "mem-1",
          "bucketId": null,
          "title": "Summit",
          "uploader": {
            "type": "USER",
            "displayName": "Kevin",
            "permalink": "kevin",
            "userId": 1
          },
          "createdAt": "2026-05-03T12:00:00Z",
          "updatedAt": "2026-05-03T12:05:00Z",
          "capturedAt": "2026-05-03T11:45:00Z",
          "capturedAtOverride": true,
          "mimeType": "image/jpeg",
          "sizeBytes": 2048,
          "contentUrl": "/api/friends/memory-media/media-1/content",
          "highlight": false,
          "thumbnailUrl": null,
          "previewUrl": null,
          "posterUrl": null,
          "cameraMake": null,
          "cameraModel": null,
          "lensModel": null,
          "imageWidth": null,
          "imageHeight": null,
          "gpsLatitude": null,
          "gpsLongitude": null
        }
        """.data(using: .utf8)!

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )
        let capturedAt = try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-05-03T11:45:00Z"))

        let updated = try await client.updateMemoryMediaCapturedAt(
            tenantName: "friends",
            memoryId: "mem-1",
            mediaId: "media-1",
            capturedAt: capturedAt
        )

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        let body = try XCTUnwrap(request.httpBodyString)
        XCTAssertEqual(updated.id, "media-1")
        XCTAssertTrue(updated.capturedAtOverride)
        XCTAssertFalse(updated.highlight)
        XCTAssertEqual(request.url?.absoluteString, "https://memories.example.test/api/friends/memories/mem-1/media/media-1/metadata/capturedAt")
        XCTAssertEqual(request.httpMethod, "PATCH")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
        XCTAssertTrue(body.contains(#""capturedAt":"2026-05-03T11:45:00Z""#))
    }

    func testAPIClientUpdatesMemoryMediaHighlight() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.responseData = """
        {
          "id": "media-1",
          "memoryId": "mem-1",
          "bucketId": null,
          "title": "Summit",
          "uploader": {
            "type": "USER",
            "displayName": "Kevin",
            "permalink": "kevin",
            "userId": 1
          },
          "createdAt": "2026-05-03T12:00:00Z",
          "updatedAt": "2026-05-03T12:06:00Z",
          "capturedAt": "2026-05-03T12:00:00Z",
          "capturedAtOverride": false,
          "mimeType": "image/jpeg",
          "sizeBytes": 2048,
          "contentUrl": "/api/friends/memory-media/media-1/content",
          "highlight": true,
          "thumbnailUrl": null,
          "previewUrl": null,
          "posterUrl": null,
          "cameraMake": null,
          "cameraModel": null,
          "lensModel": null,
          "imageWidth": null,
          "imageHeight": null,
          "gpsLatitude": null,
          "gpsLongitude": null
        }
        """.data(using: .utf8)!

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )

        let updated = try await client.updateMemoryMediaHighlight(
            tenantName: "friends",
            memoryId: "mem-1",
            mediaId: "media-1",
            highlight: true
        )

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        let body = try XCTUnwrap(request.httpBodyString)
        XCTAssertEqual(updated.id, "media-1")
        XCTAssertTrue(updated.highlight)
        XCTAssertEqual(request.url?.absoluteString, "https://memories.example.test/api/friends/memories/mem-1/media/media-1/metadata/highlight")
        XCTAssertEqual(request.httpMethod, "PATCH")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
        XCTAssertTrue(body.contains(#""highlight":true"#))
    }

    func testAPIClientDeletesMemoryMedia() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.statusCode = 204
        URLProtocolStub.responseData = Data()

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )

        try await client.deleteMemoryMedia(tenantName: "friends", memoryId: "mem-1", mediaId: "media-1")

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        XCTAssertEqual(request.url?.absoluteString, "https://memories.example.test/api/friends/memories/mem-1/media/media-1")
        XCTAssertEqual(request.httpMethod, "DELETE")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
    }

    func testAPIClientThrowsLocalizedKnownServerErrorCode() async throws {
        UserDefaults.standard.set(AppLanguagePreference.en.rawValue, forKey: AppLanguagePreference.storageKey)
        URLProtocolStub.reset()
        URLProtocolStub.statusCode = 413
        URLProtocolStub.responseData = #"{"code":"UPLOAD_TOO_LARGE","message":"Upload too large"}"#.data(using: .utf8)!

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )

        do {
            _ = try await client.memoryMedia(tenantName: "friends", memoryId: "mem-1")
            XCTFail("Expected server error")
        } catch let error as VeyqAPIError {
            XCTAssertEqual(error.statusCode, 413)
            XCTAssertEqual(error.apiCode, "UPLOAD_TOO_LARGE")
            XCTAssertEqual(error.localizedDescription, "The upload is too large.")
        }
    }

    func testAPIClientLocalizesCompromisedPasswordErrorCode() async throws {
        UserDefaults.standard.set(AppLanguagePreference.de.rawValue, forKey: AppLanguagePreference.storageKey)
        URLProtocolStub.reset()
        URLProtocolStub.statusCode = 400
        URLProtocolStub.responseData = #"{"code":"PASSWORD_COMPROMISED","message":"Password appears in known breaches"}"#.data(using: .utf8)!

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            session: session
        )

        do {
            _ = try await client.refreshMobileSession(refreshToken: "refresh")
            XCTFail("Expected compromised password error")
        } catch let error as VeyqAPIError {
            XCTAssertEqual(error.statusCode, 400)
            XCTAssertEqual(error.apiCode, "PASSWORD_COMPROMISED")
            XCTAssertEqual(error.localizedDescription, "Wähle ein Passwort, das nicht in bekannten Datenlecks vorkommt.")
        }
    }

    func testAPIClientFallsBackToServerMessageForUnknownErrorCode() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.statusCode = 400
        URLProtocolStub.responseData = #"{"code":"NEW_SERVER_CODE","message":"Server fallback message"}"#.data(using: .utf8)!

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            session: session
        )

        do {
            _ = try await client.memoryMedia(tenantName: "friends", memoryId: "mem-1")
            XCTFail("Expected server error")
        } catch let error as VeyqAPIError {
            XCTAssertEqual(error.statusCode, 400)
            XCTAssertEqual(error.apiCode, "NEW_SERVER_CODE")
            XCTAssertEqual(error.localizedDescription, "Server fallback message")
        }
    }

    func testAPIClientKeepsUnauthorizedErrorForRefreshFlow() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.statusCode = 401
        URLProtocolStub.responseData = #"{"code":"UNAUTHORIZED","message":"Unauthorized"}"#.data(using: .utf8)!

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "expired",
            session: session
        )

        do {
            _ = try await client.memoryMedia(tenantName: "friends", memoryId: "mem-1")
            XCTFail("Expected unauthorized error")
        } catch VeyqAPIError.unauthorized {
            XCTAssertTrue(true)
        }
    }

    func testAPIClientCreatesMemoryMediaUploadSession() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.responseData = #"{"uploadId":"upload-1","chunkSizeBytes":4096}"#.data(using: .utf8)!

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )

        let upload = try await client.createMemoryMediaUpload(
            tenantName: "friends",
            memoryId: "mem-1",
            filename: "beach.jpg",
            mimeType: "image/jpeg",
            sizeBytes: 2048,
            title: "beach.jpg"
        )

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        let body = try XCTUnwrap(request.httpBodyString)
        XCTAssertEqual(upload.uploadId, "upload-1")
        XCTAssertEqual(request.url?.absoluteString, "https://memories.example.test/api/friends/memories/mem-1/media/uploads")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertTrue(body.contains(#""filename":"beach.jpg""#))
        XCTAssertTrue(body.contains(#""mimeType":"#))
        XCTAssertTrue(body.contains("image"))
        XCTAssertTrue(body.contains("jpeg"))
    }

    func testAPIClientUploadsMemoryMediaChunk() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.responseData = Data()
        URLProtocolStub.statusCode = 204

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )

        try await client.uploadMemoryMediaChunk(
            tenantName: "friends",
            memoryId: "mem-1",
            uploadId: "upload-1",
            chunkIndex: 2,
            data: Data([0x01, 0x02])
        )

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        XCTAssertEqual(request.url?.absoluteString, "https://memories.example.test/api/friends/memories/mem-1/media/uploads/upload-1/chunks/2")
        XCTAssertEqual(request.httpMethod, "PUT")
        XCTAssertEqual(URLProtocolStub.lastRequestBody, Data([0x01, 0x02]))
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/octet-stream")
    }

    func testAPIClientRestartsExpiredUploadSession() async throws {
        let fileURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("veyq-upload-restart-\(UUID().uuidString).jpg")
        try Data([0x01, 0x02, 0x03, 0x04, 0x05]).write(to: fileURL)
        defer { try? FileManager.default.removeItem(at: fileURL) }

        URLProtocolStub.reset()
        URLProtocolStub.queuedResponses = [
            URLProtocolStub.Response(statusCode: 404, data: #"{"code":"NOT_FOUND","message":"Upload not found"}"#.data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: #"{"uploadId":"upload-new","chunkSizeBytes":4}"#.data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: #"{"uploadId":"upload-new","filename":"photo.jpg","mimeType":"image/jpeg","sizeBytes":5,"chunkSizeBytes":4,"uploadedChunks":[],"uploadedBytes":0}"#.data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 204, data: Data()),
            URLProtocolStub.Response(statusCode: 204, data: Data()),
            URLProtocolStub.Response(statusCode: 200, data: """
            {
              "id": "media-new",
              "memoryId": "mem-1",
              "bucketId": null,
              "title": "photo.jpg",
              "uploader": {
                "type": "USER",
                "displayName": "Kevin",
                "permalink": "kevin",
                "userId": 1
              },
              "createdAt": "2026-05-03T12:00:00.000Z",
              "capturedAt": null,
              "capturedAtOverride": false,
              "mimeType": "image/jpeg",
              "sizeBytes": 5,
              "contentUrl": "/api/friends/memory-media/media-new/content",
              "thumbnailUrl": null,
              "previewUrl": null,
              "posterUrl": null,
              "cameraMake": null,
              "cameraModel": null,
              "lensModel": null,
              "imageWidth": null,
              "imageHeight": null,
              "gpsLatitude": null,
              "gpsLongitude": null
            }
            """.data(using: .utf8)!)
        ]

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )
        let progress = UploadProgressRecorder()

        let media = try await client.uploadMemoryMediaFile(
            tenantName: "friends",
            memoryId: "mem-1",
            fileURL: fileURL,
            filename: "photo.jpg",
            mimeType: "image/jpeg",
            title: "photo.jpg",
            existingUploadId: "upload-old"
        ) { uploadId, loadedBytes, totalBytes in
            await progress.record(uploadId: uploadId, loadedBytes: loadedBytes, totalBytes: totalBytes)
        }

        let requestedURLs = URLProtocolStub.requestHistory.compactMap { $0.url?.absoluteString }
        XCTAssertEqual(media.id, "media-new")
        XCTAssertEqual(requestedURLs, [
            "https://memories.example.test/api/friends/memories/mem-1/media/uploads/upload-old",
            "https://memories.example.test/api/friends/memories/mem-1/media/uploads",
            "https://memories.example.test/api/friends/memories/mem-1/media/uploads/upload-new",
            "https://memories.example.test/api/friends/memories/mem-1/media/uploads/upload-new/chunks/0",
            "https://memories.example.test/api/friends/memories/mem-1/media/uploads/upload-new/chunks/1",
            "https://memories.example.test/api/friends/memories/mem-1/media/uploads/upload-new/complete"
        ])
        let progressUploadIDs = await progress.uploadIDs()
        XCTAssertEqual(progressUploadIDs, ["upload-new"])
    }

    func testAPIClientRetriesTransientChunkAndCompleteFailures() async throws {
        let fileURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("veyq-upload-transient-\(UUID().uuidString).jpg")
        try Data([0x01, 0x02, 0x03, 0x04, 0x05]).write(to: fileURL)
        defer { try? FileManager.default.removeItem(at: fileURL) }

        URLProtocolStub.reset()
        URLProtocolStub.queuedResponses = [
            URLProtocolStub.Response(statusCode: 200, data: #"{"uploadId":"upload-retry","chunkSizeBytes":4}"#.data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: #"{"uploadId":"upload-retry","filename":"photo.jpg","mimeType":"image/jpeg","sizeBytes":5,"chunkSizeBytes":4,"uploadedChunks":[],"uploadedBytes":0}"#.data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 500, data: Data()),
            URLProtocolStub.Response(statusCode: 204, data: Data()),
            URLProtocolStub.Response(statusCode: 204, data: Data()),
            URLProtocolStub.Response(statusCode: 503, data: Data()),
            URLProtocolStub.Response(statusCode: 200, data: """
            {
              "id": "media-retry",
              "memoryId": "mem-1",
              "bucketId": null,
              "title": "photo.jpg",
              "uploader": {
                "type": "USER",
                "displayName": "Kevin",
                "permalink": "kevin",
                "userId": 1
              },
              "createdAt": "2026-05-03T12:00:00.000Z",
              "capturedAt": null,
              "capturedAtOverride": false,
              "mimeType": "image/jpeg",
              "sizeBytes": 5,
              "contentUrl": "/api/friends/memory-media/media-retry/content",
              "thumbnailUrl": null,
              "previewUrl": null,
              "posterUrl": null,
              "cameraMake": null,
              "cameraModel": null,
              "lensModel": null,
              "imageWidth": null,
              "imageHeight": null,
              "gpsLatitude": null,
              "gpsLongitude": null
            }
            """.data(using: .utf8)!)
        ]

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            accessToken: "access",
            session: session
        )

        let media = try await client.uploadMemoryMediaFile(
            tenantName: "friends",
            memoryId: "mem-1",
            fileURL: fileURL,
            filename: "photo.jpg",
            mimeType: "image/jpeg",
            title: "photo.jpg",
            existingUploadId: nil
        ) { _, _, _ in }

        let requestedURLs = URLProtocolStub.requestHistory.compactMap { $0.url?.absoluteString }
        XCTAssertEqual(media.id, "media-retry")
        XCTAssertEqual(requestedURLs.filter { $0.hasSuffix("/chunks/0") }.count, 2)
        XCTAssertEqual(requestedURLs.filter { $0.hasSuffix("/complete") }.count, 2)
    }

    func testAPIClientRefreshesMobileSessionWithRefreshTokenBody() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.responseData = """
        {
          "tokenType": "Bearer",
          "accessToken": "new-access",
          "accessTokenExpiresAt": "2027-01-15T08:15:00Z",
          "refreshToken": "new-refresh",
          "refreshTokenExpiresAt": "2027-02-15T08:00:00Z",
          "user": {
            "userId": 1,
            "email": "admin@example.test",
            "displayName": "Admin",
            "permalink": "admin",
            "serverRole": "ServerOwner",
            "passkeyCount": 0,
            "hasPassword": true,
            "avatarUrl": null
          }
        }
        """.data(using: .utf8)!

        let session = URLSession(configuration: .stubbed)
        let client = VeyqAPIClient(
            instanceURL: URL(string: "https://memories.example.test")!,
            session: session
        )

        let refreshed = try await client.refreshMobileSession(refreshToken: "old-refresh")

        let request = try XCTUnwrap(URLProtocolStub.lastRequest)
        let body = try XCTUnwrap(request.httpBodyString)
        XCTAssertEqual(refreshed.accessToken, "new-access")
        XCTAssertEqual(request.url?.absoluteString, "https://memories.example.test/api/auth/mobile/session/refresh")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), nil)
        XCTAssertEqual(body, #"{"refreshToken":"old-refresh"}"#)
    }

    @MainActor
    func testMockMemoryDetailIncludesGalleryMedia() async {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        try? await store.connect(to: "https://memories.example.test")
        let memory = Memory.samples[0]
        await store.loadDetail(for: memory)

        XCTAssertFalse(store.memoryDetails[memory.id]?.media.isEmpty ?? true)
    }

    @MainActor
    func testMockMemoryDetailIncludesTracks() async {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        try? await store.connect(to: "https://memories.example.test")
        let memory = Memory.samples[0]
        await store.loadDetail(for: memory)

        let tracks = store.memoryDetails[memory.id]?.tracks ?? []
        XCTAssertEqual(tracks.map(\.id), ["track-ridge", "track-lake"])
        XCTAssertTrue(tracks.allSatisfy(\.hasRenderableGeometry))
    }

    func testMemoryMediaChronologicalSortUsesCapturedAtThenCreatedAt() {
        let fallbackOldest = makeMedia(id: "fallback-oldest", createdAt: 100, capturedAt: nil)
        let capturedNewest = makeMedia(id: "captured-newest", createdAt: 50, capturedAt: 300)
        let capturedMiddleEarlierUpload = makeMedia(id: "captured-middle-a", createdAt: 120, capturedAt: 200)
        let capturedMiddleLaterUpload = makeMedia(id: "captured-middle-b", createdAt: 130, capturedAt: 200)

        let sorted = MemoryMedia.chronologicallySorted([
            capturedNewest,
            capturedMiddleLaterUpload,
            fallbackOldest,
            capturedMiddleEarlierUpload
        ])

        XCTAssertEqual(sorted.map(\.id), [
            "fallback-oldest",
            "captured-middle-a",
            "captured-middle-b",
            "captured-newest"
        ])
    }

    func testJournalParserKeepsMarkdownAroundMediaEmbed() {
        let blocks = MemoryJournalParser.blocks(from: "# Day 2\n\n::media:media-1 Sunset\n\nAfter dinner.")

        XCTAssertEqual(blocks, [
            .markdown("# Day 2\n\n"),
            .media(caption: "Sunset", id: "media-1"),
            .markdown("\n\nAfter dinner.")
        ])
    }

    func testJournalParserTurnsCommaSeparatedMediaIntoCarousel() {
        let blocks = MemoryJournalParser.blocks(from: "::media:media-1,media-2,media-3 Trip set")

        XCTAssertEqual(blocks, [
            .carousel(caption: "Trip set", ids: ["media-1", "media-2", "media-3"])
        ])
    }

    func testJournalParserDecodesPercentEncodedMediaIDs() {
        let blocks = MemoryJournalParser.blocks(from: "::media:media%201,media%202 Set")

        XCTAssertEqual(blocks, [
            .carousel(caption: "Set", ids: ["media 1", "media 2"])
        ])
    }

    @MainActor
    func testMergeUploadedMediaKeepsDetailMediaChronological() async {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        try? await store.connect(to: "https://memories.example.test")
        let memory = Memory.samples[0]
        await store.loadDetail(for: memory)

        let uploaded = makeMedia(
            id: "uploaded-between-existing",
            createdAt: 1_775_001_800,
            capturedAt: 1_775_001_800
        )

        store.mergeUploadedMedia(uploaded)

        let media = store.memoryDetails[memory.id]?.media ?? []
        XCTAssertEqual(Array(media.map(\.id).prefix(3)), [
            "media-summit",
            "uploaded-between-existing",
            "media-trail"
        ])
    }

    @MainActor
    func testMemoryMediaUpdatesMergeAndRemoveMissingMedia() async {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        try? await store.connect(to: "https://memories.example.test")
        let memory = Memory.samples[0]
        await store.loadDetail(for: memory)

        let updatedExisting = makeMedia(
            id: "media-summit",
            createdAt: 1_775_000_000,
            updatedAt: 1_775_010_000,
            capturedAt: 1_775_000_000
        )
        let inserted = makeMedia(
            id: "media-live",
            createdAt: 1_775_001_200,
            updatedAt: 1_775_010_100,
            capturedAt: 1_775_001_200
        )

        let changed = store.mergeMemoryMediaUpdates(
            for: memory.id,
            updates: MemoryMediaUpdatesResponse(
                media: [updatedExisting, inserted],
                currentMediaIds: ["media-summit", "media-live"]
            )
        )

        let media = store.memoryDetails[memory.id]?.media ?? []
        XCTAssertTrue(changed)
        XCTAssertEqual(media.map(\.id), ["media-summit", "media-live"])
        XCTAssertEqual(media.first?.updatedAt, updatedExisting.updatedAt)
    }

    @MainActor
    func testUpdateMediaCapturedAtMergesLoadedDetail() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        try await store.connect(to: "https://memories.example.test")
        let memory = Memory.samples[0]
        await store.loadDetail(for: memory)
        let target = try XCTUnwrap(store.memoryDetails[memory.id]?.media.first)
        let capturedAt = Date(timeIntervalSince1970: 1_775_099_000)

        let updated = try await store.updateMediaCapturedAt(target, capturedAt: capturedAt)

        let media = store.memoryDetails[memory.id]?.media ?? []
        XCTAssertEqual(updated.capturedAt, capturedAt)
        XCTAssertTrue(updated.capturedAtOverride)
        XCTAssertEqual(media.last?.id, target.id)
        XCTAssertEqual(media.last?.capturedAt, capturedAt)
    }

    @MainActor
    func testDeleteMediaRemovesLoadedDetailItem() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        try await store.connect(to: "https://memories.example.test")
        let memory = Memory.samples[0]
        await store.loadDetail(for: memory)
        let target = try XCTUnwrap(store.memoryDetails[memory.id]?.media.first)

        try await store.deleteMedia(target)

        let media = store.memoryDetails[memory.id]?.media ?? []
        XCTAssertFalse(media.contains(where: { $0.id == target.id }))
    }

    @MainActor
    func testUpdateMediaHighlightUpdatesLoadedDetailItem() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        try await store.connect(to: "https://memories.example.test")
        let memory = Memory.samples[0]
        await store.loadDetail(for: memory)
        let target = try XCTUnwrap(store.memoryDetails[memory.id]?.media.first { $0.id == "media-trail" })

        let updated = try await store.updateMediaHighlight(target, highlight: true)

        XCTAssertTrue(updated.highlight)
        XCTAssertTrue(store.memoryDetails[memory.id]?.media.first { $0.id == "media-trail" }?.highlight ?? false)
    }

    @MainActor
    func testSaveMemoryJournalUpdatesLoadedDetail() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        try await store.connect(to: "https://memories.example.test")
        let memory = Memory.samples[0]
        await store.loadDetail(for: memory)

        let saved = try await store.saveMemoryJournal(for: memory, markdown: "# Edited\n\nLive preview text.")

        XCTAssertEqual(saved.markdown, "# Edited\n\nLive preview text.")
        XCTAssertEqual(store.memoryDetails[memory.id]?.journal.markdown, "# Edited\n\nLive preview text.")
        XCTAssertFalse(store.memoryDetails[memory.id]?.media.isEmpty ?? false)
    }

    private func makeMedia(
        id: String,
        memoryId: String = "summer-south-tyrol",
        createdAt: TimeInterval,
        updatedAt: TimeInterval? = nil,
        capturedAt: TimeInterval?
    ) -> MemoryMedia {
        MemoryMedia(
            id: id,
            memoryId: memoryId,
            bucketId: nil,
            title: id,
            uploader: MediaUploader(type: "USER", displayName: "Kevin", permalink: "kevin", userId: 1),
            createdAt: Date(timeIntervalSince1970: createdAt),
            updatedAt: updatedAt.map { Date(timeIntervalSince1970: $0) },
            capturedAt: capturedAt.map { Date(timeIntervalSince1970: $0) },
            capturedAtOverride: false,
            mimeType: "image/jpeg",
            sizeBytes: 1_024,
            contentUrl: "/api/family/memory-media/\(id)/content",
            highlight: false,
            thumbnailUrl: nil,
            previewUrl: nil,
            posterUrl: nil,
            cameraMake: nil,
            cameraModel: nil,
            lensModel: nil,
            imageWidth: 1024,
            imageHeight: 768,
            gpsLatitude: nil,
            gpsLongitude: nil
        )
    }

    private func makePendingImportBatch(
        id: String,
        createdAt: Date,
        targetMemory: UploadTargetMemory? = nil
    ) -> PendingImportBatch {
        PendingImportBatch(
            id: id,
            fileURLs: [URL(fileURLWithPath: "/tmp/\(id).jpg")],
            createdAt: createdAt,
            targetMemory: targetMemory
        )
    }

    private func makeUploadTarget() -> UploadTargetMemory {
        UploadTargetMemory(
            tenantName: "family",
            memoryId: "summer-south-tyrol",
            memoryTitle: "Summer in South Tyrol"
        )
    }

    private func testUser() -> VeyqUser {
        VeyqUser(
            userId: 1,
            email: "admin@example.test",
            displayName: "Admin",
            permalink: "admin",
            serverRole: "ServerOwner",
            passkeyCount: 0,
            hasPassword: true,
            avatarUrl: nil
        )
    }

    private func discoveryJSON() -> String {
        """
        {
          "product": "veyq",
          "apiBaseUrl": "https://memories.example.test/api",
          "clientCapabilitiesUrl": "https://memories.example.test/api/client-capabilities",
          "mobileLoginUrl": "https://memories.example.test/login?handoff=mobile",
          "mobileCallbackScheme": "veyq"
        }
        """
    }

    private func capabilitiesJSON(_ ids: [String]) -> String {
        let items = ids.map { id in
            #"{"id":"\#(id)","introducedInVersion":"1.0","audience":"ALL_USERS"}"#
        }.joined(separator: ",")
        return #"{"capabilities":[\#(items)]}"#
    }

    private func nativeSessionJSON(accessToken: String, refreshToken: String) -> String {
        """
        {
          "tokenType": "Bearer",
          "accessToken": "\(accessToken)",
          "accessTokenExpiresAt": "2027-01-15T08:15:00Z",
          "refreshToken": "\(refreshToken)",
          "refreshTokenExpiresAt": "2027-02-15T08:00:00Z",
          "user": {
            "userId": 1,
            "email": "admin@example.test",
            "displayName": "Admin",
            "permalink": "admin",
            "serverRole": "ServerOwner",
            "passkeyCount": 0,
            "hasPassword": true,
            "avatarUrl": null
          }
        }
        """
    }

    private func bootstrapJSON() -> String {
        """
        {
          "serverId": "server-1",
          "user": {
            "userId": 1,
            "email": "admin@example.test",
            "displayName": "Admin",
            "permalink": "admin",
            "serverRole": "ServerOwner",
            "passkeyCount": 0,
            "hasPassword": true,
            "avatarUrl": null
          },
          "tenants": [
            {
              "id": 1,
              "name": "family",
              "displayName": "Family Memories",
              "domain": "memories.example.test",
              "role": "TenantOwner"
            }
          ]
        }
        """
    }

    private func navigationJSON() -> String {
        """
        {
          "location": {
            "kind": "TENANT",
            "path": "/",
            "displayName": "Family Memories"
          },
          "scopeRoots": [],
          "favoriteMemories": [],
          "folders": [],
          "memories": []
        }
        """
    }

    private func scopedNavigationJSON() -> String {
        """
        {
          "location": {
            "kind": "TENANT",
            "path": "/",
            "displayName": "Family Memories"
          },
          "scopeRoots": [
            {
              "kind": "GROUP",
              "name": "family",
              "displayName": "Family",
              "path": "/@family"
            },
            {
              "kind": "USER",
              "name": "me",
              "displayName": "Admin",
              "path": "/~me"
            }
          ],
          "favoriteMemories": [],
          "folders": [],
          "memories": []
        }
        """
    }

    private func multiTenantBootstrapJSON() -> String {
        """
        {
          "serverId": "srv_123",
          "user": {
            "userId": 1,
            "email": "admin@example.test",
            "displayName": "Admin",
            "permalink": "admin",
            "serverRole": "ServerOwner",
            "passkeyCount": 0,
            "hasPassword": true,
            "avatarUrl": null
          },
          "tenants": [
            {
              "id": 1,
              "name": "family",
              "displayName": "Family Memories",
              "domain": "memories.example.test",
              "role": "TenantOwner"
            },
            {
              "id": 2,
              "name": "friends",
              "displayName": "Friends Memories",
              "domain": "friends.example.test",
              "role": "TenantMember"
            }
          ]
        }
        """
    }

    func testInMemorySessionStoreSaveLoadDelete() throws {
        let store = InMemoryNativeSessionStore()
        let session = StoredNativeSession(
            instanceURL: URL(string: "https://memories.example.test")!,
            apiBaseURL: URL(string: "https://memories.example.test/api")!,
            accessToken: "access",
            accessTokenExpiresAt: Date(timeIntervalSince1970: 1_800_000_000),
            refreshToken: "refresh",
            refreshTokenExpiresAt: Date(timeIntervalSince1970: 1_800_003_600),
            user: VeyqUser(userId: 1, email: "admin@example.test", displayName: "Admin", permalink: "admin", serverRole: "ServerOwner", passkeyCount: 0, hasPassword: true, avatarUrl: nil)
        )

        try store.save(session)
        XCTAssertEqual(try store.loadActive(), session)
        XCTAssertEqual(try store.loadAll(), [session])

        try store.delete()
        XCTAssertNil(try store.loadActive())
        XCTAssertTrue(try store.loadAll().isEmpty)
    }

    func testKeychainSessionStoreSaveLoadDeleteWithIsolatedService() throws {
        let store = KeychainNativeSessionStore(service: "app.veyq.ios.tests.\(UUID().uuidString)", account: "active")
        let session = StoredNativeSession(
            instanceURL: URL(string: "https://memories.example.test")!,
            apiBaseURL: URL(string: "https://memories.example.test/api")!,
            accessToken: "access",
            accessTokenExpiresAt: Date(timeIntervalSince1970: 1_800_000_000),
            refreshToken: "refresh",
            refreshTokenExpiresAt: Date(timeIntervalSince1970: 1_800_003_600),
            user: testUser(),
            capabilities: [VeyqCapability(id: "memory.core", introducedInVersion: "1.0", audience: "ALL_USERS")]
        )
        defer { try? store.delete() }

        try store.save(session)

        XCTAssertEqual(try store.loadActive(), session)
        XCTAssertEqual(try store.loadAll(), [session])

        try store.delete()
        XCTAssertNil(try store.loadActive())
        XCTAssertTrue(try store.loadAll().isEmpty)
    }

    func testStoredNativeSessionDecodesLegacyPayloadWithoutCapabilities() throws {
        let data = """
        {
          "instanceURL": "https://memories.example.test",
          "apiBaseURL": "https://memories.example.test/api",
          "accessToken": "access",
          "accessTokenExpiresAt": "2027-01-15T08:15:00Z",
          "refreshToken": "refresh",
          "refreshTokenExpiresAt": "2027-02-15T08:00:00Z",
          "user": {
            "userId": 1,
            "email": "admin@example.test",
            "displayName": "Admin",
            "permalink": "admin",
            "serverRole": "ServerOwner",
            "passkeyCount": 0,
            "hasPassword": true,
            "avatarUrl": null
          }
        }
        """.data(using: .utf8)!

        let session = try JSONDecoder.veyq.decode(StoredNativeSession.self, from: data)

        XCTAssertEqual(session.instanceURL.absoluteString, "https://memories.example.test")
        XCTAssertTrue(session.capabilities.isEmpty)
        XCTAssertNil(session.selectedTenantName)
    }
}

@MainActor
private final class StubMobileAuthenticator: MobileAuthenticating {
    func authenticate(loginURL: URL, callbackScheme: String) async throws -> URL {
        let state = URLComponents(url: loginURL, resolvingAgainstBaseURL: false)?
            .queryItems?
            .first { $0.name == "state" }?
            .value ?? ""
        return URL(string: "\(callbackScheme)://auth/callback?code=handoff-code&state=\(state)")!
    }
}

private final class URLProtocolStub: URLProtocol, @unchecked Sendable {
    struct Response {
        let statusCode: Int
        let data: Data
    }

    nonisolated(unsafe) static var responseData = Data()
    nonisolated(unsafe) static var statusCode = 200
    nonisolated(unsafe) static var queuedResponses: [Response] = []
    nonisolated(unsafe) static var lastRequest: URLRequest?
    nonisolated(unsafe) static var lastRequestBody: Data?
    nonisolated(unsafe) static var requestHistory: [URLRequest] = []

    static func reset() {
        responseData = Data()
        statusCode = 200
        queuedResponses = []
        lastRequest = nil
        lastRequestBody = nil
        requestHistory = []
    }

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        Self.lastRequest = request
        Self.lastRequestBody = Self.bodyData(from: request)
        Self.requestHistory.append(request)
        let stubbedResponse = Self.queuedResponses.isEmpty
            ? Response(statusCode: Self.statusCode, data: Self.responseData)
            : Self.queuedResponses.removeFirst()
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: stubbedResponse.statusCode,
            httpVersion: nil,
            headerFields: nil
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: stubbedResponse.data)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    private static func bodyData(from request: URLRequest) -> Data? {
        if let httpBody = request.httpBody {
            return httpBody
        }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }

        var data = Data()
        let bufferSize = 4096
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        defer { buffer.deallocate() }

        while stream.hasBytesAvailable {
            let read = stream.read(buffer, maxLength: bufferSize)
            if read < 0 { return nil }
            if read == 0 { break }
            data.append(buffer, count: read)
        }

        return data
    }
}

private final class RotatedNativeSessionStore: NativeSessionStoring {
    private let staleSession: StoredNativeSession
    private let rotatedSession: StoredNativeSession
    private var loadActiveCount = 0
    private var currentSession: StoredNativeSession?

    init(staleSession: StoredNativeSession, rotatedSession: StoredNativeSession) {
        self.staleSession = staleSession
        self.rotatedSession = rotatedSession
    }

    func loadActive() throws -> StoredNativeSession? {
        defer { loadActiveCount += 1 }
        if loadActiveCount == 0 {
            return staleSession
        }
        return currentSession ?? rotatedSession
    }

    func loadAll() throws -> [StoredNativeSession] {
        [currentSession ?? (loadActiveCount == 0 ? staleSession : rotatedSession)]
    }

    func save(_ session: StoredNativeSession) throws {
        currentSession = session
    }

    func delete() throws {
        currentSession = nil
    }

    func delete(instanceURL: URL) throws {
        if currentSession?.instanceURL == instanceURL {
            currentSession = nil
        }
    }
}

private actor UploadProgressRecorder {
    private var events: [(uploadId: String, loadedBytes: Int64, totalBytes: Int64)] = []

    func record(uploadId: String, loadedBytes: Int64, totalBytes: Int64) {
        events.append((uploadId: uploadId, loadedBytes: loadedBytes, totalBytes: totalBytes))
    }

    func uploadIDs() -> [String] {
        Array(Set(events.map(\.uploadId))).sorted()
    }
}

private extension URLSessionConfiguration {
    static var stubbed: URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [URLProtocolStub.self]
        return configuration
    }
}

private extension URLRequest {
    var httpBodyString: String? {
        if let httpBody {
            return String(data: httpBody, encoding: .utf8)
        }
        guard let httpBodyStream else { return nil }
        httpBodyStream.open()
        defer { httpBodyStream.close() }

        var data = Data()
        let bufferSize = 1024
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        defer { buffer.deallocate() }

        while httpBodyStream.hasBytesAvailable {
            let read = httpBodyStream.read(buffer, maxLength: bufferSize)
            if read <= 0 { break }
            data.append(buffer, count: read)
        }
        return String(data: data, encoding: .utf8)
    }
}
