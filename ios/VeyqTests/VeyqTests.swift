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
    func testSearchFindsMemoryByTitle() async throws {
        let store = VeyqStore(sessionStore: InMemoryNativeSessionStore(), mockMode: true)
        _ = try await store.connect(to: "https://memories.example.test")
        let personalSpace = try XCTUnwrap(store.spaces.first { $0.kind == .personal })
        store.selectSpace(personalSpace)
        let results = store.searchMemories(matching: "garden")

        XCTAssertEqual(results.map(\.id), ["morning-garden"])
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

    @MainActor
    func testConnectPersistsClientCapabilitySnapshotWithNativeSession() async throws {
        URLProtocolStub.reset()
        URLProtocolStub.queuedResponses = [
            URLProtocolStub.Response(statusCode: 200, data: discoveryJSON().data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: capabilitiesJSON(["auth.mobile-handoff", "memory.core", "memory.media"]).data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: nativeSessionJSON(accessToken: "access", refreshToken: "refresh").data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: capabilitiesJSON(["auth.mobile-handoff", "memory.core", "memory.media"]).data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: bootstrapJSON().data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: navigationJSON().data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: "[]".data(using: .utf8)!)
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
            URLProtocolStub.Response(statusCode: 200, data: navigationJSON().data(using: .utf8)!),
            URLProtocolStub.Response(statusCode: 200, data: "[]".data(using: .utf8)!)
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
          "capturedAt": null,
          "capturedAtOverride": false,
          "mimeType": "image/jpeg",
          "sizeBytes": 2048000,
          "contentUrl": "/api/friends/memory-media/med_abc123/content",
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
        XCTAssertEqual(media.imageWidth, 6240)
        XCTAssertEqual(media.gpsLatitude, 54.1825)
    }

    func testGalleryAspectRatioBoundsExtremeValues() {
        XCTAssertEqual(JustifiedMediaLayout.galleryDisplayAspectRatio(width: 16, height: 9), 4.0 / 3.0)
        XCTAssertEqual(JustifiedMediaLayout.galleryDisplayAspectRatio(width: 9, height: 16), 3.0 / 4.0)
        XCTAssertEqual(JustifiedMediaLayout.galleryDisplayAspectRatio(width: nil, height: 9), 4.0 / 3.0)
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

    private func makeMedia(
        id: String,
        memoryId: String = "summer-south-tyrol",
        createdAt: TimeInterval,
        capturedAt: TimeInterval?
    ) -> MemoryMedia {
        MemoryMedia(
            id: id,
            memoryId: memoryId,
            bucketId: nil,
            title: id,
            uploader: MediaUploader(type: "USER", displayName: "Kevin", permalink: "kevin", userId: 1),
            createdAt: Date(timeIntervalSince1970: createdAt),
            capturedAt: capturedAt.map { Date(timeIntervalSince1970: $0) },
            capturedAtOverride: false,
            mimeType: "image/jpeg",
            sizeBytes: 1_024,
            contentUrl: "/api/family/memory-media/\(id)/content",
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
          "scopeRoots": []
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
        let store = KeychainNativeSessionStore(service: "com.veyq.ios.tests.\(UUID().uuidString)", account: "active")
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
