package app.holdread.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextLayoutResult
import app.holdread.engine.EngineEvent
import app.holdread.engine.EngineSettings
import app.holdread.engine.FixedTrackLayout
import app.holdread.engine.ReadingEngine
import app.holdread.engine.Segmenter
import app.holdread.engine.Status
import app.holdread.engine.Tokenizer
import app.holdread.engine.TrackLayout
import app.holdread.engine.Word

/** One block of a chapter, tokenized: its normalized text and the global index of its first word. */
data class PreparedBlock(val text: String, val heading: Boolean, val firstWord: Int, val words: List<Word>) {
    val lastWord: Int get() = firstWord + words.size - 1
}

data class PreparedChapter(val title: String, val blocks: List<PreparedBlock>) {
    val words: List<Word> = blocks.flatMap { it.words }
}

fun prepareChapter(chapter: Chapter, tokenizer: Tokenizer): PreparedChapter {
    var next = 0
    val blocks = chapter.blocks.mapNotNull { b ->
        val words = tokenizer.tokenize(b.text)
        if (words.isEmpty()) return@mapNotNull null
        PreparedBlock(Tokenizer.normalize(b.text), b.heading, next, words).also { next += words.size }
    }
    return PreparedChapter(chapter.title, blocks)
}

/** Vertical position of the line being read, in page-content pixels. */
data class LineBox(val top: Float, val bottom: Float)

/**
 * Everything the reader screen shows, driven by [ReadingEngine]. The screen reports text layout and
 * finger events here; [frame] runs once per display frame while a finger is on the track.
 */
@Stable
class ReaderState(
    val book: Book,
    settings: EngineSettings = EngineSettings(),
    segmenter: Segmenter = AndroidSegmenter,
) {
    private val tokenizer = Tokenizer(segmenter)

    var chapterIndex by mutableIntStateOf(0)
        private set
    var chapter by mutableStateOf(prepareChapter(book.chapters[0], tokenizer))
        private set

    val engine = ReadingEngine(chapter.words, PendingLayout, settings)

    var settings by mutableStateOf(settings)
        private set

    // --- mirrored engine state, so Compose recomposes when it changes ---
    var index by mutableIntStateOf(0)
        private set
    var status by mutableStateOf(Status.IDLE)
        private set
    var rewinding by mutableStateOf(false)
        private set
    var needLift by mutableStateOf(false)
        private set
    var skips by mutableIntStateOf(0)
        private set
    var unitsRead by mutableIntStateOf(0)
        private set
    var liveRate by mutableStateOf<Int?>(null)
        private set
    var bookFinished by mutableStateOf(false)
        private set
    /** Bumped whenever word positions change, so the track redraws. */
    var layoutVersion by mutableIntStateOf(0)
        private set

    // --- finger ---
    /** A finger is on the track (drives the frame loop). */
    var fingerDown by mutableStateOf(false)
        private set
    var fingerX by mutableFloatStateOf(0f)
        private set
    private var pendingDown = false

    // --- layout reported by the screen ---
    private var blockLayouts = arrayOfNulls<TextLayoutResult>(chapter.blocks.size)
    private var blockTops = FloatArray(chapter.blocks.size) { Float.NaN }
    private var trackLayout: TrackLayout? = null

    val layoutReady: Boolean get() = trackLayout != null

    /** For tests: which blocks have been measured. */
    internal fun layoutDebug(): String =
        "chapter=$chapterIndex blocks=${chapter.blocks.size} measured=${blockLayouts.map { it != null }}"
    val layout: TrackLayout get() = trackLayout ?: PendingLayout

    /** Words on the line being read. */
    val currentLine: IntRange
        get() = if (layoutReady && !engine.finished) engine.currentLine else IntRange.EMPTY

    fun onBlockLayout(block: Int, result: TextLayoutResult) {
        if (block !in blockLayouts.indices) return
        blockLayouts[block] = result
        rebuildLayout()
    }

    fun onBlockPositioned(block: Int, top: Float) {
        if (block !in blockTops.indices) return
        blockTops[block] = top
    }

    /** Where the current line sits on the page, for centering and the highlight band. */
    fun currentLineBox(): LineBox? {
        if (!layoutReady || engine.finished) return null
        val i = index
        val b = blockOf(i) ?: return null
        val res = blockLayouts[b] ?: return null
        val top = blockTops[b].takeUnless { it.isNaN() } ?: return null
        val w = chapter.words[i]
        val line = res.getLineForOffset(w.start)
        return LineBox(top + res.getLineTop(line), top + res.getLineBottom(line))
    }

    fun blockOf(i: Int): Int? = chapter.blocks.indexOfFirst { i in it.firstWord..it.lastWord }.takeIf { it >= 0 }

    // --- finger events, in track coordinates (same x as the text) ---

    fun onFingerDown(x: Float) {
        fingerDown = true
        fingerX = x
        pendingDown = true
    }

    fun onFingerMove(x: Float) {
        fingerX = x
        engine.move(x)
    }

    fun onFingerUp() {
        fingerDown = false
        pendingDown = false
        engine.up()
        sync(null)
    }

    /** One display frame while a finger is down. [t] is the frame time in milliseconds. */
    fun frame(t: Long) {
        if (!layoutReady || bookFinished) return
        if (pendingDown) {
            engine.down(fingerX, t)
            pendingDown = false
        }
        val events = engine.frame(t)
        if (events.any { it is EngineEvent.ChapterEnded }) {
            if (chapterIndex < book.chapters.size - 1) loadChapter(chapterIndex + 1) else bookFinished = true
        }
        sync(t)
    }

    fun updateSettings(s: EngineSettings) {
        settings = s
        engine.settings = s
    }

    fun goToChapter(n: Int) {
        if (n in book.chapters.indices) {
            bookFinished = false
            loadChapter(n)
            sync(null)
        }
    }

    private fun loadChapter(n: Int) {
        chapterIndex = n
        chapter = prepareChapter(book.chapters[n], tokenizer)
        blockLayouts = arrayOfNulls(chapter.blocks.size)
        blockTops = FloatArray(chapter.blocks.size) { Float.NaN }
        trackLayout = null
        engine.loadChapter(chapter.words, PendingLayout)
    }

    private fun rebuildLayout() {
        val layouts = blockLayouts
        if (layouts.any { it == null }) return
        val words = chapter.words
        val lefts = FloatArray(words.size)
        val rights = FloatArray(words.size)
        val lines = mutableListOf<IntRange>()
        for ((b, block) in chapter.blocks.withIndex()) {
            val res = layouts[b]!!
            var lineStart = block.firstWord
            var lineNo = -1
            for ((k, w) in block.words.withIndex()) {
                val i = block.firstWord + k
                val first = res.getBoundingBox(w.start)
                val last = res.getBoundingBox((w.end - 1).coerceAtLeast(w.start))
                val ln = res.getLineForOffset(w.start)
                lefts[i] = first.left
                // a word broken across lines keeps the part on its first line
                rights[i] = if (res.getLineForOffset(w.end - 1) == ln) last.right else res.getLineRight(ln)
                if (ln != lineNo) {
                    if (lineNo >= 0) lines += lineStart until i
                    lineStart = i
                    lineNo = ln
                }
            }
            lines += lineStart..block.lastWord
        }
        val l = FixedTrackLayout(lines, lefts, rights)
        trackLayout = l
        engine.relayout(l)
        layoutVersion++
        sync(null)
    }

    private fun sync(t: Long?) {
        index = engine.index
        status = engine.status
        rewinding = engine.rewinding
        needLift = engine.needLift
        skips = engine.skips
        unitsRead = engine.unitsRead
        liveRate = if (t != null && engine.touching) engine.liveRate(t) else null
    }

    /** Before text is measured: no word is reachable. */
    private object PendingLayout : TrackLayout {
        override fun lineOf(index: Int): IntRange = index..index
        override fun left(index: Int): Float = -1e6f
        override fun right(index: Int): Float = -1e6f
    }
}

@Composable
fun rememberReaderState(book: Book): ReaderState = remember(book) { ReaderState(book) }
