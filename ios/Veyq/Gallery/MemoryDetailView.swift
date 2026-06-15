import SwiftUI
import PhotosUI
import UIKit
import VeyqCrypto

/// A memory's justified gallery. Loads each media's thumbnail, derives its clamped aspect ratio, and lays
/// the tiles out with the shared justified algorithm. Tapping a tile opens a lightbox.
@MainActor
final class GalleryStore: ObservableObject {
    struct Tile: Identifiable { let id: String; let image: UIImage; let aspectRatio: CGFloat; let takenAt: String; let lat: Double?; let lng: Double? }

    let apiBase: String
    let session: Login.UnlockedSession
    let scopeId: String
    let contentKey: Data
    let nodeId: String

    @Published var tiles: [Tile] = []
    @Published var loading = true
    @Published var uploading = false
    @Published var journalMarkdown = ""
    @Published var journalSaving = false
    private var journalLoaded = false

    init(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, nodeId: String) {
        self.apiBase = apiBase; self.session = session; self.scopeId = scopeId; self.contentKey = contentKey; self.nodeId = nodeId
    }

    func load() async {
        do {
            let media = try await Media.listMedia(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId)
            // Sort by decrypted capture time (the server lists in upload order, blind to takenAt).
            let withMeta = media.compactMap { m -> (Media.MediaItem, Media.MediaMeta)? in
                (try? Media.decodeMediaMeta(contentKey: contentKey, item: m)).map { (m, $0) }
            }.sorted { $0.1.takenAt < $1.1.takenAt }
            var result: [Tile] = []
            for (m, meta) in withMeta {
                let ref = m.derivatives.first(where: { $0.kind == "THUMBNAIL" })?.blob ?? m.original
                let bytes = try await Media.downloadBlob(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, ref: ref)
                if let img = UIImage(data: bytes) {
                    result.append(Tile(id: m.id, image: img, aspectRatio: JustifiedLayout.clampAspect(img.size.width, img.size.height), takenAt: meta.takenAt, lat: meta.lat, lng: meta.lng))
                }
            }
            tiles = result
        } catch {
            // leave empty
        }
        loading = false
    }

    func upload(_ datas: [Data]) async {
        uploading = true
        let iso = ISO8601DateFormatter()
        for data in datas {
            _ = try? await Media.uploadMedia(
                apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: nodeId,
                bytes: data, thumb: Self.thumbnail(data), takenAt: iso.string(from: Date()), mimeType: "image/jpeg",
            )
        }
        await load()
        uploading = false
    }

    func setCover(_ mediaId: String) async {
        _ = try? await Curate.setCover(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId, coverMediaId: mediaId)
    }

    func loadJournal() async {
        guard !journalLoaded else { return }
        if let content = try? await Journal.getJournal(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: nodeId) {
            journalMarkdown = content.markdown
        }
        journalLoaded = true
    }

    func saveJournal(_ markdown: String) async {
        journalSaving = true
        if (try? await Journal.updateJournal(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: nodeId, markdown: markdown)) != nil {
            journalMarkdown = markdown
        }
        journalSaving = false
    }

    static func thumbnail(_ data: Data, maxDim: CGFloat = 400) -> Data? {
        guard let img = UIImage(data: data) else { return nil }
        let scale = min(1, maxDim / max(img.size.width, img.size.height))
        let size = CGSize(width: img.size.width * scale, height: img.size.height * scale)
        return UIGraphicsImageRenderer(size: size).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
            .jpegData(compressionQuality: 0.8)
    }
}

struct MemoryDetailView: View {
    @StateObject private var store: GalleryStore
    let name: String
    @State private var lightbox: UIImage?
    @State private var lightboxMediaId: String?
    @State private var pickerItems: [PhotosPickerItem] = []
    @State private var showDownload = false
    @State private var downloading = false
    @State private var shareItem: ShareItem?
    @State private var tab = 0

    init(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, node: MemoryTree.MemoryNode, name: String) {
        _store = StateObject(wrappedValue: GalleryStore(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: node.id))
        self.name = name
    }

    var body: some View {
        GeometryReader { geo in
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(name).font(.title2.bold()).foregroundStyle(VeyqStyle.text)
                    Picker("", selection: $tab) {
                        Text(T("detail.gallery")).tag(0)
                        Text(T("detail.journal")).tag(1)
                    }
                    .pickerStyle(.segmented)

                    if tab == 1 {
                        JournalView(store: store)
                    } else {
                    Text("\(store.tiles.count) \(T("detail.media"))").font(.footnote).foregroundStyle(VeyqStyle.muted)
                    if store.loading {
                        Text(T("detail.loadingGallery")).font(.footnote).foregroundStyle(VeyqStyle.muted)
                    } else if store.tiles.isEmpty {
                        Text(T("detail.noMedia")).font(.footnote).foregroundStyle(VeyqStyle.muted)
                    } else {
                        let range = DateBuckets.dateRange(store.tiles) { $0.takenAt }
                        if !range.isEmpty {
                            Text(range).font(.footnote).foregroundStyle(VeyqStyle.muted)
                        }
                        ForEach(DateBuckets.groupByDay(store.tiles, takenAt: { $0.takenAt }), id: \.key) { bucket in
                            VStack(alignment: .leading, spacing: 6) {
                                Text(DateBuckets.dayLabel(bucket.key)).font(.subheadline.weight(.semibold)).foregroundStyle(VeyqStyle.text)
                                gallery(bucket.items, width: geo.size.width - 32)
                            }
                        }
                    }
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(VeyqStyle.bg)
        }
        .navigationTitle(name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showDownload = true } label: { Image(systemName: downloading ? "ellipsis" : "square.and.arrow.down") }
                    .disabled(downloading || store.tiles.isEmpty)
            }
            ToolbarItem(placement: .topBarTrailing) {
                PhotosPicker(selection: $pickerItems, maxSelectionCount: 10, matching: .images) {
                    Text(store.uploading ? T("detail.uploading") : T("detail.upload")).foregroundStyle(VeyqStyle.accent)
                }
                .disabled(store.uploading)
            }
        }
        .confirmationDialog(T("download.title"), isPresented: $showDownload, titleVisibility: .visible) {
            Button(T("download.onlyThis")) { startDownload(false) }
            Button(T("download.withChapters")) { startDownload(true) }
            Button(T("common.cancel"), role: .cancel) {}
        }
        .sheet(item: $shareItem) { item in ActivityView(url: item.url) }
        .onChange(of: pickerItems) { _, items in
            guard !items.isEmpty else { return }
            Task {
                var datas: [Data] = []
                for item in items {
                    if let data = try? await item.loadTransferable(type: Data.self) { datas.append(data) }
                }
                pickerItems = []
                await store.upload(datas)
            }
        }
        .task { await store.load() }
        .fullScreenCover(item: Binding(get: { lightbox.map { LightboxImage(image: $0) } }, set: { _ in lightbox = nil; lightboxMediaId = nil })) { item in
            ZStack {
                Color.black.ignoresSafeArea()
                VStack(spacing: 16) {
                    Image(uiImage: item.image).resizable().scaledToFit()
                    HStack(spacing: 12) {
                        Button(T("cover.setAsCover")) {
                            if let id = lightboxMediaId { Task { await store.setCover(id) } }
                            lightbox = nil; lightboxMediaId = nil
                        }
                        .buttonStyle(.borderedProminent).tint(VeyqStyle.accent)
                        Button(T("common.close")) { lightbox = nil; lightboxMediaId = nil }.tint(.white)
                    }
                }
                .padding()
            }
        }
    }

    private func startDownload(_ withChapters: Bool) {
        downloading = true
        Task {
            defer { downloading = false }
            if let url = try? await DownloadService.buildZip(apiBase: store.apiBase, session: store.session, scopeId: store.scopeId, contentKey: store.contentKey, nodeId: store.nodeId, name: name, withChapters: withChapters) {
                shareItem = ShareItem(url: url)
            }
        }
    }

    private func gallery(_ items: [GalleryStore.Tile], width: CGFloat) -> some View {
        let layout = JustifiedLayout.calculate(items.map { JustifiedLayout.Source(id: $0.id, aspectRatio: $0.aspectRatio) }, containerWidth: width, targetHeight: 160, gap: 6)
        return ZStack(alignment: .topLeading) {
            ForEach(layout.tiles) { t in
                if let tile = items.first(where: { $0.id == t.id }) {
                    Image(uiImage: tile.image)
                        .resizable().scaledToFill()
                        .frame(width: t.width, height: t.height).clipped()
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .offset(x: t.left, y: t.top)
                        .onTapGesture { lightbox = tile.image; lightboxMediaId = tile.id }
                }
            }
        }
        .frame(width: width, height: layout.height, alignment: .topLeading)
    }
}

private struct LightboxImage: Identifiable { let id = UUID(); let image: UIImage }

struct ShareItem: Identifiable { let id = UUID(); let url: URL }

/// Bridges UIActivityViewController so the built zip can be saved to Files / shared.
struct ActivityView: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
