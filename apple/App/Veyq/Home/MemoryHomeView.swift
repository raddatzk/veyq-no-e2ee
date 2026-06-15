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
    @State private var showCreateTenant = false
    @State private var newTenantName = ""
    @State private var newTenantDomain = ""
    @State private var showCreateGroup = false
    @State private var newGroupName = ""
    @State private var newGroupTenant = ""
    @State private var manageGroup: SwitchActiveSpace.SpaceEntry?
    @State private var deleteNodeTarget: HomeStore.Entry?
    @State private var deleteTenantTarget: SwitchActiveSpace.SpaceEntry?
    @State private var inviteTenant: SwitchActiveSpace.SpaceEntry?
    @State private var deepMemory: HomeStore.Entry?

    init(apiBase: String, appBaseUrl: String, session: Login.UnlockedSession) {
        _store = StateObject(wrappedValue: HomeStore(apiBase: apiBase, appBaseUrl: appBaseUrl, session: session))
    }

    var body: some View {
        Group {
            // Wide (iPad/Mac): the web layout — a persistent Spaces sidebar + content pane. Narrow
            // (iPhone / split window): the compact iOS stack, with spaces in the toolbar. Same store.
            if hSize == .regular {
                regularLayout
            } else {
                NavigationStack { content }
            }
        }
        .tint(VeyqStyle.accent)
        .task { await store.start(); await consumePendingOpen() }
        .onChange(of: app.pendingOpenPath) { _, _ in Task { await consumePendingOpen() } }
        .sheet(item: $deepMemory) { entry in
            if let scope = store.scope, let key = store.contentKey {
                NavigationStack {
                    MemoryDetailView(apiBase: store.apiBase, session: store.session, scopeId: scope.scopeId, contentKey: key, node: entry.node, name: entry.name)
                        .toolbar { ToolbarItem(placement: .topBarTrailing) { Button(T("common.close")) { deepMemory = nil } } }
                }
            }
        }
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
        .alert(T("tenants.create"), isPresented: $showCreateTenant) {
            TextField(T("common.name"), text: $newTenantName)
            TextField(T("tenants.domain"), text: $newTenantDomain).textInputAutocapitalization(.never).autocorrectionDisabled()
            Button(T("common.create")) { let n = newTenantName; let d = newTenantDomain; newTenantName = ""; newTenantDomain = ""; Task { _ = await store.createGroup(displayName: n, domain: d) } }
            Button(T("common.cancel"), role: .cancel) { newTenantName = ""; newTenantDomain = "" }
        } message: { Text(T("tenants.domainHint")) }
        .sheet(isPresented: $showCreateGroup) { createGroupSheet }
        .sheet(item: $manageGroup) { g in GroupMembersView(store: store, space: g, onDeleted: {}) }
        .sheet(item: $deleteNodeTarget) { entry in
            ConfirmDeleteView(name: entry.name) { Task { await store.deleteNode(entry.node.id) } }
        }
        .sheet(item: $deleteTenantTarget) { t in
            ConfirmDeleteView(name: store.displayName(for: t)) { Task { await store.deleteTenant(t) } }
        }
        .sheet(item: $inviteTenant) { t in InviteMemberView(store: store, space: t) }
    }

    /// Consume a pending "open in app" deep-link path: navigate the space + folder back-stack, then present
    /// the trailing memory (if any) so its back/close lands on the right folder.
    private func consumePendingOpen() async {
        guard let path = app.pendingOpenPath else { return }
        app.pendingOpenPath = nil
        deepMemory = await store.openPath(path)
    }

    private var createGroupSheet: some View {
        NavigationStack {
            Form {
                if tenantSpaces.count > 1 {
                    Picker(T("groups.inTenant"), selection: $newGroupTenant) {
                        ForEach(tenantSpaces, id: \.scopeId) { Text(store.displayName(for: $0)).tag($0.tenantId ?? "") }
                    }
                }
                TextField(T("groups.name"), text: $newGroupName)
            }
            .navigationTitle(T("groups.createTitle"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(T("common.cancel")) { showCreateGroup = false } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(T("common.create")) {
                        let tid = newGroupTenant.isEmpty ? tenantSpaces.first?.tenantId : newGroupTenant
                        let n = newGroupName; newGroupName = ""; showCreateGroup = false
                        if let tid { Task { _ = await store.createGroupSpace(tenantId: tid, name: n) } }
                    }.disabled(newGroupName.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
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
                if let entry = store.activeEntry, let deadline = entry.scheduledDeletionAt {
                    deletionBanner(entry: entry, deadline: deadline)
                }
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

    /// A warning that the active tenant is scheduled for deletion, with an owner-only cancel action.
    @ViewBuilder private func deletionBanner(entry: SwitchActiveSpace.SpaceEntry, deadline: String) -> some View {
        HStack(spacing: 12) {
            Text(T("delete.scheduledBanner", formatDeletionDate(deadline)))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(VeyqStyle.danger)
                .frame(maxWidth: .infinity, alignment: .leading)
            if entry.role == "Owner" {
                Button(T("delete.cancelDeletion")) { Task { await store.cancelTenantDeletion(entry) } }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(VeyqStyle.danger)
            }
        }
        .padding(12)
        .background(VeyqStyle.dangerSoft, in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(VeyqStyle.dangerLine, lineWidth: 1))
    }

    private func formatDeletionDate(_ iso: String) -> String {
        let parser = ISO8601DateFormatter()
        parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let date = parser.date(from: iso) ?? ISO8601DateFormatter().date(from: iso)
        guard let date else { return iso }
        let out = DateFormatter()
        out.dateStyle = .medium
        return out.string(from: date)
    }

    @ToolbarContentBuilder private var toolbarContent: some ToolbarContent {
        // Spaces + settings live in the sidebar on regular width, so only show them in the toolbar on compact.
        if hSize != .regular {
            ToolbarItem(placement: .topBarLeading) {
                Menu {
                    ForEach(store.spaces, id: \.scopeId) { space in
                        Button(store.displayName(for: space)) { Task { await store.switchSpace(space) } }
                    }
                    Divider()
                    Button { showCreateTenant = true } label: { Label(T("tenants.create"), systemImage: "building.2") }
                    Button { newGroupTenant = ""; showCreateGroup = true } label: { Label(T("groups.create"), systemImage: "person.2.badge.gearshape") }
                    Button { showJoinGroup = true } label: { Label(T("groups.join"), systemImage: "person.badge.plus") }
                } label: { Image(systemName: "square.grid.2x2") }
            }
        }
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                Button(T("home.createMemory")) { createType = "MEMORY"; newName = ""; showCreate = true }
                Button(T("home.createFolder")) { createType = "FOLDER"; newName = ""; showCreate = true }
            } label: { Image(systemName: "plus") }
        }
        if hSize != .regular {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showSettings = true } label: { Image(systemName: "gearshape") }
            }
        }
    }

    // MARK: - Regular-width (iPad/Mac) web-style layout

    private var regularLayout: some View {
        NavigationSplitView {
            sidebar.navigationSplitViewColumnWidth(min: 220, ideal: 264, max: 320)
        } detail: {
            NavigationStack { content }
        }
        .navigationSplitViewStyle(.balanced)
    }

    private var sidebar: some View {
        List {
            Section(T("home.spaces")) {
                ForEach(personalSpaces, id: \.scopeId) { spaceRow($0) }
            }
            Section(T("home.tenants")) {
                ForEach(tenantSpaces, id: \.scopeId) { spaceRow($0) }
                Button { showCreateTenant = true } label: {
                    Label(T("tenants.create"), systemImage: "building.2").foregroundStyle(VeyqStyle.accent)
                }
                Button { showJoinGroup = true } label: {
                    Label(T("groups.join"), systemImage: "person.badge.plus").foregroundStyle(VeyqStyle.accent)
                }
            }
            Section(T("home.groups")) {
                ForEach(groupSpaces, id: \.scopeId) { spaceRow($0) }
                if !tenantSpaces.isEmpty {
                    Button { newGroupTenant = ""; showCreateGroup = true } label: {
                        Label(T("groups.create"), systemImage: "plus.circle").foregroundStyle(VeyqStyle.accent)
                    }
                }
            }
        }
        .listStyle(.sidebar)
        .navigationTitle("veyq")
        .safeAreaInset(edge: .bottom) {
            Button { showSettings = true } label: {
                Label(T("home.settings"), systemImage: "gearshape")
                    .font(.system(size: 14, weight: .semibold))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
            }
            .tint(VeyqStyle.text)
            .background(VeyqStyle.surface)
        }
    }

    private var personalSpaces: [SwitchActiveSpace.SpaceEntry] { store.spaces.filter { $0.kind == "PERSONAL" } }
    private var tenantSpaces: [SwitchActiveSpace.SpaceEntry] { store.spaces.filter { $0.kind == "TENANT" } }
    private var groupSpaces: [SwitchActiveSpace.SpaceEntry] { store.spaces.filter { $0.kind == "GROUP" } }

    private func spaceSub(_ s: SwitchActiveSpace.SpaceEntry) -> String {
        s.kind == "PERSONAL" ? T("home.personal") : (s.role ?? T("home.group"))
    }

    private func spaceRow(_ s: SwitchActiveSpace.SpaceEntry) -> some View {
        let name = store.displayName(for: s)
        return Button { Task { await store.switchSpace(s) } } label: {
            HStack(spacing: 10) {
                Text(String(name.prefix(1)).uppercased())
                    .font(.system(size: 13, weight: .bold)).foregroundStyle(.white)
                    .frame(width: 28, height: 28)
                    .background(VeyqStyle.accent, in: RoundedRectangle(cornerRadius: 7))
                VStack(alignment: .leading, spacing: 1) {
                    Text(name).font(.system(size: 14, weight: .semibold)).foregroundStyle(VeyqStyle.text)
                    Text(spaceSub(s)).font(.system(size: 11)).foregroundStyle(VeyqStyle.muted)
                }
            }
        }
        .listRowBackground(store.activeScopeId == s.scopeId ? VeyqStyle.accent.opacity(0.15) : Color.clear)
        .contextMenu {
            if s.kind == "GROUP" && s.role == "GroupOwner" {
                Button { manageGroup = s } label: { Label(T("groups.members"), systemImage: "person.2") }
            }
            if s.kind == "TENANT" && s.role == "Owner" {
                Button { inviteTenant = s } label: { Label(T("invite.title"), systemImage: "envelope") }
                Button(role: .destructive) { deleteTenantTarget = s } label: { Label(T("delete.tenant"), systemImage: "trash") }
            }
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
        Button(T("common.delete"), role: .destructive) { deleteNodeTarget = entry }
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
