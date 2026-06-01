import SwiftUI

struct MemoryHomeView: View {
    @EnvironmentObject private var store: VeyqStore
    @State private var spaceSwitcherPresented = false

    var body: some View {
        ZStack {
            VeyqScreenBackground()

            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    homeHeader

                    if let error = store.errorMessage {
                        VeyqEmptyState(
                            symbolName: "exclamationmark.triangle",
                            title: "Something needs attention",
                            rawMessage: error,
                            tint: VeyqColor.danger
                        )
                    }

                    VStack(alignment: .leading, spacing: 14) {
                        HStack(alignment: .lastTextBaseline) {
                            Text(store.selectedTenant?.displayName ?? L10n.string("Memories"))
                                .font(.title2.bold())

                            Spacer()

                            Text("\(store.visibleMemories.count)")
                                .font(.callout.weight(.semibold))
                                .foregroundStyle(.secondary)
                        }

                        if store.visibleMemories.isEmpty {
                            VeyqEmptyState(
                                symbolName: "rectangle.stack.badge.plus",
                                title: "No Memories here yet",
                                message: "Switch scope or add the first photos and videos from a Memory.",
                                tint: VeyqColor.accent
                            )
                        } else {
                            LazyVStack(spacing: 14) {
                                ForEach(store.visibleMemories) { memory in
                                    NavigationLink(value: memory) {
                                        MemoryCard(memory: memory)
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityIdentifier("memory-card-\(memory.id)")
                                }
                            }
                        }
                    }
                }
                .padding()
                .padding(.bottom, VeyqLayout.floatingDockContentPadding)
            }
        }
        .refreshable {
            await store.refreshCurrentMemories()
        }
        .sheet(isPresented: $spaceSwitcherPresented) {
            SpaceSwitcherSheet()
        }
    }

    private var homeHeader: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Memories")
                        .font(.largeTitle.bold())
                        .accessibilityIdentifier("memory-home-title")
                    Text("Trips, moments and shared chapters kept together.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer()

                Image(systemName: "sparkles.rectangle.stack")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(VeyqColor.accent)
                    .frame(width: 44, height: 44)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                            .stroke(VeyqColor.hairline)
                    }
                    .accessibilityHidden(true)
            }

            Button {
                spaceSwitcherPresented = true
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: store.selectedSpace?.symbolName ?? "person.3")
                        .font(.caption.weight(.semibold))
                    Text(store.selectedSpace?.label ?? L10n.string("Memories"))
                        .font(.callout.weight(.semibold))
                    Image(systemName: "chevron.down")
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.secondary)
                }
                .foregroundStyle(VeyqColor.accent)
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
                .background(.thinMaterial, in: Capsule())
                .overlay {
                    Capsule()
                        .stroke(VeyqColor.accent.opacity(0.18))
                }
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Current Space")
            .accessibilityValue(store.selectedSpace?.label ?? L10n.string("Memories"))
            .accessibilityIdentifier("memory-space-button")
        }
    }
}

private struct SpaceSwitcherSheet: View {
    @EnvironmentObject private var store: VeyqStore
    @Environment(\.dismiss) private var dismiss
    @State private var newInstanceURL = ""
    @State private var instanceError: String?
    @State private var addingInstance = false
    @State private var addInstancePresented = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    NavigationLink(value: ContextDestination.scope) {
                        ContextSummaryRow(
                            symbolName: store.selectedSpace?.symbolName ?? "person.3",
                            title: "Memory Scope",
                            value: store.selectedSpace?.label ?? L10n.string("Memories")
                        )
                    }
                    .accessibilityIdentifier("context-scope-row")

                    if store.tenants.count > 1 {
                        NavigationLink(value: ContextDestination.tenant) {
                            ContextSummaryRow(
                                symbolName: "building.2",
                                title: "Tenant",
                                value: store.selectedTenant?.displayName ?? L10n.string("Select Tenant")
                            )
                        }
                        .accessibilityIdentifier("context-tenant-row")
                    }

                    NavigationLink(value: ContextDestination.instance) {
                        ContextSummaryRow(
                            symbolName: "server.rack",
                            title: "Instance",
                            value: currentInstanceTitle
                        )
                    }
                    .accessibilityIdentifier("context-instance-row")
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Context")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: ContextDestination.self) { destination in
                switch destination {
                case .scope:
                    scopeList
                case .tenant:
                    tenantList
                case .instance:
                    instanceList
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .alert("Connect to Instance", isPresented: $addInstancePresented) {
            TextField("Instance URL", text: $newInstanceURL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            Button("Connect") {
                Task { await connectToNewInstance() }
            }
            .disabled(newInstanceURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || addingInstance)

            Button("Cancel", role: .cancel) {
                newInstanceURL = ""
            }
        } message: {
            Text("Enter the base URL of another veyq instance.")
        }
    }

    private var scopeList: some View {
        List {
            ForEach(VeyqSpaceKind.allCases, id: \.self) { kind in
                let spaces = store.spaces.filter { $0.kind == kind }

                if !spaces.isEmpty {
                    Section(kind.sectionTitle) {
                        ForEach(spaces) { space in
                            Button {
                                store.selectSpace(space)
                                dismiss()
                            } label: {
                                SelectionOptionRow(
                                    symbolName: space.symbolName,
                                    title: space.label,
                                    subtitle: space.path,
                                    isSelected: store.selectedSpace == space
                                )
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier(space.spaceOptionAccessibilityIdentifier)
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Memory Scope")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var tenantList: some View {
        List {
            Section {
                ForEach(store.tenants) { tenant in
                    Button {
                        store.selectTenant(tenant)
                        dismiss()
                    } label: {
                        SelectionOptionRow(
                            symbolName: "building.2",
                            title: tenant.displayName,
                            subtitle: tenant.domain,
                            isSelected: store.selectedTenant?.id == tenant.id
                        )
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("tenant-option-\(tenant.name)")
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Tenant")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var instanceList: some View {
        List {
            if !store.instances.isEmpty {
                Section("Instances") {
                    ForEach(store.instances, id: \.instanceURL) { session in
                        Button {
                            Task {
                                await store.switchInstance(session)
                                dismiss()
                            }
                        } label: {
                            SelectionOptionRow(
                                symbolName: "server.rack",
                                title: instanceTitle(session),
                                subtitle: session.user.displayName,
                                isSelected: store.connectedInstanceURL == session.instanceURL
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            Section {
                Button {
                    addInstancePresented = true
                } label: {
                    Label("Connect to Instance", systemImage: "plus")
                }
                .disabled(addingInstance)
                .accessibilityIdentifier("add-instance-button")

                if let activeSession {
                    Button {
                        Task {
                            await store.deleteInstance(activeSession)
                            dismiss()
                        }
                    } label: {
                        Label("Remove Current Instance", systemImage: "trash")
                    }
                    .foregroundStyle(VeyqColor.danger)
                }
            }

            if let instanceError {
                Section {
                    Text(instanceError)
                        .font(.caption)
                        .foregroundStyle(VeyqColor.danger)
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Instance")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var activeSession: StoredNativeSession? {
        store.instances.first { $0.instanceURL == store.connectedInstanceURL }
    }

    private var currentInstanceTitle: String {
        guard let activeSession else { return L10n.string("Connected") }
        return instanceTitle(activeSession)
    }

    private func instanceTitle(_ session: StoredNativeSession) -> String {
        session.instanceURL.host ?? session.instanceURL.absoluteString
    }

    private func connectToNewInstance() async {
        addingInstance = true
        instanceError = nil
        defer { addingInstance = false }

        do {
            _ = try await store.connect(to: newInstanceURL)
            newInstanceURL = ""
            dismiss()
        } catch let error as InstanceURLValidationError {
            instanceError = error.localizedDescription
        } catch {
            instanceError = L10n.string("Instance could not be connected.")
        }
    }
}

private enum ContextDestination: Hashable {
    case scope
    case tenant
    case instance
}

private struct ContextSummaryRow: View {
    let symbolName: String
    let title: LocalizedStringKey
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbolName)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(VeyqColor.accent)
                .frame(width: 30, height: 30)
                .background(VeyqColor.accent.opacity(0.12), in: Circle())

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                Text(value)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

private struct SelectionOptionRow: View {
    let symbolName: String
    let title: String
    let subtitle: String
    let isSelected: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbolName)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(VeyqColor.accent)
                .frame(width: 30, height: 30)
                .background(VeyqColor.accent.opacity(0.12), in: Circle())

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            if isSelected {
                Image(systemName: "checkmark.circle.fill")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(VeyqColor.accent)
            }
        }
        .contentShape(Rectangle())
    }
}

private extension VeyqSpaceKind {
    var sectionTitle: String {
        switch self {
        case .tenant: L10n.string("All Memories")
        case .group: L10n.string("Groups")
        case .personal: L10n.string("Personal")
        }
    }
}

private struct MemoryCard: View {
    let memory: Memory

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            cover
                .frame(height: 168)
                .clipShape(RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
                .overlay(alignment: .bottomLeading) {
                    LinearGradient(
                        colors: [.clear, .black.opacity(0.52)],
                        startPoint: .center,
                        endPoint: .bottom
                    )
                    .clipShape(RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
                }
                .overlay(alignment: .bottomLeading) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(memory.title)
                            .font(.title3.bold())
                            .foregroundStyle(VeyqColor.onStrong)
                            .lineLimit(2)
                            .shadow(radius: 8)
                        Text(memory.dateRange)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(VeyqColor.onStrong.opacity(0.88))
                            .shadow(radius: 8)
                    }
                    .padding(14)
                }

            HStack(spacing: 8) {
                VeyqMetadataPill(symbolName: "photo.on.rectangle", text: L10n.format("%d photos", memory.mediaCount), tint: VeyqColor.success)
                VeyqMetadataPill(symbolName: "square.stack.3d.up", text: L10n.format("%d chapters", memory.childCount), tint: VeyqColor.info)

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.tertiary)
            }
            .padding(12)
        }
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                .stroke(VeyqColor.hairline)
        }
        .shadow(color: VeyqColor.cardShadow, radius: 14, x: 0, y: 8)
    }

    @ViewBuilder
    private var cover: some View {
        if let coverUrl = memory.coverUrl, !coverUrl.isEmpty {
            AuthenticatedMediaImage(urlString: coverUrl, contentMode: .fill, crop: memory.coverCrop)
                .background(VeyqColor.accent.opacity(0.14))
        } else {
            placeholderCover
        }
    }

    private var placeholderCover: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12)
                .fill(
                    LinearGradient(
                        colors: [
                            VeyqColor.accent.opacity(0.28),
                            VeyqColor.rose.opacity(0.18),
                            VeyqColor.success.opacity(0.12)
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
            Image(systemName: "sparkles.rectangle.stack")
                .font(.largeTitle.weight(.semibold))
                .foregroundStyle(VeyqColor.onStrong.opacity(0.9))
        }
    }
}
