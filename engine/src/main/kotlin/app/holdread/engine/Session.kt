package app.holdread.engine

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

/** How long a timed session runs. */
sealed interface SessionGoal {
    data class Minutes(val minutes: Int) : SessionGoal {
        val ms: Long get() = minutes * 60_000L
    }
    /** Ends when the current chapter is finished. */
    data object Chapter : SessionGoal

    companion object {
        val CHOICES: List<SessionGoal> = listOf(5, 10, 20, 30, 45).map { Minutes(it) } + Chapter
    }
}

data class SessionStats(
    val readingMs: Long,
    val units: Int,
    val skips: Int,
    val leaves: Int,
) {
    /** Actual speed in units per minute (0 if almost no time). */
    val rate: Int get() {
        val minutes = readingMs / 60_000.0
        return if (minutes > 0.05) (units / minutes).roundToInt() else 0
    }
}

/**
 * A timed reading session. It reads the engine's counters as deltas from when it started,
 * so the engine never needs to know about sessions.
 */
class ReadingSession(val goal: SessionGoal, engine: ReadingEngine) {
    private var baseMs = engine.readingMs
    private var baseUnits = engine.unitsRead
    private var baseSkips = engine.skips
    private var unitsCarried = 0
    private var msCarried = 0L
    private var skipsCarried = 0

    var leaves: Int = 0
        private set
    var done: Boolean = false
        private set
    var completed: Boolean = false
        private set
    private var final: SessionStats? = null

    fun stats(engine: ReadingEngine): SessionStats = final ?: SessionStats(
        readingMs = msCarried + engine.readingMs - baseMs,
        units = max(0, unitsCarried + engine.unitsRead - baseUnits),
        skips = skipsCarried + engine.skips - baseSkips,
        leaves = leaves,
    )

    /** The app went to the background mid-session. */
    fun onLeave() {
        if (!done) leaves++
    }

    /**
     * Call after each engine frame. Returns true when the session just completed.
     * [chapterEnded] is true when the frame produced [EngineEvent.ChapterEnded].
     */
    fun update(engine: ReadingEngine, chapterEnded: Boolean): Boolean {
        if (done) return false
        val s = stats(engine)
        val reached = when (goal) {
            is SessionGoal.Minutes -> s.readingMs >= goal.ms
            SessionGoal.Chapter -> chapterEnded
        }
        if (reached) finish(engine, completed = true)
        return reached
    }

    /** Before the host swaps the engine's chapter, fold the counters so nothing is lost. */
    fun rebase(engine: ReadingEngine) {
        val s = stats(engine)
        msCarried = s.readingMs; unitsCarried = s.units; skipsCarried = s.skips
        baseMs = engine.readingMs; baseUnits = engine.unitsRead; baseSkips = engine.skips
    }

    /** Give up (after the 3-second hold). Breaks the streak. */
    fun giveUp(engine: ReadingEngine) {
        if (!done) finish(engine, completed = false)
    }

    private fun finish(engine: ReadingEngine, completed: Boolean) {
        final = stats(engine)
        done = true
        this.completed = completed
    }

    companion object {
        /** Hold "give up" this long to quit a session. */
        const val GIVE_UP_HOLD_MS = 3000L
    }
}

/** Consecutive days with a completed session. */
data class Streak(val days: Int = 0, val lastDay: LocalDate? = null) {
    /** A session was completed on [today]. */
    fun completed(today: LocalDate): Streak = when (lastDay) {
        today -> this
        today.minusDays(1) -> Streak(days + 1, today)
        else -> Streak(1, today)
    }

    /** A session was given up: the streak resets. */
    fun gaveUp(): Streak = Streak()

    /** Days to show today: a streak whose last day is before yesterday has lapsed. */
    fun current(today: LocalDate): Int =
        if (lastDay != null && !lastDay.isBefore(today.minusDays(1))) days else 0
}
