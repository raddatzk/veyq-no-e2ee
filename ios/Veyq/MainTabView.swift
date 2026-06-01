import SwiftUI

struct MainTabView: View {
    let onDisconnect: () -> Void
    @EnvironmentObject private var store: VeyqStore
    @State private var memoryPath: [Memory] = []
    @State private var searchPresented = false
    @State private var uploadsPresented = false
    @State private var settingsPresented = false

    var body: some View {
        NavigationStack(path: $memoryPath) {
            MemoryHomeView()
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            settingsPresented = true
                        } label: {
                            Image(systemName: "gearshape")
                        }
                        .accessibilityLabel(L10n.string("Settings"))
                        .accessibilityIdentifier("settings-button")
                    }
                }
                .navigationDestination(for: Memory.self) { memory in
                    MemoryDetailView(memory: memory)
                }
        }
        .safeAreaInset(edge: .bottom) {
            floatingDock
        }
        .sheet(isPresented: $searchPresented) {
            SearchSheetView()
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
        .onChange(of: navigationContextKey) { _, _ in
            memoryPath.removeAll()
        }
    }

    private var navigationContextKey: MemoryNavigationContextKey {
        MemoryNavigationContextKey(
            instanceURL: store.connectedInstanceURL,
            tenantID: store.selectedTenant?.id,
            spaceID: store.selectedSpace?.id
        )
    }

    private var floatingDock: some View {
        HStack(spacing: 12) {
            if !store.uploadItems.isEmpty {
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

            Button {
                searchPresented = true
            } label: {
                Image(systemName: "magnifyingglass")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(.primary)
                    .frame(width: 58, height: 58)
                    .background(.thinMaterial, in: Circle())
                    .overlay {
                        Circle()
                            .stroke(VeyqColor.hairline)
                    }
                    .shadow(color: VeyqColor.dockShadow, radius: 12, x: 0, y: 6)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Search")
            .accessibilityIdentifier("bottom-search-button")
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.horizontal, 18)
        .padding(.top, 8)
        .padding(.bottom, 8)
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

private struct SearchSheetView: View {
    @EnvironmentObject private var store: VeyqStore
    @State private var query = ""
    @Environment(\.dismiss) private var dismiss

    private var results: [Memory] {
        store.searchMemories(matching: query)
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    TextField("Search Memories", text: $query)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("search-query-field")
                }

                Section {
                    if results.isEmpty {
                        Text(query.isEmpty ? L10n.string("Enter a Memory title.") : L10n.string("No Memories found."))
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(results) { memory in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(memory.title)
                                    .font(.headline)
                                    .accessibilityIdentifier("search-result-\(memory.id)")
                                Text(store.spaceLabel(for: memory.path))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                Text(memory.dateRange)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                } header: {
                    Text("Results")
                }
            }
            .navigationTitle("Search")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Text("Search")
                        .font(.headline)
                        .accessibilityIdentifier("search-sheet-title")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
        }
    }
}
