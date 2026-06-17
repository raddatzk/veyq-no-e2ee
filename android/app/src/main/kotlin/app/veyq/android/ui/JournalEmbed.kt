package app.veyq.android.ui

// Journal rich-embed tokens — `::media:id1,id2 label`, `::track:id1,id2 label`, `::chapter:id label`. Mirrors
// the web `journalEmbed.ts` wire format so journals render identically across platforms. A token ends at a
// newline, end-of-text, or the next token, so several can sit back-to-back.

enum class JournalEmbedKind { MEDIA, TRACK, CHAPTER }

data class JournalEmbed(val kind: JournalEmbedKind, val ids: List<String>, val label: String?)

sealed interface JournalContentBlock {
    data class Markdown(val text: String) : JournalContentBlock
    data class Embed(val embed: JournalEmbed) : JournalContentBlock
}

object JournalEmbedParser {
    const val MAX_TRACK_IDS = 20
    private val regex = Regex("::(media|track|chapter):([^\\s\\n]+)(?:[ \\t]+([^\\n]*?))?(?=\\n|$|::(?:media|track|chapter):)")

    /** Split the journal into an ordered run of Markdown segments and embed tokens for block-based rendering. */
    fun splitBlocks(markdown: String): List<JournalContentBlock> {
        val blocks = mutableListOf<JournalContentBlock>()
        var cursor = 0
        for (m in regex.findAll(markdown)) {
            val range = m.range
            if (range.first > cursor) blocks.add(JournalContentBlock.Markdown(markdown.substring(cursor, range.first)))
            cursor = range.last + 1
            val kind = when (m.groupValues[1]) { "media" -> JournalEmbedKind.MEDIA; "track" -> JournalEmbedKind.TRACK; else -> JournalEmbedKind.CHAPTER }
            val raw = m.groupValues[2].split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val ids = when (kind) { JournalEmbedKind.TRACK -> normalizeTrackIds(raw); JournalEmbedKind.CHAPTER -> raw.take(1); else -> raw }
            if (ids.isEmpty()) continue
            val label = m.groupValues[3].trim().ifEmpty { null }
            blocks.add(JournalContentBlock.Embed(JournalEmbed(kind, ids, label)))
        }
        if (cursor < markdown.length) blocks.add(JournalContentBlock.Markdown(markdown.substring(cursor)))
        return blocks
    }

    /** Dedupe + trim track ids and cap them (one embed can carry several tracks onto one map). */
    fun normalizeTrackIds(ids: List<String>): List<String> =
        ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_TRACK_IDS)

    /** Serialize an embed back to its token form (used by the editor pickers when inserting/editing). */
    fun embedMarkdown(kind: JournalEmbedKind, ids: List<String>, label: String?): String {
        val norm = when (kind) { JournalEmbedKind.TRACK -> normalizeTrackIds(ids); JournalEmbedKind.CHAPTER -> ids.take(1); else -> ids }
        val suffix = label?.trim()?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""
        return "::${kind.name.lowercase()}:${norm.joinToString(",")}$suffix"
    }
}
