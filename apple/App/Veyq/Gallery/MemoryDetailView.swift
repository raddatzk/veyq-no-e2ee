import SwiftUI
import PhotosUI
import ImageIO
import UIKit
import UniformTypeIdentifiers
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
    @Published var tracks: [Track.TrackItem] = []
    @Published var importing = false
    private var tracksLoaded = false

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
            let exif = Self.exifGps(data)
            _ = try? await Media.uploadMedia(
                apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: nodeId,
                bytes: data, thumb: Self.thumbnail(data), takenAt: exif.takenAt ?? iso.string(from: Date()),
                mimeType: "image/jpeg", lat: exif.lat, lng: exif.lng,
            )
        }
        await load()
        uploading = false
    }

    /// Pull GPS location + capture time from a JPEG's EXIF via ImageIO (sealed client-side as sealedMeta —
    /// the server never sees either). Returns nils when absent.
    static func exifGps(_ data: Data) -> (lat: Double?, lng: Double?, takenAt: String?) {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any]
        else { return (nil, nil, nil) }
        var lat: Double?, lng: Double?
        if let gps = props[kCGImagePropertyGPSDictionary] as? [CFString: Any],
           let rawLat = gps[kCGImagePropertyGPSLatitude] as? Double,
           let rawLng = gps[kCGImagePropertyGPSLongitude] as? Double {
            lat = (gps[kCGImagePropertyGPSLatitudeRef] as? String) == "S" ? -rawLat : rawLat
            lng = (gps[kCGImagePropertyGPSLongitudeRef] as? String) == "W" ? -rawLng : rawLng
        }
        var takenAt: String?
        if let exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any],
           let s = exif[kCGImagePropertyExifDateTimeOriginal] as? String {
            // EXIF "yyyy:MM:dd HH:mm:ss" → ISO 8601
            let parser = DateFormatter(); parser.dateFormat = "yyyy:MM:dd HH:mm:ss"; parser.locale = Locale(identifier: "en_US_POSIX")
            if let date = parser.date(from: s) { takenAt = ISO8601DateFormatter().string(from: date) }
        }
        return (lat, lng, takenAt)
    }

    func setCover(_ mediaId: String) async {
        _ = try? await Curate.setCover(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId, coverMediaId: mediaId)
    }

    /// Mint a public share link for this memory (origin = the instance that serves the web viewer).
    func createShareLink(role: String) async -> URL? {
        guard let link = try? await Share.createShare(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: nodeId, role: role, origin: apiBase) else { return nil }
        return URL(string: link)
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

    func loadTracks() async {
        guard !tracksLoaded else { return }
        if let t = try? await Track.getTracks(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: nodeId) { tracks = t }
        tracksLoaded = true
    }

    func importTrack(_ data: Data) async {
        guard let route = Gpx.parse(data) else { return }
        importing = true
        if (try? await Track.createTrack(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: nodeId, route: route)) != nil {
            if let t = try? await Track.getTracks(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: nodeId) { tracks = t }
        }
        importing = false
    }

    func deleteTrack(_ id: String) async {
        try? await Track.deleteTrack(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId, trackId: id)
        tracks.removeAll { $0.id == id }
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
    @State private var showGpxImporter = false
    @State private var showShareRole = false

    init(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, node: MemoryTree.MemoryNode, name: String) {
        _store = StateObject(wrappedValue: GalleryStore(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: node.id))
        self.name = name
    }

    var body: some View {
        GeometryReader { geo in
            // Cap the gallery/content to a reading width so iPad/Mac don't render one enormous justified row.
            let contentWidth = min(geo.size.width, 1024)
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(name).font(.title2.bold()).foregroundStyle(VeyqStyle.text)
                    Picker("", selection: $tab) {
                        Text(T("detail.gallery")).tag(0)
                        Text(T("detail.journal")).tag(1)
                        Text(T("detail.map")).tag(2)
                    }
                    .pickerStyle(.segmented)

                    if tab == 1 {
                        JournalView(store: store)
                    } else if tab == 2 {
                        let pts = store.tiles.compactMap { t -> MemoryMapView.Point? in
                            guard let lat = t.lat, let lng = t.lng else { return nil }
                            return MemoryMapView.Point(id: t.id, lat: lat, lng: lng)
                        }
                        HStack {
                            Spacer()
                            Button(store.importing ? T("track.importing") : T("track.import")) { showGpxImporter = true }
                                .tint(VeyqStyle.accent).disabled(store.importing)
                        }
                        if pts.isEmpty && store.tracks.isEmpty {
                            Text(T("map.empty")).font(.footnote).foregroundStyle(VeyqStyle.muted)
                        } else {
                            MemoryMapView(points: pts, tracks: store.tracks) { id in
                                if let tile = store.tiles.first(where: { $0.id == id }) { lightbox = tile.image; lightboxMediaId = id }
                            }
                        }
                        ForEach(store.tracks) { tr in
                            HStack {
                                Text("🛤 \(tr.name)").font(.system(size: 14)).foregroundStyle(VeyqStyle.text)
                                Spacer()
                                Button(T("track.delete")) { let id = tr.id; Task { await store.deleteTrack(id) } }.tint(VeyqStyle.danger)
                            }
                        }
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
                                gallery(bucket.items, width: contentWidth - 32)
                            }
                        }
                    }
                    }
                }
                .padding(16)
                .readableWidth()
            }
            .background(VeyqStyle.bg)
        }
        .navigationTitle(name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showShareRole = true } label: { Image(systemName: "person.crop.circle.badge.plus") }
            }
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
        .confirmationDialog(T("share.share"), isPresented: $showShareRole, titleVisibility: .visible) {
            Button(T("share.roleViewer")) { Task { if let url = await store.createShareLink(role: "VIEWER") { shareItem = ShareItem(url: url) } } }
            Button(T("share.roleContributor")) { Task { if let url = await store.createShareLink(role: "CONTRIBUTOR") { shareItem = ShareItem(url: url) } } }
            Button(T("share.roleUploader")) { Task { if let url = await store.createShareLink(role: "UPLOADER") { shareItem = ShareItem(url: url) } } }
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
        .onChange(of: tab) { _, t in if t == 2 { Task { await store.loadTracks() } } }
        .fileImporter(isPresented: $showGpxImporter, allowedContentTypes: [.xml, UTType(filenameExtension: "gpx") ?? .data]) { result in
            guard case let .success(url) = result else { return }
            Task {
                let ok = url.startAccessingSecurityScopedResource()
                defer { if ok { url.stopAccessingSecurityScopedResource() } }
                if let data = try? Data(contentsOf: url) { await store.importTrack(data) }
            }
        }
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
