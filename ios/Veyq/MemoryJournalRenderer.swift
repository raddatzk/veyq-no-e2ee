import Foundation
import SwiftUI

enum MemoryJournalBlock: Equatable {
    case markdown(String)
    case media(caption: String, id: String)
    case carousel(caption: String, ids: [String])
}

enum MemoryJournalParser {
    static func blocks(from markdown: String) -> [MemoryJournalBlock] {
        guard !markdown.isEmpty else { return [] }

        let pattern = #"::media:([^\s\n]+)(?:[ \t]+([^\n]*?))?(?=\n|$|::(?:media|chapter|playlist|track):)"#
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

            let rawIDs = stringCapture(at: 1, in: match, source: markdown)
            let caption = stringCapture(at: 2, in: match, source: markdown)
            let ids = rawIDs
                .split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
                .map { $0.removingPercentEncoding ?? $0 }

            if ids.count > 1 {
                blocks.append(.carousel(caption: caption, ids: ids))
            } else {
                blocks.append(.media(caption: caption, id: ids.first ?? rawIDs))
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

    private var mediaByID: [String: MemoryMedia] {
        media.reduce(into: [:]) { result, item in
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
