import SwiftUI
import VeyqCrypto

/// A memory's journal: a rendered Markdown view with an Edit mode (plain TextEditor on the Markdown source).
/// Inline spans (bold/italic/code/links) use SwiftUI's built-in Markdown AttributedString; block structure
/// (headings, bullet/ordered lists, paragraphs) is handled here. Rich embed tokens are deferred.
struct JournalView: View {
    @ObservedObject var store: GalleryStore
    /// Open the lightbox on a tile (owned by MemoryDetailView) — wired so ::media embeds are tappable.
    var onOpenMedia: (GalleryStore.Tile) -> Void = { _ in }
    /// Navigate into a chapter by node id (owned by MemoryDetailView) — wired so ::chapter embeds are tappable.
    var onOpenChapter: (String) -> Void = { _ in }
    @State private var editing = false
    @State private var live = false
    @State private var blocks: [JournalEditBlock] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if live {
                LiveJournalView(store: store) { markdown in store.journalMarkdown = markdown; live = false }
            } else if editing {
                JournalBlockEditor(store: store, blocks: $blocks)
                HStack {
                    Spacer()
                    Button(T("common.cancel")) { editing = false }.tint(VeyqStyle.muted)
                    Button(store.journalSaving ? T("journal.saving") : T("journal.save")) {
                        let md = journalMarkdown(from: blocks)
                        Task { await store.saveJournal(md); editing = false }
                    }
                    .buttonStyle(.borderedProminent).tint(VeyqStyle.accent).disabled(store.journalSaving)
                }
            } else {
                HStack {
                    Spacer()
                    Button(T("journal.live")) { live = true }.tint(VeyqStyle.accent)
                    Button(T("journal.edit")) { blocks = journalEditBlocks(store.journalMarkdown); Task { await store.loadTracks() }; editing = true }.tint(VeyqStyle.accent)
                }
                if store.journalMarkdown.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    VeyqEmptyState(symbolName: "text.book.closed", title: T("journal.empty"), message: T("journal.edit"), tint: VeyqStyle.info)
                } else {
                    JournalBodyView(markdown: store.journalMarkdown, store: store, onOpenMedia: onOpenMedia, onOpenChapter: onOpenChapter)
                }
            }
        }
        .padding(18)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: VeyqStyle.radius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: VeyqStyle.radius, style: .continuous)
                .stroke(VeyqStyle.line)
        }
        .shadow(color: VeyqStyle.cardShadow, radius: 18, y: 10)
        .task {
            await store.loadJournal()
            // Track embeds need the tracks loaded (inline map); playlist embeds need the playlists (link card).
            let blocks = JournalEmbedParser.splitBlocks(store.journalMarkdown)
            if blocks.contains(where: { if case let .embed(e) = $0 { return e.kind == .track }; return false }) { await store.loadTracks() }
            if blocks.contains(where: { if case let .embed(e) = $0 { return e.kind == .playlist }; return false }) { await store.loadPlaylists() }
        }
    }
}

/// Read-only journal renderer with rich embeds (matches web JournalBody): Markdown text segments interleaved
/// with ::media (thumbnail / swipeable carousel → lightbox) and ::track (inline map) blocks. ::chapter renders
/// as a simple chip (legacy iOS did not navigate chapters from the journal).
struct JournalBodyView: View {
    let markdown: String
    @ObservedObject var store: GalleryStore
    var onOpenMedia: (GalleryStore.Tile) -> Void
    var onOpenChapter: (String) -> Void = { _ in }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(JournalEmbedParser.splitBlocks(markdown)) { block in
                switch block {
                case let .markdown(md, _):
                    if !md.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { MarkdownText(markdown: md) }
                case let .embed(e):
                    switch e.kind {
                    case .media: mediaEmbed(e)
                    case .track: trackEmbed(e)
                    case .chapter: chapterEmbed(e)
                    case .playlist: playlistEmbed(e)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func tiles(_ e: JournalEmbed) -> [GalleryStore.Tile] {
        e.ids.compactMap { id in store.tiles.first(where: { $0.id == id }) }
    }

    @ViewBuilder private func mediaEmbed(_ e: JournalEmbed) -> some View {
        let items = tiles(e)
        VStack(alignment: .leading, spacing: 6) {
            if items.count == 1 {
                Image(uiImage: items[0].image).resizable().scaledToFit()
                    .frame(maxHeight: 360)
                    .frame(maxWidth: .infinity)
                    .background(VeyqStyle.mediaBackdrop)
                    .clipShape(RoundedRectangle(cornerRadius: VeyqStyle.radius, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radius, style: .continuous).strokeBorder(VeyqStyle.line))
                    .onTapGesture { onOpenMedia(items[0]) }
            } else if items.count > 1 {
                TabView {
                    ForEach(items) { tile in
                        Image(uiImage: tile.image).resizable().scaledToFit()
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .background(VeyqStyle.mediaBackdrop)
                            .onTapGesture { onOpenMedia(tile) }
                    }
                }
                .tabViewStyle(.page)
                .frame(height: 320)
                .clipShape(RoundedRectangle(cornerRadius: VeyqStyle.radius, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radius, style: .continuous).strokeBorder(VeyqStyle.line))
            } else {
                Text(T("journal.embedMissing")).font(.footnote).italic().foregroundStyle(VeyqStyle.muted)
            }
            if let label = e.label { Text(label).font(.caption).foregroundStyle(VeyqStyle.muted) }
        }
    }

    @ViewBuilder private func trackEmbed(_ e: JournalEmbed) -> some View {
        let routes = store.tracks.filter { e.ids.contains($0.id) }
        VStack(alignment: .leading, spacing: 6) {
            if routes.isEmpty {
                Text(T("journal.embedMissing")).font(.footnote).italic().foregroundStyle(VeyqStyle.muted)
            } else {
                MemoryMapView(points: [], tracks: routes) { _ in }
                    .frame(height: 240).clipShape(RoundedRectangle(cornerRadius: 10))
                ForEach(routes) { route in TrackStatsRow(route: route) }
            }
            if let label = e.label { Text(label).font(.caption).foregroundStyle(VeyqStyle.muted) }
        }
    }

    @ViewBuilder private func chapterEmbed(_ e: JournalEmbed) -> some View {
        let id = e.ids.first ?? ""
        Button { onOpenChapter(id) } label: {
            HStack(spacing: 6) {
                Text("📖")
                Text(e.label ?? id).foregroundStyle(VeyqStyle.accent).underline()
            }
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(VeyqStyle.elevatedSurface).clipShape(Capsule())
            .overlay(Capsule().strokeBorder(VeyqStyle.line))
        }
        .buttonStyle(.plain)
        .disabled(id.isEmpty)
    }

    @ViewBuilder private func playlistEmbed(_ e: JournalEmbed) -> some View {
        if let p = store.playlists.first(where: { $0.id == e.ids.first }), let url = URL(string: p.url) {
            Link(destination: url) {
                HStack(spacing: 8) {
                    Text(p.provider.label).font(.caption.weight(.semibold)).foregroundStyle(VeyqStyle.muted)
                        .padding(.horizontal, 8).padding(.vertical, 3).background(VeyqStyle.elevatedSurface, in: Capsule())
                    Text(e.label ?? (p.title?.isEmpty == false ? p.title! : p.url)).foregroundStyle(VeyqStyle.accent).underline().lineLimit(1)
                }
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background(VeyqStyle.elevatedSurface).clipShape(RoundedRectangle(cornerRadius: VeyqStyle.radiusSm, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radiusSm, style: .continuous).strokeBorder(VeyqStyle.line))
            }
            .buttonStyle(.plain)
        } else {
            Text(T("journal.embedMissing")).font(.footnote).italic().foregroundStyle(VeyqStyle.muted)
        }
    }
}

/// One track's derived statistics line: name + distance, elevation (when present), duration (when present).
/// Computed client-side from the route points (the operator-blind server never sees them).
struct TrackStatsRow: View {
    let route: Track.TrackItem

    var body: some View {
        let stats = Track.stats(route.points)
        let ele = Track.formatElevation(stats)
        HStack(spacing: 10) {
            Text("🛤 \(route.name)").font(.caption.weight(.semibold)).foregroundStyle(VeyqStyle.text)
            Text(Track.formatDistance(stats.distanceM)).font(.caption).foregroundStyle(VeyqStyle.muted)
            if let d = stats.durationS { Text(Track.formatDuration(d)).font(.caption).foregroundStyle(VeyqStyle.muted) }
            if !ele.isEmpty { Text(ele).font(.caption).foregroundStyle(VeyqStyle.muted) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Renders a safe Markdown subset as stacked SwiftUI Text blocks (headings, lists, paragraphs); inline
/// formatting via AttributedString(markdown:). Unknown syntax falls back to plain text.
struct MarkdownText: View {
    let markdown: String

    private enum Block: Identifiable {
        case heading(Int, String, id: Int)
        case bullet([String], id: Int)
        case ordered([String], id: Int)
        case paragraph(String, id: Int)
        var id: Int {
            switch self {
            case let .heading(_, _, id), let .bullet(_, id), let .ordered(_, id), let .paragraph(_, id): return id
            }
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(parse()) { block in
                switch block {
                case let .heading(level, text, _):
                    Text(inline(text))
                        .font(.system(size: level == 1 ? 22 : level == 2 ? 19 : 17, weight: .bold))
                        .foregroundStyle(VeyqStyle.text)
                case let .bullet(items, _):
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                            HStack(alignment: .top, spacing: 6) { Text("•").foregroundStyle(VeyqStyle.muted); Text(inline(item)).foregroundStyle(VeyqStyle.text) }
                        }
                    }
                case let .ordered(items, _):
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(Array(items.enumerated()), id: \.offset) { idx, item in
                            HStack(alignment: .top, spacing: 6) { Text("\(idx + 1).").foregroundStyle(VeyqStyle.muted); Text(inline(item)).foregroundStyle(VeyqStyle.text) }
                        }
                    }
                case let .paragraph(text, _):
                    Text(inline(text)).foregroundStyle(VeyqStyle.text).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func inline(_ s: String) -> AttributedString {
        (try? AttributedString(markdown: s, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(s)
    }

    private func parse() -> [Block] {
        var blocks: [Block] = []
        var paragraph: [String] = []
        var bullets: [String] = []
        var ordered: [String] = []
        var counter = 0
        func id() -> Int { counter += 1; return counter }
        func flushParagraph() { if !paragraph.isEmpty { blocks.append(.paragraph(paragraph.joined(separator: "\n"), id: id())); paragraph = [] } }
        func flushBullets() { if !bullets.isEmpty { blocks.append(.bullet(bullets, id: id())); bullets = [] } }
        func flushOrdered() { if !ordered.isEmpty { blocks.append(.ordered(ordered, id: id())); ordered = [] } }

        for raw in markdown.replacingOccurrences(of: "\r\n", with: "\n").split(separator: "\n", omittingEmptySubsequences: false) {
            let line = String(raw)
            if line.trimmingCharacters(in: .whitespaces).isEmpty { flushParagraph(); flushBullets(); flushOrdered(); continue }
            if let m = line.range(of: #"^(#{1,3})\s+"#, options: .regularExpression) {
                flushParagraph(); flushBullets(); flushOrdered()
                let hashes = line[m].trimmingCharacters(in: .whitespaces)
                blocks.append(.heading(hashes.count, String(line[m.upperBound...]), id: id()))
            } else if let m = line.range(of: #"^[-*]\s+"#, options: .regularExpression) {
                flushParagraph(); flushOrdered(); bullets.append(String(line[m.upperBound...]))
            } else if let m = line.range(of: #"^\d+\.\s+"#, options: .regularExpression) {
                flushParagraph(); flushBullets(); ordered.append(String(line[m.upperBound...]))
            } else {
                flushBullets(); flushOrdered(); paragraph.append(line)
            }
        }
        flushParagraph(); flushBullets(); flushOrdered()
        return blocks
    }
}
