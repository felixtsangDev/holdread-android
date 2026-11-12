package app.holdread.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class DwellTest {
    private fun dwell(w: Word, wpm: Int = 600) = EngineRules.minDwellMs(w, wpm)

    @Test
    fun englishScalesWithLengthAndCaps() {
        // base = 100 ms at 600 wpm
        assertEquals(65.0, dwell(Word("a")), 1e-9)          // 0.6 + 0.05
        assertEquals(100.0, dwell(Word("eightish")), 1e-9) // 0.6 + 0.4
        assertEquals(160.0, dwell(Word("internationalization")), 1e-9) // capped at 1.6
    }

    @Test
    fun pausesAddTime() {
        assertEquals(70.0 + 25, dwell(Word("ab", post = ",")), 1e-9)
        assertEquals(70.0 + 50, dwell(Word("ab", post = ".")), 1e-9)
        assertEquals(70.0 + 50 + 40, dwell(Word("ab", post = ".", paragraphEnd = true)), 1e-9)
    }

    @Test
    fun cjkIsPerCharacter() {
        assertEquals(200.0, dwell(Word("讀書")), 1e-9)
        assertEquals(2, Word("讀書").units)
        assertEquals(300.0 + 50, dwell(Word("圖書館", post = "。")), 1e-9)
    }

    @Test
    fun defaultSpeed() {
        // 350 wpm: base ~171 ms
        assertEquals(60_000.0 / 350 * 0.85, dwell(Word("hello"), 350), 1e-9)
    }
}
