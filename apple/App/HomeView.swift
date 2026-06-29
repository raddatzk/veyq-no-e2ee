import SwiftUI
import VeyqKit

/// The Memory shell (cr-0034) — the Apple parity of the web browse shell, reusing the first iOS layout
/// (`legacy/iOS/Veyq/Home/MemoryHomeView.swift`) over the E2EE `VeyqClient` data layer. **Responsive universal
/// app:** a `NavigationSplitView` (spaces sidebar + content) shows columns on regular width (iPad landscape,
/// macOS) and collapses to a stack with a drawer on compact (iPhone). Operator-blind: names are decrypted on
/// device; the level + breadcrumb are derived client-side ([HomeModel]). Favorites / settings / the space
/// switcher are later Apple slices.
struct HomeView: View {
    @EnvironmentObject var app: AppModel
    @StateObject private var model = HomeModel()

    @State private var showCreate = false
    @State private var createIsFolder = false
    @State private var newName = ""
    @State private var renameTarget: VeyqClient.TreeNode?
    @State private var renameName = ""
    @State private var deleteTarget: VeyqClient.TreeNode?
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
        .alert(createIsFolder ? "New folder" : "New Memory", isPresented: $showCreate) {
            TextField("Name", text: $newName)
            Button("Create") {
                let n = newName
                Task { createIsFolder ? await model.createFolder(n) : await model.createMemory(n) }
            }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Rename", isPresented: Binding(get: { renameTarget != nil }, set: { if !$0 { renameTarget = nil } })) {
            TextField("Name", text: $renameName)
            Button("Rename") {
                if let t = renameTarget { let n = renameName; renameTarget = nil; Task { await model.rename(t, to: n) } }
            }
            Button("Cancel", role: .cancel) { renameTarget = nil }
        }
        .confirmationDialog(
            "Delete “\(deleteTarget?.name ?? "")”? This can’t be undone.",
            isPresented: Binding(get: { deleteTarget != nil }, set: { if !$0 { deleteTarget = nil } }),
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) {
                if let t = deleteTarget { deleteTarget = nil; Task { await model.delete(t) } }
            }
            Button("Cancel", role: .cancel) { deleteTarget = nil }
        }
    }

    // MARK: sidebar (the responsive space rail; collapses to a drawer on compact)

    private var sidebar: some View {
        List {
            Section("Spaces") {
                Label("Personal", systemImage: "person.crop.circle.fill")
                    .foregroundStyle(VeyqStyle.text)
            }
        }
        .navigationTitle("veyq")
        .safeAreaInset(edge: .bottom) { profileBar }
    }

    private var profileBar: some View {
        VStack(spacing: 0) {
            Divider()
            HStack(spacing: 10) {
                Image(systemName: "person.crop.circle").foregroundStyle(VeyqStyle.muted)
                Text(app.email).font(.system(size: 13)).foregroundStyle(VeyqStyle.muted).lineLimit(1)
                Spacer()
                Button { Task { await app.signOut() } } label: {
                    Image(systemName: "rectangle.portrait.and.arrow.right")
                }
                .buttonStyle(.plain).foregroundStyle(VeyqStyle.muted)
                .accessibilityIdentifier("Sign out")
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
        }
    }

    // MARK: detail (the current level)

    private var browse: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if let e = model.error { MessageBox(text: e) }
                if model.loading { ProgressView().padding(.vertical, 8) }
                if model.crumbs.count > 1 { breadcrumb }

                sectionHeader("Memories", model.level.memories.count)
                if model.level.memories.isEmpty {
                    emptyText("No Memories here yet.")
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 160), spacing: 12)], spacing: 12) {
                        ForEach(model.level.memories, id: \.id) { memoryCard($0) }
                    }
                }

                sectionHeader("Folders", model.level.folders.count)
                if model.level.folders.isEmpty {
                    emptyText("No folders here yet.")
                } else {
                    ForEach(model.level.folders, id: \.id) { folderRow($0) }
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(VeyqStyle.bg)
        .navigationTitle(model.crumbs.last?.name ?? "Personal")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button("New Memory") { createIsFolder = false; newName = ""; showCreate = true }
                    Button("New folder") { createIsFolder = true; newName = ""; showCreate = true }
                } label: { Image(systemName: "plus") }
                .accessibilityIdentifier("Create")
            }
        }
    }

    private var breadcrumb: some View {
        HStack(spacing: 4) {
            ForEach(Array(model.crumbs.enumerated()), id: \.element.id) { idx, crumb in
                if idx > 0 { Text("/").foregroundStyle(VeyqStyle.muted) }
                Button(crumb.name) { model.goto(crumb) }
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
        Button("Rename") { renameTarget = node; renameName = node.name }
        Button("Delete", role: .destructive) { deleteTarget = node }
    }
}
