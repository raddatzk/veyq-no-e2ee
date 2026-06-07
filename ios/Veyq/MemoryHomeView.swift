import SwiftUI

struct MemoryHomeView: View {
    let onContextTap: () -> Void
    let onCreateGroupTap: () -> Void
    let showsGroupSelector: Bool
    let showsContentSections: Bool
    let onGroupSelect: (VeyqSpace) -> Void
    @EnvironmentObject private var store: VeyqStore
    @State private var foldersExpanded = true
    @State private var skeletonPulse = false

    private var headerTitle: String {
        store.selectedTenant?.displayName ?? L10n.string("Memories")
    }

    private var headerSubtitle: String? {
        if showsGroupSelector && !showsContentSections {
            return nil
        }

        let subtitle = store.navigationLocation?.displayName ?? store.selectedSpace?.label
        guard let subtitle else {
            return nil
        }
        let normalizedTitle = headerTitle.trimmingCharacters(in: .whitespacesAndNewlines).localizedLowercase
        let normalizedSubtitle = subtitle.trimmingCharacters(in: .whitespacesAndNewlines).localizedLowercase
        let normalizedSectionTitle = memorySectionTitle.trimmingCharacters(in: .whitespacesAndNewlines).localizedLowercase
        return normalizedSubtitle == normalizedTitle || normalizedSubtitle == normalizedSectionTitle ? nil : subtitle
    }

    private var memorySectionTitle: String {
        if store.canNavigateUpFolder {
            return store.navigationLocation?.displayName ?? L10n.string("Folder")
        }
        return store.selectedSpace?.label ?? L10n.string("Memories")
    }

    private var homeBackLabel: String {
        L10n.string("Up One Folder")
    }

    private var homeBackAccessibilityIdentifier: String {
        "folder-up-toolbar-button"
    }

    private func navigateBackInHome() {
        if store.canNavigateUpFolder {
            store.navigateUpFolder()
        }
    }

    var body: some View {
        ZStack {
            VeyqScreenBackground()

            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    homeHeader
                    if showsGroupSelector {
                        groupsSection
                    }

                    if let error = store.errorMessage {
                        VeyqEmptyState(
                            symbolName: "exclamationmark.triangle",
                            title: "Something needs attention",
                            rawMessage: error,
                            tint: VeyqColor.danger
                        )
                    }

                    if showsContentSections {
                        memoriesSection
                        foldersSection
                    }
                }
                .padding()
                .padding(.bottom, VeyqLayout.floatingDockContentPadding)
            }
        }
        .refreshable {
            await store.refreshCurrentMemories()
        }
        .task {
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) {
                skeletonPulse = true
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                if showsContentSections && store.canNavigateUpFolder {
                    Button {
                        navigateBackInHome()
                    } label: {
                        Label(homeBackLabel, systemImage: "chevron.left")
                    }
                    .accessibilityIdentifier(homeBackAccessibilityIdentifier)
                }
            }
        }
    }

    private var homeHeader: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 8) {
                    Text(headerTitle)
                        .font(.largeTitle.bold())
                        .foregroundStyle(.primary)
                        .accessibilityIdentifier("memory-home-title")
                    if let headerSubtitle {
                        Text(headerSubtitle)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

                Spacer(minLength: 12)

                Button {
                    onContextTap()
                } label: {
                    Image(systemName: "chevron.down.circle.fill")
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(VeyqColor.accent)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L10n.string("Context"))
                .accessibilityIdentifier("memory-context-button")
            }
        }
    }

    private var memoriesSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .lastTextBaseline) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(memorySectionTitle)
                        .font(.title2.bold())
                    if !store.memoryNavigationLoading, !store.visibleMemories.isEmpty {
                        Text(memorySectionSubtitle)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer()

                Text(store.memoryNavigationLoading ? " " : "\(store.visibleMemories.count)")
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(.secondary)
            }

            if store.memoryNavigationLoading {
                LazyVStack(spacing: 14) {
                    ForEach(0..<2, id: \.self) { _ in
                        MemoryCardSkeleton(pulse: skeletonPulse)
                    }
                }
            } else if store.visibleMemories.isEmpty {
                VeyqEmptyState(
                    symbolName: "rectangle.stack.badge.plus",
                    title: "No Memories here yet",
                    message: "Create a Memory, then add photos, journal notes and context.",
                    tint: VeyqColor.accent
                )
            } else {
                LazyVStack(spacing: 14) {
                    ForEach(store.visibleMemories) { memory in
                        NavigationLink(value: memory) {
                            MemoryCard(memory: memory)
                        }
                        .buttonStyle(.plain)
                        .simultaneousGesture(TapGesture().onEnded {
                            store.recordRecentMemory(memory)
                        })
                        .accessibilityIdentifier("memory-card-\(memory.id)")
                    }
                }
            }
        }
    }

    private var memorySectionSubtitle: LocalizedStringKey {
        if store.canNavigateUpFolder {
            return "Memories in this organizational folder"
        }
        return "Curated trips, events and moments"
    }

    @ViewBuilder
    private var groupsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .lastTextBaseline) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Groups")
                        .font(.headline)
                    Text("Shared Memory spaces for family and friends.")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.secondary)
                }

                Spacer()

                Text("\(store.groupSpaces.count)")
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(.secondary)
            }

            if store.groupSpaces.isEmpty {
                VeyqEmptyState(
                    symbolName: "person.2.badge.plus",
                    title: "No groups available.",
                    message: "Create a group to collect Memories with selected people.",
                    tint: VeyqColor.accent
                )
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 12) {
                        ForEach(store.groupSpaces) { space in
                            Button {
                                onGroupSelect(space)
                            } label: {
                                GroupSpaceCard(
                                    space: space,
                                    isSelected: showsContentSections && store.selectedSpace == space
                                )
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("group-space-card-\(space.id)")
                        }

                        Button {
                            onCreateGroupTap()
                        } label: {
                            NewGroupCard()
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("new-group-card")
                    }
                    .padding(.vertical, 2)
                }
            }
        }
    }

    @ViewBuilder
    private var foldersSection: some View {
        if store.memoryNavigationLoading || store.canNavigateUpFolder || !store.visibleFolders.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                Button {
                    withAnimation(.snappy(duration: 0.2)) {
                        foldersExpanded.toggle()
                    }
                } label: {
                    HStack(alignment: .lastTextBaseline) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("Folders")
                                .font(.headline)
                            Text("Keep related Memories together in one place.")
                                .font(.caption.weight(.medium))
                                .foregroundStyle(.secondary)
                        }

                        Spacer()

                        Text(store.memoryNavigationLoading ? " " : "\(store.visibleFolders.count)")
                            .font(.callout.weight(.semibold))
                            .foregroundStyle(.secondary)

                        Image(systemName: foldersExpanded ? "chevron.up" : "chevron.down")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(.tertiary)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L10n.string("Folders"))
                .accessibilityValue(foldersExpanded ? L10n.string("Shown") : L10n.string("Hidden"))

                if foldersExpanded || store.canNavigateUpFolder {
                    VStack(spacing: 10) {
                    if store.memoryNavigationLoading {
                        ForEach(0..<2, id: \.self) { _ in
                            FolderRowSkeleton(pulse: skeletonPulse)
                        }
                    } else {
                        ForEach(store.visibleFolders) { folder in
                            Button {
                                store.openFolder(folder)
                            } label: {
                                FolderRow(symbolName: "folder", title: folder.displayName, subtitle: folder.path)
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("folder-row-\(folder.id)")
                        }
                    }
                    }
                }
            }
        }
    }
}

private struct FolderRow: View {
    let symbolName: String
    let title: String
    let subtitle: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbolName)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(VeyqColor.info)
                .frame(width: 38, height: 38)
                .background(VeyqColor.info.opacity(0.12), in: RoundedRectangle(cornerRadius: 8, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer()

            Image(systemName: "chevron.right")
                .font(.caption.weight(.bold))
                .foregroundStyle(.tertiary)
        }
        .padding(12)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                .stroke(VeyqColor.hairline)
        }
        .contentShape(Rectangle())
    }
}

struct FolderAuthoringSheet: View {
    @EnvironmentObject private var store: VeyqStore
    @Environment(\.dismiss) private var dismiss
    @State private var displayName = ""
    @State private var name = ""
    @State private var nameEdited = false
    @State private var saving = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(L10n.string("Name"), text: $displayName)
                        .textInputAutocapitalization(.words)
                        .autocorrectionDisabled(false)
                        .accessibilityIdentifier("folder-title-field")

                    TextField(L10n.string("Path Name"), text: $name)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("folder-name-field")
                } header: {
                    Text("Folder")
                } footer: {
                    Text(L10n.format("Creates an organizational folder in %@.", store.currentFolderPath))
                }

                if let errorMessage {
                    Section {
                        Text(errorMessage)
                            .font(.caption)
                            .foregroundStyle(VeyqColor.danger)
                    }
                }
            }
            .navigationTitle("Create Folder")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                    .disabled(saving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(saving ? L10n.string("Saving...") : L10n.string("Create")) {
                        Task { await save() }
                    }
                    .disabled(!canSave || saving)
                    .accessibilityIdentifier("folder-save-button")
                }
            }
            .onChange(of: displayName) { _, value in
                guard !nameEdited else { return }
                name = MemoryNameFormatter.slug(from: value)
            }
            .onChange(of: name) { _, value in
                nameEdited = true
                let sanitized = MemoryNameFormatter.slug(from: value)
                if sanitized != value {
                    name = sanitized
                }
            }
        }
        .presentationDetents([.medium])
    }

    private var canSave: Bool {
        !displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    @MainActor
    private func save() async {
        guard canSave else { return }
        saving = true
        errorMessage = nil
        defer { saving = false }

        do {
            _ = try await store.createFolder(displayName: displayName, name: name)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private struct GroupSpaceCard: View {
    let space: VeyqSpace
    let isSelected: Bool

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(isSelected ? VeyqColor.accent : VeyqColor.accent.opacity(0.12))
                Text(groupInitial)
                    .font(.headline.weight(.bold))
                    .foregroundStyle(isSelected ? VeyqColor.onStrong : VeyqColor.accent)
            }
            .frame(width: 42, height: 42)
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 4) {
                Text(displayLabel)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Text("Group")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            if isSelected {
                Image(systemName: "checkmark.circle.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(VeyqColor.accent)
                    .accessibilityHidden(true)
            }
        }
        .frame(width: 188, alignment: .leading)
        .padding(12)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                .stroke(isSelected ? VeyqColor.accent.opacity(0.42) : VeyqColor.hairline)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(displayLabel)
        .accessibilityValue(isSelected ? L10n.string("Selected") : L10n.string("Group"))
    }

    private var displayLabel: String {
        space.label.hasPrefix("@") ? String(space.label.dropFirst()) : space.label
    }

    private var groupInitial: String {
        displayLabel.trimmingCharacters(in: .whitespacesAndNewlines).first.map { String($0).uppercased() } ?? "@"
    }
}

private struct NewGroupCard: View {
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "plus")
                .font(.headline.weight(.bold))
                .foregroundStyle(VeyqColor.onStrong)
                .frame(width: 42, height: 42)
                .background(VeyqColor.accent, in: Circle())
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 4) {
                Text("Create Group")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Text("New shared space")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer()
        }
        .frame(width: 188, alignment: .leading)
        .padding(12)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                .stroke(VeyqColor.accent.opacity(0.32), style: StrokeStyle(lineWidth: 1, dash: [5, 4]))
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(L10n.string("Create Group"))
    }
}

struct GroupAuthoringSheet: View {
    @EnvironmentObject private var store: VeyqStore
    @Environment(\.dismiss) private var dismiss
    @State private var displayName = ""
    @State private var name = ""
    @State private var nameEdited = false
    @State private var saving = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(L10n.string("Name"), text: $displayName)
                        .textInputAutocapitalization(.words)
                        .autocorrectionDisabled(false)
                        .accessibilityIdentifier("group-title-field")

                    TextField(L10n.string("Path Name"), text: $name)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("group-name-field")
                } header: {
                    Text("Group")
                } footer: {
                    Text("Groups create shared Memory spaces inside the current tenant.")
                }

                if let errorMessage {
                    Section {
                        Text(errorMessage)
                            .font(.caption)
                            .foregroundStyle(VeyqColor.danger)
                    }
                }
            }
            .navigationTitle("Create Group")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                    .disabled(saving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(saving ? L10n.string("Saving...") : L10n.string("Create")) {
                        Task { await save() }
                    }
                    .disabled(!canSave || saving)
                    .accessibilityIdentifier("group-save-button")
                }
            }
            .onChange(of: displayName) { _, value in
                guard !nameEdited else { return }
                name = MemoryNameFormatter.slug(from: value)
            }
            .onChange(of: name) { _, value in
                nameEdited = true
                let sanitized = MemoryNameFormatter.slug(from: value)
                if sanitized != value {
                    name = sanitized
                }
            }
        }
        .presentationDetents([.medium])
    }

    private var canSave: Bool {
        !displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    @MainActor
    private func save() async {
        guard canSave else { return }
        saving = true
        errorMessage = nil
        defer { saving = false }

        do {
            let groupName = name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? MemoryNameFormatter.slug(from: displayName)
                : name
            _ = try await store.createGroup(displayName: displayName, name: groupName)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

struct SpaceSwitcherSheet: View {
    @EnvironmentObject private var store: VeyqStore
    @Environment(\.dismiss) private var dismiss

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

}

private enum ContextDestination: Hashable {
    case scope
    case tenant
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

struct SelectionOptionRow: View {
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

private struct FolderRowSkeleton: View {
    let pulse: Bool

    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(VeyqColor.info.opacity(0.12))
                .frame(width: 38, height: 38)

            VStack(alignment: .leading, spacing: 8) {
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .fill(.secondary.opacity(0.22))
                    .frame(width: 126, height: 14)
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .fill(.secondary.opacity(0.14))
                    .frame(width: 86, height: 10)
            }

            Spacer()

            RoundedRectangle(cornerRadius: 4, style: .continuous)
                .fill(.secondary.opacity(0.12))
                .frame(width: 8, height: 18)
        }
        .padding(12)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                .stroke(VeyqColor.hairline)
        }
        .opacity(pulse ? 0.58 : 1.0)
        .accessibilityHidden(true)
    }
}

private struct MemoryCardSkeleton: View {
    let pulse: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack(alignment: .bottomLeading) {
                RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [
                                VeyqColor.accent.opacity(0.20),
                                VeyqColor.info.opacity(0.12),
                                VeyqColor.rose.opacity(0.14)
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )

                VStack(alignment: .leading, spacing: 8) {
                    RoundedRectangle(cornerRadius: 5, style: .continuous)
                        .fill(VeyqColor.onStrong.opacity(0.35))
                        .frame(width: 220, height: 22)
                    RoundedRectangle(cornerRadius: 4, style: .continuous)
                        .fill(VeyqColor.onStrong.opacity(0.24))
                        .frame(width: 138, height: 12)
                }
                .padding(14)
            }
            .frame(height: 168)
            .clipShape(RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))

            HStack(spacing: 8) {
                Capsule()
                    .fill(VeyqColor.success.opacity(0.20))
                    .frame(width: 96, height: 28)
                Capsule()
                    .fill(VeyqColor.info.opacity(0.18))
                    .frame(width: 104, height: 28)

                Spacer()

                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .fill(.secondary.opacity(0.12))
                    .frame(width: 8, height: 18)
            }
            .padding(12)
        }
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                .stroke(VeyqColor.hairline)
        }
        .shadow(color: VeyqColor.cardShadow, radius: 14, x: 0, y: 8)
        .opacity(pulse ? 0.58 : 1.0)
        .accessibilityHidden(true)
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
        MemoryPlaceholderCoverSurface()
    }
}

struct MemoryPlaceholderCoverSurface: View {
    var body: some View {
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
