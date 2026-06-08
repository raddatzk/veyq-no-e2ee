import LinkPresentation
import MapKit
import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

private extension UTType {
    static let gpxTrack = UTType(filenameExtension: "gpx") ?? .xml
}

enum MemoryTrackSelection {
    static func synced(enabledTrackIDs: Set<String>, availableTrackIDs: Set<String>) -> Set<String> {
        guard !enabledTrackIDs.isEmpty else { return availableTrackIDs }
        let retained = enabledTrackIDs.intersection(availableTrackIDs)
        return retained.isEmpty ? availableTrackIDs : retained
    }
}

struct MemoryDetailView: View {
    @EnvironmentObject private var store: VeyqStore
    @Environment(\.dismiss) private var dismiss
    let memory: Memory
    @State private var selectedPhotoItems: [PhotosPickerItem] = []
    @State private var importingPhotos = false
    @State private var headerScrollOffset: CGFloat = 0
    @State private var infoPresented = false
    @State private var editMemoryPresented = false
    @State private var createChapterPresented = false
    @State private var coverPresented = false
    @State private var deleteConfirmationPresented = false
    @State private var deletingMemory = false
    @State private var enabledTrackIDs = Set<String>()
    @State private var importingTrackFiles = false

    private var detail: LoadedMemoryDetail? {
        store.memoryDetails[memory.id]
    }

    private var isLoadingDetail: Bool {
        store.memoryDetailLoadingIDs.contains(memory.id)
    }

    private var mediaError: String? {
        store.memoryDetailMediaErrors[memory.id]
    }

    private var mapPoints: [MemoryMapPoint] {
        MemoryMapPoint.points(from: visibleMedia)
    }

    private var allMapPoints: [MemoryMapPoint] {
        MemoryMapPoint.points(from: media)
    }

    private var media: [MemoryMedia] {
        detail?.media ?? []
    }

    private var visibleMedia: [MemoryMedia] {
        selectedMediaFilter.visibleMedia(from: media)
    }

    private var selectedMediaFilter: MemoryMediaFilter {
        store.mediaFilter(for: memory.id)
    }

    private var selectedMediaFilterBinding: Binding<MemoryMediaFilter> {
        Binding(
            get: { store.mediaFilter(for: memory.id) },
            set: { store.setMediaFilter($0, for: memory.id) }
        )
    }

    private var journalMarkdown: String {
        detail?.journal.markdown ?? ""
    }

    private var chapters: [Memory] {
        detail?.chapters ?? []
    }

    private var tracks: [MemoryTrack] {
        detail?.tracks ?? []
    }

    private var hasGalleryContent: Bool {
        !media.isEmpty || isLoadingDetail || mediaError != nil || store.supportsCapability("memory.media-resumable-upload")
    }

    private var hasKnownMediaContent: Bool {
        !media.isEmpty || (detail?.header.directMediaCount ?? memory.mediaCount) > 0
    }

    private var hasJournalContent: Bool {
        !journalMarkdown.isEmpty || isLoadingDetail || store.supportsCapability("memory.journal-markdown")
    }

    private var hasMapContent: Bool {
        store.supportsCapability("memory.maps")
            && (!allMapPoints.isEmpty || !tracks.isEmpty || isLoadingDetail || store.supportsCapability("memory.tracks"))
    }

    private var hasChapterContent: Bool {
        !chapters.isEmpty || isLoadingDetail || store.supportsCapability("memory.core")
    }

    private var canManageMemory: Bool {
        store.supportsCapability("memory.core")
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

    private var headerDateRange: String {
        let startDate = detail?.header.startDate ?? memory.startDate
        let endDate = detail?.header.endDate ?? memory.endDate
        return startDate == endDate ? startDate : "\(startDate) - \(endDate)"
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
                    .padding(.top, 8)
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
            await store.watchMemoryMediaUpdates(for: memory)
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

                if hasKnownMediaContent {
                    MemoryMediaFilterToggle(
                        filter: selectedMediaFilterBinding,
                        accessibilityIdentifier: "memory-detail-media-filter-button"
                    )
                }

                memoryMoreMenu
            }
        }
        .sheet(isPresented: $infoPresented) {
            MemoryInfoSheet(memory: memory, detail: detail, spaceLabel: store.spaceLabel(for: memory.path))
        }
        .sheet(isPresented: $editMemoryPresented) {
            MemoryAuthoringSheet(mode: .edit(memory: memory, header: detail?.header))
                .environmentObject(store)
        }
        .sheet(isPresented: $createChapterPresented) {
            MemoryAuthoringSheet(mode: .createChapter(parent: memory, parentTitle: headerTitle))
                .environmentObject(store)
        }
        .sheet(isPresented: $coverPresented) {
            MemoryCoverSheet(memory: memory, detail: detail)
                .environmentObject(store)
        }
        .confirmationDialog(
            L10n.string("Delete Memory"),
            isPresented: $deleteConfirmationPresented,
            titleVisibility: .visible
        ) {
            Button(L10n.string("Delete Memory"), role: .destructive) {
                Task { await deleteCurrentMemory() }
            }
            .disabled(deletingMemory)
            Button(L10n.string("Cancel"), role: .cancel) {}
        } message: {
            Text(L10n.format("Delete \"%@\" and its chapters and media?", headerTitle))
        }
        .onChange(of: selectedPhotoItems) { _, items in
            guard !items.isEmpty else { return }
            Task {
                await importPhotoPickerItems(items)
            }
        }
        .onChange(of: tracks.map(\.id)) { _, trackIDs in
            syncEnabledTracks(trackIDs)
        }
        .fileImporter(
            isPresented: $importingTrackFiles,
            allowedContentTypes: [.gpxTrack, .xml],
            allowsMultipleSelection: true
        ) { result in
            importTrackFiles(result)
        }
    }

    private var collapseProgress: CGFloat {
        min(1, max(0, -headerScrollOffset / 150))
    }

    private var memoryMoreMenu: some View {
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

            if canManageMemory {
                Button {
                    editMemoryPresented = true
                } label: {
                    Label(L10n.string("Edit Memory"), systemImage: "pencil")
                }
                Button {
                    coverPresented = true
                } label: {
                    Label(L10n.string("Cover"), systemImage: "photo")
                }
                Button(role: .destructive) {
                    deleteConfirmationPresented = true
                } label: {
                    Label(L10n.string("Delete Memory"), systemImage: "trash")
                }
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .accessibilityLabel(L10n.string("More"))
        .accessibilityIdentifier("memory-more-button")
    }

    private var collapsingHeader: some View {
        CollapsingMemoryHeader(
            title: headerTitle,
            dateRange: headerDateRange,
            mediaCount: detail?.header.directMediaCount ?? memory.mediaCount,
            chapterCount: detail?.header.chapterCount ?? memory.childCount,
            mappedMediaCount: mapPoints.count,
            trackCount: tracks.count,
            coverUrl: coverUrl,
            coverCrop: coverCrop,
            progress: collapseProgress
        )
    }

    @ViewBuilder
    private var overviewContent: some View {
        VStack(alignment: .leading, spacing: 26) {
            if hasJournalContent {
                journalPreviewSection
            }
            if hasGalleryContent {
                galleryPreviewSection
            }
            if hasMapContent {
                mapPreviewSection
            }
            if hasChapterContent {
                chaptersCarouselSection
            }
        }
    }

    private var galleryPreviewSection: some View {
        MemoryOverviewSection(title: "Gallery") {
            if !media.isEmpty {
                NavigationLink {
                    MemoryGalleryView(
                        memoryId: memory.id,
                        title: headerTitle,
                        media: media,
                        filter: selectedMediaFilterBinding,
                        shareURL: internalShareURL(for: .gallery)
                    )
                        .environmentObject(store)
                } label: {
                    Text("Open Gallery")
                }
                .font(.subheadline.weight(.semibold))
                .accessibilityIdentifier("memory-all-media-button")
            }
        } content: {
            if !media.isEmpty {
                Group {
                    if visibleMedia.isEmpty {
                        VeyqEmptyState(
                            symbolName: "star",
                            title: "No highlights yet",
                            message: "Mark favorite photos or videos as Highlights from the media viewer.",
                            tint: VeyqColor.warning
                        )
                    } else {
                        JustifiedMemoryMediaGrid(media: visibleMedia, maxRows: 3)
                    }
                }
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
            if !journalMarkdown.isEmpty {
                NavigationLink {
                    MemoryJournalView(
                        memory: memory,
                        title: headerTitle,
                        markdown: journalMarkdown,
                        media: media,
                        shareURL: internalShareURL(for: .journal)
                    )
                        .environmentObject(store)
                } label: {
                    Text("Read Journal")
                }
                .font(.subheadline.weight(.semibold))
                .accessibilityIdentifier("memory-read-journal-button")
            } else if store.supportsCapability("memory.journal-markdown") {
                NavigationLink {
                    MemoryJournalView(
                        memory: memory,
                        title: headerTitle,
                        markdown: "",
                        media: media,
                        shareURL: internalShareURL(for: .journal),
                        editingInitially: true
                    )
                        .environmentObject(store)
                } label: {
                    Text("Start Journal")
                }
                .font(.subheadline.weight(.semibold))
                .accessibilityIdentifier("memory-start-journal-button")
            }
        } content: {
            if !journalMarkdown.isEmpty {
                MemoryJournalRenderer(markdown: journalMarkdown, media: media)
            } else if isLoadingDetail {
                ProgressView("Loading journal...")
                    .font(.subheadline)
            } else {
                VeyqEmptyState(
                    symbolName: "text.alignleft",
                    title: "No journal yet",
                    message: "Write a short note to give this Memory context beyond the media.",
                    tint: VeyqColor.info
                )
            }
        }
    }

    private var chaptersCarouselSection: some View {
        MemoryOverviewSection(title: "Chapters") {
            if canManageMemory {
                Button {
                    createChapterPresented = true
                } label: {
                    Image(systemName: "plus")
                }
                .font(.subheadline.weight(.semibold))
                .accessibilityLabel(L10n.string("Create Chapter"))
                .accessibilityIdentifier("create-chapter-button")
            }
        } content: {
            if !chapters.isEmpty {
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
                    message: "Use chapters for days, places or smaller moments inside this Memory.",
                    tint: VeyqColor.info
                )
            }
        }
    }

    @ViewBuilder
    private var mapPreviewSection: some View {
        if store.supportsCapability("memory.maps") {
            MemoryOverviewSection(title: "Map") {
                if !mapPoints.isEmpty || !tracks.isEmpty || store.supportsCapability("memory.tracks") {
                    NavigationLink {
                        MemoryMapView(
                            points: mapPoints,
                            media: visibleMedia,
                            tracks: tracks,
                            mediaFilter: selectedMediaFilterBinding,
                            enabledTrackIDs: $enabledTrackIDs,
                            importingTrackFiles: $importingTrackFiles
                        )
                    } label: {
                        Text("Open Map")
                    }
                    .font(.subheadline.weight(.semibold))
                    .accessibilityIdentifier("memory-open-map-button")
                }
            } content: {
                if !mapPoints.isEmpty || !tracks.isEmpty {
                    MemoryMapPreview(
                        points: mapPoints,
                        media: visibleMedia,
                        tracks: tracks,
                        mediaFilter: selectedMediaFilterBinding,
                        enabledTrackIDs: $enabledTrackIDs,
                        importingTrackFiles: $importingTrackFiles
                    )
                        .accessibilityIdentifier("memory-map-preview")
                } else if !allMapPoints.isEmpty && selectedMediaFilter == .highlights {
                    VeyqEmptyState(
                        symbolName: "location.slash",
                        title: "No mapped highlights",
                        message: "Switch to All Media to see every mapped photo and video.",
                        tint: VeyqColor.warning
                    )
                    .accessibilityIdentifier("memory-map-empty-filtered")
                } else if isLoadingDetail {
                    ProgressView("Loading map...")
                        .font(.subheadline)
                } else if store.supportsCapability("memory.tracks") {
                    NavigationLink {
                        MemoryMapView(
                            points: mapPoints,
                            media: visibleMedia,
                            tracks: tracks,
                            mediaFilter: selectedMediaFilterBinding,
                            enabledTrackIDs: $enabledTrackIDs,
                            importingTrackFiles: $importingTrackFiles
                        )
                    } label: {
                        MemoryMapEmptyPreview()
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("memory-map-empty-link")
                }
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

    private func syncEnabledTracks(_ trackIDs: [String]) {
        enabledTrackIDs = MemoryTrackSelection.synced(enabledTrackIDs: enabledTrackIDs, availableTrackIDs: Set(trackIDs))
    }

    private func importTrackFiles(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            store.uploadTrackFiles(urls, targetMemory: memory)
        case .failure(let error):
            store.reportError(error.localizedDescription)
        }
    }

    @MainActor
    private func deleteCurrentMemory() async {
        deletingMemory = true
        defer { deletingMemory = false }
        do {
            try await store.deleteMemory(memory)
            dismiss()
        } catch {
            store.reportError(error.localizedDescription)
        }
    }
}

private struct CollapsingMemoryHeader: View {
    let title: String
    let dateRange: String
    let mediaCount: Int
    let chapterCount: Int
    let mappedMediaCount: Int
    let trackCount: Int
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
        let expandedHeight: CGFloat = coverUrl == nil ? 248 : 346
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
            coverHero
                .frame(height: 190)
                .clipShape(RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
                .overlay(alignment: .bottomLeading) {
                    LinearGradient(
                        colors: [.clear, .black.opacity(0.52)],
                        startPoint: .center,
                        endPoint: .bottom
                    )
                    .clipShape(RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
                }
                .overlay(alignment: .bottomLeading) {
                    if coverUrl == nil {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(title)
                                .font(.title3.bold())
                                .foregroundStyle(VeyqColor.onStrong)
                                .lineLimit(2)
                                .shadow(radius: 8)
                                .accessibilityIdentifier("memory-detail-title")
                            Text(dateRange)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(VeyqColor.onStrong.opacity(0.88))
                                .shadow(radius: 8)
                        }
                        .padding(14)
                    }
                }
                .accessibilityHidden(coverUrl != nil)
                .padding(.bottom, 4)

            if coverUrl != nil {
                Text(title)
                    .font(.title.bold())
                    .accessibilityIdentifier("memory-detail-title")
                Text(dateRange)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 14) {
                    headerMetadataPills
                }

                VStack(alignment: .leading, spacing: 8) {
                    headerMetadataPills
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var coverHero: some View {
        if let coverUrl {
            AuthenticatedMediaImage(urlString: coverUrl, contentMode: .fill, crop: coverCrop)
                .background(VeyqColor.accent.opacity(0.14))
        } else {
            MemoryPlaceholderCoverSurface()
        }
    }

    @ViewBuilder
    private var headerMetadataPills: some View {
        VeyqMetadataPill(symbolName: "photo.on.rectangle", text: L10n.format("%d photos", mediaCount), tint: VeyqColor.success)
        if chapterCount > 0 {
            VeyqMetadataPill(symbolName: "square.stack.3d.up", text: L10n.format("%d chapters", chapterCount), tint: VeyqColor.info)
        }
        if mappedMediaCount > 0 {
            VeyqMetadataPill(symbolName: "location", text: L10n.format("%d mapped media", mappedMediaCount), tint: VeyqColor.success)
        }
        if trackCount > 0 {
            VeyqMetadataPill(symbolName: "figure.walk", text: L10n.format("%d tracks", trackCount), tint: VeyqColor.warning)
        }
    }

    private var compactHeader: some View {
        HStack(alignment: .center, spacing: 12) {
            if let coverUrl {
                AuthenticatedMediaImage(urlString: coverUrl, contentMode: .fill, crop: coverCrop)
                    .frame(width: 58, height: 58)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .accessibilityHidden(true)
            } else {
                MemoryPlaceholderCoverSurface()
                    .frame(width: 58, height: 58)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .accessibilityHidden(true)
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

struct MemoryAuthoringSheet: View {
    @EnvironmentObject private var store: VeyqStore
    @Environment(\.dismiss) private var dismiss
    let mode: MemoryAuthoringMode

    @State private var displayName: String
    @State private var name: String
    @State private var startDate: Date
    @State private var endDate: Date
    @State private var nameEdited = false
    @State private var advancedPresented = false
    @State private var saving = false
    @State private var errorMessage: String?

    init(mode: MemoryAuthoringMode) {
        self.mode = mode
        let initial = mode.initialValues
        _displayName = State(initialValue: initial.displayName)
        _name = State(initialValue: initial.name)
        _startDate = State(initialValue: initial.startDate)
        _endDate = State(initialValue: initial.endDate)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(L10n.string("Title"), text: $displayName)
                        .textInputAutocapitalization(.words)
                        .autocorrectionDisabled(false)
                        .accessibilityIdentifier("memory-title-field")
                } header: {
                    Text("Memory")
                } footer: {
                    Text(mode.footer)
                }

                Section(L10n.string("Date Range")) {
                    DatePicker(L10n.string("Start Date"), selection: $startDate, displayedComponents: .date)
                        .accessibilityIdentifier("memory-start-date-picker")
                    DatePicker(L10n.string("End Date"), selection: $endDate, displayedComponents: .date)
                        .accessibilityIdentifier("memory-end-date-picker")
                }

                if mode.isEditing {
                    pathNameSection
                } else {
                    Section {
                        DisclosureGroup(isExpanded: $advancedPresented) {
                            pathNameField
                        } label: {
                            Label(L10n.string("Advanced"), systemImage: "slider.horizontal.3")
                        }
                    } footer: {
                        Text(L10n.string("The permalink is generated from the title and can usually stay unchanged."))
                    }
                }

                if let errorMessage {
                    Section {
                        Text(errorMessage)
                            .font(.caption)
                            .foregroundStyle(VeyqColor.danger)
                    }
                }
            }
            .navigationTitle(mode.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                    .disabled(saving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(saving ? L10n.string("Saving...") : L10n.string("Save")) {
                        Task { await save() }
                    }
                    .disabled(!canSave || saving)
                    .accessibilityIdentifier("memory-save-button")
                }
            }
            .onChange(of: displayName) { _, value in
                guard !nameEdited else { return }
                name = MemoryNameFormatter.slug(from: value)
            }
            .onChange(of: name) { _, value in
                nameEdited = true
                let sanitized = MemoryNameFormatter.slug(from: value)
                if sanitized != value {
                    name = sanitized
                }
            }
            .onChange(of: startDate) { oldValue, newValue in
                if endDate < newValue || Calendar.current.isDate(endDate, inSameDayAs: oldValue) {
                    endDate = newValue
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var canSave: Bool {
        !displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            startDate <= endDate
    }

    private var pathNameSection: some View {
        Section {
            pathNameField
        } header: {
            Text(L10n.string("Permalink"))
        } footer: {
            Text(L10n.string("Changing the path affects links to this Memory."))
        }
    }

    private var pathNameField: some View {
        TextField(L10n.string("Permalink"), text: $name)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .textContentType(.URL)
            .accessibilityIdentifier("memory-name-field")
    }

    @MainActor
    private func save() async {
        guard canSave else { return }
        saving = true
        errorMessage = nil
        defer { saving = false }

        do {
            let start = VeyqDateOnlyFormatter.string(from: startDate)
            let end = VeyqDateOnlyFormatter.string(from: endDate)
            switch mode {
            case .createRoot:
                _ = try await store.createMemory(displayName: displayName, name: name, startDate: start, endDate: end)
            case .createChapter(let parent, _):
                _ = try await store.createMemory(displayName: displayName, name: name, startDate: start, endDate: end, parentMemory: parent)
            case .edit(let memory, _):
                _ = try await store.updateMemory(memory, displayName: displayName, name: name, startDate: start, endDate: end)
            }
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

enum MemoryAuthoringMode {
    case createRoot
    case createChapter(parent: Memory, parentTitle: String)
    case edit(memory: Memory, header: MemoryHeader?)

    var title: String {
        switch self {
        case .createRoot: L10n.string("Create Memory")
        case .createChapter: L10n.string("Create Chapter")
        case .edit: L10n.string("Edit Memory")
        }
    }

    var footer: String {
        switch self {
        case .createRoot:
            L10n.string("Start with a title and date range. Photos, journal notes and context can be added next.")
        case .createChapter(_, let parentTitle):
            L10n.format("Creates a chapter inside \"%@\" for a day, place or smaller moment.", parentTitle)
        case .edit:
            L10n.string("Updates the Memory title, permalink and date range.")
        }
    }

    var isEditing: Bool {
        if case .edit = self {
            return true
        }
        return false
    }

    var initialValues: MemoryAuthoringInitialValues {
        switch self {
        case .createRoot:
            let now = Date()
            return MemoryAuthoringInitialValues(displayName: "", name: "", startDate: now, endDate: now)
        case .createChapter:
            let now = Date()
            return MemoryAuthoringInitialValues(displayName: "", name: "", startDate: now, endDate: now)
        case .edit(let memory, let header):
            return MemoryAuthoringInitialValues(
                displayName: header?.displayName ?? memory.displayName,
                name: memory.name,
                startDate: VeyqDateOnlyFormatter.date(from: header?.startDate ?? memory.startDate),
                endDate: VeyqDateOnlyFormatter.date(from: header?.endDate ?? memory.endDate)
            )
        }
    }
}

struct MemoryAuthoringInitialValues {
    let displayName: String
    let name: String
    let startDate: Date
    let endDate: Date
}

private struct MemoryCoverSheet: View {
    @EnvironmentObject private var store: VeyqStore
    @Environment(\.dismiss) private var dismiss
    let memory: Memory
    let detail: LoadedMemoryDetail?

    @State private var savingMediaId: String?
    @State private var removing = false
    @State private var errorMessage: String?

    private var imageMedia: [MemoryMedia] {
        (detail?.media ?? []).filter(\.isImage)
    }

    private var currentCoverMediaId: String? {
        memory.coverMediaId
    }

    private var hasCover: Bool {
        (detail?.header.coverUrl ?? memory.coverUrl)?.isEmpty == false
    }

    var body: some View {
        NavigationStack {
            List {
                if hasCover {
                    Section {
                        Button(role: .destructive) {
                            Task { await removeCover() }
                        } label: {
                            Label(removing ? L10n.string("Removing Cover...") : L10n.string("Remove Cover"), systemImage: "trash")
                        }
                        .disabled(removing || savingMediaId != nil)
                        .accessibilityIdentifier("remove-cover-button")
                    }
                }

                Section {
                    if imageMedia.isEmpty {
                        VeyqEmptyState(
                            symbolName: "photo.on.rectangle.angled",
                            title: "No image media",
                            message: "Add image media before choosing a cover.",
                            tint: VeyqColor.info
                        )
                    } else {
                        ForEach(imageMedia) { media in
                            Button {
                                Task { await setCover(media) }
                            } label: {
                                CoverMediaRow(
                                    media: media,
                                    isSelected: media.id == currentCoverMediaId,
                                    isSaving: savingMediaId == media.id
                                )
                            }
                            .buttonStyle(.plain)
                            .disabled(removing || savingMediaId != nil)
                            .accessibilityIdentifier("cover-media-\(media.id)")
                        }
                    }
                } header: {
                    Text(L10n.string("Image Media"))
                } footer: {
                    Text(L10n.string("Uses a centered 16:9 cover crop. Crop adjustment can be added later."))
                }

                if let errorMessage {
                    Section {
                        Text(errorMessage)
                            .font(.caption)
                            .foregroundStyle(VeyqColor.danger)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(L10n.string("Cover"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(L10n.string("Done")) {
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    @MainActor
    private func setCover(_ media: MemoryMedia) async {
        savingMediaId = media.id
        errorMessage = nil
        defer { savingMediaId = nil }
        do {
            _ = try await store.setMemoryCover(for: memory, media: media)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    @MainActor
    private func removeCover() async {
        removing = true
        errorMessage = nil
        defer { removing = false }
        do {
            _ = try await store.removeMemoryCover(for: memory)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private struct CoverMediaRow: View {
    let media: MemoryMedia
    let isSelected: Bool
    let isSaving: Bool

    var body: some View {
        HStack(spacing: 12) {
            AuthenticatedMediaImage(urlString: media.preferredTileUrl, contentMode: .fill)
                .frame(width: 56, height: 56)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 4) {
                Text(media.displayTitle)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Text(media.mimeType)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer()

            if isSaving {
                ProgressView()
            } else if isSelected {
                Image(systemName: "checkmark.circle.fill")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(VeyqColor.accent)
            }
        }
        .contentShape(Rectangle())
    }
}

enum VeyqDateOnlyFormatter {
    private static let formatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }()

    static func string(from date: Date) -> String {
        formatter.string(from: date)
    }

    static func date(from value: String) -> Date {
        formatter.date(from: value) ?? Date()
    }
}

enum MemoryNameFormatter {
    static func slug(from value: String) -> String {
        let folded = value.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: .current)
        let scalars = folded.unicodeScalars.map { scalar -> Character in
            if CharacterSet.alphanumerics.contains(scalar) {
                return Character(String(scalar).lowercased())
            }
            if scalar == "-" || scalar == "_" {
                return Character(String(scalar))
            }
            return "-"
        }
        let collapsed = String(scalars).replacingOccurrences(of: "-+", with: "-", options: .regularExpression)
        return collapsed.trimmingCharacters(in: CharacterSet(charactersIn: "-_"))
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

private struct MemoryMapPreview: View {
    @EnvironmentObject private var store: VeyqStore
    let points: [MemoryMapPoint]
    let media: [MemoryMedia]
    let tracks: [MemoryTrack]
    @Binding var mediaFilter: MemoryMediaFilter
    @Binding var enabledTrackIDs: Set<String>
    @Binding var importingTrackFiles: Bool
    @State private var position: MapCameraPosition = .automatic
    @State private var visibleMapRect: MKMapRect?
    @State private var mapViewportSize: CGSize = .zero

    init(
        points: [MemoryMapPoint],
        media: [MemoryMedia],
        tracks: [MemoryTrack],
        mediaFilter: Binding<MemoryMediaFilter>,
        enabledTrackIDs: Binding<Set<String>>,
        importingTrackFiles: Binding<Bool>
    ) {
        self.points = points
        self.media = media
        self.tracks = tracks
        _mediaFilter = mediaFilter
        _enabledTrackIDs = enabledTrackIDs
        _importingTrackFiles = importingTrackFiles
        let availableTracks = tracks.filter(\.hasRenderableGeometry)
        let selectedTrackIDs = MemoryTrackSelection.synced(
            enabledTrackIDs: enabledTrackIDs.wrappedValue,
            availableTrackIDs: Set(availableTracks.map(\.id))
        )
        let selectedTracks = availableTracks.filter { selectedTrackIDs.contains($0.id) }
        _position = State(initialValue: MemoryMapRegion.cameraPosition(points: points, tracks: selectedTracks))
    }

    private var enabledTracks: [MemoryTrack] {
        tracks.filter { enabledTrackIDs.contains($0.id) && $0.hasRenderableGeometry }
    }

    private var clusters: [MemoryMapCluster] {
        MemoryMapCluster.clusters(
            from: points,
            in: visibleMapRect ?? MemoryMapRegion.mapRect(points: points, tracks: enabledTracks),
            viewportSize: mapViewportSize,
            markerDimension: MemoryMapClusterMarker.dimension(for: .compact, count: 2)
        )
    }

    var body: some View {
        NavigationLink {
            MemoryMapView(
                points: points,
                media: media,
                tracks: tracks,
                mediaFilter: $mediaFilter,
                enabledTrackIDs: $enabledTrackIDs,
                importingTrackFiles: $importingTrackFiles
            )
        } label: {
            Map(position: $position) {
                ForEach(Array(enabledTracks.enumerated()), id: \.element.id) { index, track in
                    MapPolyline(coordinates: track.coordinates)
                        .stroke(MemoryTrackPalette.color(at: index), style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
                }

                ForEach(MemoryTrackEndpoint.endpoints(from: enabledTracks)) { endpoint in
                    Annotation("", coordinate: endpoint.coordinate) {
                        MemoryTrackEndpointMarker(endpoint: endpoint)
                            .accessibilityLabel(endpoint.accessibilityLabel)
                    }
                }

                ForEach(clusters) { cluster in
                    Annotation("", coordinate: cluster.coordinate) {
                        MemoryMapClusterMarker(store: store, media: primaryMedia(for: cluster), count: cluster.count, size: .compact)
                            .accessibilityLabel(cluster.accessibilityLabel)
                    }
                }
            }
            .mapStyle(.standard(elevation: .flat, pointsOfInterest: .excludingAll))
            .onMapCameraChange(frequency: .onEnd) { context in
                visibleMapRect = context.rect
            }
            .frame(height: 168)
            .background {
                GeometryReader { proxy in
                    Color.clear
                        .onAppear {
                            mapViewportSize = proxy.size
                        }
                        .onChange(of: proxy.size) { _, size in
                            mapViewportSize = size
                        }
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
            .allowsHitTesting(false)
            .overlay(alignment: .bottomLeading) {
                VStack(alignment: .leading, spacing: 7) {
                    Label(mapSummary, systemImage: "location")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(VeyqColor.success)
                    Label(L10n.string("Open Map"), systemImage: "arrow.up.left.and.arrow.down.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(VeyqColor.accent)
                }
                .padding(.horizontal, 11)
                .padding(.vertical, 9)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: VeyqLayout.controlRadius, style: .continuous))
                .padding(10)
            }
            .overlay {
                RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                    .stroke(VeyqColor.hairline)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L10n.string("Open Map"))
        .onAppear {
            syncEnabledTrackSelection()
            syncMapPosition()
        }
        .onChange(of: enabledTrackIDs) {
            syncMapPosition()
        }
        .onChange(of: tracks.map(\.id)) {
            syncEnabledTrackSelection()
            syncMapPosition()
        }
        .onChange(of: points.map(\.id)) {
            syncMapPosition()
        }
    }

    private var mapSummary: String {
        L10n.format("%d mapped media · %d tracks", points.count, enabledTracks.count)
    }

    private func toggleTrack(_ track: MemoryTrack) {
        if enabledTrackIDs.contains(track.id) {
            enabledTrackIDs.remove(track.id)
        } else {
            enabledTrackIDs.insert(track.id)
        }
    }

    private func syncEnabledTrackSelection() {
        let available = Set(tracks.filter(\.hasRenderableGeometry).map(\.id))
        enabledTrackIDs = MemoryTrackSelection.synced(enabledTrackIDs: enabledTrackIDs, availableTrackIDs: available)
    }

    private func syncMapPosition() {
        position = MemoryMapRegion.cameraPosition(points: points, tracks: enabledTracks)
        visibleMapRect = MemoryMapRegion.mapRect(points: points, tracks: enabledTracks)
    }

    private func primaryMedia(for cluster: MemoryMapCluster) -> MemoryMedia? {
        let clusterMediaIDs = Set(cluster.mediaIDs)
        return media.first { clusterMediaIDs.contains($0.id) }
    }
}

private struct MemoryMapEmptyPreview: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                    .fill(VeyqColor.surface)
                Image(systemName: "map")
                    .font(.system(size: 44, weight: .semibold))
                    .foregroundStyle(VeyqColor.info)
                Image(systemName: "figure.walk")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(VeyqColor.onStrong)
                    .frame(width: 38, height: 38)
                    .background(VeyqColor.warning, in: Circle())
                    .offset(x: 42, y: 30)
            }
            .frame(height: 168)
            .overlay {
                RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                    .stroke(VeyqColor.hairline)
            }
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 6) {
                Text("No map yet")
                    .font(.headline)
                Text("Upload photos with location data or GPX tracks to enrich this Memory.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(14)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                .stroke(VeyqColor.hairline)
        }
    }
}

struct MemoryMapCluster: Identifiable, Equatable {
    let id: String
    let coordinate: CLLocationCoordinate2D
    let points: [MemoryMapPoint]

    var count: Int {
        points.count
    }

    var mediaIDs: [String] {
        points.map(\.mediaId)
    }

    var accessibilityLabel: String {
        count == 1 ? (points.first?.title ?? L10n.string("Mapped media")) : L10n.format("%d photos", count)
    }

    static func clusters(from points: [MemoryMapPoint]) -> [MemoryMapCluster] {
        clusters(from: points, in: nil, viewportSize: .zero, markerDimension: 44)
    }

    static func clusters(
        from points: [MemoryMapPoint],
        in mapRect: MKMapRect?,
        viewportSize: CGSize,
        markerDimension: CGFloat
    ) -> [MemoryMapCluster] {
        var accumulators: [ClusterAccumulator] = []
        let maximumClusterDistanceMeters = maximumClusterDistanceMeters(
            in: mapRect,
            viewportSize: viewportSize,
            markerDimension: markerDimension
        )
        let sortedPoints = points.sorted { lhs, rhs in
            lhs.mediaId.localizedStandardCompare(rhs.mediaId) == .orderedAscending
        }

        for point in sortedPoints {
            let location = CLLocation(latitude: point.latitude, longitude: point.longitude)
            if let index = accumulators.firstIndex(where: { $0.location.distance(from: location) <= maximumClusterDistanceMeters }) {
                accumulators[index].append(point)
            } else {
                accumulators.append(ClusterAccumulator(point: point))
            }
        }

        return accumulators.map { accumulator in
            return MemoryMapCluster(
                id: accumulator.points.map(\.mediaId).joined(separator: ":"),
                coordinate: accumulator.coordinate,
                points: accumulator.points
            )
        }
        .sorted { lhs, rhs in
            lhs.id.localizedStandardCompare(rhs.id) == .orderedAscending
        }
    }

    private static func maximumClusterDistanceMeters(
        in mapRect: MKMapRect?,
        viewportSize: CGSize,
        markerDimension: CGFloat
    ) -> CLLocationDistance {
        guard let mapRect,
              viewportSize.width > 0,
              viewportSize.height > 0,
              markerDimension > 0
        else {
            return 50_000
        }
        let centerLatitude = MKMapPoint(x: mapRect.midX, y: mapRect.midY).coordinate.latitude
        let metersPerMapPoint = MKMetersPerMapPointAtLatitude(centerLatitude)
        let visibleMeters = max(mapRect.width, mapRect.height) * metersPerMapPoint
        let viewportPoints = Double(max(viewportSize.width, viewportSize.height))
        guard viewportPoints > 0 else { return 50_000 }
        let metersPerScreenPoint = visibleMeters / viewportPoints
        return Double(markerDimension) * 2 * metersPerScreenPoint
    }

    private struct ClusterAccumulator {
        private(set) var points: [MemoryMapPoint]
        private(set) var coordinate: CLLocationCoordinate2D

        var location: CLLocation {
            CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)
        }

        init(point: MemoryMapPoint) {
            points = [point]
            coordinate = point.coordinate
        }

        mutating func append(_ point: MemoryMapPoint) {
            points.append(point)
            let latitude = points.map(\.latitude).reduce(0, +) / Double(points.count)
            let longitude = points.map(\.longitude).reduce(0, +) / Double(points.count)
            coordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
        }

        var mediaIDs: [String] {
            points.map(\.mediaId)
        }
    }

    static func == (lhs: MemoryMapCluster, rhs: MemoryMapCluster) -> Bool {
        lhs.id == rhs.id
            && lhs.points == rhs.points
            && lhs.coordinate.latitude == rhs.coordinate.latitude
            && lhs.coordinate.longitude == rhs.coordinate.longitude
    }
}

private struct MemoryMapClusterMarker: View {
    enum Size {
        case compact
        case regular
    }

    let store: VeyqStore
    let media: MemoryMedia?
    let count: Int
    let size: Size
    @State private var image: UIImage?
    @State private var loadedURLString: String?
    @State private var loading = false

    static func dimension(for size: Size, count: Int) -> CGFloat {
        switch size {
        case .compact:
            count > 1 ? 34 : 30
        case .regular:
            count > 1 ? 44 : 38
        }
    }

    private var dimension: CGFloat {
        Self.dimension(for: size, count: count)
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Circle()
                .fill(VeyqColor.surface)

            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: dimension, height: dimension)
                    .clipShape(Circle())
            } else {
                Image(systemName: "photo.fill")
                    .font(.system(size: size == .compact ? 13 : 15, weight: .bold))
                    .foregroundStyle(VeyqColor.accent)
                    .opacity(loading ? 0.50 : 0.86)
            }

            Circle()
                .stroke(VeyqColor.onStrong.opacity(0.86), lineWidth: 2)

            if count > 1 {
                Text("\(count)")
                    .font(.system(size: size == .compact ? 11 : 12, weight: .bold))
                    .foregroundStyle(VeyqColor.onStrong)
                    .monospacedDigit()
                    .minimumScaleFactor(0.70)
                    .lineLimit(1)
                    .padding(.horizontal, 5)
                    .frame(minWidth: size == .compact ? 18 : 21, minHeight: size == .compact ? 18 : 21)
                    .background(VeyqColor.accent, in: Capsule())
                    .overlay {
                        Capsule()
                            .stroke(VeyqColor.onStrong.opacity(0.90), lineWidth: 1.5)
                    }
                    .offset(x: 5, y: 5)
            }
        }
        .frame(width: dimension, height: dimension)
        .shadow(color: VeyqColor.cardShadow, radius: size == .compact ? 6 : 8, x: 0, y: size == .compact ? 3 : 4)
        .task(id: media?.preferredTileUrl) {
            guard let urlString = media?.preferredTileUrl, !urlString.isEmpty else {
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

struct MemoryTrackEndpoint: Identifiable, Equatable {
    enum Kind: String {
        case start
        case end
    }

    let id: String
    let coordinate: CLLocationCoordinate2D
    let tint: Color
    let kind: Kind

    var symbolName: String {
        switch kind {
        case .start: "circle.fill"
        case .end: "flag"
        }
    }

    var accessibilityLabel: String {
        switch kind {
        case .start: L10n.string("Track start")
        case .end: L10n.string("Track end")
        }
    }

    static func endpoints(from tracks: [MemoryTrack]) -> [MemoryTrackEndpoint] {
        tracks.enumerated().flatMap { index, track in
            guard let first = track.coordinates.first,
                  let last = track.coordinates.last
            else {
                return [MemoryTrackEndpoint]()
            }
            let tint = MemoryTrackPalette.color(at: index)
            return [
                MemoryTrackEndpoint(id: "\(track.id)-start", coordinate: first, tint: tint, kind: .start),
                MemoryTrackEndpoint(id: "\(track.id)-end", coordinate: last, tint: tint, kind: .end)
            ]
        }
    }

    static func == (lhs: MemoryTrackEndpoint, rhs: MemoryTrackEndpoint) -> Bool {
        lhs.id == rhs.id
            && lhs.coordinate.latitude == rhs.coordinate.latitude
            && lhs.coordinate.longitude == rhs.coordinate.longitude
            && lhs.kind == rhs.kind
    }
}

private struct MemoryTrackEndpointMarker: View {
    let endpoint: MemoryTrackEndpoint

    var body: some View {
        Image(systemName: endpoint.symbolName)
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(endpoint.tint)
            .frame(width: 24, height: 24)
            .background(.regularMaterial, in: Circle())
            .overlay {
                Circle()
                    .stroke(VeyqColor.onStrong.opacity(0.86), lineWidth: 1.5)
            }
            .shadow(color: VeyqColor.cardShadow, radius: 5, x: 0, y: 2)
    }
}

private struct MemoryMapView: View {
    let points: [MemoryMapPoint]
    let media: [MemoryMedia]
    let tracks: [MemoryTrack]
    @Binding var mediaFilter: MemoryMediaFilter
    @Binding var enabledTrackIDs: Set<String>
    @Binding var importingTrackFiles: Bool
    @EnvironmentObject private var store: VeyqStore
    @State private var position: MapCameraPosition = .automatic
    @State private var visibleMapRect: MKMapRect?
    @State private var mapViewportSize: CGSize = .zero
    @State private var mapControlsSize: CGSize = .zero
    @State private var viewerMedia: [MemoryMedia] = []
    @State private var viewerSelectedIndex: Int?
    @State private var trackShareItem: MemoryTrackShareItem?
    @State private var sharingTrackID: String?
    @State private var trackShareErrorMessage: String?

    init(
        points: [MemoryMapPoint],
        media: [MemoryMedia],
        tracks: [MemoryTrack],
        mediaFilter: Binding<MemoryMediaFilter>,
        enabledTrackIDs: Binding<Set<String>>,
        importingTrackFiles: Binding<Bool>
    ) {
        self.points = points
        self.media = media
        self.tracks = tracks
        _mediaFilter = mediaFilter
        _enabledTrackIDs = enabledTrackIDs
        _importingTrackFiles = importingTrackFiles
        let availableTracks = tracks.filter(\.hasRenderableGeometry)
        let selectedTrackIDs = MemoryTrackSelection.synced(
            enabledTrackIDs: enabledTrackIDs.wrappedValue,
            availableTrackIDs: Set(availableTracks.map(\.id))
        )
        let selectedTracks = availableTracks.filter { selectedTrackIDs.contains($0.id) }
        _position = State(initialValue: MemoryMapRegion.cameraPosition(points: points, tracks: selectedTracks))
    }

    private var enabledTracks: [MemoryTrack] {
        tracks.filter { enabledTrackIDs.contains($0.id) && $0.hasRenderableGeometry }
    }

    private var clusters: [MemoryMapCluster] {
        MemoryMapCluster.clusters(
            from: points,
            in: visibleMapRect ?? MemoryMapRegion.mapRect(points: points, tracks: enabledTracks),
            viewportSize: mapViewportSize,
            markerDimension: MemoryMapClusterMarker.dimension(for: .regular, count: 2)
        )
    }

    private var isEmpty: Bool {
        points.isEmpty && tracks.isEmpty
    }

    private var mapSummary: String {
        L10n.format("%d mapped media · %d tracks", points.count, enabledTracks.count)
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            if isEmpty {
                VeyqScreenBackground()
                MemoryMapEmptyPreview()
                    .padding()
                    .frame(maxHeight: .infinity, alignment: .center)
                    .accessibilityIdentifier("memory-map-fullscreen-empty")
            } else {
                Map(position: $position) {
                    ForEach(Array(enabledTracks.enumerated()), id: \.element.id) { index, track in
                        MapPolyline(coordinates: track.coordinates)
                            .stroke(MemoryTrackPalette.color(at: index), style: StrokeStyle(lineWidth: 6, lineCap: .round, lineJoin: .round))
                    }

                    ForEach(MemoryTrackEndpoint.endpoints(from: enabledTracks)) { endpoint in
                        Annotation("", coordinate: endpoint.coordinate) {
                            MemoryTrackEndpointMarker(endpoint: endpoint)
                                .accessibilityLabel(endpoint.accessibilityLabel)
                        }
                    }

                    ForEach(clusters) { cluster in
                        Annotation("", coordinate: cluster.coordinate) {
                            Button {
                                openMediaViewer(for: cluster)
                            } label: {
                                MemoryMapClusterMarker(store: store, media: primaryMedia(for: cluster), count: cluster.count, size: .regular)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(cluster.accessibilityLabel)
                        }
                    }
                }
                .mapStyle(.standard(elevation: .realistic, pointsOfInterest: .excludingAll))
                .background {
                    GeometryReader { proxy in
                        Color.clear
                            .onAppear {
                                mapViewportSize = proxy.size
                            }
                            .onChange(of: proxy.size) { _, size in
                                mapViewportSize = size
                            }
                    }
                }
                .onMapCameraChange(frequency: .continuous) { context in
                    visibleMapRect = context.rect
                }
                .ignoresSafeArea(edges: .bottom)
                .accessibilityIdentifier("memory-map-fullscreen")

                mapControls
                    .padding(.horizontal)
                    .padding(.bottom, 18)
                    .background {
                        GeometryReader { proxy in
                            Color.clear
                                .onAppear {
                                    mapControlsSize = proxy.size
                                }
                                .onChange(of: proxy.size) { _, size in
                                    mapControlsSize = size
                                }
                        }
                    }
            }
        }
        .navigationTitle("Map")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.regularMaterial, for: .navigationBar)
        .toolbar {
            if !isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    MemoryMediaFilterToggle(
                        filter: $mediaFilter,
                        accessibilityIdentifier: "memory-map-filter-button"
                    )
                }
            }

            if store.supportsCapability("memory.tracks") {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        importingTrackFiles = true
                    } label: {
                        Image(systemName: "map.fill")
                    }
                    .accessibilityLabel(L10n.string("Add GPX Track"))
                    .accessibilityIdentifier("memory-map-toolbar-add-gpx-track-button")
                }
            }

            if !isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        recenterMap()
                    } label: {
                        Image(systemName: "location.viewfinder")
                    }
                    .accessibilityLabel(L10n.string("Recenter Map"))
                }
            }
        }
        .onAppear {
            syncEnabledTrackSelection()
            syncMapPosition()
        }
        .onChange(of: enabledTrackIDs) {
            syncMapPosition()
        }
        .onChange(of: tracks.map(\.id)) {
            syncEnabledTrackSelection()
            syncMapPosition()
        }
        .onChange(of: points.map(\.id)) {
            syncMapPosition()
        }
        .onChange(of: mapControlsSize) {
            syncMapPosition()
        }
        .fullScreenCover(isPresented: Binding(
            get: { viewerSelectedIndex != nil },
            set: { isPresented in
                if !isPresented {
                    viewerSelectedIndex = nil
                    viewerMedia = []
                }
            }
        )) {
            MemoryMediaViewer(
                media: viewerMedia,
                selectedIndex: Binding(
                    get: { viewerSelectedIndex ?? 0 },
                    set: { viewerSelectedIndex = $0 }
                )
            )
            .environmentObject(store)
        }
        .sheet(item: $trackShareItem) { item in
            MemoryTrackActivityView(item: item)
        }
        .alert(L10n.string("Track could not be shared."), isPresented: Binding(
            get: { trackShareErrorMessage != nil },
            set: { isPresented in
                if !isPresented {
                    trackShareErrorMessage = nil
                }
            }
        )) {
            Button(L10n.string("OK"), role: .cancel) {}
        } message: {
            if let trackShareErrorMessage {
                Text(trackShareErrorMessage)
            }
        }
    }

    private var mapControls: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Label(mapSummary, systemImage: "location")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(VeyqColor.success)
                Spacer(minLength: 8)
                Button {
                    recenterMap()
                } label: {
                    Image(systemName: "location.viewfinder")
                        .frame(width: 34, height: 34)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .accessibilityLabel(L10n.string("Recenter Map"))
            }

            if !tracks.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                            MemoryTrackSummaryRow(
                                track: track,
                                tint: MemoryTrackPalette.color(at: index),
                                enabled: enabledTrackIDs.contains(track.id),
                                focusAction: {
                                    focusTrack(track)
                                },
                                visibilityAction: {
                                    toggleTrack(track)
                                },
                                shareAction: {
                                    Task {
                                        await shareTrack(track)
                                    }
                                },
                                sharing: sharingTrackID == track.id
                            )
                            .frame(width: 336)
                        }
                    }
                    .padding(.vertical, 1)
                }
                .accessibilityIdentifier("memory-track-summaries")
            }
        }
        .padding(14)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                .stroke(VeyqColor.hairline)
        }
        .shadow(color: VeyqColor.dockShadow, radius: 18, x: 0, y: 8)
    }

    private func toggleTrack(_ track: MemoryTrack) {
        if enabledTrackIDs.contains(track.id) {
            enabledTrackIDs.remove(track.id)
        } else {
            enabledTrackIDs.insert(track.id)
        }
    }

    private func syncEnabledTrackSelection() {
        let available = Set(tracks.filter(\.hasRenderableGeometry).map(\.id))
        enabledTrackIDs = MemoryTrackSelection.synced(enabledTrackIDs: enabledTrackIDs, availableTrackIDs: available)
    }

    private func syncMapPosition() {
        let nextRect = overlayAdjustedMapRect(MemoryMapRegion.mapRect(points: points, tracks: enabledTracks))
        position = .rect(nextRect)
        visibleMapRect = nextRect
    }

    private func recenterMap() {
        let nextRect = overlayAdjustedMapRect(MemoryMapRegion.mapRect(points: points, tracks: enabledTracks))
        position = .automatic
        Task { @MainActor in
            await Task.yield()
            visibleMapRect = nextRect
            position = .rect(nextRect)
        }
    }

    private func focusTrack(_ track: MemoryTrack) {
        guard track.hasRenderableGeometry else { return }
        enabledTrackIDs.insert(track.id)
        let nextRect = overlayAdjustedMapRect(MemoryMapRegion.mapRect(points: [], tracks: [track]))
        position = .automatic
        Task { @MainActor in
            await Task.yield()
            visibleMapRect = nextRect
            position = .rect(nextRect)
        }
    }

    @MainActor
    private func shareTrack(_ track: MemoryTrack) async {
        guard sharingTrackID == nil else { return }
        sharingTrackID = track.id
        defer { sharingTrackID = nil }
        do {
            let filename = track.downloadFilename
            let url = try await store.loadMediaFile(from: track.contentUrl, preferredFilename: filename)
            trackShareItem = MemoryTrackShareItem(url: url, filename: filename)
        } catch {
            trackShareErrorMessage = error.localizedDescription
        }
    }

    private func overlayAdjustedMapRect(_ rect: MKMapRect) -> MKMapRect {
        MemoryMapRegion.mapRect(
            rect,
            fittingViewportSize: mapViewportSize,
            bottomObscuredHeight: mapControlsSize.height
        )
    }

    private func openMediaViewer(for cluster: MemoryMapCluster) {
        let clusterMediaIDs = Set(cluster.mediaIDs)
        let selectedMedia = media.filter { clusterMediaIDs.contains($0.id) }
        guard !selectedMedia.isEmpty else { return }
        viewerMedia = selectedMedia
        viewerSelectedIndex = 0
    }

    private func primaryMedia(for cluster: MemoryMapCluster) -> MemoryMedia? {
        let clusterMediaIDs = Set(cluster.mediaIDs)
        return media.first { clusterMediaIDs.contains($0.id) }
    }
}

private struct MemoryTrackShareItem: Identifiable {
    let url: URL
    let filename: String

    var id: String { url.absoluteString }
}

private struct MemoryTrackActivityView: UIViewControllerRepresentable {
    let item: MemoryTrackShareItem

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(
            activityItems: [MemoryTrackActivityItemSource(item: item)],
            applicationActivities: nil
        )
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

private final class MemoryTrackActivityItemSource: NSObject, UIActivityItemSource {
    let item: MemoryTrackShareItem

    init(item: MemoryTrackShareItem) {
        self.item = item
    }

    func activityViewControllerPlaceholderItem(_ activityViewController: UIActivityViewController) -> Any {
        item.url
    }

    func activityViewController(
        _ activityViewController: UIActivityViewController,
        itemForActivityType activityType: UIActivity.ActivityType?
    ) -> Any? {
        item.url
    }

    func activityViewController(
        _ activityViewController: UIActivityViewController,
        subjectForActivityType activityType: UIActivity.ActivityType?
    ) -> String {
        item.filename
    }

    func activityViewControllerLinkMetadata(_ activityViewController: UIActivityViewController) -> LPLinkMetadata? {
        let metadata = LPLinkMetadata()
        metadata.title = item.filename
        metadata.originalURL = item.url
        return metadata
    }
}

extension MemoryTrack {
    var downloadFilename: String {
        let candidate = originalFilename.trimmingCharacters(in: .whitespacesAndNewlines)
        let fallback = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = candidate.isEmpty ? (fallback.isEmpty ? id : fallback) : candidate
        return base.lowercased().hasSuffix(".gpx") ? base : "\(base).gpx"
    }
}

enum MemoryMapRegion {
    private static let minimumMapRectSide: Double = 4_000

    static func region(for points: [MemoryMapPoint]) -> MKCoordinateRegion {
        region(points: points, tracks: [])
    }

    static func cameraPosition(points: [MemoryMapPoint], tracks: [MemoryTrack]) -> MapCameraPosition {
        let coordinates = points.map(\.coordinate) + tracks.flatMap(\.coordinates)
        guard !coordinates.isEmpty else {
            return .region(region(points: points, tracks: tracks))
        }
        return .rect(mapRect(for: coordinates))
    }

    static func region(points: [MemoryMapPoint], tracks: [MemoryTrack]) -> MKCoordinateRegion {
        let coordinates = points.map(\.coordinate) + tracks.flatMap(\.coordinates)
        guard let first = coordinates.first else {
            return MKCoordinateRegion(
                center: CLLocationCoordinate2D(latitude: 0, longitude: 0),
                span: MKCoordinateSpan(latitudeDelta: 1, longitudeDelta: 1)
            )
        }

        let latitudes = coordinates.map(\.latitude)
        let longitudes = coordinates.map(\.longitude)
        let minLatitude = latitudes.min() ?? first.latitude
        let maxLatitude = latitudes.max() ?? first.latitude
        let minLongitude = longitudes.min() ?? first.longitude
        let maxLongitude = longitudes.max() ?? first.longitude
        let latitudeDelta = max(0.02, (maxLatitude - minLatitude) * 1.35)
        let longitudeDelta = max(0.02, (maxLongitude - minLongitude) * 1.35)

        return MKCoordinateRegion(
            center: CLLocationCoordinate2D(
                latitude: (minLatitude + maxLatitude) / 2,
                longitude: (minLongitude + maxLongitude) / 2
            ),
            span: MKCoordinateSpan(latitudeDelta: latitudeDelta, longitudeDelta: longitudeDelta)
        )
    }

    static func mapRect(points: [MemoryMapPoint], tracks: [MemoryTrack]) -> MKMapRect {
        mapRect(for: points.map(\.coordinate) + tracks.flatMap(\.coordinates))
    }

    static func mapRect(
        _ rect: MKMapRect,
        fittingViewportSize viewportSize: CGSize,
        bottomObscuredHeight: CGFloat
    ) -> MKMapRect {
        guard viewportSize.height > 0,
              bottomObscuredHeight > 0,
              rect.width > 0,
              rect.height > 0
        else {
            return rect
        }

        let availableHeight = max(1, viewportSize.height - min(bottomObscuredHeight, viewportSize.height - 1))
        let scale = Double(viewportSize.height / availableHeight)
        let adjustedHeight = rect.height * scale
        let extraHeight = max(0, adjustedHeight - rect.height)
        return MKMapRect(
            x: rect.minX,
            y: rect.minY,
            width: rect.width,
            height: rect.height + extraHeight
        )
    }

    private static func mapRect(for coordinates: [CLLocationCoordinate2D]) -> MKMapRect {
        let coordinateRects = coordinates.map { coordinate in
            let point = MKMapPoint(coordinate)
            return MKMapRect(x: point.x, y: point.y, width: 0, height: 0)
        }
        guard let first = coordinateRects.first else {
            return MKMapRect(
                x: MKMapPoint(CLLocationCoordinate2D(latitude: 0, longitude: 0)).x - minimumMapRectSide / 2,
                y: MKMapPoint(CLLocationCoordinate2D(latitude: 0, longitude: 0)).y - minimumMapRectSide / 2,
                width: minimumMapRectSide,
                height: minimumMapRectSide
            )
        }

        let union = coordinateRects.dropFirst().reduce(first) { partial, rect in
            partial.union(rect)
        }
        let width = max(union.width, minimumMapRectSide)
        let height = max(union.height, minimumMapRectSide)
        let normalized = MKMapRect(
            x: union.midX - width / 2,
            y: union.midY - height / 2,
            width: width,
            height: height
        )

        return normalized.insetBy(dx: -width * 0.2, dy: -height * 0.2)
    }
}

private struct MemoryTrackSummaryRow: View {
    let track: MemoryTrack
    let tint: Color
    let enabled: Bool
    let focusAction: () -> Void
    let visibilityAction: () -> Void
    let shareAction: () -> Void
    let sharing: Bool

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Button(action: focusAction) {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(alignment: .top, spacing: 8) {
                        Circle()
                            .fill(enabled ? tint : Color.secondary.opacity(0.38))
                            .frame(width: 9, height: 9)
                            .padding(.top, 8)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(track.displayTitle)
                                .font(.headline)
                                .lineLimit(1)
                                .foregroundStyle(.primary)
                            Text(MemoryTrackFormatter.activityLabel(track.activityType))
                                .font(.caption.weight(.medium))
                                .foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 8)
                        Color.clear
                            .frame(width: 76, height: 34)
                    }

                    HStack(spacing: 10) {
                        MemoryTrackMetric(title: "Distance", value: MemoryTrackFormatter.distance(track.distanceMeters), symbolName: "point.topleft.down.curvedto.point.bottomright.up")
                        MemoryTrackMetric(title: "Duration", value: MemoryTrackFormatter.duration(track.durationSeconds), symbolName: "clock")
                        MemoryTrackMetric(title: "Ascent", value: MemoryTrackFormatter.elevation(track.ascentMeters), symbolName: "arrow.up.right")
                        MemoryTrackMetric(title: "Descent", value: MemoryTrackFormatter.elevation(track.descentMeters), symbolName: "arrow.down.right")
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                        .stroke(enabled ? tint.opacity(0.36) : VeyqColor.hairline)
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(track.displayTitle)
            .accessibilityHint(L10n.string("Show on map"))
            .accessibilityIdentifier("memory-track-summary-\(track.id)")

            HStack(spacing: 4) {
                Button(action: shareAction) {
                    ZStack {
                        Image(systemName: "square.and.arrow.up")
                            .opacity(sharing ? 0 : 1)
                        if sharing {
                            ProgressView()
                                .scaleEffect(0.72)
                        }
                    }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(tint)
                    .frame(width: 34, height: 34)
                    .contentShape(Rectangle())
                }
                .disabled(sharing)
                .accessibilityLabel(L10n.string("Share Track"))
                .accessibilityIdentifier("memory-track-summary-share-\(track.id)")

                Button(action: visibilityAction) {
                    Image(systemName: enabled ? "eye.fill" : "eye.slash")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(enabled ? tint : .secondary)
                        .frame(width: 34, height: 34)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel(track.displayTitle)
                .accessibilityValue(enabled ? L10n.string("Shown") : L10n.string("Hidden"))
                .accessibilityIdentifier("memory-track-summary-visibility-\(track.id)")
            }
            .buttonStyle(.plain)
            .padding(.top, 10)
            .padding(.trailing, 10)
        }
    }
}

private struct MemoryTrackMetric: View {
    let title: LocalizedStringKey
    let value: String
    let symbolName: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label {
                Text(title)
            } icon: {
                Image(systemName: symbolName)
            }
            .font(.caption2.weight(.semibold))
            .foregroundStyle(.secondary)
            Text(value)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.82)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

enum MemoryTrackFormatter {
    static func distance(_ meters: Int64) -> String {
        if meters >= 1000 {
            let kilometers = Double(meters) / 1000
            return String(format: "%.1f km", kilometers)
        }
        return L10n.format("%d m", Int(meters))
    }

    static func elevation(_ meters: Int64?) -> String {
        guard let meters else { return L10n.string("n/a") }
        return L10n.format("%d m", Int(meters))
    }

    static func duration(_ seconds: TimeInterval?) -> String {
        guard let seconds, seconds > 0 else { return L10n.string("n/a") }
        let totalMinutes = max(1, Int((seconds / 60).rounded()))
        let hours = totalMinutes / 60
        let minutes = totalMinutes % 60
        if hours <= 0 {
            return L10n.format("%d min", minutes)
        }
        if minutes == 0 {
            return L10n.format("%d h", hours)
        }
        return L10n.format("%d h %d min", hours, minutes)
    }

    static func activityLabel(_ value: String) -> String {
        switch value {
        case "HIKING": L10n.string("Hiking")
        case "WALKING": L10n.string("Walking")
        case "RUNNING": L10n.string("Running")
        case "CYCLING": L10n.string("Cycling")
        case "MOUNTAIN_BIKING": L10n.string("Mountain biking")
        case "ROAD_CYCLING": L10n.string("Road cycling")
        case "GRAVEL_CYCLING": L10n.string("Gravel cycling")
        case "SKIING": L10n.string("Skiing")
        case "WATER": L10n.string("Water")
        default: L10n.string("Other")
        }
    }
}

private enum MemoryTrackPalette {
    static func color(at index: Int) -> Color {
        let colors: [Color] = [
            VeyqColor.accent,
            VeyqColor.success,
            VeyqColor.info,
            .pink,
            .orange
        ]
        return colors[index % colors.count]
    }
}

private struct MemoryGalleryView: View {
    @EnvironmentObject private var store: VeyqStore
    let memoryId: String
    let title: String
    let media: [MemoryMedia]
    @Binding var filter: MemoryMediaFilter
    let shareURL: URL?

    private var currentMedia: [MemoryMedia] {
        store.memoryDetails[memoryId]?.media ?? media
    }

    private var visibleMedia: [MemoryMedia] {
        filter.visibleMedia(from: currentMedia)
    }

    var body: some View {
        ZStack {
            VeyqScreenBackground()
            ScrollView {
                if visibleMedia.isEmpty && filter == .highlights {
                    VeyqEmptyState(
                        symbolName: "star",
                        title: "No highlights yet",
                        message: "Mark favorite photos or videos as Highlights from the media viewer.",
                        tint: VeyqColor.warning
                    )
                    .padding(.horizontal)
                    .padding(.bottom, VeyqLayout.floatingDockContentPadding)
                } else {
                    JustifiedMemoryMediaGrid(media: visibleMedia)
                        .padding(.horizontal)
                        .padding(.bottom, VeyqLayout.floatingDockContentPadding)
                }
            }
            .accessibilityIdentifier("memory-full-gallery")
        }
        .navigationTitle("Gallery")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                MemoryMediaFilterToggle(
                    filter: $filter,
                    accessibilityIdentifier: "memory-gallery-filter-button"
                )
            }

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

enum MemoryMediaFilter: String, CaseIterable, Identifiable {
    case all
    case highlights

    var id: String { rawValue }

    var title: LocalizedStringKey {
        switch self {
        case .all: "All Media"
        case .highlights: "Highlights"
        }
    }

    var systemImage: String {
        switch self {
        case .all: "photo.on.rectangle"
        case .highlights: "star"
        }
    }

    func visibleMedia(from media: [MemoryMedia]) -> [MemoryMedia] {
        switch self {
        case .all: media
        case .highlights: media.filter(\.highlight)
        }
    }
}

private struct MemoryMediaFilterToggle: View {
    @Binding var filter: MemoryMediaFilter
    let accessibilityIdentifier: String

    var body: some View {
        Button {
            filter = filter == .all ? .highlights : .all
        } label: {
            Image(systemName: filter == .highlights ? "star.fill" : "star")
        }
        .accessibilityLabel(filter == .highlights ? L10n.string("Show All Media") : L10n.string("Show Highlights"))
        .accessibilityValue(Text(filter.title))
        .accessibilityIdentifier(accessibilityIdentifier)
    }
}

private struct MemoryJournalView: View {
    @EnvironmentObject private var store: VeyqStore
    let memory: Memory
    let title: String
    let markdown: String
    let media: [MemoryMedia]
    let shareURL: URL?
    var editingInitially = false

    @State private var editing = false
    @State private var draft = ""
    @State private var saving = false
    @State private var errorMessage: String?
    @State private var mode = MemoryJournalEditorMode.edit

    private var currentJournal: MemoryJournal? {
        store.memoryDetails[memory.id]?.journal
    }

    private var currentMarkdown: String {
        currentJournal?.markdown ?? markdown
    }

    private var currentMedia: [MemoryMedia] {
        store.memoryDetails[memory.id]?.media ?? media
    }

    private var canEdit: Bool {
        store.supportsCapability("memory.journal-markdown")
    }

    private var hasChanges: Bool {
        draft != currentMarkdown
    }

    var body: some View {
        ZStack {
            VeyqScreenBackground()
            if editing {
                journalEditor
            } else {
                ScrollView {
                    if currentMarkdown.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        VeyqEmptyState(
                            symbolName: "text.alignleft",
                            title: "No journal yet",
                            message: "Journal text will appear here when this Memory has a written note.",
                            tint: VeyqColor.info
                        )
                        .padding(.horizontal)
                    } else {
                        MemoryJournalRenderer(markdown: currentMarkdown, media: currentMedia)
                            .padding(.horizontal)
                    }
                }
                .padding(.bottom, VeyqLayout.floatingDockContentPadding)
                .accessibilityIdentifier("memory-journal-reader")
            }
        }
        .navigationTitle("Journal")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if draft.isEmpty {
                draft = currentMarkdown
            }
            if editingInitially {
                beginEditing()
            }
        }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if editing {
                    Button("Cancel") {
                        cancelEditing()
                    }
                    .disabled(saving)
                    .accessibilityIdentifier("memory-journal-cancel-button")

                    Button("Save") {
                        Task { await saveJournal() }
                    }
                    .disabled(saving || !hasChanges)
                    .accessibilityIdentifier("memory-journal-save-button")
                } else {
                    if canEdit {
                        Button {
                            beginEditing()
                        } label: {
                            Image(systemName: "square.and.pencil")
                        }
                        .accessibilityLabel(L10n.string("Edit Journal"))
                        .accessibilityIdentifier("memory-journal-edit-button")
                    }

                    if let shareURL {
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

    private var journalEditor: some View {
        VStack(spacing: 14) {
            Picker("Journal View", selection: $mode) {
                Text("Edit").tag(MemoryJournalEditorMode.edit)
                Text("Preview").tag(MemoryJournalEditorMode.preview)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .accessibilityIdentifier("memory-journal-mode-picker")

            if let errorMessage {
                Label(errorMessage, systemImage: "exclamationmark.triangle")
                    .font(.subheadline)
                    .foregroundStyle(VeyqColor.danger)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
                    .accessibilityIdentifier("memory-journal-error")
            }

            if saving {
                ProgressView("Saving journal...")
                    .font(.subheadline)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
                    .accessibilityIdentifier("memory-journal-saving")
            }

            Group {
                switch mode {
                case .edit:
                    TextEditor(text: $draft)
                        .font(.body.monospaced())
                        .scrollContentBackground(.hidden)
                        .padding(12)
                        .background(VeyqColor.surface, in: RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
                        .overlay {
                            RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                                .stroke(VeyqColor.hairline)
                        }
                        .padding(.horizontal)
                        .accessibilityIdentifier("memory-journal-editor")
                case .preview:
                    ScrollView {
                        if draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            VeyqEmptyState(
                                symbolName: "text.alignleft",
                                title: "Empty preview",
                                message: "Preview updates while you write.",
                                tint: VeyqColor.info
                            )
                            .padding(.horizontal)
                        } else {
                            MemoryJournalRenderer(markdown: draft, media: currentMedia)
                                .padding(.horizontal)
                        }
                    }
                    .accessibilityIdentifier("memory-journal-live-preview")
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .padding(.top, 12)
        .padding(.bottom, VeyqLayout.floatingDockContentPadding)
    }

    private func beginEditing() {
        draft = currentMarkdown
        errorMessage = nil
        mode = .edit
        editing = true
    }

    private func cancelEditing() {
        draft = currentMarkdown
        errorMessage = nil
        editing = false
    }

    @MainActor
    private func saveJournal() async {
        saving = true
        errorMessage = nil
        defer { saving = false }
        do {
            let saved = try await store.saveMemoryJournal(for: memory, markdown: draft)
            draft = saved.markdown
            editing = false
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private enum MemoryJournalEditorMode {
    case edit
    case preview
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
