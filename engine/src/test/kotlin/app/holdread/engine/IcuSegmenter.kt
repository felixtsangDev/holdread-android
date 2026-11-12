package app.holdread.engine

import com.ibm.icu.text.BreakIterator

/** ICU4J word segmentation: the same engine Android exposes as android.icu.text.BreakIterator. */
object IcuSegmenter : Segmenter {
    override fun segments(text: String): List<String> {
        val it = BreakIterator.getWordInstance()
        it.setText(text)
        val out = mutableListOf<String>()
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
