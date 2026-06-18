import SwiftUI
import VeyqCrypto

// Block-based journal editor for mobile (the agreed alternative to web's inline CodeMirror chips): the journal
// is a vertical list of editable text blocks interleaved with embed CHIPS. Tapping a chip opens a picker to
// re-select or delete it; the "+ Einbetten" menu appends a new embed. Embeds therefore sit on their own line —
// the Markdown round-trips with the web format. iOS authors ::media / ::track (legacy iOS had no ::chapter).

struct EmbedData: Equatable { var kind: JournalEmbedKind; var ids: [String]; var label: String? }
struct JournalEditBlock: Identifiable { let id = UUID(); var text: String = ""; var embed: EmbedData? = nil }

/// Parse Markdown into editable blocks (text + embed). Empty text blocks are dropped; at least one remains.
func journalEditBlocks(_ markdown: String) -> [JournalEditBlock] {
    var blocks = JournalEmbedParser.splitBlocks(markdown).map { block -> JournalEditBlock in
        switch block {
        case let .markdown(md, _): return JournalEditBlock(text: md.trimmingCharacters(in: .whitespacesAndNewlines))
        case let .embed(e): return JournalEditBlock(embed: EmbedData(kind: e.kind, ids: e.ids, label: e.label))
        }
    }.filter { $0.embed != nil || !$0.text.isEmpty }
    if blocks.isEmpty || blocks.last?.embed != nil { blocks.append(JournalEditBlock(text: "")) }
    return blocks
}

/// Reassemble editable blocks back into the journal Markdown (embed tokens on their own line).
func journalMarkdown(from blocks: [JournalEditBlock]) -> String {
    blocks.compactMap { b -> String? in
        if let e = b.embed { return JournalEmbedParser.embedMarkdown(e.kind, e.ids, label: e.label) }
        let t = b.text.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }.joined(separator: "\n\n")
}

struct JournalBlockEditor: View {
    @ObservedObject var store: GalleryStore
    @Binding var blocks: [JournalEditBlock]
    @State private var picker: PickerState?

    enum PickerState: Identifiable {
        case insert(JournalEmbedKind)
        case edit(UUID, EmbedData)
        var id: String { switch self { case let .insert(k): return "i-\(k.rawValue)"; case let .edit(id, _): return "e-\(id.uuidString)" } }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Spacer()
                Menu {
                    Button("🖼 \(T("journal.embedKind.media"))") { picker = .insert(.media) }
                    Button("🛤 \(T("journal.embedKind.track"))") { Task { await store.loadTracks() }; picker = .insert(.track) }
                    Button("🎵 \(T("journal.embedKind.playlist"))") { Task { await store.loadPlaylists() }; picker = .insert(.playlist) }
                } label: { Label(T("journal.embed"), systemImage: "plus.circle") }.tint(VeyqStyle.accent)
            }
            ForEach($blocks) { $block in
                if let embed = block.embed {
                    chip(embed) { picker = .edit(block.id, embed) }
                } else {
                    TextEditor(text: $block.text)
                        .font(.system(size: 15, design: .monospaced)).foregroundStyle(VeyqStyle.text)
                        .scrollContentBackground(.hidden).frame(minHeight: 80).padding(8)
                        .background(VeyqStyle.surface).clipShape(RoundedRectangle(cornerRadius: VeyqStyle.radius))
                        .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radius).strokeBorder(VeyqStyle.line))
                }
            }
        }
        .sheet(item: $picker) { state in
            switch state {
            case let .insert(kind):
                EmbedPickerSheet(store: store, kind: kind, initial: nil) { applyInsert(kind, $0) }
            case let .edit(id, data):
                EmbedPickerSheet(store: store, kind: data.kind, initial: data) { applyEdit(id, $0) }
            }
        }
    }

    private func chip(_ e: EmbedData, _ onTap: @escaping () -> Void) -> some View {
        Button(action: onTap) {
            HStack(spacing: 6) {
                Text(e.kind == .media ? "🖼" : e.kind == .track ? "🛤" : e.kind == .playlist ? "🎵" : "🗂")
                Text(chipText(e)).foregroundStyle(VeyqStyle.text)
                Image(systemName: "pencil").foregroundStyle(VeyqStyle.muted).font(.caption)
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(VeyqStyle.surface).clipShape(Capsule()).overlay(Capsule().strokeBorder(VeyqStyle.line))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
    private func chipText(_ e: EmbedData) -> String {
        if let l = e.label, !l.isEmpty { return l }
        switch e.kind {
        case .media: return "\(e.ids.count) \(T("detail.media"))"
        case .track: return store.tracks.first(where: { $0.id == e.ids.first })?.name ?? "\(e.ids.count)"
        case .chapter: return e.ids.first ?? ""
        case .playlist: return store.playlists.first(where: { $0.id == e.ids.first }).map { $0.title?.isEmpty == false ? $0.title! : $0.url } ?? (e.ids.first ?? "")
        }
    }

    private func applyInsert(_ kind: JournalEmbedKind, _ result: EmbedPickerSheet.Result?) {
        picker = nil
        guard let r = result, !r.delete, !r.ids.isEmpty else { return }
        blocks.append(JournalEditBlock(embed: EmbedData(kind: kind, ids: r.ids, label: r.label)))
        blocks.append(JournalEditBlock(text: ""))
    }
    private func applyEdit(_ id: UUID, _ result: EmbedPickerSheet.Result?) {
        picker = nil
        guard let r = result, let idx = blocks.firstIndex(where: { $0.id == id }) else { return }
        if r.delete || r.ids.isEmpty { blocks.remove(at: idx); return }
        blocks[idx].embed?.ids = r.ids
        blocks[idx].embed?.label = r.label
    }
}

/// Picker sheet for a ::media (thumbnail grid) or ::track (list) embed: multi-select + an optional label, with
/// Delete in edit mode. Returns nil on cancel.
struct EmbedPickerSheet: View {
    struct Result { var ids: [String]; var label: String?; var delete: Bool = false }
    @ObservedObject var store: GalleryStore
    let kind: JournalEmbedKind
    let initial: EmbedData?
    let onDone: (Result?) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var selected: [String] = []
    @State private var label: String = ""

    private let cols = [GridItem(.adaptive(minimum: 92), spacing: 6)]

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                TextField(kind == .media ? T("journal.caption") : T("journal.label"), text: $label)
                    .textFieldStyle(.roundedBorder)
                ScrollView {
                    if kind == .media {
                        LazyVGrid(columns: cols, spacing: 6) {
                            ForEach(store.tiles) { tile in
                                Button { toggle(tile.id) } label: {
                                    Image(uiImage: tile.image).resizable().scaledToFill()
                                        .frame(width: 92, height: 92).clipped().clipShape(RoundedRectangle(cornerRadius: 8))
                                        .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(selected.contains(tile.id) ? VeyqStyle.accent : .clear, lineWidth: 3))
                                }
                            }
                        }
                        if store.tiles.isEmpty { Text(T("journal.mediaPickerEmpty")).font(.footnote).foregroundStyle(VeyqStyle.muted) }
                    } else if kind == .playlist {
                        VStack(spacing: 4) {
                            ForEach(store.playlists) { p in
                                Button { selected = [p.id] } label: {  // single-select
                                    HStack {
                                        Text(p.provider.label).font(.caption.weight(.semibold)).foregroundStyle(VeyqStyle.muted)
                                        Text(p.title?.isEmpty == false ? p.title! : p.url).foregroundStyle(VeyqStyle.text).lineLimit(1)
                                        Spacer()
                                        if selected.contains(p.id) { Image(systemName: "checkmark").foregroundStyle(VeyqStyle.accent) }
                                    }
                                    .padding(10).background(VeyqStyle.surface).clipShape(RoundedRectangle(cornerRadius: 8))
                                }
                            }
                            if store.playlists.isEmpty { Text(T("journal.pickerEmpty")).font(.footnote).foregroundStyle(VeyqStyle.muted) }
                        }
                    } else {
                        VStack(spacing: 4) {
                            ForEach(store.tracks) { tr in
                                Button { toggle(tr.id) } label: {
                                    HStack {
                                        Text("🛤 \(tr.name)").foregroundStyle(VeyqStyle.text)
                                        Spacer()
                                        if selected.contains(tr.id) { Image(systemName: "checkmark").foregroundStyle(VeyqStyle.accent) }
                                    }
                                    .padding(10).background(VeyqStyle.surface).clipShape(RoundedRectangle(cornerRadius: 8))
                                }
                            }
                            if store.tracks.isEmpty { Text(T("journal.pickerEmpty")).font(.footnote).foregroundStyle(VeyqStyle.muted) }
                        }
                    }
                }
            }
            .padding(16)
            .background(VeyqStyle.bg)
            .navigationTitle(kind == .media ? T("journal.mediaPickerTitle") : kind == .playlist ? T("journal.playlistPickerTitle") : T("journal.trackPickerTitle"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(T("common.cancel")) { onDone(nil); dismiss() } }
                if initial != nil {
                    ToolbarItem(placement: .topBarLeading) { Button(T("common.delete"), role: .destructive) { onDone(Result(ids: [], label: nil, delete: true)); dismiss() } }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(T("journal.insert")) { onDone(Result(ids: selected, label: label.isEmpty ? nil : label)); dismiss() }
                        .disabled(selected.isEmpty)
                }
            }
        }
        .onAppear {
            selected = initial?.ids ?? []
            label = initial?.label ?? ""
            if kind == .track { Task { await store.loadTracks() } }
            if kind == .playlist { Task { await store.loadPlaylists() } }
        }
    }

    private func toggle(_ id: String) {
        if selected.contains(id) { selected.removeAll { $0 == id } } else { selected.append(id) }
    }
}
