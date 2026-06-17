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
    // `image` is the thumbnail (grid). `preview` (≈2048px) backs the lightbox; `original` is only fetched on
    // an explicit save. Both refs fall back to the original for media without that derivative.
    struct Tile: Identifiable { let id: String; let image: UIImage; let aspectRatio: CGFloat; let takenAt: String; let lat: Double?; let lng: Double?; let preview: Media.BlobRef; let original: Media.BlobRef; let mimeType: String }

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
        let media = (try? await Media.listMedia(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId)) ?? []
        // Sort by decrypted capture time (the server lists in upload order, blind to takenAt).
        let withMeta = media.compactMap { m -> (Media.MediaItem, Media.MediaMeta)? in
            (try? Media.decodeMediaMeta(contentKey: contentKey, item: m)).map { (m, $0) }
        }.sorted { $0.1.takenAt < $1.1.takenAt }
        // Download the (small) thumbnails bound-parallel — Data is Sendable, so only the decode happens on the
        // main actor after. A 50-image memory no longer serializes 50 round-trips before first render.
        let thumbRefs = withMeta.map { $0.0.derivatives.first(where: { $0.kind == "THUMBNAIL" })?.blob ?? $0.0.original }
        let bytesByIndex = await downloadThumbnails(thumbRefs)
        var result: [Tile] = []
        for (i, (m, meta)) in withMeta.enumerated() {
            guard let bytes = bytesByIndex[i], let img = UIImage(data: bytes) else { continue }
            let preview = m.derivatives.first(where: { $0.kind == "PREVIEW" })?.blob ?? m.original
            result.append(Tile(id: m.id, image: img, aspectRatio: JustifiedLayout.clampAspect(img.size.width, img.size.height),
                               takenAt: meta.takenAt, lat: meta.lat, lng: meta.lng, preview: preview, original: m.original, mimeType: m.mimeType))
        }
        tiles = result
        loading = false
    }

    /// Fetch thumbnail bytes for the given refs, at most 6 in flight, returned keyed by their input index.
    private func downloadThumbnails(_ refs: [Media.BlobRef]) async -> [Int: Data] {
        let apiBase = apiBase, session = session, scopeId = scopeId, contentKey = contentKey
        return await withTaskGroup(of: (Int, Data?).self) { group in
            var out: [Int: Data] = [:]
            var next = 0
            func add(_ i: Int) {
                let ref = refs[i]
                group.addTask { (i, try? await Media.downloadBlob(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, ref: ref)) }
            }
            while next < min(6, refs.count) { add(next); next += 1 }
            for await (i, data) in group {
                if let data { out[i] = data }
                if next < refs.count { add(next); next += 1 }
            }
            return out
        }
    }

    /// Decrypt a tile's PREVIEW (≈2048px) for the lightbox.
    func loadPreviewImage(_ tile: Tile) async -> UIImage? {
        guard let bytes = try? await Media.downloadBlob(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, ref: tile.preview) else { return nil }
        return UIImage(data: bytes)
    }

    /// Decrypt a tile's FULL-resolution original (only on an explicit save).
    func loadOriginalData(_ tile: Tile) async -> Data? {
        try? await Media.downloadBlob(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, ref: tile.original)
    }

    func upload(_ datas: [Data]) async {
        uploading = true
        let iso = ISO8601DateFormatter()
        for data in datas {
            let exif = Self.exifGps(data)
            _ = try? await Media.uploadMedia(
                apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: nodeId,
                bytes: data, thumb: Self.thumbnail(data), preview: Self.preview(data),
                takenAt: exif.takenAt ?? iso.string(from: Date()),
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

    func deleteMedia(_ mediaId: String) async {
        try? await Groups.deleteMedia(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId, mediaId: mediaId)
        await load()
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
        if (try? await JournalPersistence.saveJournalMarkdown(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId, contentKey: contentKey, markdown: markdown)) != nil {
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

    /// Downscale to fit `maxDim` (longest edge) as JPEG at `quality`; nil for non-images.
    static func downscale(_ data: Data, maxDim: CGFloat, quality: CGFloat) -> Data? {
        guard let img = UIImage(data: data) else { return nil }
        let scale = min(1, maxDim / max(img.size.width, img.size.height))
        let size = CGSize(width: img.size.width * scale, height: img.size.height * scale)
        return UIGraphicsImageRenderer(size: size).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
            .jpegData(compressionQuality: quality)
    }

    static func thumbnail(_ data: Data) -> Data? { downscale(data, maxDim: 400, quality: 0.8) }   // gallery grid
    static func preview(_ data: Data) -> Data? { downscale(data, maxDim: 2048, quality: 0.85) }   // lightbox
}

struct MemoryDetailView: View {
    @StateObject private var store: GalleryStore
    let name: String
    @State private var lightboxTile: GalleryStore.Tile?
    @State private var lightboxImage: UIImage?   // the preview, loaded async; the thumbnail shows meanwhile
    @State private var savingOriginal = false
    @State private var mediaDelArm = false
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
                                if let tile = store.tiles.first(where: { $0.id == id }) { openLightbox(tile) }
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
        .fullScreenCover(item: $lightboxTile) { tile in
            ZStack {
                Color.black.ignoresSafeArea()
                VStack(spacing: 16) {
                    Image(uiImage: lightboxImage ?? tile.image).resizable().scaledToFit()
                    HStack(spacing: 12) {
                        Button(T("cover.setAsCover")) {
                            Task { await store.setCover(tile.id) }
                            closeLightbox()
                        }
                        .buttonStyle(.borderedProminent).tint(VeyqStyle.accent)
                        Button(savingOriginal ? T("detail.uploading") : T("detail.download")) { saveOriginal(tile) }
                            .tint(.white).disabled(savingOriginal)
                        if !mediaDelArm {
                            Button(T("common.delete")) { mediaDelArm = true }.tint(VeyqStyle.danger)
                        } else {
                            Button(T("delete.confirm")) {
                                Task { await store.deleteMedia(tile.id) }
                                closeLightbox()
                            }.buttonStyle(.borderedProminent).tint(VeyqStyle.danger)
                        }
                        Button(T("common.close")) { closeLightbox() }.tint(.white)
                    }
                }
                .padding()
            }
        }
    }

    /// Open the lightbox on `tile`: show its thumbnail instantly, then swap in the higher-res preview.
    private func openLightbox(_ tile: GalleryStore.Tile) {
        lightboxTile = tile
        lightboxImage = nil
        Task {
            if let img = await store.loadPreviewImage(tile), lightboxTile?.id == tile.id { lightboxImage = img }
        }
    }
    private func closeLightbox() { lightboxTile = nil; lightboxImage = nil; mediaDelArm = false }

    /// Fetch THIS medium's full-resolution original and hand it to the share sheet (save to Files/Photos).
    private func saveOriginal(_ tile: GalleryStore.Tile) {
        guard !savingOriginal else { return }
        savingOriginal = true
        Task {
            defer { savingOriginal = false }
            guard let data = await store.loadOriginalData(tile) else { return }
            let ext = (tile.mimeType.split(separator: "/").last.map(String.init) ?? "bin").replacingOccurrences(of: "jpeg", with: "jpg")
            // Under the export dir (not bare temp) so the launch sweep wipes this decrypted original too.
            try? FileManager.default.createDirectory(at: DownloadService.exportDir, withIntermediateDirectories: true)
            let url = DownloadService.exportDir.appendingPathComponent("\(name)-\(tile.id.prefix(8)).\(ext)")
            guard (try? data.write(to: url)) != nil else { return }
            shareItem = ShareItem(url: url)
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
                        .onTapGesture { openLightbox(tile) }
                }
            }
        }
        .frame(width: width, height: layout.height, alignment: .topLeading)
    }
}

struct ShareItem: Identifiable { let id = UUID(); let url: URL }

/// Bridges UIActivityViewController so the built zip can be saved to Files / shared.
struct ActivityView: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
