package app.holdread.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenizerTest {
    private val tok = Tokenizer(IcuSegmenter)

    @Test
    fun punctuationSticksToWords() {
        val w = tok.tokenize("He said, \"don't go.\" (Really?)")
        assertEquals(listOf("He", "said,", "\"don't", "go.\"", "(Really?)"), w.map { it.display })
        assertEquals("don't", w[2].core)
        assertEquals(1, w[1].pause)
        assertEquals(2, w[3].pause)
        assertTrue(w.last().paragraphEnd)
        assertFalse(w.first().paragraphEnd)
    }

    @Test
    fun offsetsPointIntoNormalizedText() {
        val text = "  He   said, \n \"don't go.\" — fine. "
        val norm = Tokenizer.normalize(text)
        for (w in tok.tokenize(text)) assertEquals(w.display, norm.substring(w.start, w.end))
    }

    @Test
    fun loneDashStaysTextButIsNotAWord() {
        val w = tok.tokenize("well — ok")
        assertEquals(listOf("well", "ok"), w.map { it.display })
    }

    @Test
    fun chineseCountsPerCharacter() {
        val text = "我今日去圖書館讀書。「好啊！」佢話。"
        val w = tok.tokenize(text)
        assertTrue(w.all { it.cjk })
        // every CJK character is counted once, punctuation never
        assertEquals(text.count { Cjk.contains(it.toString()) }, w.sumOf { it.units })
        assertTrue(w.any { it.pre == "「" })
        assertTrue(w.any { it.post.startsWith("！」") || it.post.startsWith("！") })
        val norm = Tokenizer.normalize(text)
        for (x in w) assertEquals(x.display, norm.substring(x.start, x.end))
    }

    @Test
    fun emptyBlocks() {
        assertTrue(tok.tokenize("   ").isEmpty())
        assertTrue(tok.tokenize("— …").isEmpty())
    }
}
