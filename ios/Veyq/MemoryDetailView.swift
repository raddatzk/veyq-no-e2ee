import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct MemoryDetailView: View {
    @EnvironmentObject private var store: VeyqStore
    let memory: Memory
    @State private var selectedPhotoItems: [PhotosPickerItem] = []
    @State private var importingPhotos = false
    @State private var headerScrollOffset: CGFloat = 0
    @State private var infoPresented = false

    private var detail: LoadedMemoryDetail? {
        store.memoryDetails[memory.id]
    }

    private var isLoadingDetail: Bool {
        store.memoryDetailLoadingIDs.contains(memory.id)
    }

    private var mediaError: String? {
        store.memoryDetailMediaErrors[memory.id]
    }

    private var coverUrl: String? {
        let value = detail?.header.coverUrl ?? memory.coverUrl
        return value?.isEmpty == false ? value : nil
    }

    private var coverCrop: CoverCrop? {
        detail?.header.coverCrop ?? memory.coverCrop
    }

    private var headerTitle: String {
        detail?.header.displayName ?? memory.title
    }

    var body: some View {
        ZStack {
            VeyqScreenBackground()

            ScrollView {
                GeometryReader { proxy in
                    Color.clear
                        .preference(key: MemoryDetailScrollOffsetKey.self, value: proxy.frame(in: .named("memory-detail-scroll")).minY)
                }
                .frame(height: 0)

                collapsingHeader
                    .padding(.horizontal)
                    .padding(.top, 12)

                overviewContent
                    .padding(.horizontal)
                    .padding(.bottom, VeyqLayout.floatingDockContentPadding)
            }
        }
        .refreshable {
            await store.loadDetail(for: memory, force: true)
        }
        .coordinateSpace(name: "memory-detail-scroll")
        .onPreferenceChange(MemoryDetailScrollOffsetKey.self) { value in
            headerScrollOffset = value
        }
        .navigationTitle(headerTitle)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: detailTaskID) {
            await store.loadDetail(for: memory, force: true)
        }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if store.supportsCapability("memory.media-resumable-upload") {
                    PhotosPicker(
                        selection: $selectedPhotoItems,
                        maxSelectionCount: 0,
                        matching: .any(of: [.images, .videos])
                    ) {
                        Image(systemName: MemoryDetailToolbarSymbols.upload)
                    }
                    .disabled(importingPhotos)
                    .accessibilityLabel(L10n.string("Upload photos"))
                    .accessibilityIdentifier("memory-upload-button")
                }

                Menu {
                    if let shareURL = internalShareURL(for: .gallery) {
                        ShareLink(item: shareURL) {
                            Label(L10n.string("Share"), systemImage: "square.and.arrow.up")
                        }
                        .accessibilityIdentifier("memory-internal-share-link")
                    }
                    Button {
                        infoPresented = true
                    } label: {
                        Label(L10n.string("Details"), systemImage: "info.circle")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityLabel(L10n.string("More"))
                .accessibilityIdentifier("memory-more-button")
            }
        }
        .sheet(isPresented: $infoPresented) {
            MemoryInfoSheet(memory: memory, detail: detail, spaceLabel: store.spaceLabel(for: memory.path))
        }
        .onChange(of: selectedPhotoItems) { _, items in
            guard !items.isEmpty else { return }
            Task {
                await importPhotoPickerItems(items)
            }
        }
    }

    private var collapseProgress: CGFloat {
        min(1, max(0, -headerScrollOffset / 150))
    }

    private var collapsingHeader: some View {
        CollapsingMemoryHeader(
            title: headerTitle,
            dateRange: memory.dateRange,
            mediaCount: detail?.header.directMediaCount ?? memory.mediaCount,
            chapterCount: detail?.header.chapterCount ?? memory.childCount,
            coverUrl: coverUrl,
            coverCrop: coverCrop,
            progress: collapseProgress
        )
    }

    @ViewBuilder
    private var overviewContent: some View {
        VStack(alignment: .leading, spacing: 26) {
            galleryPreviewSection
            journalPreviewSection
            chaptersCarouselSection
        }
    }

    private var galleryPreviewSection: some View {
        MemoryOverviewSection(title: "Gallery") {
            if let media = detail?.media, !media.isEmpty {
                NavigationLink {
                    MemoryGalleryView(title: headerTitle, media: media, shareURL: internalShareURL(for: .gallery))
                        .environmentObject(store)
                } label: {
                    Text("All Media")
                }
                .font(.subheadline.weight(.semibold))
                .accessibilityIdentifier("memory-all-media-button")
            }
        } content: {
            if let media = detail?.media, !media.isEmpty {
                JustifiedMemoryMediaGrid(media: media, maxRows: 3)
                    .accessibilityIdentifier("memory-gallery-preview")
            } else if isLoadingDetail {
                ProgressView("Loading photos...")
                    .font(.subheadline)
            } else if let mediaError {
                VeyqEmptyState(
                    symbolName: "exclamationmark.triangle",
                    title: "Photos could not be loaded",
                    rawMessage: mediaError,
                    tint: VeyqColor.danger
                )
            } else {
                VeyqEmptyState(
                    symbolName: "photo.badge.plus",
                    title: "No photos yet",
                    message: "Add photos or videos to start shaping this Memory.",
                    tint: VeyqColor.accent
                )
            }
        }
    }

    private var journalPreviewSection: some View {
        MemoryOverviewSection(title: "Journal") {
            if let markdown = detail?.journal.markdown, !markdown.isEmpty {
                NavigationLink {
                    MemoryJournalView(
                        title: headerTitle,
                        markdown: markdown,
                        media: detail?.media ?? [],
                        shareURL: internalShareURL(for: .journal)
                    )
                        .environmentObject(store)
                } label: {
                    Text("Read Journal")
                }
                .font(.subheadline.weight(.semibold))
                .accessibilityIdentifier("memory-read-journal-button")
            }
        } content: {
            if let markdown = detail?.journal.markdown, !markdown.isEmpty {
                MemoryJournalRenderer(markdown: markdown, media: detail?.media ?? [])
            } else {
                VeyqEmptyState(
                    symbolName: "text.alignleft",
                    title: "No journal yet",
                    message: "Journal text will appear here when this Memory has a written note.",
                    tint: VeyqColor.info
                )
            }
        }
    }

    private var chaptersCarouselSection: some View {
        MemoryOverviewSection(title: "Chapters") {
            EmptyView()
        } content: {
            if let chapters = detail?.chapters, !chapters.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        ForEach(chapters) { chapter in
                            NavigationLink(value: chapter) {
                                MemoryChapterCard(chapter: chapter)
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("memory-chapter-\(chapter.id)")
                        }
                    }
                    .scrollTargetLayout()
                }
                .scrollTargetBehavior(.viewAligned)
                .accessibilityIdentifier("memory-chapters-carousel")
            } else if isLoadingDetail {
                ProgressView("Loading chapters...")
                    .font(.subheadline)
            } else {
                VeyqEmptyState(
                    symbolName: "square.stack.3d.up.slash",
                    title: "No chapters yet",
                    message: "Related Memories will appear here when this Memory has children.",
                    tint: VeyqColor.info
                )
            }
        }
    }

    private var detailTaskID: String {
        "\(memory.id)-\(store.selectedTenant?.id ?? -1)"
    }

    private func internalShareURL(for destination: MemoryInternalShareDestination) -> URL? {
        MemoryInternalShareLink.url(
            instanceURL: store.connectedInstanceURL,
            memoryPath: memory.path,
            destination: destination
        )
    }

    @MainActor
    private func importPhotoPickerItems(_ items: [PhotosPickerItem]) async {
        importingPhotos = true
        defer {
            importingPhotos = false
            selectedPhotoItems = []
        }

        var imageURLs: [URL] = []
        var videoURLs: [URL] = []

        for item in items {
            let kind: VeyqUploadFileKind = item.supportedContentTypes.contains(where: { $0.conforms(to: .movie) }) ? .video : .image
            let filename = defaultUploadFilename(for: kind)
            do {
                if let data = try await item.loadTransferable(type: Data.self) {
                    let stored = try UploadQueuePersistence.writeIntoUploadStore(data: data, preferredFilename: filename, kind: kind)
                    if kind == .image {
                        imageURLs.append(stored)
                    } else {
                        videoURLs.append(stored)
                    }
                }
            } catch {
                store.reportError(error.localizedDescription)
            }
        }

        store.enqueueUploads(fileURLs: imageURLs, targetMemory: memory, kind: .image)
        store.enqueueUploads(fileURLs: videoURLs, targetMemory: memory, kind: .video)
    }

    private func defaultUploadFilename(for kind: VeyqUploadFileKind) -> String {
        let suffix = UUID().uuidString.prefix(8)
        return kind == .image ? "Photo-\(suffix).jpg" : "Video-\(suffix).mov"
    }
}

private struct CollapsingMemoryHeader: View {
    let title: String
    let dateRange: String
    let mediaCount: Int
    let chapterCount: Int
    let coverUrl: String?
    let coverCrop: CoverCrop?
    let progress: CGFloat

    private var expandedOpacity: Double {
        Double(1 - min(1, progress * 1.25))
    }

    private var compactOpacity: Double {
        Double(max(0, (progress - 0.28) / 0.72))
    }

    private var headerHeight: CGFloat {
        let expandedHeight: CGFloat = coverUrl == nil ? 126 : 282
        let compactHeight: CGFloat = 82
        return expandedHeight - ((expandedHeight - compactHeight) * progress)
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            expandedHeader
                .opacity(expandedOpacity)
                .allowsHitTesting(progress < 0.75)

            compactHeader
                .opacity(compactOpacity)
                .allowsHitTesting(progress >= 0.75)
        }
        .frame(maxWidth: .infinity, minHeight: headerHeight, maxHeight: headerHeight, alignment: .topLeading)
        .animation(.snappy(duration: 0.18), value: progress)
    }

    private var expandedHeader: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let coverUrl {
                AuthenticatedMediaImage(urlString: coverUrl, contentMode: .fill, crop: coverCrop)
                    .frame(height: 190)
                    .clipShape(RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
                    .overlay(alignment: .bottomLeading) {
                        LinearGradient(
                            colors: [.clear, .black.opacity(0.46)],
                            startPoint: .center,
                            endPoint: .bottom
                        )
                        .clipShape(RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
                    }
                    .accessibilityHidden(true)
                    .padding(.bottom, 4)
            }

            Text(title)
                .font(.title.bold())
                .accessibilityIdentifier("memory-detail-title")
            Text(dateRange)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            HStack(spacing: 14) {
                VeyqMetadataPill(symbolName: "photo.on.rectangle", text: L10n.format("%d photos", mediaCount), tint: VeyqColor.success)
                VeyqMetadataPill(symbolName: "square.stack.3d.up", text: L10n.format("%d chapters", chapterCount), tint: VeyqColor.info)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var compactHeader: some View {
        HStack(alignment: .center, spacing: 12) {
            if let coverUrl {
                AuthenticatedMediaImage(urlString: coverUrl, contentMode: .fill, crop: coverCrop)
                    .frame(width: 58, height: 58)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .accessibilityHidden(true)
            } else {
                Image(systemName: "photo")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(VeyqColor.accent)
                    .frame(width: 58, height: 58)
                    .background(VeyqColor.accent.opacity(0.14), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            }

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.title3.bold())
                    .lineLimit(2)
                    .accessibilityIdentifier("memory-detail-title")
            }
        }
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct MemoryDetailScrollOffsetKey: PreferenceKey {
    static let defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

private struct MemoryOverviewSection<Action: View, Content: View>: View {
    let title: LocalizedStringKey
    @ViewBuilder let action: Action
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                Text(title)
                    .font(.title3.bold())
                    .frame(maxWidth: .infinity, alignment: .leading)
                action
            }

            content
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private struct MemoryChapterCard: View {
    let chapter: Memory

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Group {
                if let coverUrl = chapter.coverUrl {
                    AuthenticatedMediaImage(urlString: coverUrl, contentMode: .fill, crop: chapter.coverCrop)
                } else {
                    ZStack {
                        VeyqColor.accent.opacity(0.14)
                        Image(systemName: "square.stack.3d.up")
                            .font(.title2.weight(.semibold))
                            .foregroundStyle(VeyqColor.accent)
                    }
                }
            }
            .frame(height: 104)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 4) {
                Text(chapter.displayName)
                    .font(.headline)
                    .lineLimit(2)
                Text(chapter.dateRange)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(L10n.format("%d photos", chapter.mediaCount))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(width: 220)
        .padding(12)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                .stroke(VeyqColor.hairline)
        }
    }
}

private struct MemoryGalleryView: View {
    let title: String
    let media: [MemoryMedia]
    let shareURL: URL?

    var body: some View {
        ZStack {
            VeyqScreenBackground()
            ScrollView {
                JustifiedMemoryMediaGrid(media: media)
                    .padding(.horizontal)
                    .padding(.bottom, VeyqLayout.floatingDockContentPadding)
            }
            .accessibilityIdentifier("memory-full-gallery")
        }
        .navigationTitle("Gallery")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let shareURL {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        ShareLink(item: shareURL) {
                            Label(L10n.string("Share"), systemImage: "square.and.arrow.up")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .accessibilityLabel(L10n.string("More"))
                    .accessibilityIdentifier("memory-gallery-more-button")
                }
            }
        }
    }
}

private struct MemoryJournalView: View {
    let title: String
    let markdown: String
    let media: [MemoryMedia]
    let shareURL: URL?

    var body: some View {
        ZStack {
            VeyqScreenBackground()
            ScrollView {
                MemoryJournalRenderer(markdown: markdown, media: media)
                    .padding(.horizontal)
                    .padding(.bottom, VeyqLayout.floatingDockContentPadding)
            }
            .accessibilityIdentifier("memory-journal-reader")
        }
        .navigationTitle("Journal")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let shareURL {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        ShareLink(item: shareURL) {
                            Label(L10n.string("Share"), systemImage: "square.and.arrow.up")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .accessibilityLabel(L10n.string("More"))
                    .accessibilityIdentifier("memory-journal-more-button")
                }
            }
        }
    }
}

enum MemoryDetailToolbarSymbols {
    static let upload = "photo.badge.plus"
}

enum MemoryInternalShareDestination {
    case gallery
    case journal
    case chapters

    var fragment: String? {
        switch self {
        case .gallery:
            return nil
        case .journal:
            return "journal"
        case .chapters:
            return "children"
        }
    }
}

enum MemoryInternalShareLink {
    static func url(instanceURL: URL?, memoryPath: String, destination: MemoryInternalShareDestination) -> URL? {
        guard let instanceURL,
              var components = URLComponents(url: instanceURL, resolvingAgainstBaseURL: false)
        else {
            return nil
        }

        let basePath = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let normalizedMemoryPath = memoryPath.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let pathParts = [basePath, normalizedMemoryPath].filter { !$0.isEmpty }
        components.path = pathParts.isEmpty ? "/" : "/\(pathParts.joined(separator: "/"))"
        components.query = nil
        components.fragment = destination.fragment
        return components.url
    }
}

private struct MemoryInfoSheet: View {
    @Environment(\.dismiss) private var dismiss
    let memory: Memory
    let detail: LoadedMemoryDetail?
    let spaceLabel: String

    var body: some View {
        NavigationStack {
            List {
                Section("Context") {
                    LabeledContent("Photos", value: "\(detail?.header.directMediaCount ?? memory.mediaCount)")
                    LabeledContent("Chapters", value: "\(detail?.header.chapterCount ?? memory.childCount)")
                    LabeledContent("Space", value: spaceLabel)
                    LabeledContent("Date", value: memory.dateRange)
                }
            }
            .navigationTitle("Details")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
        }
    }
}
