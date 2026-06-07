import SwiftUI

struct MainTabView: View {
    let onDisconnect: () -> Void
    @EnvironmentObject private var store: VeyqStore
    @AppStorage("veyq.root.tab.customization") private var rootTabCustomization = TabViewCustomization()
    @State private var selectedRootTab = RootScopeTab.tenant
    @State private var memoryPath: [Memory] = []
    @State private var contextPresented = false
    @State private var searchQuery = ""
    @State private var searchTask: Task<Void, Never>?
    @State private var uploadsPresented = false
    @State private var settingsPresented = false
    @State private var createMemoryPresented = false
    @State private var createFolderPresented = false
    @State private var createGroupPresented = false
    @State private var groupsOverviewActive = false

    var body: some View {
        TabView(selection: rootTabSelection) {
            Tab(L10n.string("All Memories"), systemImage: "person.3", value: RootScopeTab.tenant) {
                rootNavigationView(for: .tenant)
            }
            .customizationID("app.veyq.ios.scope.tenant")
            .customizationBehavior(.reorderable, for: .tabBar)

            Tab(L10n.string("Groups"), systemImage: selectedGroupSpace?.symbolName ?? "at", value: RootScopeTab.groups) {
                rootNavigationView(for: .groups)
            }
            .customizationID("app.veyq.ios.scope.groups")
            .customizationBehavior(.reorderable, for: .tabBar)

            Tab(L10n.string("Personal"), systemImage: "person", value: RootScopeTab.personal) {
                rootNavigationView(for: .personal)
            }
            .customizationID("app.veyq.ios.scope.personal")
            .customizationBehavior(.reorderable, for: .tabBar)
        }
        .tabViewStyle(.sidebarAdaptable)
        .tabViewCustomization($rootTabCustomization)
        .transaction { transaction in
            transaction.animation = nil
        }
        .safeAreaInset(edge: .bottom) {
            if shouldShowFloatingDock {
                floatingDock
            }
        }
        .sheet(isPresented: $contextPresented) {
            SpaceSwitcherSheet()
                .environmentObject(store)
        }
        .sheet(isPresented: $settingsPresented) {
            SettingsView(onDisconnect: onDisconnect)
                .environmentObject(store)
        }
        .sheet(isPresented: $uploadsPresented) {
            NavigationStack {
                UploadQueueView()
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("Done") {
                                uploadsPresented = false
                            }
                        }
                    }
            }
        }
        .sheet(isPresented: $createMemoryPresented) {
            MemoryAuthoringSheet(mode: .createRoot)
                .environmentObject(store)
        }
        .sheet(isPresented: $createFolderPresented) {
            FolderAuthoringSheet()
                .environmentObject(store)
        }
        .sheet(isPresented: $createGroupPresented) {
            GroupAuthoringSheet()
                .environmentObject(store)
        }
        .onChange(of: navigationContextKey) { _, _ in
            memoryPath.removeAll()
            searchQuery = ""
            selectedRootTab = RootScopeTab(space: store.selectedSpace)
            groupsOverviewActive = false
        }
        .onChange(of: searchQuery) { _, query in
            searchTask?.cancel()
            searchTask = Task {
                try? await Task.sleep(nanoseconds: 220_000_000)
                guard !Task.isCancelled else { return }
                await store.updateSearch(matching: query)
            }
        }
        .onAppear {
            selectedRootTab = RootScopeTab(space: store.selectedSpace)
        }
    }

    private func rootNavigationView(for tab: RootScopeTab) -> some View {
        NavigationStack(path: $memoryPath) {
            MemoryHomeView(
                onContextTap: {
                    contextPresented = true
                },
                onCreateGroupTap: {
                    createGroupPresented = true
                },
                showsGroupSelector: tab == .groups && selectedRootTab == .groups,
                showsContentSections: showsContentSections(for: tab),
                onGroupSelect: { space in
                    groupsOverviewActive = false
                    store.selectSpace(space)
                }
            )
            .toolbar {
                topBarToolbar
            }
            .navigationDestination(for: Memory.self) { memory in
                MemoryDetailView(memory: memory)
                    .toolbar(.hidden, for: .tabBar)
            }
            .searchable(
                text: $searchQuery,
                placement: .navigationBarDrawer(displayMode: .always),
                prompt: Text(L10n.string("Search Memories"))
            ) {
                searchSuggestions
            }
        }
        .toolbar(shouldShowRootTabBar(for: tab) ? .visible : .hidden, for: .tabBar)
    }

    private var navigationContextKey: MemoryNavigationContextKey {
        MemoryNavigationContextKey(
            instanceURL: store.connectedInstanceURL,
            tenantID: store.selectedTenant?.id,
            spaceID: store.selectedSpace?.id
        )
    }

    private var rootTabSelection: Binding<RootScopeTab> {
        Binding(
            get: {
                selectedRootTab
            },
            set: { tab in
                var transaction = Transaction()
                transaction.animation = nil
                withTransaction(transaction) {
                    selectedRootTab = tab
                    selectRootTab(tab)
                }
            }
        )
    }

    @ToolbarContentBuilder
    private var topBarToolbar: some ToolbarContent {
        if memoryPath.isEmpty {
            ToolbarItem(placement: .topBarTrailing) {
                RootActionBar(
                    mode: rootActionMode,
                    onSettingsTap: { settingsPresented = true },
                    onCreateMemoryTap: { createMemoryPresented = true },
                    onCreateFolderTap: { createFolderPresented = true },
                    onCreateGroupTap: { createGroupPresented = true }
                )
                .transaction { transaction in
                    transaction.animation = nil
                }
            }
        }
    }

    private var rootActionMode: RootActionBar.Mode {
        if selectedRootTab == .groups && groupsOverviewActive {
            return .groupsOverview
        }
        if store.supportsCapability("memory.core") {
            return .memoryContent
        }
        return .settingsOnly
    }

    private var floatingDock: some View {
        HStack(alignment: .bottom, spacing: 12) {
            Spacer(minLength: 0)

            if !store.uploadItems.isEmpty {
                uploadStatusButton
            }
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.horizontal, 18)
        .padding(.top, 8)
        .padding(.bottom, 8)
    }

    private var shouldShowFloatingDock: Bool {
        memoryPath.isEmpty
    }

    private var tenantSpace: VeyqSpace? {
        store.spaces.first { $0.kind == .tenant }
    }

    private var personalSpace: VeyqSpace? {
        store.spaces.first { $0.kind == .personal }
    }

    private var selectedGroupSpace: VeyqSpace? {
        guard store.selectedSpace?.kind == .group else { return nil }
        return store.selectedSpace
    }

    private var searchResults: [SearchResultSummary] {
        store.searchResults
    }

    @ViewBuilder
    private var searchSuggestions: some View {
        if searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            Text(L10n.string("Enter a Memory title."))
                .foregroundStyle(.secondary)
        } else if store.searchLoading {
            Text(L10n.string("Searching Memories..."))
                .foregroundStyle(.secondary)
        } else if let searchError = store.searchErrorMessage {
            Text(searchError)
                .foregroundStyle(.secondary)
        } else if searchResults.isEmpty {
            Text(L10n.string("No Memories found."))
                .foregroundStyle(.secondary)
        } else {
            ForEach(searchResults) { result in
                Button {
                    Task {
                        searchQuery = ""
                        if let memory = await store.openSearchResult(result) {
                            memoryPath = [memory]
                        } else {
                            memoryPath.removeAll()
                        }
                    }
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: result.kind == .memory ? "photo.on.rectangle.angled" : "folder")
                            .foregroundStyle(.secondary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(result.displayName)
                                .font(.headline)
                            Text(store.spaceLabel(for: result.path))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .accessibilityIdentifier("search-result-\(result.id)")
            }
        }
    }

    private func selectRootTab(_ tab: RootScopeTab) {
        memoryPath.removeAll()
        switch tab {
        case .tenant:
            groupsOverviewActive = false
            if let tenantSpace {
                store.selectSpace(tenantSpace)
            }
        case .groups:
            groupsOverviewActive = true
        case .personal:
            groupsOverviewActive = false
            if let personalSpace {
                store.selectSpace(personalSpace)
            }
        }
    }

    private func shouldHideTabBar(for tab: RootScopeTab) -> Bool {
        store.canNavigateUpFolder && (tab != .groups || !groupsOverviewActive)
    }

    private func shouldShowRootTabBar(for tab: RootScopeTab) -> Bool {
        memoryPath.isEmpty && !shouldHideTabBar(for: tab)
    }

    private func showsContentSections(for tab: RootScopeTab) -> Bool {
        guard tab == selectedRootTab else { return false }
        return tab != .groups || !groupsOverviewActive
    }

    private var uploadStatusButton: some View {
        Button {
            uploadsPresented = true
        } label: {
            HStack(spacing: 8) {
                Image(systemName: uploadStatusIcon)
                    .font(.subheadline.weight(.semibold))
                Text(uploadStatusLabel)
                    .font(.subheadline.weight(.semibold))
            }
            .foregroundStyle(uploadStatusTint)
            .frame(height: 52)
            .padding(.horizontal, 16)
            .background(.thinMaterial, in: Capsule())
            .overlay {
                Capsule()
                    .stroke(uploadStatusTint.opacity(0.28))
            }
            .shadow(color: uploadStatusTint.opacity(0.14), radius: 12, x: 0, y: 6)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(uploadStatusAccessibilityLabel)
        .accessibilityIdentifier("upload-status-button")
    }

    private var failedUploadCount: Int {
        store.uploadItems.filter { $0.state == .failed }.count
    }

    private var activeUploadCount: Int {
        store.uploadItems.filter { $0.state.isActive }.count
    }

    private var uploadStatusLabel: String {
        if failedUploadCount > 0 {
            return L10n.format("%d Failed", failedUploadCount)
        }

        let count = activeUploadCount > 0 ? activeUploadCount : store.uploadItems.count
        return L10n.format("%d Uploads", count)
    }

    private var uploadStatusAccessibilityLabel: String {
        if failedUploadCount > 0 {
            return L10n.format("%d failed uploads", failedUploadCount)
        }

        let count = activeUploadCount > 0 ? activeUploadCount : store.uploadItems.count
        return L10n.format("%d active uploads", count)
    }

    private var uploadStatusIcon: String {
        failedUploadCount > 0 ? "exclamationmark.arrow.triangle.2.circlepath" : "arrow.up.circle"
    }

    private var uploadStatusTint: Color {
        failedUploadCount > 0 ? VeyqColor.danger : VeyqColor.accent
    }

}

private struct MemoryNavigationContextKey: Equatable {
    let instanceURL: URL?
    let tenantID: Int?
    let spaceID: String?
}

private struct RootActionBar: View {
    enum Mode {
        case settingsOnly
        case groupsOverview
        case memoryContent
    }

    let mode: Mode
    let onSettingsTap: () -> Void
    let onCreateMemoryTap: () -> Void
    let onCreateFolderTap: () -> Void
    let onCreateGroupTap: () -> Void

    var body: some View {
        HStack(spacing: 16) {
            Button {
                onSettingsTap()
            } label: {
                Image(systemName: "gearshape")
            }
            .accessibilityLabel(L10n.string("Settings"))
            .accessibilityIdentifier("settings-button")

            switch mode {
            case .settingsOnly:
                EmptyView()
            case .groupsOverview:
                Button {
                    onCreateGroupTap()
                } label: {
                    Image(systemName: "person.2.badge.plus")
                }
                .accessibilityLabel(L10n.string("Create Group"))
                .accessibilityIdentifier("create-group-button")
            case .memoryContent:
                Button {
                    onCreateMemoryTap()
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel(L10n.string("Create Memory"))
                .accessibilityIdentifier("create-memory-button")

                Menu {
                    Button {
                        onCreateFolderTap()
                    } label: {
                        Label(L10n.string("Create Folder"), systemImage: "folder.badge.plus")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityLabel(L10n.string("Organize"))
                .accessibilityIdentifier("organize-content-menu")
            }
        }
    }
}

private enum RootScopeTab: Hashable {
    case tenant
    case groups
    case personal

    init(space: VeyqSpace?) {
        switch space?.kind {
        case .group:
            self = .groups
        case .personal:
            self = .personal
        case .tenant, .none:
            self = .tenant
        }
    }
}

struct PendingImportTargetSheet: View {
    @EnvironmentObject private var store: VeyqStore
    @Environment(\.dismiss) private var dismiss
    let batch: PendingImportBatch
    var onCancel: (() -> Void)?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(store.visibleMemories) { memory in
                        Button {
                            store.enqueuePendingImport(batch, targetMemory: memory)
                            dismiss()
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(memory.title)
                                    .font(.headline)
                                Text(memory.dateRange)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .accessibilityIdentifier("pending-import-target-\(memory.id)")
                }
            } header: {
                Text(L10n.format("%d items", batch.fileURLs.count))
            }
            }
            .navigationTitle("Choose Memory")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Cancel") {
                        onCancel?()
                        dismiss()
                    }
                }
            }
            .accessibilityIdentifier("pending-import-target-sheet")
        }
    }
}
