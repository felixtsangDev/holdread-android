package app.holdread.engine

/**
 * Where each word of the chapter sits, as the engine needs it: which words share a line,
 * and each word's horizontal span in track coordinates (the track mirrors the line, so
 * a word's span on the track equals its span on screen).
 */
interface TrackLayout {
    /** First and last word index of the line that contains [index]. */
    fun lineOf(index: Int): IntRange
    fun left(index: Int): Float
    fun right(index: Int): Float
}

/** A [TrackLayout] built from explicit lines and spans. Used by tests and by the app after text layout. */
class FixedTrackLayout(
    lines: List<IntRange>,
    private val lefts: FloatArray,
    private val rights: FloatArray,
) : TrackLayout {
    private val lineIndex = IntArray(lefts.size)
    private val lines = lines.toList()

    init {
        require(lefts.size == rights.size)
        for ((li, r) in this.lines.withIndex()) for (i in r) lineIndex[i] = li
    }

    override fun lineOf(index: Int): IntRange = lines[lineIndex[index.coerceIn(0, lineIndex.lastIndex)]]
    override fun left(index: Int): Float = lefts[index]
    override fun right(index: Int): Float = rights[index]

    companion object {
        /**
         * Lays words out like a monospace font: each character [charWidth] wide, words separated by
         * [spaceWidth]. [lineSizes] says how many words go on each line; every line starts at x = 0.
         */
        fun monospace(words: List<Word>, lineSizes: List<Int>, charWidth: Float, spaceWidth: Float): FixedTrackLayout {
            require(lineSizes.sum() == words.size) { "line sizes ${lineSizes.sum()} != ${words.size} words" }
            val lefts = FloatArray(words.size)
            val rights = FloatArray(words.size)
            val lines = mutableListOf<IntRange>()
            var i = 0
            for (n in lineSizes) {
                var x = 0f
                lines += i until i + n
                repeat(n) {
                    lefts[i] = x
                    rights[i] = x + words[i].display.length * charWidth
                    x = rights[i] + spaceWidth
                    i++
                }
            }
            return FixedTrackLayout(lines, lefts, rights)
        }
    }
}
