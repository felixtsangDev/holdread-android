package app.holdread.reader

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.holdread.engine.CursorMode
import app.holdread.engine.EngineSettings
import app.holdread.engine.Status
import app.holdread.ui.HoldReadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Drives the real reader screen with simulated touches on the track: hold, slide, release.
 * Native graphics, so text is measured with real fonts and word positions are real.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ReaderScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val book = Book(
        "Test", "HoldRead",
        listOf(
            Chapter(
                "One",
                listOf(
                    Block(
                        "The quick brown fox jumps over the lazy dog and then it runs far away into the " +
                            "green woods where nobody can see it any more at all, not even the birds.",
                    ),
                ),
            ),
            Chapter("Two", listOf(Block("A second chapter."))),
        ),
    )

    private lateinit var state: ReaderState

    private fun launch(settings: EngineSettings = EngineSettings(wpm = 300), dark: Boolean = false, b: Book = book) {
        state = ReaderState(b, settings, AndroidSegmenter)
        rule.mainClock.autoAdvance = false
        rule.setContent { HoldReadTheme(dark = dark) { ReaderScreen(state) } }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        assertTrue("text was never measured", state.layoutReady)
    }

    /** Track-local x for an engine x (the track is inset from the page text by 22 - 8 dp). */
    private fun trackX(engineX: Float): Float = engineX + with(rule.density) { 14.dp.toPx() }

    /** Just past the middle of word [i]: enough for the position cursor to count it. */
    private fun past(i: Int): Float = state.layout.left(i) + (state.layout.right(i) - state.layout.left(i)) * 0.6f

    private val track get() = rule.onNodeWithTag(ReaderTags.TRACK)
    private val y get() = with(rule.density) { 33.dp.toPx() }

    private fun frames(ms: Long) {
        var left = ms
        while (left > 0) {
            rule.mainClock.advanceTimeBy(16)
            left -= 16
        }
    }

    private fun down(engineX: Float) = track.performTouchInput { down(Offset(trackX(engineX), y)) }
    private fun moveTo(engineX: Float) = track.performTouchInput { moveTo(Offset(trackX(engineX), y)) }
    private fun up() = track.performTouchInput { up() }

    /** Slide from the current finger position to [engineX] over [ms], one move per frame. */
    private fun slideTo(engineX: Float, ms: Long) {
        val from = state.fingerX
        val steps = (ms / 16).coerceAtLeast(1)
        for (s in 1..steps) {
            moveTo(from + (engineX - from) * s / steps)
            rule.mainClock.advanceTimeBy(16)
        }
    }

    @Test
    fun holdAndSlideReadsAtTheSpeedCap() {
        launch(EngineSettings(wpm = 300)) // base 200 ms per word
        val line = state.currentLine
        assertTrue("first line too short: $line", line.count() >= 5)

        down(-5f)
        moveTo(past(line.first + 2))
        frames(96)
        // the shortest English word dwells 0.65 x 200 = 130 ms, so nothing is read yet
        assertEquals(0, state.index)
        assertTrue(state.fingerDown)

        frames(3000)
        // the cursor caught up with the finger and stopped at the first word it hasn't passed
        assertEquals(line.first + 3, state.index)
        assertEquals(Status.GO, state.status)
        assertEquals(3, state.unitsRead)

        up()
        frames(100)
        assertFalse(state.fingerDown)
        assertEquals(Status.IDLE, state.status)
        frames(1000)
        assertEquals("nothing is read with the finger up", line.first + 3, state.index)
    }

    @Test
    fun runningAheadIsFlaggedAndCountsASkip() {
        launch()
        val line = state.currentLine
        down(-5f)
        frames(32)
        moveTo(past(line.first + 4)) // more than 3 words ahead
        frames(1000)
        assertEquals(Status.AHEAD, state.status)
        assertEquals(0, state.index)
        assertEquals(0, state.skips)

        moveTo(past(line.first))
        frames(48)
        assertEquals(1, state.skips)
        assertEquals(1, state.index)
        up()
    }

    @Test
    fun slidingLeftGoesBackWithinTheLine() {
        launch(EngineSettings(wpm = 900))
        val line = state.currentLine
        down(-5f)
        slideTo(past(line.first + 4), 2000)
        frames(500)
        assertEquals(line.first + 5, state.index)

        moveTo(past(line.first + 1))
        frames(48)
        assertEquals(line.first + 2, state.index)
        assertEquals(2, state.unitsRead)
        up()
    }

    @Test
    fun newLineNeedsTheFingerBackOnTheLeft() {
        launch(EngineSettings(wpm = 900))
        val first = state.currentLine
        down(-5f)
        slideTo(state.layout.right(first.last) + 2f, 4000)
        frames(500)
        assertEquals(first.last + 1, state.index)
        assertTrue(state.rewinding)
        assertEquals(Status.REWIND, state.status)
        val second = state.currentLine
        assertEquals(first.last + 1, second.first)

        moveTo(-5f)
        frames(48)
        assertFalse(state.rewinding)
        // still can't go back to the previous line
        assertEquals(second.first, state.index)

        slideTo(past(second.first), 800)
        frames(300)
        assertEquals(second.first + 1, state.index)
        up()
    }

    @Test
    fun wordCursorFollowsTheWordUnderTheFinger() {
        launch(EngineSettings(wpm = 600, cursor = CursorMode.WORD))
        val line = state.currentLine
        down(state.layout.left(line.first) + 1f)
        frames(300)
        assertEquals(line.first + 1, state.index)
        moveTo(state.layout.left(line.first + 1) + 1f)
        frames(300)
        assertEquals(line.first + 2, state.index)
        up()
    }

    @Test
    fun finishingAChapterNeedsALift() {
        launch(EngineSettings(wpm = 900), b = Book("T", "A", listOf(Chapter("One", listOf(Block("Hi there."))), Chapter("Two", listOf(Block("Next one."))))))
        down(-5f)
        slideTo(state.layout.right(1) + 2f, 600)
        frames(600)
        assertEquals(1, state.chapterIndex)
        assertTrue(state.needLift)
        frames(300)
        assertEquals("no reading until the finger lifts", 0, state.index)
        up()
        frames(32)
        assertFalse(state.needLift)
    }

    @Test
    fun screenshots() {
        launch(EngineSettings(wpm = 600), b = SampleBook.book)
        down(-5f)
        slideTo(past(state.currentLine.first + 3), 1500)
        frames(300)
        shoot("reader-zh-reading")
        moveTo(past(state.currentLine.first + 3) + 400f)
        frames(200)
        shoot("reader-zh-ahead")
        up()
        state.goToChapter(1)
        frames(500)
        down(-5f)
        slideTo(past(state.currentLine.first + 3), 1500)
        frames(300)
        shoot("reader-en-reading")
        up()
    }

    @Test
    fun screenshotDark() {
        launch(EngineSettings(wpm = 600), dark = true, b = SampleBook.book)
        state.goToChapter(1)
        frames(500)
        down(-5f)
        slideTo(past(state.currentLine.first + 2), 1500)
        frames(300)
        shoot("reader-en-dark")
        up()
    }

    /**
     * Draws the window straight into a bitmap. (captureToImage waits for idle, which never comes while
     * a finger is on the track and the frame loop runs.) Saved as JPEG so CI can print it into the log.
     */
    private fun shoot(name: String) {
        val dir = File(System.getProperty("holdread.screenshots") ?: "build/outputs/screenshots").apply { mkdirs() }
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bmp)) }
        val small = Bitmap.createScaledBitmap(bmp, view.width / 3, view.height / 3, true)
        File(dir, "$name.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }
}
