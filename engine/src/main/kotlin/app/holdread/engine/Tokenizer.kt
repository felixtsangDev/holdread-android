package app.holdread.engine

/**
 * Splits text into word-break segments. On Android this is `android.icu.text.BreakIterator`;
 * in JVM tests it is ICU4J, so both behave the same (including dictionary-based Chinese words).
 */
fun interface Segmenter {
    fun segments(text: String): List<String>
}

/**
 * Turns a paragraph of text into [Word]s, gluing punctuation to its neighbour:
 * opening marks go to the next word, everything else to the previous word.
 * Punctuation with no neighbour (e.g. a dash between spaces) stays in the text but is not a word.
 * Ported from the web prototype's `renderChapter`.
 */
class Tokenizer(private val segmenter: Segmenter) {

    /**
     * Tokenizes one block (paragraph, heading, list item) after [normalize]-ing it.
     * Word offsets point into the normalized text. The last word gets [Word.paragraphEnd].
     */
    fun tokenize(block: String): List<Word> {
        val text = normalize(block)
        if (text.isEmpty()) return emptyList()

        val out = mutableListOf<Builder>()
        var lead = ""
        var leadStart = 0
        var prevIsWord = false
        var offset = 0
        for (seg in segmenter.segments(text)) {
            when {
                isWordLike(seg) -> {
                    out += Builder(pre = lead, core = seg, start = if (lead.isEmpty()) offset else leadStart)
                    lead = ""
                    prevIsWord = true
                }
                seg.isBlank() -> {
                    lead = ""
                    prevIsWord = false
                }
                else -> {
                    val m = PUNCT_SPLIT.matchEntire(seg)!!
                    var close = m.groupValues[1]
                    var open = m.groupValues[2]
                    // a straight quote right after a word closes it: go." -> [go."]
                    if (prevIsWord) {
                        val q = open.takeWhile { it == '"' || it == '\'' }
                        close += q
                        open = open.drop(q.length)
                    }
                    if (close.isNotEmpty()) {
                        // closing marks join the previous word; with no word right before, they stay plain text
                        if (prevIsWord) out.last().post += close else lead = ""
                    }
                    if (open.isNotEmpty()) {
                        if (lead.isEmpty()) leadStart = offset + close.length
                        lead += open
                        prevIsWord = false
                    }
                }
            }
            offset += seg.length
        }
        return out.mapIndexed { i, b -> Word(b.core, b.pre, b.post, paragraphEnd = i == out.lastIndex, start = b.start) }
    }

    /** Tokenizes several blocks into one chapter-long list. */
    fun tokenize(blocks: List<String>): List<Word> = blocks.flatMap { tokenize(it) }

    private class Builder(val pre: String, val core: String, val start: Int) {
        var post: String = ""
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        // leading part closes the previous word; trailing opening marks start the next one
        private val PUNCT_SPLIT = Regex("^(.*?)([“‘(\\[{「『《〈\"']*)$", RegexOption.DOT_MATCHES_ALL)

        /** Collapses whitespace and trims, the way blocks are shown on screen. */
        fun normalize(block: String): String = WHITESPACE.replace(block, " ").trim()

        fun isWordLike(seg: String): Boolean =
            Cjk.contains(seg) || seg.any { Character.isLetterOrDigit(it) }
    }
}
