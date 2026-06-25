package app.veyq.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Native parity for the journal embed grammar — byte-compatible with the web and iOS parsers
// (frontend/src/features/memory/journal/journalEmbed.ts; mirrors journalEmbed.test.ts).
class JournalEmbedTest {
    private fun embeds(md: String) =
        JournalEmbedParser.splitBlocks(md).filterIsInstance<JournalContentBlock.Embed>().map { it.embed }
    private fun markdowns(md: String) =
        JournalEmbedParser.splitBlocks(md).filterIsInstance<JournalContentBlock.Markdown>().map { it.text }

    @Test fun parsesSingleMediaTokenWithLabel() {
        val e = embeds("::media:a My trip")
        assertEquals(1, e.size)
        assertEquals(JournalEmbedKind.MEDIA, e[0].kind)
        assertEquals(listOf("a"), e[0].ids)
        assertEquals("My trip", e[0].label)
    }

    @Test fun parsesCommaSeparatedMediaIdsWithoutLabel() {
        val e = embeds("::media:a,b,c")
        assertEquals(listOf("a", "b", "c"), e[0].ids)
        assertNull(e[0].label)
    }

    @Test fun keepsOnlyFirstIdForChapter() {
        val e = embeds("::chapter:c1,c2 Day one")
        assertEquals(listOf("c1"), e[0].ids)
        assertEquals("Day one", e[0].label)
    }

    @Test fun keepsOnlyFirstIdForPlaylist() {
        assertEquals(listOf("p1"), embeds("::playlist:p1,p2 Mix")[0].ids)
    }

    @Test fun dedupesAndCapsTrackIds() {
        assertEquals(listOf("t1", "t2"), embeds("::track:t1,t1,t2 Hike")[0].ids)
    }

    @Test fun parsesTwoBackToBackTokens() {
        // a space (or newline) bounds one token from the next; the lazy label stops at the `::kind:` lookahead
        val e = embeds("::media:a ::track:t1")
        assertEquals(2, e.size)
        assertEquals(JournalEmbedKind.MEDIA, e[0].kind)
        assertEquals(JournalEmbedKind.TRACK, e[1].kind)
    }

    @Test fun endsTokenAtNewline() {
        assertEquals("caption", embeds("::media:a caption\nnext line")[0].label)
        assertTrue(markdowns("::media:a caption\nnext line").any { it.contains("next line") })
    }

    @Test fun ignoresEmptyTarget() {
        assertTrue(embeds("::media:,").isEmpty())
    }

    @Test fun splitsTextAndEmbedsInSourceOrder() {
        val blocks = JournalEmbedParser.splitBlocks("intro\n::media:a,b\noutro")
        assertEquals(3, blocks.size)
        assertTrue((blocks[0] as JournalContentBlock.Markdown).text.contains("intro"))
        assertTrue(blocks[1] is JournalContentBlock.Embed)
        assertTrue((blocks[2] as JournalContentBlock.Markdown).text.contains("outro"))
    }

    @Test fun returnsSingleMarkdownBlockWhenNoEmbeds() {
        val blocks = JournalEmbedParser.splitBlocks("just some text")
        assertEquals(1, blocks.size)
        assertTrue(embeds("just some text").isEmpty())
    }

    @Test fun handlesEmbedAtTheVeryStart() {
        assertTrue(JournalEmbedParser.splitBlocks("::media:a,b\nrest")[0] is JournalContentBlock.Embed)
    }

    @Test fun roundTripsLabelledMediaEmbed() {
        val token = JournalEmbedParser.embedMarkdown(JournalEmbedKind.MEDIA, listOf("a", "b"), "Trip")
        assertEquals("::media:a,b Trip", token)
        val e = embeds(token)
        assertEquals(listOf("a", "b"), e[0].ids)
        assertEquals("Trip", e[0].label)
    }

    @Test fun omitsSuffixWhenThereIsNoLabel() {
        assertEquals("::media:a", JournalEmbedParser.embedMarkdown(JournalEmbedKind.MEDIA, listOf("a"), null))
    }

    @Test fun normalizesTrackIdsOnSerialize() {
        assertEquals("::track:t1,t2", JournalEmbedParser.embedMarkdown(JournalEmbedKind.TRACK, listOf("t1", "t1", "t2"), null))
    }

    @Test fun dedupesTrimsDropsEmptiesAndCaps() {
        val many = (1..25).map { "t$it" }
        val out = JournalEmbedParser.normalizeTrackIds(listOf(" t1 ", "t1", "") + many)
        assertEquals(20, out.size)
        assertEquals("t1", out.first())
        assertEquals(out.size, out.toSet().size)
    }
}
