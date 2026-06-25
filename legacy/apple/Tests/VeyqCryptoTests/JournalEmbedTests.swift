import XCTest
import VeyqCrypto

// Native parity for the journal embed grammar — keep byte-compatible with the web parser
// frontend/src/features/memory/journal/journalEmbed.ts (mirrors journalEmbed.test.ts).
final class JournalEmbedTests: XCTestCase {
    private func embeds(_ md: String) -> [JournalEmbed] {
        JournalEmbedParser.splitBlocks(md).compactMap { b -> JournalEmbed? in
            if case let .embed(e) = b { return e }
            return nil
        }
    }
    private func markdowns(_ md: String) -> [String] {
        JournalEmbedParser.splitBlocks(md).compactMap { b -> String? in
            if case let .markdown(t, _) = b { return t }
            return nil
        }
    }

    func testParsesSingleMediaTokenWithLabel() {
        let e = embeds("::media:a My trip")
        XCTAssertEqual(e.count, 1)
        XCTAssertEqual(e[0].kind, .media)
        XCTAssertEqual(e[0].ids, ["a"])
        XCTAssertEqual(e[0].label, "My trip")
    }

    func testParsesCommaSeparatedMediaIdsWithoutLabel() {
        let e = embeds("::media:a,b,c")
        XCTAssertEqual(e.count, 1)
        XCTAssertEqual(e[0].ids, ["a", "b", "c"])
        XCTAssertNil(e[0].label)
    }

    func testKeepsOnlyFirstIdForChapter() {
        let e = embeds("::chapter:c1,c2 Day one")
        XCTAssertEqual(e[0].ids, ["c1"])
        XCTAssertEqual(e[0].label, "Day one")
    }

    func testKeepsOnlyFirstIdForPlaylist() {
        let e = embeds("::playlist:p1,p2 Mix")
        XCTAssertEqual(e[0].ids, ["p1"])
    }

    func testDedupesAndCapsTrackIds() {
        let e = embeds("::track:t1,t1,t2 Hike")
        XCTAssertEqual(e[0].ids, ["t1", "t2"])
    }

    func testParsesTwoBackToBackTokens() {
        // a space (or newline) bounds one token from the next; the lazy label stops at the `::kind:` lookahead
        let e = embeds("::media:a ::track:t1")
        XCTAssertEqual(e.count, 2)
        XCTAssertEqual(e[0].kind, .media)
        XCTAssertEqual(e[1].kind, .track)
    }

    func testEndsTokenAtNewline() {
        let e = embeds("::media:a caption\nnext line")
        XCTAssertEqual(e[0].label, "caption")
        XCTAssertTrue(markdowns("::media:a caption\nnext line").contains { $0.contains("next line") })
    }

    func testIgnoresEmptyTarget() {
        XCTAssertTrue(embeds("::media:,").isEmpty)
    }

    func testSplitsTextAndEmbedsInSourceOrder() {
        let blocks = JournalEmbedParser.splitBlocks("intro\n::media:a,b\noutro")
        XCTAssertEqual(blocks.count, 3)
        if case let .markdown(t0, _) = blocks[0] { XCTAssertTrue(t0.contains("intro")) } else { XCTFail("expected markdown first") }
        guard case .embed = blocks[1] else { return XCTFail("expected embed in the middle") }
        if case let .markdown(t2, _) = blocks[2] { XCTAssertTrue(t2.contains("outro")) } else { XCTFail("expected markdown last") }
    }

    func testReturnsSingleMarkdownBlockWhenNoEmbeds() {
        let blocks = JournalEmbedParser.splitBlocks("just some text")
        XCTAssertEqual(blocks.count, 1)
        XCTAssertTrue(embeds("just some text").isEmpty)
    }

    func testHandlesEmbedAtTheVeryStart() {
        let blocks = JournalEmbedParser.splitBlocks("::media:a,b\nrest")
        guard case .embed = blocks.first else { return XCTFail("expected an embed as the first block") }
    }

    func testRoundTripsLabelledMediaEmbed() {
        let token = JournalEmbedParser.embedMarkdown(.media, ["a", "b"], label: "Trip")
        XCTAssertEqual(token, "::media:a,b Trip")
        let e = embeds(token)
        XCTAssertEqual(e[0].ids, ["a", "b"])
        XCTAssertEqual(e[0].label, "Trip")
    }

    func testOmitsSuffixWhenThereIsNoLabel() {
        XCTAssertEqual(JournalEmbedParser.embedMarkdown(.media, ["a"], label: nil), "::media:a")
    }

    func testNormalizesTrackIdsOnSerialize() {
        XCTAssertEqual(JournalEmbedParser.embedMarkdown(.track, ["t1", "t1", "t2"], label: nil), "::track:t1,t2")
    }

    func testDedupesTrimsDropsEmptiesAndCaps() {
        let many = (1...25).map { "t\($0)" }
        let out = JournalEmbedParser.normalizeTrackIds([" t1 ", "t1", ""] + many)
        XCTAssertEqual(out.count, 20)
        XCTAssertEqual(out.first, "t1")
        XCTAssertEqual(Set(out).count, out.count)
    }
}
