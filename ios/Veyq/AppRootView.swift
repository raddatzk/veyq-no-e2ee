import SwiftUI

struct AppRootView: View {
    @EnvironmentObject private var store: VeyqStore
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("veyq.instance.url") private var savedInstanceURL = ""
    @AppStorage(AppThemePreference.storageKey) private var themePreferenceRawValue = AppThemePreference.system.rawValue
    @AppStorage(AppLanguagePreference.storageKey) private var languagePreferenceRawValue = AppLanguagePreference.system.rawValue
    @State private var pendingImportBatch: PendingImportBatch?
    @State private var activeImportHandoffBatchIDs: Set<String> = []

    var body: some View {
        Group {
            if store.isConnected {
                MainTabView {
                    store.disconnect()
                    savedInstanceURL = ""
                }
            } else if !store.hasCompletedSessionRestore {
                ProgressView()
                    .accessibilityIdentifier("session-restore-progress")
            } else {
                NavigationStack {
                    connectView
                }
            }
        }
        .tint(VeyqColor.accent)
        .preferredColorScheme(AppThemePreference.resolve(themePreferenceRawValue).colorScheme)
        .environment(\.locale, AppLanguagePreference.resolve(languagePreferenceRawValue).locale)
        .task {
            guard !store.isConnected, !store.hasCompletedSessionRestore else { return }
            await store.restoreSession()
            presentLatestPendingImportIfAvailable()
        }
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .active {
                presentLatestPendingImportIfAvailable()
            }
        }
        .onChange(of: store.isConnected) { _, isConnected in
            if isConnected {
                presentLatestPendingImportIfAvailable()
            }
        }
        .onOpenURL { url in
            handleOpenURL(url)
        }
        .sheet(item: pendingImportSheetBinding) { batch in
            PendingImportTargetSheet(batch: batch) {
                UploadQueuePersistence.removePendingImportBatch(id: batch.id)
                pendingImportBatch = nil
            }
                .environmentObject(store)
        }
        .alert("veyq", isPresented: errorAlertBinding) {
            Button("OK") {
                store.clearError()
            }
        } message: {
            Text(store.errorMessage ?? "")
        }
    }

    private var connectView: some View {
        InstanceConnectView(initialURL: savedInstanceURL) { rawValue in
            do {
                let normalized = try await store.connect(to: rawValue)
                savedInstanceURL = normalized.absoluteString
                return nil
            } catch let error as InstanceURLValidationError {
                return error.localizedDescription
            } catch let error as VeyqAPIError {
                return error.localizedDescription
            } catch {
                return L10n.string("Enter a valid veyq instance URL.")
            }
        }
    }

    private var pendingImportSheetBinding: Binding<PendingImportBatch?> {
        Binding {
            store.isConnected ? pendingImportBatch : nil
        } set: { batch in
            pendingImportBatch = batch
        }
    }

    private var errorAlertBinding: Binding<Bool> {
        Binding {
            store.errorMessage != nil
        } set: { isPresented in
            if !isPresented {
                store.clearError()
            }
        }
    }

    private func handleOpenURL(_ url: URL) {
        guard let deepLink = VeyqDeepLink(url: url) else { return }

        switch deepLink {
        case .importBatch(let batchId):
            handleImportHandoff(batchId: batchId)
        case .open(let canonicalURL):
            savedInstanceURL = VeyqDeepLink.instanceCandidateURL(for: canonicalURL)?.absoluteString ?? savedInstanceURL
            Task {
                await store.openCanonicalLink(canonicalURL)
            }
        }
    }

    private func handleImportHandoff(batchId: String) {
        guard !batchId.isEmpty else {
            store.reportError(L10n.string("Shared files could not be opened."))
            return
        }

        activeImportHandoffBatchIDs.insert(batchId)
        defer {
            activeImportHandoffBatchIDs.remove(batchId)
        }

        guard let batch = UploadQueuePersistence.loadPendingImportBatch(id: batchId) else {
            store.reportError(L10n.string("Shared files could not be opened."))
            return
        }

        if let target = batch.targetMemory {
            if pendingImportBatch?.id == batch.id {
                pendingImportBatch = nil
            }
            Task {
                await store.enqueuePendingImportHandoff(batch, target: target)
            }
            return
        }
        UploadQueuePersistence.removePendingImportBatch(id: batch.id)
    }

    private func presentLatestPendingImportIfAvailable() {
        guard store.isConnected, pendingImportBatch == nil else { return }

        let batches = UploadQueuePersistence.loadPendingImportBatches()
        UploadQueuePersistence.removeUntargetedPendingImportBatches(batches)
        let action = PendingImportPresentationPlanner.action(
            for: batches,
            pendingSheetBatchId: pendingImportBatch?.id,
            activeHandoffBatchIds: activeImportHandoffBatchIDs
        )

        switch action {
        case .enqueueTargeted(let targetedBatches):
            for batch in targetedBatches {
                guard let target = batch.targetMemory else { continue }
                Task {
                    await store.enqueuePendingImportHandoff(batch, target: target)
                }
            }
        case .none:
            break
        }
    }
}

enum VeyqDeepLink: Equatable {
    case importBatch(String)
    case open(URL)

    init?(url: URL) {
        guard url.scheme?.lowercased() == "veyq" else { return nil }
        switch url.host?.lowercased() {
        case "import":
            guard let batchId = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                .queryItems?
                .first(where: { $0.name == "batchId" })?
                .value
            else {
                return nil
            }
            self = .importBatch(batchId)
        case "open":
            guard let rawURL = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                .queryItems?
                .first(where: { $0.name == "url" })?
                .value,
                let canonicalURL = URL(string: rawURL),
                canonicalURL.isHTTPURL
            else {
                return nil
            }
            self = .open(canonicalURL)
        default:
            return nil
        }
    }

    static func instanceCandidateURL(for canonicalURL: URL) -> URL? {
        guard var components = URLComponents(url: canonicalURL, resolvingAgainstBaseURL: false),
              canonicalURL.isHTTPURL
        else {
            return nil
        }
        components.path = ""
        components.query = nil
        components.fragment = nil
        return components.url
    }
}

private extension URL {
    var isHTTPURL: Bool {
        let scheme = scheme?.lowercased()
        return scheme == "https" || scheme == "http"
    }
}
