package app.holdread.engine

import kotlin.math.max
import kotlin.math.min

enum class CursorMode {
    /** A caret follows the finger; a word counts once the finger passes its middle. */
    POSITION,
    /** The underline jumps word by word to the word under the finger. */
    WORD,
}

enum class Status {
    /** Finger up, or finger not on a word. */
    IDLE,
    /** Reading at a good pace. */
    GO,
    /** Finger is ahead of the cursor; the cursor is catching up at the speed cap. */
    FAST,
    /** Finger is more than [EngineRules.AHEAD_WINDOW] words ahead: counting stops. */
    AHEAD,
    /** The line changed under the finger: drag back to the left to continue. */
    REWIND,
}

data class EngineSettings(
    /** Speed cap in words per minute (characters per minute for CJK). 100–900, default 350. */
    val wpm: Int = 350,
    val cursor: CursorMode = CursorMode.POSITION,
) {
    init {
        require(wpm in EngineRules.MIN_WPM..EngineRules.MAX_WPM) { "wpm $wpm out of range" }
    }
}

/** The exact numbers from the prototype. iOS must use the same ones; see spec/engine.md. */
object EngineRules {
    const val MIN_WPM = 100
    const val MAX_WPM = 900
    const val DEFAULT_WPM = 350

    /** The finger may run this many words ahead while the cursor catches up. */
    const val AHEAD_WINDOW = 3
    /** Being ahead for longer than this counts as one skip. */
    const val SKIP_AFTER_MS = 600L
    /** Reading time only counts while the cursor advanced within this long. */
    const val IDLE_AFTER_MS = 2500L
    /** A single frame never adds more than this to reading time (app was paused, etc). */
    const val MAX_FRAME_MS = 250L
    /** In WORD mode, the finger must be within this distance of a word to hit it. */
    const val WORD_HIT_SLOP = 40f
    /** In POSITION mode, going back is checked a little to the right of the finger, so jitter doesn't rewind. */
    const val BACK_SLOP = 8f
    /** Live speed is averaged over this window. */
    const val LIVE_RATE_WINDOW_MS = 6000L
    private const val LIVE_RATE_SAMPLES = 12

    internal const val SAMPLES = LIVE_RATE_SAMPLES

    /** Minimum time the cursor must stay on [word] at [wpm], in milliseconds. */
    fun minDwellMs(word: Word?, wpm: Int): Double {
        if (word == null) return 150.0
        val base = 60_000.0 / wpm
        var d = if (word.cjk) base * word.units else base * min(1.6, 0.6 + 0.05 * word.core.length)
        when (word.pause) {
            2 -> d += base * 0.5
            1 -> d += base * 0.25
        }
        if (word.paragraphEnd) d += base * 0.4
        return d
    }
}

/** Something the host (UI, session) should react to. Returned from [ReadingEngine.frame]. */
sealed interface EngineEvent {
    data class Advanced(val from: Int, val to: Int) : EngineEvent
    data class WentBack(val from: Int, val to: Int) : EngineEvent
    /** The cursor moved to a new line while the finger is down; [ReadingEngine.rewinding] is now true. */
    data class LineChanged(val line: IntRange) : EngineEvent
    /** The last word of the chapter was read. The host loads the next chapter with [ReadingEngine.loadChapter]. */
    data object ChapterEnded : EngineEvent
    data object Skipped : EngineEvent
}

/**
 * The hold-to-read engine for one chapter, ported 1:1 from the web prototype's finger loop.
 *
 * The host feeds it finger [down]/[move]/[up] in track coordinates and calls [frame] once per
 * display frame while the finger is down. Time is passed in explicitly (milliseconds, any
 * monotonic clock) so tests can replay a finger trace exactly.
 */
class ReadingEngine(
    words: List<Word>,
    layout: TrackLayout,
    var settings: EngineSettings = EngineSettings(),
    startIndex: Int = 0,
) {
    var words: List<Word> = words
        private set
    var layout: TrackLayout = layout
        private set

    /** Next word to read (the underlined one). Equals `words.size` once the chapter is finished. */
    var index: Int = startIndex.coerceIn(0, max(0, words.size - 1))
        private set
    var status: Status = Status.IDLE
        private set
    var touching: Boolean = false
        private set
    /** Line changed under the finger; it has to go back to the left first. */
    var rewinding: Boolean = false
        private set
    /** Chapter changed under the finger; it has to lift first. */
    var needLift: Boolean = false
        private set
    /** Last finger x on the track. */
    var fingerX: Float = 0f
        private set

    // --- counters (cumulative for this engine; a session takes deltas) ---
    /** Words read, in units (CJK characters count one each). Going back subtracts. */
    var unitsRead: Int = 0
        private set
    var skips: Int = 0
        private set
    /** Time the finger was actually reading. */
    var readingMs: Long = 0
        private set

    private var lastFrameT: Long = -1
    private var lastAdvanceT: Long = 0
    private var aheadSince: Long = -1
    private val advanceTimes = ArrayDeque<Pair<Long, Int>>()

    val finished: Boolean get() = index >= words.size
    val currentLine: IntRange get() = layout.lineOf(index.coerceAtMost(words.size - 1))

    fun down(x: Float, t: Long) {
        touching = true
        needLift = false
        rewinding = false
        fingerX = x
        lastFrameT = -1
        lastAdvanceT = t
    }

    fun move(x: Float) {
        if (touching) fingerX = x
    }

    fun up() {
        if (!touching) return
        touching = false
        needLift = false
        rewinding = false
        status = Status.IDLE
    }

    /**
     * Replaces the words, e.g. for the next chapter. If the finger is still down it must lift
     * before reading continues.
     */
    fun loadChapter(words: List<Word>, layout: TrackLayout, startIndex: Int = 0) {
        this.words = words
        this.layout = layout
        index = startIndex.coerceIn(0, max(0, words.size - 1))
        needLift = touching
        rewinding = false
    }

    /** Same words, new positions (font size changed, screen rotated). */
    fun relayout(layout: TrackLayout) {
        this.layout = layout
    }

    /** Moves the cursor without counting anything (table of contents, "read from here", restore position). */
    fun jumpTo(i: Int) {
        index = i.coerceIn(0, max(0, words.size - 1))
    }

    /**
     * Word index under the finger for the current line.
     * POSITION: the last word whose middle the finger has passed (`line.first - 1` if none).
     * WORD: the nearest word within [EngineRules.WORD_HIT_SLOP], or -1.
     */
    fun fingerIndex(x: Float): Int {
        if (finished) return -1
        val line = currentLine
        if (settings.cursor == CursorMode.WORD) {
            var best = -1
            var bestD = Float.MAX_VALUE
            for (i in line) {
                val l = layout.left(i)
                val r = layout.right(i)
                val d = if (x < l) l - x else if (x > r) x - r else 0f
                if (d < bestD) { bestD = d; best = i }
            }
            return if (bestD < EngineRules.WORD_HIT_SLOP) best else -1
        }
        var j = line.first - 1
        for (i in line) {
            val mid = (layout.left(i) + layout.right(i)) / 2
            if (mid <= x) j = i else break
        }
        return j
    }

    /** Call once per frame while [touching]. */
    fun frame(t: Long): List<EngineEvent> {
        if (!touching || finished) return emptyList()
        val events = mutableListOf<EngineEvent>()
        val dt = if (lastFrameT < 0) 0 else min(EngineRules.MAX_FRAME_MS, t - lastFrameT)
        lastFrameT = t

        // the track changes under a still finger when the line changes, so re-read it every frame
        val fingerJ = fingerIndex(fingerX)
        val fingerOK = settings.cursor == CursorMode.POSITION || fingerJ >= 0

        if (!needLift) {
            if (rewinding) {
                if (fingerOK && fingerJ <= index) rewinding = false
                status = Status.REWIND
            } else if (fingerJ > index + EngineRules.AHEAD_WINDOW) {
                status = Status.AHEAD
                if (aheadSince < 0) aheadSince = t
            } else {
                if (aheadSince >= 0) {
                    if (t - aheadSince > EngineRules.SKIP_AFTER_MS) {
                        skips++
                        events += EngineEvent.Skipped
                    }
                    aheadSince = -1
                }
                // sliding left goes back, but only within the current line
                val lineStart = currentLine.first
                val backJ = if (settings.cursor == CursorMode.POSITION) fingerIndex(fingerX + EngineRules.BACK_SLOP) else fingerJ
                if (fingerOK && backJ < index - 1 && backJ + 1 >= lineStart) {
                    goBack(max(lineStart, backJ + 1), events)
                    lastAdvanceT = t
                } else if (fingerJ >= index && t - lastAdvanceT >= EngineRules.minDwellMs(words[index], settings.wpm)) {
                    lastAdvanceT = t
                    advance(t, events)
                }
                status = when {
                    finished -> Status.IDLE
                    fingerJ > index + 1 -> Status.FAST
                    fingerJ >= index - 1 && fingerOK -> Status.GO
                    else -> Status.IDLE
                }
            }
        }

        if (t - lastAdvanceT < EngineRules.IDLE_AFTER_MS) readingMs += dt
        return events
    }

    /** Recent reading speed in units per minute, or null if there isn't enough data. */
    fun liveRate(t: Long): Int? {
        val recent = advanceTimes.filter { t - it.first < EngineRules.LIVE_RATE_WINDOW_MS }
        if (recent.size <= 2) return null
        val minutes = (recent.last().first - recent.first().first) / 60_000.0
        if (minutes <= 0) return null
        return Math.round(recent.drop(1).sumOf { it.second } / minutes).toInt()
    }

    private fun advance(t: Long, events: MutableList<EngineEvent>) {
        val from = index
        val word = words[index]
        unitsRead += word.units
        advanceTimes.addLast(t to word.units)
        if (advanceTimes.size > EngineRules.SAMPLES) advanceTimes.removeFirst()

        if (index < words.size - 1) {
            val oldLine = layout.lineOf(index)
            index++
            events += EngineEvent.Advanced(from, index)
            val newLine = layout.lineOf(index)
            if (newLine != oldLine) {
                rewinding = true
                events += EngineEvent.LineChanged(newLine)
            }
        } else {
            index = words.size
            needLift = true
            events += EngineEvent.Advanced(from, index)
            events += EngineEvent.ChapterEnded
        }
    }

    private fun goBack(to: Int, events: MutableList<EngineEvent>) {
        if (to >= index) return
        for (i in to until index) unitsRead -= words[i].units
        unitsRead = max(0, unitsRead)
        events += EngineEvent.WentBack(index, to)
        index = to
    }
}
