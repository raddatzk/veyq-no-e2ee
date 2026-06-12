import SwiftUI

struct UploadQueueView: View {
    @EnvironmentObject private var store: VeyqStore

    var body: some View {
        ZStack {
            VeyqScreenBackground()

            List {
                if store.uploadItems.isEmpty {
                    Section {
                        VeyqEmptyState(
                            symbolName: "arrow.up.circle",
                            title: "No active uploads",
                            message: "Photos and videos you add to Memories will appear here.",
                            tint: VeyqColor.success
                        )
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                    }
                } else {
                    Section {
                        ForEach(store.uploadItems) { item in
                            UploadQueueRow(item: item)
                        }
                    } header: {
                        Text("Upload Queue")
                            .accessibilityIdentifier("upload-queue-title")
                    } footer: {
                        Text("Uploads continue while the app is open. Interrupted uploads resume from this queue when possible.")
                    }
                }
            }
            .scrollContentBackground(.hidden)
        }
        .navigationTitle("Uploads")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Clear") {
                    store.clearFinishedUploads()
                }
                .disabled(!store.uploadItems.contains { $0.state == .complete || $0.state == .cancelled })
            }
        }
    }
}

private struct UploadQueueRow: View {
    @EnvironmentObject private var store: VeyqStore
    let item: UploadQueueItem

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: item.state.symbolName)
                    .foregroundStyle(item.state.tint)
                    .frame(width: 28)
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 4) {
                    Text(item.filename)
                        .font(.headline)
                    Text(item.target.memoryTitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    if let error = item.errorMessage, item.state == .failed {
                        Text(error)
                            .font(.caption)
                            .foregroundStyle(VeyqColor.danger)
                    }
                    if let detail = UploadQueueProgressPresenter.detailText(for: item) {
                        Text(detail)
                            .font(.caption)
                            .foregroundStyle(UploadQueueProgressPresenter.isStalled(item) ? VeyqColor.warning : .secondary)
                    }
                }

                Spacer()

                Text(item.state.label)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(item.state.tint)
                    .accessibilityIdentifier("upload-state-\(item.state.rawValue)")
            }

            ProgressView(value: item.progress)
                .tint(progressTint)

            if item.state == .failed || item.state.isActive {
                HStack {
                    if item.state == .failed {
                        Button("Retry") {
                            store.retryUpload(item)
                        }
                        .buttonStyle(.bordered)
                        .disabled(!item.retryable)
                        .accessibilityIdentifier("upload-retry-\(item.id)")
                    }

                    if item.state.isActive {
                        Button("Cancel") {
                            store.cancelUpload(item)
                        }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("upload-cancel-\(item.id)")
                    }
                }
                .font(.caption)
            }
        }
        .padding(.vertical, 6)
        .listRowBackground(
            RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                .fill(.thinMaterial)
                .padding(.vertical, 4)
        )
    }
}

private extension UploadQueueRow {
    var progressTint: Color {
        if UploadQueueProgressPresenter.isStalled(item) {
            return VeyqColor.warning
        }
        return item.state == .failed ? VeyqColor.danger : VeyqColor.success
    }
}

private extension UploadQueueState {
    var symbolName: String {
        switch self {
        case .queued: "clock"
        case .uploading: "arrow.up.circle"
        case .failed: "exclamationmark.triangle"
        case .complete: "checkmark.circle"
        case .cancelled: "xmark.circle"
        }
    }

    var tint: Color {
        switch self {
        case .queued: VeyqColor.info
        case .uploading: VeyqColor.success
        case .failed: VeyqColor.danger
        case .complete: VeyqColor.success
        case .cancelled: .secondary
        }
    }
}
