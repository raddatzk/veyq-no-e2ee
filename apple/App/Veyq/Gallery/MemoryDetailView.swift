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
    struct Tile: Identifiable {
        let id: String; let image: UIImage; let aspectRatio: CGFloat; let takenAt: String; let lat: Double?; let lng: Double?
        let preview: Media.BlobRef; let original: Media.BlobRef; let mimeType: String; var highlighted: Bool
        let createdBy: String?; let bucketId: String?
        let cameraMake: String?; let cameraModel: String?; let lens: String?
        let width: Int?; let height: Int?; let fileSize: Int?; let exif: [String: String]?
    }

    let apiBase: String
    let session: Login.UnlockedSession
    let scopeId: String
    let contentKey: Data
    let nodeId: String
    let tenantId: String?   // set for group/tenant scopes → enables resolving other members' bucket names

    @Published var tiles: [Tile] = []
    @Published var loading = true
    @Published var uploading = false
    @Published var journalMarkdown = ""
    @Published var journalSaving = false
    private var journalLoaded = false
    @Published var tracks: [Track.TrackItem] = []
    @Published var importing = false
    private var tracksLoaded = false

    init(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, nodeId: String, tenantId: String? = nil) {
        self.apiBase = apiBase; self.session = session; self.scopeId = scopeId; self.contentKey = contentKey; self.nodeId = nodeId; self.tenantId = tenantId
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
                               takenAt: meta.takenAt, lat: meta.lat, lng: meta.lng, preview: preview, original: m.original, mimeType: m.mimeType, highlighted: m.highlighted,
                               createdBy: m.createdBy, bucketId: m.bucketId,
                               cameraMake: meta.cameraMake, cameraModel: meta.cameraModel, lens: meta.lens,
                               width: meta.width, height: meta.height, fileSize: meta.fileSize, exif: meta.exif))
        }
        tiles = result
        loading = false
    }

    /// Toggle a medium's highlight flag (server-visible benign metadata) and patch the local tile in place.
    func toggleHighlight(_ tile: Tile) async {
        let next = !tile.highlighted
        guard (try? await Media.updateMedia(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId, mediaId: tile.id, highlighted: next)) != nil else { return }
        if let i = tiles.firstIndex(where: { $0.id == tile.id }) { tiles[i].highlighted = next }
    }

    /// Re-seal a medium's capture date, preserving its location + EXIF camera details, then reload to re-sort.
    func updateTakenAt(_ tile: Tile, _ iso: String) async {
        let meta = Media.MediaMeta(takenAt: iso, lat: tile.lat, lng: tile.lng, cameraMake: tile.cameraMake, cameraModel: tile.cameraModel, lens: tile.lens, width: tile.width, height: tile.height, fileSize: tile.fileSize, exif: tile.exif)
        guard let sealed = try? Media.sealMediaMeta(contentKey: contentKey, meta: meta) else { return }
        guard (try? await Media.updateMedia(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId, mediaId: tile.id, sealedMeta: sealed)) != nil else { return }
        await load()
    }

    // ---- contribution buckets (ADR 0023) ----
    @Published var buckets: [Buckets.Bucket] = []
    @Published var memberNames: [String: String] = [:]   // userId → display name (group scopes), for other members
    private var memberNamesLoaded = false
    var ownerKey: String { "user:\(session.user.id)" }

    func loadBuckets() async {
        if let b = try? await Buckets.list(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId) { buckets = b }
        // Resolve other members' names from the group directory (account-level, server-visible) — operator-blind
        // re: content; the server never sees the sealed bucket names. Mirrors what Legacy returned server-side.
        if !memberNamesLoaded, let tid = tenantId {
            memberNamesLoaded = true
            if let members = try? await Groups.listGroupMembers(apiBase: apiBase, session: session, tenantId: tid, scopeId: scopeId) {
                memberNames = Dictionary(uniqueKeysWithValues: members.map { ($0.userId, $0.displayName) })
            }
        }
    }
    func bucketLabel(_ b: Buckets.Bucket) -> String {
        if b.primary && b.ownerKey == ownerKey { return session.user.displayName }
        let sealed = Buckets.name(contentKey: contentKey, bucket: b)
        if !sealed.isEmpty { return sealed }
        if b.ownerKey.hasPrefix("user:") { return memberNames[String(b.ownerKey.dropFirst(5))] ?? "" }
        return ""
    }
    func isOwnBucket(_ b: Buckets.Bucket) -> Bool { b.ownerKey == ownerKey }
    /// A tile belongs to a bucket: sub-bucket matches bucketId; primary matches the contributor's un-bucketed media.
    func tileInBucket(_ tile: Tile, _ b: Buckets.Bucket) -> Bool {
        b.primary ? (tile.createdBy == b.ownerKey && tile.bucketId == nil) : (tile.bucketId == b.id)
    }
    func bucketCount(_ b: Buckets.Bucket) -> Int { tiles.filter { tileInBucket($0, b) }.count }

    func createBucket(_ name: String) async {
        try? await Buckets.create(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: nodeId, name: name)
        await loadBuckets()
    }
    func renameBucket(_ id: String, _ name: String) async {
        try? await Buckets.rename(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: nodeId, bucketId: id, name: name)
        await loadBuckets()
    }
    func deleteBucket(_ id: String) async {
        try? await Buckets.delete(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId, bucketId: id)
        await loadBuckets(); await load()
    }
    func moveMedia(_ tile: Tile, toBucket bucketId: String) async {
        guard (try? await Media.updateMedia(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId, mediaId: tile.id, bucketId: bucketId)) != nil else { return }
        await load(); await loadBuckets()
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
        for data in datas {
            let meta = Media.extractMeta(data)
            _ = try? await Media.uploadMedia(
                apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: nodeId,
                bytes: data, thumb: Self.thumbnail(data), preview: Self.preview(data),
                takenAt: meta.takenAt, mimeType: "image/jpeg", meta: meta,
            )
        }
        await load()
        uploading = false
    }

    // extractMeta moved to VeyqCrypto (Media.extractMeta) so the GPS/EXIF mapping is unit-tested.

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

    func renameTrack(_ track: Track.TrackItem, _ name: String) async {
        guard (try? await Track.updateTrack(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: nodeId, trackId: track.id, route: Track.Route(name: name, points: track.points))) != nil else { return }
        if let t = try? await Track.getTracks(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: nodeId) { tracks = t }
    }

    @Published var playlists: [Playlists.Item] = []
    private var playlistsLoaded = false

    func loadPlaylists() async {
        guard !playlistsLoaded else { return }
        if let p = try? await Playlists.list(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: nodeId) { playlists = p }
        playlistsLoaded = true
    }

    private func reloadPlaylists() async {
        if let p = try? await Playlists.list(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: nodeId) { playlists = p }
    }

    func addPlaylist(url: String, title: String) async {
        guard (try? await Playlists.create(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: nodeId, url: url, title: title, note: nil, featured: false)) != nil else { return }
        await reloadPlaylists()
    }

    func featurePlaylist(_ id: String, _ featured: Bool) async {
        try? await Playlists.setFeatured(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId, playlistId: id, featured: featured)
        await reloadPlaylists()
    }

    func deletePlaylist(_ id: String) async {
        try? await Playlists.delete(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId, playlistId: id)
        playlists.removeAll { $0.id == id }
    }

    /// Child nodes (sub-folders/chapters/memories) shown as cover cards in the gallery tab + counted in Info.
    struct Child: Identifiable, Hashable {
        let node: MemoryTree.MemoryNode; let name: String; let cover: UIImage?
        var id: String { node.id }; var type: String { node.type }
        static func == (l: Child, r: Child) -> Bool { l.id == r.id }
        func hash(into hasher: inout Hasher) { hasher.combine(id) }
    }
    @Published var children: [Child] = []
    private var childrenLoaded = false
    var chapterCount: Int { children.filter { $0.type == "CHAPTER" }.count }

    func loadChildren() async {
        guard !childrenLoaded else { return }
        childrenLoaded = true
        guard let nodes = try? await Browse.listChildren(apiBase: apiBase, session: session, scopeId: scopeId, parentId: nodeId) else { return }
        var out: [Child] = []
        for n in nodes {
            let name = (try? MemoryTree.decodeNodeName(contentKey: contentKey, node: n)) ?? "—"
            let cover = await Self.childCover(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: n.id, coverMediaId: n.coverMediaId)
            out.append(Child(node: n, name: name, cover: cover.flatMap(UIImage.init)))
        }
        children = out
    }

    /// Resolve a node by id (any node in the scope) into a navigable Child — backs ::chapter journal embeds.
    func resolveChild(_ id: String) async -> Child? {
        guard let all = try? await MemoryTree.listNodes(apiBase: apiBase, session: session, scopeId: scopeId),
              let node = all.first(where: { $0.id == id }) else { return nil }
        let name = (try? MemoryTree.decodeNodeName(contentKey: contentKey, node: node)) ?? "—"
        return Child(node: node, name: name, cover: nil)
    }

    private nonisolated static func childCover(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, nodeId: String, coverMediaId: String?) async -> Data? {
        guard let media = try? await Media.listMedia(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId) else { return nil }
        let chosen = (coverMediaId.flatMap { cid in media.first { $0.id == cid } }) ?? media.first
        guard let item = chosen else { return nil }
        let ref = item.derivatives.first(where: { $0.kind == "THUMBNAIL" })?.blob ?? item.original
        return try? await Media.downloadBlob(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, ref: ref)
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
    @State private var showLightbox = false
    @State private var lightboxIndex = 0              // index into orderedTiles
    @State private var lightboxImage: UIImage?   // the preview, loaded async; the thumbnail shows meanwhile
    @State private var savingOriginal = false
    @State private var mediaDelArm = false
    @State private var showHighlightsOnly = false   // gallery filter (favourites)
    @State private var selectedBucketId: String?     // contributions: scope the gallery to a bucket
    @State private var cameraFilter: String?         // contributions: narrow a bucket by EXIF camera
    @State private var creatingBucket = false
    @State private var renamingBucket: Buckets.Bucket?
    @State private var bucketNameInput = ""
    @State private var hiddenTracks: Set<String> = []        // per-track map visibility
    @State private var renamingTrack: Track.TrackItem?
    @State private var trackNameInput = ""
    @State private var addingPlaylist = false           // playlists: add link flow
    @State private var playlistUrlInput = ""
    @State private var playlistTitleInput = ""
    @State private var lightboxZoom: CGFloat = 1              // pinch-zoom in the lightbox
    @State private var lightboxInfo = false          // info panel toggle in the lightbox
    @State private var editingDate = false           // capture-date editor toggle in the lightbox
    @State private var chapterNav: GalleryStore.Child?   // ::chapter embed navigation target
    @State private var pickerItems: [PhotosPickerItem] = []
    @State private var showDownload = false
    @State private var downloading = false
    @State private var shareItem: ShareItem?
    @State private var tab = 0
    @State private var showGpxImporter = false
    @State private var showShareRole = false

    init(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, node: MemoryTree.MemoryNode, name: String, tenantId: String? = nil) {
        _store = StateObject(wrappedValue: GalleryStore(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: node.id, tenantId: tenantId))
        self.name = name
    }

    var body: some View {
        mainScroll
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
        .task { await store.load(); await store.loadChildren(); await store.loadTracks() }
        .onChange(of: tab) { _, t in
            if t == 2 { Task { await store.loadTracks() } }
            if t == 3 { Task { await store.loadTracks(); await store.loadChildren() } }
            if t == 6 { Task { await store.loadPlaylists() } }
        }
        .fileImporter(isPresented: $showGpxImporter, allowedContentTypes: [.xml, UTType(filenameExtension: "gpx") ?? .data]) { result in
            guard case let .success(url) = result else { return }
            Task {
                let ok = url.startAccessingSecurityScopedResource()
                defer { if ok { url.stopAccessingSecurityScopedResource() } }
                if let data = try? Data(contentsOf: url) { await store.importTrack(data) }
            }
        }
        .fullScreenCover(isPresented: $showLightbox) {
            if let tile = currentTile {
                lightbox(tile)
            } else {
                Color.black.ignoresSafeArea().onAppear { closeLightbox() }
            }
        }
    }

    private var mainScroll: some View {
        GeometryReader { geo in
            // Cap the gallery/content to a reading width so iPad/Mac don't render one enormous justified row.
            let contentWidth = min(geo.size.width, 1024)
            ScrollView {
                detailContent(contentWidth: contentWidth)
                    .padding(16)
                    .readableWidth()
            }
            .background(VeyqStyle.bg)
        }
        .navigationTitle(name)
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(item: $chapterNav) { child in
            MemoryDetailView(apiBase: store.apiBase, session: store.session, scopeId: store.scopeId, contentKey: store.contentKey, node: child.node, name: child.name, tenantId: store.tenantId)
        }
        .toolbar { detailToolbar }
        .task { await store.loadBuckets() }
        .onChange(of: tab) { _, t in if t == 4 { Task { await store.loadBuckets() } } }
        .alert(T("contrib.newBucket"), isPresented: $creatingBucket) {
            TextField("", text: $bucketNameInput)
            Button(T("common.cancel"), role: .cancel) {}
            Button(T("journal.save")) { let n = bucketNameInput; if !n.isEmpty { Task { await store.createBucket(n) } } }
        }
        .alert(T("common.rename"), isPresented: Binding(get: { renamingBucket != nil }, set: { if !$0 { renamingBucket = nil } })) {
            TextField("", text: $bucketNameInput)
            Button(T("common.cancel"), role: .cancel) { renamingBucket = nil }
            Button(T("journal.save")) { if let b = renamingBucket { let n = bucketNameInput; if !n.isEmpty { Task { await store.renameBucket(b.id, n) } } }; renamingBucket = nil }
        }
        .alert(T("track.renamePrompt"), isPresented: Binding(get: { renamingTrack != nil }, set: { if !$0 { renamingTrack = nil } })) {
            TextField("", text: $trackNameInput)
            Button(T("common.cancel"), role: .cancel) { renamingTrack = nil }
            Button(T("journal.save")) { if let tr = renamingTrack { let n = trackNameInput; if !n.isEmpty { Task { await store.renameTrack(tr, n) } } }; renamingTrack = nil }
        }
        .alert(T("playlist.add"), isPresented: $addingPlaylist) {
            TextField(T("playlist.urlPrompt"), text: $playlistUrlInput)
            TextField(T("playlist.titlePrompt"), text: $playlistTitleInput)
            Button(T("common.cancel"), role: .cancel) { playlistUrlInput = ""; playlistTitleInput = "" }
            Button(T("playlist.add")) {
                let url = playlistUrlInput.trimmingCharacters(in: .whitespaces); let title = playlistTitleInput
                playlistUrlInput = ""; playlistTitleInput = ""
                if !url.isEmpty { Task { await store.addPlaylist(url: url, title: title) } }
            }
        }
    }

    @ToolbarContentBuilder private var detailToolbar: some ToolbarContent {
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

    private var selectedBucket: Buckets.Bucket? { store.buckets.first { $0.id == selectedBucketId } }
    // The gallery grid honours the favourites filter, the selected contribution bucket, and the camera filter.
    private var displayTiles: [GalleryStore.Tile] {
        var v = store.tiles
        if showHighlightsOnly { v = v.filter { $0.highlighted } }
        if let b = selectedBucket { v = v.filter { store.tileInBucket($0, b) } }
        if let cam = cameraFilter { v = v.filter { [$0.cameraMake, $0.cameraModel].compactMap { $0 }.joined(separator: " ") == cam } }
        return v
    }
    private var cameraOptions: [String] {
        guard let b = selectedBucket else { return [] }
        return Array(Set(store.tiles.filter { store.tileInBucket($0, b) }.map { [$0.cameraMake, $0.cameraModel].compactMap { $0 }.joined(separator: " ") }.filter { !$0.isEmpty })).sorted()
    }
    // Lightbox pages through the currently displayed (filtered) tiles, in capture-time order.
    private var orderedTiles: [GalleryStore.Tile] { displayTiles.sorted { $0.takenAt < $1.takenAt } }
    private var currentTile: GalleryStore.Tile? { orderedTiles.indices.contains(lightboxIndex) ? orderedTiles[lightboxIndex] : nil }

    @ViewBuilder private func detailContent(contentWidth: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            // Cover hero: the first (earliest) photo, like the web detail header.
            if let hero = store.tiles.first {
                Image(uiImage: hero.image).resizable().scaledToFill()
                    .frame(height: 180).frame(maxWidth: .infinity).clipped()
                    .clipShape(RoundedRectangle(cornerRadius: VeyqStyle.radius))
            }
            Text(name).font(.title2.bold()).foregroundStyle(VeyqStyle.text)
            metadataPills
            Picker("", selection: $tab) {
                Text(T("detail.gallery")).tag(0)
                if !store.children.isEmpty { Text(T("detail.chapters")).tag(5) }
                Text(T("detail.map")).tag(2)
                Text(T("detail.journal")).tag(1)
                Text(T("detail.playlists")).tag(6)
                Text(T("detail.contributions")).tag(4)
                Text(T("detail.info")).tag(3)
            }
            .pickerStyle(.segmented)

            if tab == 3 {
                infoTab
            } else if tab == 5 {
                chaptersTab
            } else if tab == 4 {
                contributionsTab
            } else if tab == 6 {
                playlistsTab
            } else if tab == 1 {
                JournalView(store: store, onOpenMedia: openLightbox, onOpenChapter: openChapter)
            } else if tab == 2 {
                mapTab
            } else {
                galleryTab(contentWidth: contentWidth)
            }
        }
    }

    @ViewBuilder private var mapTab: some View {
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
            MemoryMapView(points: pts, tracks: store.tracks.filter { !hiddenTracks.contains($0.id) }) { id in
                if let tile = store.tiles.first(where: { $0.id == id }) { openLightbox(tile) }
            }
        }
        ForEach(store.tracks) { tr in
            HStack(alignment: .top, spacing: 8) {
                Button { toggleTrack(tr.id) } label: { Image(systemName: hiddenTracks.contains(tr.id) ? "eye.slash" : "eye") }.tint(VeyqStyle.muted)
                TrackStatsRow(route: tr)
                Spacer()
                Button(T("common.rename")) { renamingTrack = tr; trackNameInput = tr.name }.tint(VeyqStyle.accent)
                Button(T("track.delete")) { let id = tr.id; Task { await store.deleteTrack(id) } }.tint(VeyqStyle.danger)
            }
        }
    }

    private func toggleTrack(_ id: String) { if hiddenTracks.contains(id) { hiddenTracks.remove(id) } else { hiddenTracks.insert(id) } }

    @ViewBuilder private var playlistsTab: some View {
        HStack {
            Spacer()
            Button(T("playlist.add")) { playlistUrlInput = ""; playlistTitleInput = ""; addingPlaylist = true }.tint(VeyqStyle.accent)
        }
        if store.playlists.isEmpty {
            Text(T("playlist.empty")).font(.footnote).foregroundStyle(VeyqStyle.muted)
        } else {
            ForEach(store.playlists) { p in
                HStack(spacing: 10) {
                    Text(p.provider.label).font(.caption.weight(.semibold)).foregroundStyle(VeyqStyle.muted)
                        .padding(.horizontal, 8).padding(.vertical, 3).background(VeyqStyle.surface, in: Capsule())
                    if let url = URL(string: p.url) {
                        Link(p.title?.isEmpty == false ? p.title! : p.url, destination: url).foregroundStyle(VeyqStyle.accent).lineLimit(1)
                    } else {
                        Text(p.title?.isEmpty == false ? p.title! : p.url).foregroundStyle(VeyqStyle.text).lineLimit(1)
                    }
                    Spacer()
                    Button { let id = p.id; let f = !p.featured; Task { await store.featurePlaylist(id, f) } } label: {
                        Image(systemName: p.featured ? "star.fill" : "star")
                    }.tint(p.featured ? VeyqStyle.accent : VeyqStyle.muted)
                    Button { let id = p.id; Task { await store.deletePlaylist(id) } } label: { Image(systemName: "trash") }.tint(VeyqStyle.danger)
                }
                .padding(8).background(VeyqStyle.surface).clipShape(RoundedRectangle(cornerRadius: 10))
            }
        }
    }

    @ViewBuilder private func galleryTab(contentWidth: CGFloat) -> some View {
        HStack {
            Text("\(displayTiles.count) \(T("detail.media"))").font(.footnote).foregroundStyle(VeyqStyle.muted)
            Spacer()
            if store.tiles.contains(where: { $0.highlighted }) {
                Button { showHighlightsOnly.toggle() } label: {
                    Label(T("detail.highlights"), systemImage: showHighlightsOnly ? "star.fill" : "star").font(.footnote)
                }
                .tint(showHighlightsOnly ? VeyqStyle.accent : VeyqStyle.muted)
            }
        }
        // Bucket scope chip + camera filter (Contributions). Shown when a bucket is selected.
        if let b = selectedBucket {
            HStack {
                Button { selectedBucketId = nil; cameraFilter = nil } label: {
                    Label(bucketLabelOrFallback(b), systemImage: "xmark.circle.fill").font(.footnote)
                }.tint(VeyqStyle.accent)
                Spacer()
                if !cameraOptions.isEmpty {
                    Menu {
                        Button(T("contrib.allCameras")) { cameraFilter = nil }
                        ForEach(cameraOptions, id: \.self) { cam in Button(cam) { cameraFilter = cam } }
                    } label: { Label(cameraFilter ?? T("contrib.allCameras"), systemImage: "camera").font(.footnote) }
                }
            }
        }
        if store.loading {
            Text(T("detail.loadingGallery")).font(.footnote).foregroundStyle(VeyqStyle.muted)
        } else if displayTiles.isEmpty {
            Text(T("detail.noMedia")).font(.footnote).foregroundStyle(VeyqStyle.muted)
        } else {
            let range = DateBuckets.dateRange(displayTiles) { $0.takenAt }
            if !range.isEmpty {
                Text(range).font(.footnote).foregroundStyle(VeyqStyle.muted)
            }
            ForEach(DateBuckets.groupByDay(displayTiles, takenAt: { $0.takenAt }), id: \.key) { bucket in
                VStack(alignment: .leading, spacing: 6) {
                    Text(DateBuckets.dayLabel(bucket.key)).font(.subheadline.weight(.semibold)).foregroundStyle(VeyqStyle.text)
                    gallery(bucket.items, width: contentWidth - 32)
                }
            }
        }
    }

    // At-a-glance counts under the title (Legacy header pills): photos, chapters, tracks, mapped media.
    @ViewBuilder private var metadataPills: some View {
        let mapped = store.tiles.filter { $0.lat != nil && $0.lng != nil }.count
        HStack(spacing: 6) {
            pill("🖼", store.tiles.count)
            if store.chapterCount > 0 { pill("📖", store.chapterCount) }
            if !store.tracks.isEmpty { pill("🛤", store.tracks.count) }
            if mapped > 0 { pill("📍", mapped) }
        }
    }
    private func pill(_ icon: String, _ count: Int) -> some View {
        Text("\(icon) \(count)").font(.caption).foregroundStyle(VeyqStyle.muted)
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(VeyqStyle.surface, in: Capsule())
    }

    @ViewBuilder private var chaptersTab: some View {
        VStack(spacing: 10) {
            ForEach(store.children) { child in
                NavigationLink {
                    MemoryDetailView(apiBase: store.apiBase, session: store.session, scopeId: store.scopeId, contentKey: store.contentKey, node: child.node, name: child.name, tenantId: store.tenantId)
                } label: {
                    HStack(spacing: 10) {
                        if let cover = child.cover {
                            Image(uiImage: cover).resizable().scaledToFill().frame(width: 56, height: 56).clipped().clipShape(RoundedRectangle(cornerRadius: 8))
                        } else {
                            Text(child.type == "CHAPTER" ? "📖" : child.type == "FOLDER" ? "🗂" : "🖼").font(.title).frame(width: 56, height: 56).background(VeyqStyle.surface).clipShape(RoundedRectangle(cornerRadius: 8))
                        }
                        Text(child.name).foregroundStyle(VeyqStyle.text)
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(VeyqStyle.muted)
                    }
                    .padding(8).background(VeyqStyle.surface).clipShape(RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
            }
        }
    }

    @ViewBuilder private var infoTab: some View {
        VStack(spacing: 0) {
            infoRow(T("detail.gallery"), "\(store.tiles.count)")
            let range = DateBuckets.dateRange(store.tiles) { $0.takenAt }
            if !range.isEmpty { infoRow(T("info.dateRange"), range) }
            infoRow(T("detail.map"), "\(store.tracks.count)")
            infoRow(T("info.chapters"), "\(store.chapterCount)")
        }
        .background(VeyqStyle.surface).clipShape(RoundedRectangle(cornerRadius: VeyqStyle.radius))
    }

    private func infoRow(_ label: String, _ value: String) -> some View {
        HStack { Text(label).foregroundStyle(VeyqStyle.muted); Spacer(); Text(value).foregroundStyle(VeyqStyle.text).font(.body.weight(.semibold)) }
            .padding(.horizontal, 14).padding(.vertical, 10)
    }

    private func bucketLabelOrFallback(_ b: Buckets.Bucket) -> String {
        let l = store.bucketLabel(b)
        return l.isEmpty ? T("contrib.member") : l
    }

    @ViewBuilder private var contributionsTab: some View {
        VStack(alignment: .leading, spacing: 6) {
            if store.buckets.isEmpty {
                Text(T("contrib.empty")).font(.footnote).foregroundStyle(VeyqStyle.muted)
            }
            ForEach(store.buckets) { b in
                HStack(spacing: 8) {
                    Button { selectedBucketId = b.id; cameraFilter = nil; tab = 0 } label: {
                        HStack {
                            Text("\(b.primary ? "👤" : "📁") \(bucketLabelOrFallback(b))").foregroundStyle(VeyqStyle.text)
                            Spacer()
                            Text("\(store.bucketCount(b))").font(.footnote).foregroundStyle(VeyqStyle.muted)
                        }
                    }.buttonStyle(.plain)
                    if store.isOwnBucket(b) {
                        if b.primary {
                            Button { bucketNameInput = ""; creatingBucket = true } label: { Image(systemName: "plus.circle") }.tint(VeyqStyle.accent)
                        } else {
                            Button { renamingBucket = b; bucketNameInput = store.bucketLabel(b) } label: { Image(systemName: "pencil") }.tint(VeyqStyle.accent)
                            Button { let id = b.id; Task { await store.deleteBucket(id); if selectedBucketId == id { selectedBucketId = nil } } } label: { Image(systemName: "trash") }.tint(VeyqStyle.danger)
                        }
                    }
                }
                .padding(.vertical, 8).padding(.horizontal, 10).padding(.leading, b.primary ? 0 : 16)
                .background(VeyqStyle.surface).clipShape(RoundedRectangle(cornerRadius: 8))
            }
        }
    }

    private func childCard(_ child: GalleryStore.Child) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack {
                if let cover = child.cover {
                    Image(uiImage: cover).resizable().scaledToFill()
                } else {
                    VeyqStyle.surface
                    Text(child.type == "CHAPTER" ? "📖" : child.type == "FOLDER" ? "🗂" : "🖼").font(.largeTitle)
                }
            }
            .frame(width: 132, height: 92).clipped().clipShape(RoundedRectangle(cornerRadius: 8))
            Text(child.name).font(.caption).foregroundStyle(VeyqStyle.text).lineLimit(1).frame(width: 132, alignment: .leading)
        }
    }

    @ViewBuilder private func lightbox(_ tile: GalleryStore.Tile) -> some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 16) {
                ZStack {
                    Image(uiImage: lightboxImage ?? tile.image).resizable().scaledToFit()
                        .scaleEffect(lightboxZoom)
                        .gesture(MagnificationGesture().onChanged { lightboxZoom = max(1, $0) }.onEnded { _ in if lightboxZoom < 1.1 { withAnimation { lightboxZoom = 1 } } })
                        .onTapGesture(count: 2) { withAnimation { lightboxZoom = lightboxZoom > 1 ? 1 : 2.5 } }
                    HStack {
                        Button { page(-1) } label: { Image(systemName: "chevron.left.circle.fill").font(.largeTitle) }
                            .tint(.white).opacity(lightboxIndex > 0 ? 0.9 : 0.2).disabled(lightboxIndex == 0)
                        Spacer()
                        Button { page(1) } label: { Image(systemName: "chevron.right.circle.fill").font(.largeTitle) }
                            .tint(.white).opacity(lightboxIndex < orderedTiles.count - 1 ? 0.9 : 0.2).disabled(lightboxIndex >= orderedTiles.count - 1)
                    }
                    .padding(.horizontal, 8)
                }
                if lightboxInfo { lightboxInfoPanel(tile) }
                if editingDate { lightboxDateEditor(tile) }
                HStack(spacing: 12) {
                    Button { Task { await store.toggleHighlight(tile) } } label: {
                        Image(systemName: tile.highlighted ? "star.fill" : "star")
                    }.tint(tile.highlighted ? VeyqStyle.accent : .white)
                    Button { lightboxInfo.toggle() } label: { Image(systemName: "info.circle") }.tint(.white)
                    Button { editingDate.toggle() } label: { Image(systemName: "calendar") }.tint(.white)
                    // Move my own medium between my own buckets.
                    if tile.createdBy == store.ownerKey, !store.buckets.filter(store.isOwnBucket).isEmpty {
                        Menu {
                            ForEach(store.buckets.filter(store.isOwnBucket)) { b in
                                Button(b.primary ? T("contrib.moveToPrimary") : bucketLabelOrFallback(b)) { Task { await store.moveMedia(tile, toBucket: b.id) } }
                            }
                        } label: { Image(systemName: "folder") }.tint(.white)
                    }
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

    @ViewBuilder private func lightboxInfoPanel(_ tile: GalleryStore.Tile) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            infoLine(T("info.takenAt"), DateBuckets.dayLabel(String(tile.takenAt.prefix(10))))
            infoLine(T("info.type"), tile.mimeType)
            if let lat = tile.lat, let lng = tile.lng {
                infoLine(T("info.location"), String(format: "%.5f, %.5f", lat, lng))
            }
            if let model = tile.cameraModel {
                infoLine(T("info.camera"), [tile.cameraMake, model].compactMap { $0 }.joined(separator: " "))
            }
            if let lens = tile.lens { infoLine(T("info.lens"), lens) }
            if let w = tile.width, let h = tile.height { infoLine(T("info.dimensions"), "\(w)×\(h)") }
            if let size = tile.fileSize { infoLine(T("info.fileSize"), formatBytes(size)) }
            ForEach((tile.exif ?? [:]).sorted(by: { $0.key < $1.key }), id: \.key) { k, v in infoLine(k, v) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12).background(Color.white.opacity(0.08)).clipShape(RoundedRectangle(cornerRadius: 10))
    }

    private func formatBytes(_ n: Int) -> String {
        if n < 1024 { return "\(n) B" }
        if n < 1024 * 1024 { return "\(n / 1024) KB" }
        return String(format: "%.1f MB", Double(n) / 1024 / 1024)
    }

    private func infoLine(_ label: String, _ value: String) -> some View {
        HStack { Text(label).foregroundStyle(.white.opacity(0.6)); Text(value).foregroundStyle(.white) }.font(.footnote)
    }

    @ViewBuilder private func lightboxDateEditor(_ tile: GalleryStore.Tile) -> some View {
        let binding = Binding<Date>(
            get: { ISO8601DateFormatter().date(from: tile.takenAt) ?? Date() },
            set: { newDate in Task { await store.updateTakenAt(tile, ISO8601DateFormatter().string(from: newDate)) } }
        )
        DatePicker(T("media.editDate"), selection: binding, displayedComponents: [.date, .hourAndMinute])
            .datePickerStyle(.compact).tint(VeyqStyle.accent).colorScheme(.dark)
            .padding(12).background(Color.white.opacity(0.08)).clipShape(RoundedRectangle(cornerRadius: 10))
    }

    private func page(_ delta: Int) {
        let next = lightboxIndex + delta
        guard orderedTiles.indices.contains(next) else { return }
        lightboxIndex = next
        lightboxZoom = 1
        loadLightboxPreview()
    }

    /// Navigate into a chapter referenced by a ::chapter journal embed (resolve the node, then push).
    private func openChapter(_ id: String) {
        guard !id.isEmpty else { return }
        Task { if let child = await store.resolveChild(id) { chapterNav = child } }
    }

    /// Open the lightbox on `tile`: show its thumbnail instantly, then swap in the higher-res preview.
    private func openLightbox(_ tile: GalleryStore.Tile) {
        guard let idx = orderedTiles.firstIndex(where: { $0.id == tile.id }) else { return }
        lightboxIndex = idx
        lightboxInfo = false; editingDate = false; mediaDelArm = false
        showLightbox = true
        loadLightboxPreview()
    }

    private func loadLightboxPreview() {
        lightboxImage = nil
        guard let tile = currentTile else { return }
        Task {
            if let img = await store.loadPreviewImage(tile), currentTile?.id == tile.id { lightboxImage = img }
        }
    }

    private func closeLightbox() { showLightbox = false; lightboxImage = nil; mediaDelArm = false; lightboxInfo = false; editingDate = false; lightboxZoom = 1 }

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
                        .overlay(alignment: .topTrailing) {
                            if tile.highlighted {
                                Image(systemName: "star.fill").font(.caption2).foregroundStyle(.yellow)
                                    .padding(4).background(.black.opacity(0.35), in: Circle()).padding(4)
                            }
                        }
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
