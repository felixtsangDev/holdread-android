package app.holdread.reader

import android.icu.text.BreakIterator
import app.holdread.engine.Segmenter

/** ICU word breaking (dictionary-based for Chinese), the same rules the engine's JVM tests use via ICU4J. */
object AndroidSegmenter : Segmenter {
    override fun segments(text: String): List<String> {
        val it = BreakIterator.getWordInstance()
        it.setText(text)
        val out = ArrayList<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            out += text.substring(start, end)
            start = end
            end = it.next()
        }
        return out
    }
}
