import SwiftUI
import VeyqKit

/// The Memory shell (cr-0034/0035) — the Apple parity of the web browse shell, reusing the first iOS layout
/// (`legacy/iOS/Veyq/Home/MemoryHomeView.swift`) over the E2EE `VeyqClient` data layer. **Responsive universal
/// app:** a `NavigationSplitView` (spaces sidebar + content) shows columns on regular width (iPad landscape,
/// macOS) and collapses to a stack with a drawer on compact (iPhone). Operator-blind: names are decrypted on
/// device; the level + breadcrumb are derived client-side ([HomeModel]). Strings go through the shared `L10n`
/// (cr-0035); the profile bar opens Settings (theme + language + sign-out). Favorites / the space switcher are
/// later Apple slices.
struct HomeView: View {
    @EnvironmentObject var app: AppModel
    @EnvironmentObject var l10n: L10n
    @StateObject private var model = HomeModel()

    @State private var showCreate = false
    @State private var createIsFolder = false
    @State private var newName = ""
    @State private var renameTarget: VeyqClient.TreeNode?
    @State private var renameName = ""
    @State private var deleteTarget: VeyqClient.TreeNode?
    @State private var showSettings = false
    /// On compact width (iPhone) open straight to the browse (the content), with the spaces sidebar as a
    /// drawer — on regular width (iPad / macOS) both columns show side by side.
    @State private var preferredColumn: NavigationSplitViewColumn = .detail

    var body: some View {
        NavigationSplitView(preferredCompactColumn: $preferredColumn) {
            sidebar
        } detail: {
            browse
        }
        .tint(VeyqStyle.accent)
        .task { await model.start(client: app.client) }
        .sheet(isPresented: $showSettings) { SettingsView() }
        .alert(createIsFolder ? l10n.t("home.createFolder") : l10n.t("home.createMemory"), isPresented: $showCreate) {
            TextField(l10n.t("common.name"), text: $newName)
            Button(l10n.t("common.create")) {
                let n = newName
                Task { createIsFolder ? await model.createFolder(n) : await model.createMemory(n) }
            }
            Button(l10n.t("common.cancel"), role: .cancel) {}
        }
        .alert(l10n.t("common.rename"), isPresented: Binding(get: { renameTarget != nil }, set: { if !$0 { renameTarget = nil } })) {
            TextField(l10n.t("common.name"), text: $renameName)
            Button(l10n.t("common.rename")) {
                if let t = renameTarget { let n = renameName; renameTarget = nil; Task { await model.rename(t, to: n) } }
            }
            Button(l10n.t("common.cancel"), role: .cancel) { renameTarget = nil }
        }
        .confirmationDialog(
            l10n.t("memory.deleteConfirm", deleteTarget?.name ?? ""),
            isPresented: Binding(get: { deleteTarget != nil }, set: { if !$0 { deleteTarget = nil } }),
            titleVisibility: .visible
        ) {
            Button(l10n.t("common.delete"), role: .destructive) {
                if let t = deleteTarget { deleteTarget = nil; Task { await model.delete(t) } }
            }
            Button(l10n.t("common.cancel"), role: .cancel) { deleteTarget = nil }
        }
    }

    // MARK: sidebar (the responsive space rail; collapses to a drawer on compact)

    private var sidebar: some View {
        List {
            Section(l10n.t("home.spaces")) {
                Label(l10n.t("home.personal"), systemImage: "person.crop.circle.fill")
                    .foregroundStyle(VeyqStyle.text)
            }
        }
        .navigationTitle("veyq")
        .safeAreaInset(edge: .bottom) { profileBar }
    }

    /// The profile bar — like the web ProfileMenu, tapping it opens Settings (theme / language / sign-out).
    /// When this device is trusted it also carries the trusted-status caption + "Stop trusting this device"
    /// control (restored in cr-0042 — it vanished when cr-0034 swapped the unlocked landing for the shell;
    /// the Apple twin of Android's spaces-rail untrust). On compact the sidebar is a drawer, so the control
    /// surfaces only once the drawer is opened.
    private var profileBar: some View {
        VStack(spacing: 0) {
            if app.deviceTrusted { trustedBar }
            Divider()
            Button { showSettings = true } label: {
                HStack(spacing: 10) {
                    Image(systemName: "person.crop.circle").foregroundStyle(VeyqStyle.muted)
                    Text(app.email).font(.system(size: 13)).foregroundStyle(VeyqStyle.muted).lineLimit(1)
                    Spacer()
                    Image(systemName: "gearshape").foregroundStyle(VeyqStyle.muted)
                }
                .padding(.horizontal, 14).padding(.vertical, 10)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("Settings")
        }
    }

    /// Trusted-state caption + the untrust control (shown only when `app.deviceTrusted`). Untrusting drops the
    /// device's convenience level back to "Secret Key each time" via `app.untrust()`; the caption then vanishes.
    private var trustedBar: some View {
        VStack(alignment: .leading, spacing: 4) {
            Divider()
            Text(l10n.t("home.deviceTrusted"))
                .font(.system(size: 12)).foregroundStyle(VeyqStyle.muted)
                .fixedSize(horizontal: false, vertical: true)
            Button(l10n.t("home.stopTrusting"), role: .destructive) {
                Task { await app.untrust() }
            }
            .font(.system(size: 13)).foregroundStyle(VeyqStyle.danger)
            .buttonStyle(.plain)
            .accessibilityIdentifier("Stop trusting this device")
        }
        .padding(.horizontal, 14).padding(.top, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: detail (the current level)

    private var browse: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if let e = model.error { MessageBox(text: e) }
                if model.loading { ProgressView().padding(.vertical, 8) }
                if model.crumbs.count > 1 { breadcrumb }

                sectionHeader(l10n.t("home.memories"), model.level.memories.count)
                if model.level.memories.isEmpty {
                    emptyText(l10n.t("home.noMemories"))
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 160), spacing: 12)], spacing: 12) {
                        ForEach(model.level.memories, id: \.id) { memoryCard($0) }
                    }
                }

                sectionHeader(l10n.t("home.folders"), model.level.folders.count)
                if model.level.folders.isEmpty {
                    emptyText(l10n.t("home.noFolders"))
                } else {
                    ForEach(model.level.folders, id: \.id) { folderRow($0) }
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(VeyqStyle.bg)
        .navigationTitle(model.path.isEmpty ? l10n.t("home.personal") : (model.path.last?.name ?? ""))
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button(l10n.t("home.createMemory")) { createIsFolder = false; newName = ""; showCreate = true }
                    Button(l10n.t("home.createFolder")) { createIsFolder = true; newName = ""; showCreate = true }
                } label: { Image(systemName: "plus") }
                .accessibilityIdentifier("Create")
            }
        }
    }

    private var breadcrumb: some View {
        HStack(spacing: 4) {
            ForEach(Array(model.crumbs.enumerated()), id: \.element.id) { idx, crumb in
                if idx > 0 { Text("/").foregroundStyle(VeyqStyle.muted) }
                Button(crumb.nodeId == nil ? l10n.t("home.personal") : crumb.name) { model.goto(crumb) }
                    .buttonStyle(.plain)
                    .font(.system(size: 14, weight: idx == model.crumbs.count - 1 ? .semibold : .regular))
                    .foregroundStyle(idx == model.crumbs.count - 1 ? VeyqStyle.text : VeyqStyle.muted)
            }
        }
    }

    private func sectionHeader(_ title: String, _ count: Int) -> some View {
        HStack(spacing: 6) {
            Text(title).font(.system(size: 17, weight: .bold)).foregroundStyle(VeyqStyle.text)
            Text("\(count)").font(.system(size: 14)).foregroundStyle(VeyqStyle.muted)
        }
    }

    private func emptyText(_ text: String) -> some View {
        Text(text).font(.system(size: 14)).foregroundStyle(VeyqStyle.muted)
    }

    private func memoryCard(_ m: VeyqClient.TreeNode) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack {
                Rectangle().fill(VeyqStyle.surfaceMuted)
                Image(systemName: "photo.on.rectangle").font(.system(size: 22)).foregroundStyle(VeyqStyle.accent)
            }
            .frame(height: 110).clipped()
            Text(m.name).font(.system(size: 14, weight: .semibold)).foregroundStyle(VeyqStyle.text)
                .lineLimit(1).padding(8).frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(VeyqStyle.surface)
        .clipShape(RoundedRectangle(cornerRadius: VeyqStyle.radius))
        .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radius).strokeBorder(VeyqStyle.line))
        .contextMenu { nodeMenu(m) }
    }

    private func folderRow(_ f: VeyqClient.TreeNode) -> some View {
        Button { model.openFolder(f) } label: {
            HStack(spacing: 10) {
                Image(systemName: "folder.fill").foregroundStyle(VeyqStyle.accent)
                Text(f.name).font(.system(size: 15, weight: .semibold)).foregroundStyle(VeyqStyle.text)
                Spacer()
                Image(systemName: "chevron.right").font(.system(size: 12)).foregroundStyle(VeyqStyle.muted)
            }
            .padding(12)
            .background(VeyqStyle.surface)
            .clipShape(RoundedRectangle(cornerRadius: VeyqStyle.radius))
            .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radius).strokeBorder(VeyqStyle.line))
        }
        .buttonStyle(.plain)
        .contextMenu { nodeMenu(f) }
    }

    @ViewBuilder private func nodeMenu(_ node: VeyqClient.TreeNode) -> some View {
        Button(l10n.t("common.rename")) { renameTarget = node; renameName = node.name }
        Button(l10n.t("common.delete"), role: .destructive) { deleteTarget = node }
    }
}
