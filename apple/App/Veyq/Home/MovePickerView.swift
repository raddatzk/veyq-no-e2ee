import SwiftUI

/// Folder picker for moving a memory/folder: lists the root + every folder outside the node's own subtree
/// (loaded from the store), and moves on tap. Cycles/self-targets are excluded server- and client-side.
struct MovePickerView: View {
    @ObservedObject var store: HomeStore
    let entry: HomeStore.Entry
    @Environment(\.dismiss) private var dismiss

    @State private var dests: [(id: String, name: String)] = []
    @State private var loading = true
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Group {
                if loading {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List(dests, id: \.id) { d in
                        Button {
                            busy = true
                            Task { await store.move(entry, to: d.id); dismiss() }
                        } label: {
                            HStack(spacing: 10) {
                                Image(systemName: d.id == "root" ? "house.fill" : "folder.fill")
                                    .foregroundStyle(VeyqStyle.accent)
                                Text(d.name).foregroundStyle(VeyqStyle.text)
                            }
                        }
                        .disabled(busy)
                    }
                }
            }
            .background(VeyqStyle.bg)
            .navigationTitle(T("move.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(T("common.close")) { dismiss() }
                }
            }
        }
        .tint(VeyqStyle.accent)
        .task {
            dests = await store.moveDestinations(entry.node)
            loading = false
        }
    }
}
