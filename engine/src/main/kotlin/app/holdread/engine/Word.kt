package app.holdread.engine

/**
 * One readable unit on the page: a word (English) or a run of characters that the
 * segmenter grouped (Chinese/Japanese/Korean), with the punctuation glued to it so
 * punctuation never wraps onto a line by itself.
 */
data class Word(
    /** The word itself, without surrounding punctuation. */
    val core: String,
    /** Opening punctuation in front of the word, e.g. `“` or `「`. */
    val pre: String = "",
    /** Closing punctuation after the word, e.g. `.”` or `，`. */
    val post: String = "",
    /** True when this is the last word of a paragraph/heading. */
    val paragraphEnd: Boolean = false,
    /** Offset of [display] inside its block's normalized text ([Tokenizer.normalize]). */
    val start: Int = 0,
) {
    /** End offset (exclusive) of [display] inside its block's normalized text. */
    val end: Int get() = start + display.length

    /** What is drawn on screen. */
    val display: String get() = pre + core + post

    /** CJK text counts per character; everything else counts per word. */
    val cjk: Boolean = Cjk.contains(core)

    /** How many "words" this counts as for speed and statistics. */
    val units: Int = if (cjk) core.length else 1

    /** 2 = sentence end, 1 = clause break, 0 = none. Adds a pause to the dwell time. */
    val pause: Int = when {
        SENTENCE_END.containsMatchIn(post) -> 2
        CLAUSE_BREAK.containsMatchIn(post) -> 1
        else -> 0
    }

    private companion object {
        val SENTENCE_END = Regex("[.!?。！？…]")
        val CLAUSE_BREAK = Regex("[,;:，；：、—]")
    }
}

object Cjk {
    // Same ranges as the web prototype: CJK ideographs + ext A, compatibility ideographs, kana, hangul.
    private val RE = Regex("[㐀-鿿豈-﫿぀-ヿ가-힯]")
    fun contains(s: CharSequence): Boolean = RE.containsMatchIn(s)
}
