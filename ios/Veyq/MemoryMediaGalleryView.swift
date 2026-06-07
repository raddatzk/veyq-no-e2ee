import AVKit
import SwiftUI
import UIKit

struct JustifiedMemoryMediaGrid: View {
    @EnvironmentObject private var store: VeyqStore
    let media: [MemoryMedia]
    var maxRows: Int? = nil

    @State private var selectedIndex: Int?

    var body: some View {
        JustifiedMediaGridLayout(gap: 8, maxRows: maxRows) {
            ForEach(Array(media.enumerated()), id: \.element.id) { index, item in
                Button {
                    selectedIndex = index
                } label: {
                    MediaTile(media: item)
                }
                .buttonStyle(.plain)
                .layoutValue(
                    key: GalleryAspectRatioKey.self,
                    value: JustifiedMediaLayout.galleryDisplayAspectRatio(width: item.imageWidth, height: item.imageHeight)
                )
                .accessibilityIdentifier("memory-gallery-tile-\(item.id)")
            }
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .clipped()
        .fullScreenCover(
            isPresented: Binding(
                get: { selectedIndex != nil },
                set: { isPresented in
                    if !isPresented {
                        selectedIndex = nil
                    }
                }
            )
        ) {
            MemoryMediaViewer(media: media, selectedIndex: Binding(
                get: { selectedIndex ?? 0 },
                set: { selectedIndex = $0 }
            ))
            .environmentObject(store)
        }
    }
}

private struct GalleryAspectRatioKey: LayoutValueKey {
    static let defaultValue = JustifiedMediaLayout.fallbackAspectRatio
}

private struct JustifiedMediaGridLayout: Layout {
    let gap: Double
    let maxRows: Int?

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = resolvedWidth(from: proposal)
        let layout = calculateLayout(width: width, subviews: subviews)
        return CGSize(width: width, height: layout.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let width = max(0, floor(bounds.width))
        let layout = calculateLayout(width: width, subviews: subviews)
        var placedSubviewIndices = Set<Int>()

        for (index, tile) in layout.tiles.enumerated() where subviews.indices.contains(index) {
            placedSubviewIndices.insert(index)
            subviews[index].place(
                at: CGPoint(x: bounds.minX + tile.left, y: bounds.minY + tile.top),
                anchor: .topLeading,
                proposal: ProposedViewSize(width: tile.renderedWidth, height: tile.renderedHeight)
            )
        }

        for index in subviews.indices where !placedSubviewIndices.contains(index) {
            subviews[index].place(
                at: CGPoint(x: bounds.maxX + gap, y: bounds.maxY + gap),
                anchor: .topLeading,
                proposal: ProposedViewSize(width: 0, height: 0)
            )
        }
    }

    private func calculateLayout(width: Double, subviews: Subviews) -> MediaGridLayoutResult {
        let targetHeight = width <= 720 ? 150.0 : 200.0
        return JustifiedMediaLayout.calculate(
            items: subviews.indices.map { index in
                MediaGridLayoutSource(
                    id: String(index),
                    aspectRatio: subviews[index][GalleryAspectRatioKey.self]
                )
            },
            containerWidth: width,
            targetHeight: targetHeight,
            gap: gap,
            showWidows: true,
            maxRows: maxRows
        )
    }

    private func resolvedWidth(from proposal: ProposedViewSize) -> Double {
        guard let width = proposal.width, width.isFinite, width > 0 else { return 0 }
        return floor(width)
    }
}

private struct MediaTile: View {
    let media: MemoryMedia

    var body: some View {
        AuthenticatedMediaImage(urlString: media.preferredTileUrl, contentMode: .fill)
        .background(VeyqColor.surface)
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .stroke(VeyqColor.hairline)
        }
        .overlay(alignment: .topLeading) {
            if media.highlight {
                MediaTileHighlightBadge()
                    .padding(7)
                    .accessibilityHidden(true)
            }
        }
        .overlay(alignment: .bottomTrailing) {
            if media.isVideo {
                MediaTileBadge(systemName: "play.fill", foreground: VeyqColor.onStrong)
                    .padding(8)
                    .accessibilityHidden(true)
            }
        }
    }
}

private struct MediaTileHighlightBadge: View {
    var body: some View {
        ZStack {
            Circle()
                .fill(VeyqColor.warning.opacity(0.24))
                .blur(radius: 8)
                .scaleEffect(1.38)

            Circle()
                .fill(.regularMaterial)

            Circle()
                .fill(VeyqColor.onStrong.opacity(0.74))

            Image(systemName: "star.fill")
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(Color.orange)
                .shadow(color: VeyqColor.mediaBackdrop.opacity(0.24), radius: 1, x: 0, y: 1)
        }
            .frame(width: 22, height: 22)
            .overlay {
                Circle()
                    .strokeBorder(
                        LinearGradient(
                            colors: [
                                VeyqColor.onStrong.opacity(0.82),
                                VeyqColor.mediaBackdrop.opacity(0.20)
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ),
                        lineWidth: 1
                    )
            }
            .overlay {
                Circle()
                    .stroke(VeyqColor.warning.opacity(0.22), lineWidth: 4)
                    .blur(radius: 3)
            }
            .shadow(color: VeyqColor.mediaBackdrop.opacity(0.28), radius: 7, x: 0, y: 3)
    }
}

private struct MediaTileBadge: View {
    let systemName: String
    let foreground: Color

    var body: some View {
        Image(systemName: systemName)
            .font(.caption.weight(.bold))
            .foregroundStyle(foreground)
            .frame(width: 30, height: 30)
            .background(VeyqColor.mediaBackdrop.opacity(0.72), in: Circle())
    }
}

struct AuthenticatedMediaImage: View {
    @EnvironmentObject private var store: VeyqStore
    let urlString: String
    let contentMode: ContentMode
    var crop: CoverCrop? = nil

    @State private var image: UIImage?
    @State private var loadedURLString: String?
    @State private var loading = false

    var body: some View {
        ZStack {
            if let image {
                imageView(image)
            } else {
                Rectangle()
                    .fill(
                        LinearGradient(
                            colors: [
                                VeyqColor.accent.opacity(0.18),
                                VeyqColor.rose.opacity(0.10),
                                VeyqColor.success.opacity(0.08)
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                Image(systemName: loading ? "hourglass" : "photo")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(VeyqColor.accent)
                    .opacity(loading ? 0.50 : 0.82)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .task(id: urlString) {
            guard !urlString.isEmpty else {
                image = nil
                loadedURLString = nil
                return
            }
            if loadedURLString != urlString {
                image = nil
            }
            loading = true
            defer { loading = false }
            let loadedImage = try? await store.loadMediaImage(from: urlString)
            guard !Task.isCancelled else { return }
            image = loadedImage
            loadedURLString = loadedImage == nil ? nil : urlString
        }
    }

    @ViewBuilder
    private func imageView(_ image: UIImage) -> some View {
        switch contentMode {
        case .fill:
            if let crop, crop.isRenderable {
                croppedImageView(image, crop: crop)
            } else {
                GeometryReader { proxy in
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: proxy.size.width, height: proxy.size.height)
                        .clipped()
                }
            }
        case .fit:
            GeometryReader { proxy in
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(width: proxy.size.width, height: proxy.size.height)
            }
        }
    }

    private func croppedImageView(_ image: UIImage, crop: CoverCrop) -> some View {
        GeometryReader { proxy in
            let sourceWidth = max(image.size.width, 1)
            let sourceHeight = max(image.size.height, 1)
            let cropPixelWidth = max(sourceWidth * crop.width, 1)
            let cropPixelHeight = max(sourceHeight * crop.height, 1)
            let scale = max(proxy.size.width / cropPixelWidth, proxy.size.height / cropPixelHeight)
            let renderedWidth = sourceWidth * scale
            let renderedHeight = sourceHeight * scale
            let cropOffsetX = crop.x * sourceWidth * scale
            let cropOffsetY = crop.y * sourceHeight * scale
            let cropCenteringX = (proxy.size.width - cropPixelWidth * scale) / 2
            let cropCenteringY = (proxy.size.height - cropPixelHeight * scale) / 2

            Image(uiImage: image)
                .resizable()
                .frame(width: renderedWidth, height: renderedHeight)
                .position(
                    x: renderedWidth / 2 - cropOffsetX + cropCenteringX,
                    y: renderedHeight / 2 - cropOffsetY + cropCenteringY
                )
                .frame(width: proxy.size.width, height: proxy.size.height, alignment: .topLeading)
                .clipped()
        }
    }
}

private struct MemoryMediaViewer: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: VeyqStore
    @Binding var selectedIndex: Int
    @State private var viewerMedia: [MemoryMedia]
    @State private var metadataPresented = false
    @State private var capturedAtEditorPresented = false
    @State private var deleteConfirmationPresented = false
    @State private var shareItem: ShareItem?
    @State private var loadingShare = false
    @State private var savingAction = false
    @State private var errorMessage: String?
    @State private var dismissDragOffset: CGFloat = 0

    private let dismissDragThreshold: CGFloat = 120
    private let predictedDismissDragThreshold: CGFloat = 220

    init(media: [MemoryMedia], selectedIndex: Binding<Int>) {
        _viewerMedia = State(initialValue: media)
        _selectedIndex = selectedIndex
    }

    private var selectedMedia: MemoryMedia? {
        viewerMedia.indices.contains(selectedIndex) ? viewerMedia[selectedIndex] : nil
    }

    var body: some View {
        NavigationStack {
            TabView(selection: $selectedIndex) {
                ForEach(Array(viewerMedia.enumerated()), id: \.element.id) { index, item in
                    MemoryMediaViewerPage(media: item)
                    .tag(index)
                    .accessibilityIdentifier("memory-gallery-viewer-page-\(item.id)")
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .automatic))
            .background(VeyqColor.mediaBackdrop)
            .navigationTitle(viewerTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbarBackground(VeyqColor.mediaBackdrop.opacity(0.78), for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task { await toggleSelectedHighlight() }
                    } label: {
                        Image(systemName: selectedMedia?.highlight == true ? "star.fill" : "star")
                    }
                    .foregroundStyle(selectedMedia?.highlight == true ? VeyqColor.warning : VeyqColor.onStrong)
                    .disabled(selectedMedia == nil || savingAction)
                    .accessibilityLabel(selectedMedia?.highlight == true ? L10n.string("Remove Highlight") : L10n.string("Mark as Highlight"))
                    .accessibilityIdentifier("memory-gallery-highlight-button")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        metadataPresented = true
                    } label: {
                        Image(systemName: "info.circle")
                    }
                    .foregroundStyle(VeyqColor.onStrong)
                    .disabled(selectedMedia == nil)
                    .accessibilityLabel("Media info")
                    .accessibilityIdentifier("memory-gallery-info-button")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button {
                            capturedAtEditorPresented = true
                        } label: {
                            Label("Edit Capture Date", systemImage: "calendar.badge.clock")
                        }
                        .disabled(selectedMedia == nil || savingAction)

                        Button(role: .destructive) {
                            deleteConfirmationPresented = true
                        } label: {
                            Label("Delete Media", systemImage: "trash")
                        }
                        .disabled(selectedMedia == nil || savingAction)
                    } label: {
                        if savingAction {
                            ProgressView()
                                .tint(VeyqColor.onStrong)
                        } else {
                            Image(systemName: "ellipsis.circle")
                        }
                    }
                    .foregroundStyle(VeyqColor.onStrong)
                    .disabled(selectedMedia == nil || savingAction)
                    .accessibilityLabel("Media actions")
                    .accessibilityIdentifier("memory-gallery-actions-button")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task { await shareSelectedOriginal() }
                    } label: {
                        if loadingShare {
                            ProgressView()
                                .tint(VeyqColor.onStrong)
                        } else {
                            Image(systemName: "square.and.arrow.up")
                        }
                    }
                    .foregroundStyle(VeyqColor.onStrong)
                    .disabled(selectedMedia == nil || loadingShare)
                    .accessibilityLabel("Share original")
                    .accessibilityIdentifier("memory-gallery-share-button")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                    }
                    .foregroundStyle(VeyqColor.onStrong)
                    .accessibilityLabel("Close")
                    .accessibilityIdentifier("memory-gallery-close-button")
                }
            }
        }
        .accessibilityIdentifier("memory-gallery-viewer")
        .offset(y: dismissDragOffset)
        .simultaneousGesture(dismissDrag)
        .animation(.interactiveSpring(response: 0.24, dampingFraction: 0.86), value: dismissDragOffset)
        .tint(VeyqColor.onStrong)
        .sheet(isPresented: $metadataPresented) {
            if let selectedMedia {
                MemoryMediaMetadataSheet(media: selectedMedia)
            }
        }
        .sheet(isPresented: $capturedAtEditorPresented) {
            if let selectedMedia {
                MemoryMediaCapturedAtEditor(media: selectedMedia) { capturedAt in
                    try await updateSelectedCapturedAt(capturedAt)
                }
            }
        }
        .sheet(item: $shareItem) { item in
            ActivityView(activityItems: [item.url])
        }
        .confirmationDialog(
            "Delete Media?",
            isPresented: $deleteConfirmationPresented,
            titleVisibility: .visible
        ) {
            Button("Delete Media", role: .destructive) {
                Task { await deleteSelectedMedia() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This removes the selected media item from its Memory.")
        }
        .alert("Media action failed", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private var dismissDrag: some Gesture {
        DragGesture(minimumDistance: 20, coordinateSpace: .local)
            .onChanged { value in
                guard isDownwardDismissDrag(value) else { return }
                dismissDragOffset = max(0, value.translation.height)
            }
            .onEnded { value in
                guard isDownwardDismissDrag(value) else {
                    dismissDragOffset = 0
                    return
                }

                if value.translation.height > dismissDragThreshold ||
                    value.predictedEndTranslation.height > predictedDismissDragThreshold {
                    dismiss()
                } else {
                    dismissDragOffset = 0
                }
            }
    }

    private func isDownwardDismissDrag(_ value: DragGesture.Value) -> Bool {
        value.translation.height > 0 &&
            abs(value.translation.height) > abs(value.translation.width) * 1.35
    }

    private var viewerTitle: String {
        guard viewerMedia.indices.contains(selectedIndex) else { return L10n.string("Media") }
        return viewerMedia[selectedIndex].displayTitle
    }

    @MainActor
    private func shareSelectedOriginal() async {
        guard let selectedMedia else { return }
        loadingShare = true
        defer { loadingShare = false }
        do {
            let url = try await store.loadMediaFile(
                from: selectedMedia.contentUrl,
                preferredFilename: selectedMedia.downloadFilename
            )
            shareItem = ShareItem(url: url)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    @MainActor
    private func updateSelectedCapturedAt(_ capturedAt: Date?) async throws {
        guard let selectedMedia else { return }
        savingAction = true
        defer { savingAction = false }
        let updated = try await store.updateMediaCapturedAt(selectedMedia, capturedAt: capturedAt)
        replaceVisibleMedia(updated)
    }

    @MainActor
    private func toggleSelectedHighlight() async {
        guard let selectedMedia else { return }
        savingAction = true
        defer { savingAction = false }
        do {
            let updated = try await store.updateMediaHighlight(selectedMedia, highlight: !selectedMedia.highlight)
            replaceVisibleMedia(updated)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    @MainActor
    private func deleteSelectedMedia() async {
        guard let selectedMedia else { return }
        savingAction = true
        defer { savingAction = false }
        do {
            try await store.deleteMedia(selectedMedia)
            viewerMedia.removeAll { $0.id == selectedMedia.id }
            if viewerMedia.isEmpty {
                dismiss()
            } else {
                selectedIndex = min(selectedIndex, viewerMedia.count - 1)
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func replaceVisibleMedia(_ updated: MemoryMedia) {
        viewerMedia = MemoryMedia.chronologicallySorted(viewerMedia.map { $0.id == updated.id ? updated : $0 })
        if let nextIndex = viewerMedia.firstIndex(where: { $0.id == updated.id }) {
            selectedIndex = nextIndex
        }
    }
}

private struct MemoryMediaViewerPage: View {
    let media: MemoryMedia

    var body: some View {
        ZStack {
            VeyqColor.mediaBackdrop.ignoresSafeArea()
            if media.isVideo {
                MemoryVideoPlayer(media: media)
            } else {
                ZoomableAuthenticatedMediaImage(urlString: media.previewUrl ?? media.contentUrl)
                    .padding(.horizontal, 8)
            }
        }
    }
}

private struct ZoomableAuthenticatedMediaImage: View {
    @EnvironmentObject private var store: VeyqStore
    let urlString: String

    @State private var image: UIImage?
    @State private var loadedURLString: String?
    @State private var loading = false

    var body: some View {
        ZStack {
            if let image {
                ZoomableImageView(image: image)
                    .accessibilityLabel("Zoomable image")
            } else {
                Rectangle()
                    .fill(VeyqColor.mediaBackdrop)
                Image(systemName: loading ? "hourglass" : "photo")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(VeyqColor.onStrong.opacity(loading ? 0.50 : 0.82))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: urlString) {
            guard !urlString.isEmpty else {
                image = nil
                loadedURLString = nil
                return
            }
            if loadedURLString != urlString {
                image = nil
            }
            loading = true
            defer { loading = false }
            let loadedImage = try? await store.loadMediaImage(from: urlString)
            guard !Task.isCancelled else { return }
            image = loadedImage
            loadedURLString = loadedImage == nil ? nil : urlString
        }
    }
}

private struct ZoomableImageView: UIViewRepresentable {
    let image: UIImage

    func makeUIView(context: Context) -> ZoomableImageContainerView {
        ZoomableImageContainerView()
    }

    func updateUIView(_ uiView: ZoomableImageContainerView, context: Context) {
        uiView.image = image
    }
}

private final class ZoomableImageContainerView: UIView, UIScrollViewDelegate {
    private let scrollView = UIScrollView()
    private let imageView = UIImageView()
    private var didConfigure = false

    var image: UIImage? {
        didSet {
            guard oldValue !== image else { return }
            imageView.image = image
            resetZoom()
        }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        configure()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        configure()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        scrollView.frame = bounds
        if scrollView.zoomScale == scrollView.minimumZoomScale {
            resetZoom()
        } else {
            centerImage()
        }
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? {
        imageView
    }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        centerImage()
    }

    private func configure() {
        guard !didConfigure else { return }
        didConfigure = true

        backgroundColor = .clear
        scrollView.backgroundColor = .clear
        scrollView.delegate = self
        scrollView.minimumZoomScale = 1
        scrollView.maximumZoomScale = 5
        scrollView.bouncesZoom = true
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.showsVerticalScrollIndicator = false
        scrollView.decelerationRate = .fast
        addSubview(scrollView)

        imageView.contentMode = .scaleAspectFit
        imageView.isUserInteractionEnabled = true
        scrollView.addSubview(imageView)

        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        scrollView.addGestureRecognizer(doubleTap)
    }

    private func resetZoom() {
        guard let image, bounds.width > 0, bounds.height > 0 else { return }

        scrollView.setZoomScale(1, animated: false)
        let fittedSize = aspectFitSize(imageSize: image.size, containerSize: bounds.size)
        imageView.frame = CGRect(origin: .zero, size: fittedSize)
        scrollView.contentSize = fittedSize
        centerImage()
    }

    private func centerImage() {
        let horizontalInset = max(0, (scrollView.bounds.width - scrollView.contentSize.width) / 2)
        let verticalInset = max(0, (scrollView.bounds.height - scrollView.contentSize.height) / 2)
        scrollView.contentInset = UIEdgeInsets(
            top: verticalInset,
            left: horizontalInset,
            bottom: verticalInset,
            right: horizontalInset
        )
    }

    private func aspectFitSize(imageSize: CGSize, containerSize: CGSize) -> CGSize {
        guard imageSize.width > 0, imageSize.height > 0, containerSize.width > 0, containerSize.height > 0 else {
            return .zero
        }
        let scale = min(containerSize.width / imageSize.width, containerSize.height / imageSize.height)
        return CGSize(width: imageSize.width * scale, height: imageSize.height * scale)
    }

    @objc private func handleDoubleTap(_ recognizer: UITapGestureRecognizer) {
        if scrollView.zoomScale > scrollView.minimumZoomScale {
            scrollView.setZoomScale(scrollView.minimumZoomScale, animated: true)
            return
        }

        let targetScale = min(scrollView.maximumZoomScale, 2.5)
        let point = recognizer.location(in: imageView)
        let zoomSize = CGSize(
            width: scrollView.bounds.width / targetScale,
            height: scrollView.bounds.height / targetScale
        )
        let zoomRect = CGRect(
            x: point.x - zoomSize.width / 2,
            y: point.y - zoomSize.height / 2,
            width: zoomSize.width,
            height: zoomSize.height
        )
        scrollView.zoom(to: zoomRect, animated: true)
    }
}

private struct MemoryVideoPlayer: View {
    @EnvironmentObject private var store: VeyqStore
    let media: MemoryMedia
    @State private var player: AVPlayer?
    @State private var loading = false
    @State private var errorMessage: String?

    var body: some View {
        ZStack {
            if let player {
                VideoPlayer(player: player)
                    .ignoresSafeArea(edges: .horizontal)
                    .onAppear { player.play() }
                    .onDisappear { player.pause() }
            } else {
                AuthenticatedMediaImage(urlString: media.posterUrl ?? media.previewUrl ?? media.preferredTileUrl, contentMode: .fit)
                    .padding(.horizontal, 8)

                VStack(spacing: 12) {
                    if loading {
                        ProgressView()
                            .tint(VeyqColor.onStrong)
                    } else {
                        Image(systemName: "play.circle.fill")
                            .font(.system(size: 64, weight: .semibold))
                            .foregroundStyle(VeyqColor.onStrong.opacity(0.88))
                    }

                    if let errorMessage {
                        Text(errorMessage)
                            .font(.caption)
                            .foregroundStyle(VeyqColor.onStrong.opacity(0.82))
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 24)
                    }
                }
            }
        }
        .task(id: media.id) {
            await loadVideo()
        }
    }

    @MainActor
    private func loadVideo() async {
        guard player == nil else { return }
        loading = true
        defer { loading = false }
        do {
            let url = try await store.loadMediaFile(from: media.contentUrl, preferredFilename: media.downloadFilename)
            player = AVPlayer(url: url)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private struct MemoryMediaMetadataSheet: View {
    @Environment(\.dismiss) private var dismiss
    let media: MemoryMedia

    var body: some View {
        NavigationStack {
            List {
                Section("Media") {
                    MetadataRow(title: "Title", value: media.displayTitle)
                    MetadataRow(title: "Highlight", value: media.highlight ? L10n.string("Yes") : L10n.string("No"))
                    MetadataRow(title: "Type", value: media.mimeType)
                    MetadataRow(title: "Size", value: media.formattedSize)
                    if let dimensions = media.formattedDimensions {
                        MetadataRow(title: "Dimensions", value: dimensions)
                    }
                }

                Section("Capture") {
                    MetadataRow(title: "Date", value: media.formattedCaptureDate)
                    if let camera = media.formattedCamera {
                        MetadataRow(title: "Camera", value: camera)
                    }
                    if let lens = media.lensModel, !lens.isEmpty {
                        MetadataRow(title: "Lens", value: lens)
                    }
                    if let location = media.formattedLocation {
                        MetadataRow(title: "Location", value: location)
                    }
                }

                Section("Uploader") {
                    MetadataRow(title: "Name", value: media.uploader.displayName)
                }
            }
            .navigationTitle("Media Info")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct MemoryMediaCapturedAtEditor: View {
    @Environment(\.dismiss) private var dismiss
    let media: MemoryMedia
    let save: (Date?) async throws -> Void

    @State private var capturedAt: Date
    @State private var hasCapturedAt: Bool
    @State private var saving = false
    @State private var errorMessage: String?

    init(media: MemoryMedia, save: @escaping (Date?) async throws -> Void) {
        self.media = media
        self.save = save
        _capturedAt = State(initialValue: media.capturedAt ?? media.createdAt)
        _hasCapturedAt = State(initialValue: media.capturedAt != nil)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Capture") {
                    Toggle("Use Capture Date", isOn: $hasCapturedAt)
                    DatePicker(
                        "Date",
                        selection: $capturedAt,
                        displayedComponents: [.date, .hourAndMinute]
                    )
                    .disabled(!hasCapturedAt)
                }

                if let errorMessage {
                    Section {
                        Text(errorMessage)
                            .font(.caption)
                            .foregroundStyle(VeyqColor.danger)
                    }
                }
            }
            .navigationTitle("Edit Capture Date")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                    .disabled(saving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(saving ? "Saving..." : "Save") {
                        Task { await saveCapturedAt() }
                    }
                    .disabled(saving)
                }
            }
        }
        .presentationDetents([.medium])
    }

    @MainActor
    private func saveCapturedAt() async {
        saving = true
        errorMessage = nil
        defer { saving = false }
        do {
            try await save(hasCapturedAt ? capturedAt : nil)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private struct MetadataRow: View {
    let title: LocalizedStringKey
    let value: String

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title)
                .foregroundStyle(.secondary)
            Spacer(minLength: 16)
            Text(value)
                .multilineTextAlignment(.trailing)
        }
    }
}

private struct ActivityView: UIViewControllerRepresentable {
    let activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

private struct ShareItem: Identifiable {
    let url: URL

    var id: String { url.absoluteString }
}

private extension MemoryMedia {
    var downloadFilename: String {
        let candidate = displayTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = candidate.isEmpty ? id : candidate
        let ext = preferredFileExtension
        return base.lowercased().hasSuffix(".\(ext)") ? base : "\(base).\(ext)"
    }

    var preferredFileExtension: String {
        if mimeType == "image/jpeg" { return "jpg" }
        if mimeType == "image/png" { return "png" }
        if mimeType == "image/webp" { return "webp" }
        if mimeType == "video/quicktime" { return "mov" }
        if mimeType == "video/mp4" { return "mp4" }
        return isVideo ? "mov" : "jpg"
    }

    var formattedSize: String {
        ByteCountFormatter.string(fromByteCount: sizeBytes, countStyle: .file)
    }

    var formattedDimensions: String? {
        guard let imageWidth, let imageHeight else { return nil }
        return "\(imageWidth) x \(imageHeight)"
    }

    var formattedCaptureDate: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: capturedAt ?? createdAt)
    }

    var formattedCamera: String? {
        let parts = [cameraMake, cameraModel]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        return parts.isEmpty ? nil : parts.joined(separator: " ")
    }

    var formattedLocation: String? {
        guard let gpsLatitude, let gpsLongitude else { return nil }
        return String(format: "%.5f, %.5f", gpsLatitude, gpsLongitude)
    }
}
