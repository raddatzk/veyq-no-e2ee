import Foundation
import MapKit
import SwiftUI

enum MemoryJournalBlock: Equatable {
    case markdown(String)
    case media(caption: String, id: String)
    case carousel(caption: String, ids: [String])
    case tracks(caption: String, ids: [String])
}

enum MemoryJournalParser {
    static func blocks(from markdown: String) -> [MemoryJournalBlock] {
        guard !markdown.isEmpty else { return [] }

        let pattern = #"::(media|track):([^\s\n]+)(?:[ \t]+([^\n]*?))?(?=\n|$|::(?:media|chapter|playlist|track):)"#
        guard let expression = try? NSRegularExpression(pattern: pattern) else {
            return [.markdown(markdown)]
        }

        let nsRange = NSRange(markdown.startIndex..<markdown.endIndex, in: markdown)
        let matches = expression.matches(in: markdown, range: nsRange)
        guard !matches.isEmpty else { return [.markdown(markdown)] }

        var blocks: [MemoryJournalBlock] = []
        var cursor = markdown.startIndex

        for match in matches {
            guard let matchRange = Range(match.range, in: markdown) else { continue }
            appendMarkdown(markdown[cursor..<matchRange.lowerBound], to: &blocks)

            let type = stringCapture(at: 1, in: match, source: markdown)
            let rawIDs = stringCapture(at: 2, in: match, source: markdown)
            let caption = stringCapture(at: 3, in: match, source: markdown)
            let ids = rawIDs
                .split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
                .map { $0.removingPercentEncoding ?? $0 }

            switch type {
            case "track":
                blocks.append(.tracks(caption: caption, ids: ids.isEmpty ? [rawIDs] : ids))
            default:
                if ids.count > 1 {
                    blocks.append(.carousel(caption: caption, ids: ids))
                } else {
                    blocks.append(.media(caption: caption, id: ids.first ?? rawIDs))
                }
            }

            cursor = matchRange.upperBound
        }

        appendMarkdown(markdown[cursor..<markdown.endIndex], to: &blocks)
        return blocks
    }

    private static func appendMarkdown(_ substring: Substring, to blocks: inout [MemoryJournalBlock]) {
        let value = String(substring)
        guard !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        blocks.append(.markdown(value))
    }

    private static func stringCapture(at index: Int, in match: NSTextCheckingResult, source: String) -> String {
        guard match.numberOfRanges > index, let range = Range(match.range(at: index), in: source) else {
            return ""
        }
        return String(source[range])
    }
}

struct MemoryJournalRenderer: View {
    let markdown: String
    let media: [MemoryMedia]
    let tracks: [MemoryTrack]

    private var mediaByID: [String: MemoryMedia] {
        media.reduce(into: [:]) { result, item in
            result[item.id] = result[item.id] ?? item
        }
    }

    private var tracksByID: [String: MemoryTrack] {
        tracks.reduce(into: [:]) { result, item in
            result[item.id] = result[item.id] ?? item
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            ForEach(Array(MemoryJournalParser.blocks(from: markdown).enumerated()), id: \.offset) { _, block in
                switch block {
                case .markdown(let value):
                    MemoryJournalMarkdownText(markdown: value)
                case .media(let caption, let id):
                    MemoryJournalMediaFigure(caption: caption, item: mediaByID[id], fallbackLabel: id)
                case .carousel(let caption, let ids):
                    MemoryJournalMediaCarousel(
                        caption: caption,
                        items: ids.map { MemoryJournalCarouselItem(id: $0, media: mediaByID[$0]) }
                    )
                case .tracks(let caption, let ids):
                    MemoryJournalTrackFigure(
                        caption: caption,
                        media: media,
                        items: ids.map { MemoryJournalTrackItem(id: $0, track: tracksByID[$0]) }
                    )
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityIdentifier("memory-journal-renderer")
    }
}

private struct MemoryJournalMarkdownText: View {
    let markdown: String

    var body: some View {
        Text(attributedMarkdown)
            .font(.body)
            .foregroundStyle(.primary)
            .textSelection(.enabled)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var attributedMarkdown: AttributedString {
        (try? AttributedString(markdown: markdown, options: AttributedString.MarkdownParsingOptions(interpretedSyntax: .full))) ??
            AttributedString(markdown)
    }
}

private struct MemoryJournalMediaFigure: View {
    let caption: String
    let item: MemoryMedia?
    let fallbackLabel: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let item {
                MemoryJournalMediaFrame(media: item)
                Text(caption.isEmpty ? item.displayTitle : caption)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                MemoryJournalUnavailableEmbed(label: caption.isEmpty ? fallbackLabel : caption)
            }
        }
        .accessibilityIdentifier("memory-journal-media")
    }
}

private struct MemoryJournalMediaCarousel: View {
    let caption: String
    let items: [MemoryJournalCarouselItem]
    @State private var selectedID: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            TabView(selection: selectedIDBinding) {
                ForEach(items) { item in
                    Group {
                        if let media = item.media {
                            MemoryJournalMediaFrame(media: media)
                        } else {
                            MemoryJournalUnavailableEmbed(label: caption.isEmpty ? item.id : caption)
                        }
                    }
                    .tag(item.id)
                    .accessibilityIdentifier("memory-journal-carousel-item-\(item.id)")
                }
            }
            .tabViewStyle(.page(indexDisplayMode: items.count > 1 ? .automatic : .never))
            .frame(height: 260)
            .clipShape(RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))

            if !caption.isEmpty {
                Text(caption)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(caption.isEmpty ? "Media carousel" : caption)
        .accessibilityIdentifier("memory-journal-carousel")
    }

    private var selectedIDBinding: Binding<String> {
        Binding(
            get: { selectedID ?? items.first?.id ?? "" },
            set: { selectedID = $0 }
        )
    }
}

private struct MemoryJournalCarouselItem: Identifiable {
    let id: String
    let media: MemoryMedia?
}

private struct MemoryJournalTrackItem: Identifiable {
    let id: String
    let track: MemoryTrack?
}

private struct MemoryJournalMediaFrame: View {
    let media: MemoryMedia

    var body: some View {
        ZStack(alignment: .center) {
            AuthenticatedMediaImage(urlString: media.preferredJournalUrl, contentMode: .fill)

            if media.isVideo {
                Image(systemName: "play.circle.fill")
                    .font(.system(size: 48, weight: .semibold))
                    .foregroundStyle(VeyqColor.onStrong.opacity(0.9))
                    .shadow(radius: 8)
                    .accessibilityHidden(true)
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: 260)
        .background(VeyqColor.surface)
        .clipShape(RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                .stroke(VeyqColor.hairline)
        }
        .accessibilityLabel(media.displayTitle)
    }
}

private struct MemoryJournalTrackFigure: View {
    let caption: String
    let media: [MemoryMedia]
    let items: [MemoryJournalTrackItem]
    @State private var enabledTrackIDs = Set<String>()
    @State private var importingTrackFiles = false
    @State private var mediaFilter = MemoryMediaFilter.all

    private var points: [MemoryMapPoint] {
        MemoryMapPoint.points(from: media)
    }

    private var availableTracks: [MemoryTrack] {
        items.compactMap(\.track).filter(\.hasRenderableGeometry)
    }

    private var missingItems: [MemoryJournalTrackItem] {
        items.filter { $0.track == nil }
    }

    private var aggregate: MemoryTrackAggregate {
        MemoryTrackAggregate(tracks: availableTracks)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if !availableTracks.isEmpty {
                NavigationLink {
                    MemoryMapView(
                        points: points,
                        media: media,
                        tracks: availableTracks,
                        recenterTracks: availableTracks,
                        mediaFilter: $mediaFilter,
                        enabledTrackIDs: $enabledTrackIDs,
                        importingTrackFiles: $importingTrackFiles
                    )
                } label: {
                    MemoryJournalTrackMapPreview(points: points, media: media, tracks: availableTracks)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L10n.string("Open Map"))

                MemoryJournalTrackSummaryCard(
                    title: caption.isEmpty ? aggregate.title : caption,
                    subtitle: aggregate.activityLabel,
                    tint: MemoryTrackPalette.color(at: 0),
                    aggregate: aggregate
                )
            }

            ForEach(missingItems) { item in
                MemoryJournalUnavailableEmbed(label: caption.isEmpty ? item.id : caption)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("memory-journal-tracks")
        .onAppear {
            syncEnabledTracks()
        }
        .onChange(of: availableTracks.map(\.id)) {
            syncEnabledTracks()
        }
    }

    private func syncEnabledTracks() {
        enabledTrackIDs = MemoryTrackSelection.synced(
            enabledTrackIDs: enabledTrackIDs,
            availableTrackIDs: Set(availableTracks.map(\.id))
        )
    }
}

private struct MemoryJournalTrackMapPreview: View {
    @EnvironmentObject private var store: VeyqStore
    let points: [MemoryMapPoint]
    let media: [MemoryMedia]
    let tracks: [MemoryTrack]
    @State private var position: MapCameraPosition = .automatic
    @State private var visibleMapRect: MKMapRect?
    @State private var mapViewportSize: CGSize = .zero

    private var clusters: [MemoryMapCluster] {
        MemoryMapCluster.clusters(
            from: points,
            in: visibleMapRect ?? MemoryMapRegion.mapRect(points: [], tracks: tracks),
            viewportSize: mapViewportSize,
            markerDimension: MemoryMapClusterMarker.dimension(for: .compact, count: 2)
        )
    }

    var body: some View {
        Map(position: $position) {
            ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                MapPolyline(coordinates: track.coordinates)
                    .stroke(MemoryTrackPalette.color(at: index), style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
            }

            ForEach(MemoryTrackEndpoint.endpoints(from: tracks)) { endpoint in
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
            Label(L10n.string("Open Map"), systemImage: "arrow.up.left.and.arrow.down.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(VeyqColor.accent)
                .padding(.horizontal, 11)
                .padding(.vertical, 9)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: VeyqLayout.controlRadius, style: .continuous))
                .padding(10)
        }
        .overlay {
            RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                .stroke(VeyqColor.hairline)
        }
        .onAppear {
            position = MemoryMapRegion.cameraPosition(points: [], tracks: tracks)
            visibleMapRect = MemoryMapRegion.mapRect(points: [], tracks: tracks)
        }
        .onChange(of: tracks.map(\.id)) {
            position = MemoryMapRegion.cameraPosition(points: [], tracks: tracks)
            visibleMapRect = MemoryMapRegion.mapRect(points: [], tracks: tracks)
        }
    }

    private func primaryMedia(for cluster: MemoryMapCluster) -> MemoryMedia? {
        let clusterMediaIDs = Set(cluster.mediaIDs)
        return media.first { clusterMediaIDs.contains($0.id) }
    }
}

private struct MemoryJournalTrackSummaryCard: View {
    let title: String
    let subtitle: String
    let tint: Color
    let aggregate: MemoryTrackAggregate

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 8) {
                Circle()
                    .fill(tint)
                    .frame(width: 9, height: 9)
                    .padding(.top, 8)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.headline)
                        .lineLimit(1)
                    Text(subtitle)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.secondary)
                }
            }

            HStack(spacing: 10) {
                MemoryTrackMetric(title: "Distance", value: MemoryTrackFormatter.distance(aggregate.distanceMeters), symbolName: "point.topleft.down.curvedto.point.bottomright.up")
                MemoryTrackMetric(title: "Duration", value: MemoryTrackFormatter.duration(aggregate.durationSeconds), symbolName: "clock")
                MemoryTrackMetric(title: "Ascent", value: MemoryTrackFormatter.elevation(aggregate.ascentMeters), symbolName: "arrow.up.right")
                MemoryTrackMetric(title: "Descent", value: MemoryTrackFormatter.elevation(aggregate.descentMeters), symbolName: "arrow.down.right")
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                .stroke(tint.opacity(0.36))
        }
    }
}

struct MemoryTrackAggregate: Equatable {
    let tracks: [MemoryTrack]

    var title: String {
        if tracks.count == 1 {
            return tracks[0].displayTitle
        }
        return L10n.format("%d tracks", tracks.count)
    }

    var activityLabel: String {
        let labels = Set(tracks.map { MemoryTrackFormatter.activityLabel($0.activityType) })
        if tracks.count == 1 {
            return labels.first ?? L10n.string("Track")
        }
        return labels.count == 1 ? (labels.first ?? L10n.string("Tracks")) : L10n.string("Tracks")
    }

    var distanceMeters: Int64 {
        tracks.map(\.distanceMeters).reduce(0, +)
    }

    var durationSeconds: TimeInterval? {
        let durations = tracks.compactMap(\.durationSeconds)
        guard !durations.isEmpty else { return nil }
        return durations.reduce(0, +)
    }

    var ascentMeters: Int64? {
        sumOptional(tracks.map(\.ascentMeters))
    }

    var descentMeters: Int64? {
        sumOptional(tracks.map(\.descentMeters))
    }

    private func sumOptional(_ values: [Int64?]) -> Int64? {
        let present = values.compactMap { $0 }
        guard !present.isEmpty else { return nil }
        return present.reduce(0, +)
    }
}

private struct MemoryJournalUnavailableEmbed: View {
    let label: String

    var body: some View {
        Label(label.isEmpty ? "Media unavailable" : label, systemImage: "nosign")
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(VeyqColor.danger)
            .frame(maxWidth: .infinity, minHeight: 96, alignment: .center)
            .padding(14)
            .background(VeyqColor.danger.opacity(0.08), in: RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                    .stroke(VeyqColor.danger.opacity(0.28))
            }
            .accessibilityIdentifier("memory-journal-unavailable")
    }
}

private extension MemoryMedia {
    var preferredJournalUrl: String {
        if isVideo {
            return posterUrl ?? previewUrl ?? thumbnailUrl ?? contentUrl
        }
        return previewUrl ?? thumbnailUrl ?? contentUrl
    }
}
