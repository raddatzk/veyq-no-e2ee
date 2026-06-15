import SwiftUI
import VeyqCrypto

/// MemoryHome: the active space's memories (cover cards) + folders (navigable), with a space switcher and
/// breadcrumb. The memory detail + justified gallery arrive in slice 3.
struct MemoryHomeView: View {
    @EnvironmentObject var app: AppState
    @StateObject private var store: HomeStore

    init(apiBase: String, session: Login.UnlockedSession) {
        _store = StateObject(wrappedValue: HomeStore(apiBase: apiBase, session: session))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if let error = store.error { MessageBox(text: error) }

                    if !store.searchText.isEmpty {
                        if store.searchHits.isEmpty {
                            emptyText("Nichts gefunden.")
                        } else {
                            ForEach(store.searchHits) { searchRow($0) }
                        }
                    } else {
                        if store.crumbs.count > 1 { breadcrumb }

                        sectionHeader("Memories", store.memories.count)
                        if store.memories.isEmpty {
                            emptyText("Noch keine Memories hier.")
                        } else {
                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 12) {
                                ForEach(store.memories) { memoryCard($0) }
                            }
                        }

                        sectionHeader("Ordner", store.folders.count)
                        if store.folders.isEmpty {
                            emptyText("Keine Ordner.")
                        } else {
                            ForEach(store.folders) { folderRow($0) }
                        }
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(VeyqStyle.bg)
            .navigationTitle(store.crumbs.last?.name ?? "veyq")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        ForEach(store.spaces, id: \.scopeId) { space in
                            Button(space.displayName) { Task { await store.switchSpace(space) } }
                        }
                    } label: { Image(systemName: "square.grid.2x2") }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { app.signOut() } label: { Image(systemName: "rectangle.portrait.and.arrow.right") }
                }
            }
            .toolbarBackground(VeyqStyle.surface, for: .navigationBar)
            .searchable(text: $store.searchText, prompt: "Suchen")
            .onChange(of: store.searchText) { _, _ in Task { await store.search() } }
        }
        .tint(VeyqStyle.accent)
        .task { await store.start() }
    }

    @ViewBuilder private func searchRow(_ hit: HomeStore.Entry) -> some View {
        if hit.node.type == "MEMORY", let scope = store.scope, let key = store.contentKey {
            NavigationLink {
                MemoryDetailView(apiBase: store.apiBase, session: store.session, scopeId: scope.scopeId, contentKey: key, node: hit.node, name: hit.name)
            } label: { searchRowLabel(hit, icon: "photo", type: "Memory") }
            .buttonStyle(.plain)
        } else {
            Button {
                store.searchText = ""
                Task { await store.openFolder(hit) }
            } label: { searchRowLabel(hit, icon: "folder.fill", type: "Ordner") }
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
    }
}
