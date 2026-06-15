import SwiftUI
import VeyqCrypto

/// MemoryHome: the active space's memories (cover cards) + folders (navigable), with a space switcher and
/// breadcrumb. The memory detail + justified gallery arrive in slice 3.
struct MemoryHomeView: View {
    @EnvironmentObject var app: AppState
    @Environment(\.horizontalSizeClass) private var hSize
    @StateObject private var store: HomeStore
    @State private var showSettings = false
    @State private var showCreate = false
    @State private var createType = "MEMORY"
    @State private var newName = ""
    @State private var showRename = false
    @State private var renameEntry: HomeStore.Entry?
    @State private var renameName = ""
    @State private var moveEntry: HomeStore.Entry?
    @State private var showJoinGroup = false

    init(apiBase: String, session: Login.UnlockedSession) {
        _store = StateObject(wrappedValue: HomeStore(apiBase: apiBase, session: session))
    }

    var body: some View {
        NavigationStack { content }
        .tint(VeyqStyle.accent)
        .task { await store.start() }
        .sheet(isPresented: $showSettings) { SettingsView().environmentObject(app) }
        .sheet(isPresented: $showJoinGroup) { JoinGroupView(store: store) }
        .alert(createType == "FOLDER" ? T("home.createFolder") : T("home.createMemory"), isPresented: $showCreate) {
            TextField(T("common.name"), text: $newName)
            Button(T("common.create")) { let n = newName; Task { await store.create(type: createType, name: n) } }
            Button(T("common.cancel"), role: .cancel) {}
        }
        .alert(T("common.rename"), isPresented: $showRename) {
            TextField(T("common.name"), text: $renameName)
            Button(T("common.rename")) { if let e = renameEntry { let n = renameName; Task { await store.rename(e, name: n) } } }
            Button(T("common.cancel"), role: .cancel) {}
        }
        .sheet(item: $moveEntry) { entry in MovePickerView(store: store, entry: entry) }
    }

    private var content: some View {
        scroll
            .toolbar { toolbarContent }
            .toolbarBackground(VeyqStyle.surface, for: .navigationBar)
            .searchable(text: $store.searchText, prompt: T("home.search"))
            .onChange(of: store.searchText) { _, _ in Task { await store.search() } }
    }

    private var scroll: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if let error = store.error { MessageBox(text: error) }
                if !store.searchText.isEmpty {
                    searchResults
                } else {
                    browseContent
                }
            }
            .padding(16)
            .readableWidth()
        }
        .background(VeyqStyle.bg)
        .navigationTitle(store.crumbs.last?.name ?? "veyq")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ToolbarContentBuilder private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Menu {
                ForEach(store.spaces, id: \.scopeId) { space in
                    Button(space.displayName) { Task { await store.switchSpace(space) } }
                }
                Divider()
                Button { showJoinGroup = true } label: { Label(T("groups.join"), systemImage: "person.badge.plus") }
            } label: { Image(systemName: "square.grid.2x2") }
        }
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                Button(T("home.createMemory")) { createType = "MEMORY"; newName = ""; showCreate = true }
                Button(T("home.createFolder")) { createType = "FOLDER"; newName = ""; showCreate = true }
            } label: { Image(systemName: "plus") }
        }
        ToolbarItem(placement: .topBarTrailing) {
            Button { showSettings = true } label: { Image(systemName: "gearshape") }
        }
    }

    @ViewBuilder private var searchResults: some View {
        if store.searchHits.isEmpty {
            emptyText(T("search.nothing"))
        } else {
            ForEach(store.searchHits) { searchRow($0) }
        }
    }

    @ViewBuilder private var browseContent: some View {
        if store.crumbs.count > 1 { breadcrumb }
        sectionHeader(T("home.memories"), store.memories.count)
        memoriesSection
        sectionHeader(T("home.folders"), store.folders.count)
        foldersSection
    }

    @ViewBuilder private var memoriesSection: some View {
        if store.memories.isEmpty {
            emptyText(T("home.noMemories"))
        } else {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 168), spacing: 12)], spacing: 12) {
                ForEach(store.memories) { memoryCard($0) }
            }
        }
    }

    @ViewBuilder private var foldersSection: some View {
        if store.folders.isEmpty {
            emptyText(T("home.noFolders"))
        } else if hSize == .regular {
            // On iPad/Mac, lay folders out in columns instead of full-width rows.
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 280), spacing: 12)], spacing: 12) {
                ForEach(store.folders) { folderRow($0) }
            }
        } else {
            ForEach(store.folders) { folderRow($0) }
        }
    }

    @ViewBuilder private func cardMenu(_ entry: HomeStore.Entry) -> some View {
        Button(T("common.rename")) { renameEntry = entry; renameName = entry.name; showRename = true }
        Button(T("common.move")) { moveEntry = entry }
    }

    @ViewBuilder private func searchRow(_ hit: HomeStore.Entry) -> some View {
        if hit.node.type == "MEMORY", let scope = store.scope, let key = store.contentKey {
            NavigationLink {
                MemoryDetailView(apiBase: store.apiBase, session: store.session, scopeId: scope.scopeId, contentKey: key, node: hit.node, name: hit.name)
            } label: { searchRowLabel(hit, icon: "photo", type: T("search.memory")) }
            .buttonStyle(.plain)
        } else {
            Button {
                store.searchText = ""
                Task { await store.openFolder(hit) }
            } label: { searchRowLabel(hit, icon: "folder.fill", type: T("search.folder")) }
            .buttonStyle(.plain)
        }
    }

    private func searchRowLabel(_ hit: HomeStore.Entry, icon: String, type: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon).foregroundStyle(VeyqStyle.accent)
            Text(hit.name).font(.system(size: 15, weight: .semibold)).foregroundStyle(VeyqStyle.text)
            Spacer()
            Text(type).font(.caption).foregroundStyle(VeyqStyle.accent)
        }
        .padding(12)
        .background(VeyqStyle.surface)
        .clipShape(RoundedRectangle(cornerRadius: VeyqStyle.radius))
        .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radius).strokeBorder(VeyqStyle.line))
    }

    private var breadcrumb: some View {
        HStack(spacing: 4) {
            ForEach(Array(store.crumbs.enumerated()), id: \.element.id) { idx, crumb in
                if idx > 0 { Text("/").foregroundStyle(VeyqStyle.muted) }
                Button(crumb.name) { Task { await store.gotoCrumb(crumb) } }
                    .font(.system(size: 14, weight: idx == store.crumbs.count - 1 ? .semibold : .regular))
                    .foregroundStyle(idx == store.crumbs.count - 1 ? VeyqStyle.text : VeyqStyle.muted)
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

    private func memoryCard(_ m: HomeStore.Entry) -> some View {
        NavigationLink {
            if let scope = store.scope, let key = store.contentKey {
                MemoryDetailView(apiBase: store.apiBase, session: store.session, scopeId: scope.scopeId, contentKey: key, node: m.node, name: m.name)
            }
        } label: {
            VStack(alignment: .leading, spacing: 0) {
                ZStack {
                    Rectangle().fill(VeyqStyle.surfaceMuted)
                    if let img = store.covers[m.node.id] {
                        Image(uiImage: img).resizable().scaledToFill()
                    } else {
                        Image(systemName: "photo").foregroundStyle(VeyqStyle.muted)
                    }
                }
                .frame(height: 110).clipped()
                Text(m.name).font(.system(size: 14, weight: .semibold)).foregroundStyle(VeyqStyle.text)
                    .lineLimit(1).padding(8).frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(VeyqStyle.surface)
            .clipShape(RoundedRectangle(cornerRadius: VeyqStyle.radius))
            .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radius).strokeBorder(VeyqStyle.line))
        }
        .buttonStyle(.plain)
        .contextMenu { cardMenu(m) }
    }

    private func folderRow(_ f: HomeStore.Entry) -> some View {
        Button { Task { await store.openFolder(f) } } label: {
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
        .contextMenu { cardMenu(f) }
    }
}
