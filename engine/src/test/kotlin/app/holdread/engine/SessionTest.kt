package app.holdread.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SessionTest {
    private val words = Tokenizer(IcuSegmenter).tokenize("aa bb cc dd ee ff gg hh")
    private fun engine() = ReadingEngine(
        words, FixedTrackLayout.monospace(words, listOf(words.size), 10f, 10f), EngineSettings(600),
    )

    /** Hold the finger past word [upTo] for [ms] milliseconds, 16 ms frames. */
    private fun ReadingEngine.hold(upTo: Int, from: Long, ms: Long, session: ReadingSession? = null): Long {
        down(0f, from)
        move(layout.right(upTo))
        var t = from
        while (t <= from + ms) {
            val ev = frame(t)
            session?.update(this, ev.any { it is EngineEvent.ChapterEnded })
            t += 16
        }
        up()
        return t
    }

    @Test
    fun timedSessionCompletesOnReadingTime() {
        val e = engine()
        val s = ReadingSession(SessionGoal.Minutes(5), e)
        // 5 minutes of a finger on the track, but the cursor stops after 3 words,
        // so only 2.5 s of each hold counts as reading
        var t = 0L
        repeat(10) { t = e.hold(2, t, 4000, s) }
        assertFalse(s.done)
        assertTrue(s.stats(e).readingMs in 20_000..30_000)
    }

    @Test
    fun countsAreDeltasFromSessionStart() {
        val e = engine()
        e.hold(1, 0, 500)
        assertEquals(2, e.unitsRead)
        val s = ReadingSession(SessionGoal.Chapter, e)
        assertEquals(0, s.stats(e).units)
        e.hold(4, 1000, 1000, s)
        assertEquals(3, s.stats(e).units)
        s.onLeave()
        s.giveUp(e)
        assertTrue(s.done)
        assertFalse(s.completed)
        assertEquals(1, s.stats(e).leaves)
    }

    @Test
    fun chapterGoalEndsWithChapter() {
        val e = engine()
        val s = ReadingSession(SessionGoal.Chapter, e)
        // finger walks along the line slowly enough to stay within 3 words
        e.down(0f, 0)
        var t = 0L
        while (!s.done && t < 10_000) {
            e.move(minOf(230f, t / 4f))
            s.update(e, e.frame(t).any { it is EngineEvent.ChapterEnded })
            t += 16
        }
        assertTrue(s.completed)
        assertEquals(8, s.stats(e).units)
        assertTrue(e.needLift)
    }

    @Test
    fun streak() {
        val d = LocalDate.of(2026, 11, 12)
        var s = Streak()
        s = s.completed(d)
        assertEquals(1, s.days)
        s = s.completed(d)
        assertEquals(1, s.days)
        s = s.completed(d.plusDays(1))
        assertEquals(2, s.days)
        assertEquals(2, s.current(d.plusDays(2)))
        assertEquals(0, s.current(d.plusDays(3)))
        s = s.completed(d.plusDays(5))
        assertEquals(1, s.days)
        assertEquals(0, s.gaveUp().days)
    }
}
