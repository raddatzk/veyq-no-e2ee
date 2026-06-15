import SwiftUI

/// A memory's journal: a rendered Markdown view with an Edit mode (plain TextEditor on the Markdown source).
/// Inline spans (bold/italic/code/links) use SwiftUI's built-in Markdown AttributedString; block structure
/// (headings, bullet/ordered lists, paragraphs) is handled here. Rich embed tokens are deferred.
struct JournalView: View {
    @ObservedObject var store: GalleryStore
    @State private var editing = false
    @State private var draft = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if editing {
                TextEditor(text: $draft)
                    .font(.system(size: 15, design: .monospaced))
                    .foregroundStyle(VeyqStyle.text)
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: 280)
                    .padding(8)
                    .background(VeyqStyle.surface)
                    .clipShape(RoundedRectangle(cornerRadius: VeyqStyle.radius))
                    .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radius).strokeBorder(VeyqStyle.line))
                HStack {
                    Spacer()
                    Button(T("common.cancel")) { editing = false }.tint(VeyqStyle.muted)
                    Button(store.journalSaving ? T("journal.saving") : T("journal.save")) {
                        let md = draft
                        Task { await store.saveJournal(md); editing = false }
                    }
                    .buttonStyle(.borderedProminent).tint(VeyqStyle.accent).disabled(store.journalSaving)
                }
            } else {
                HStack {
                    Spacer()
                    Button(T("journal.edit")) { draft = store.journalMarkdown; editing = true }.tint(VeyqStyle.accent)
                }
                if store.journalMarkdown.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Text(T("journal.empty")).font(.footnote).foregroundStyle(VeyqStyle.muted)
                } else {
                    MarkdownText(markdown: store.journalMarkdown)
                }
            }
        }
        .task { await store.loadJournal() }
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
