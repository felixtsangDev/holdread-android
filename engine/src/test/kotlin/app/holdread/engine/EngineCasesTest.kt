package app.holdread.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Replays every finger trace in every JSON file in spec/cases (shared with iOS) against [ReadingEngine].
 * See spec/engine.md for the file format.
 */
class EngineCasesTest {

    private val casesDir = File(System.getProperty("holdread.cases") ?: "../spec/cases")

    @Test
    fun allCases() {
        val files = casesDir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        assertTrue("no cases found in $casesDir", files.isNotEmpty())
        val failures = files.mapNotNull { f ->
            runCatching { runCase(Json.parseToJsonElement(f.readText()).jsonObject) }
                .exceptionOrNull()?.let { "${f.name}: ${it.message}" }
        }
        assertTrue(failures.joinToString("\n", prefix = "\n"), failures.isEmpty())
    }

    private fun runCase(case: JsonObject) {
        val settings = case["settings"]!!.jsonObject
        val cursor = when (settings["cursor"]?.jsonPrimitive?.content ?: "position") {
            "position" -> CursorMode.POSITION
            "word" -> CursorMode.WORD
            else -> error("bad cursor")
        }
        val wpm = settings["wpm"]?.jsonPrimitive?.int ?: EngineRules.DEFAULT_WPM
        val charWidth = case["charWidth"]?.jsonPrimitive?.float ?: 10f
        val spaceWidth = case["spaceWidth"]?.jsonPrimitive?.float ?: 10f
        val frameMs = case["frameMs"]?.jsonPrimitive?.long ?: 16L

        val tokenizer = Tokenizer(IcuSegmenter)
        val words = mutableListOf<Word>()
        val lineSizes = mutableListOf<Int>()
        for (block in case["blocks"]!!.jsonArray) {
            val lines = block.jsonArray.map { it.jsonPrimitive.content }
            val blockWords = tokenizer.tokenize(lines.joinToString(" "))
            val sizes = lines.map { tokenizer.tokenize(it).size }
            check(sizes.sum() == blockWords.size) { "line split doesn't match block tokens" }
            words += blockWords
            lineSizes += sizes
        }
        val layout = FixedTrackLayout.monospace(words, lineSizes, charWidth, spaceWidth)
        val engine = ReadingEngine(words, layout, EngineSettings(wpm, cursor))

        fun xOf(e: JsonElement): Float = when (e) {
            is JsonPrimitive -> e.float
            is JsonObject -> {
                val i = e["word"]!!.jsonPrimitive.int
                val f = e["f"]?.jsonPrimitive?.float ?: 0.6f
                layout.left(i) + (layout.right(i) - layout.left(i)) * f
            }
            else -> error("bad x: $e")
        }

        val steps = case["script"]!!.jsonArray.map { it.jsonObject }
        val end = steps.maxOf { maxOf(it["at"]!!.jsonPrimitive.long, it["slide"]?.jsonObject?.get("until")?.jsonPrimitive?.long ?: 0) }
        var next = 0
        var slide: Triple<Long, Float, Pair<Long, Float>>? = null // start t, start x, (until, to x)
        var chapterEnded = false

        var t = 0L
        while (t < end + frameMs) {
            val expects = mutableListOf<JsonObject>()
            while (next < steps.size && steps[next]["at"]!!.jsonPrimitive.long <= t) {
                val s = steps[next++]
                when {
                    "down" in s -> engine.down(xOf(s["down"]!!), t)
                    "move" in s -> { slide = null; engine.move(xOf(s["move"]!!)) }
                    "up" in s -> { slide = null; engine.up() }
                    "slide" in s -> {
                        val sl = s["slide"]!!.jsonObject
                        slide = Triple(t, engine.fingerX, sl["until"]!!.jsonPrimitive.long to xOf(sl["to"]!!))
                    }
                    "expect" in s -> expects += s["expect"]!!.jsonObject
                    else -> error("unknown step $s")
                }
            }
            slide?.let { (t0, x0, target) ->
                val (t1, x1) = target
                val p = if (t1 <= t0) 1f else ((t - t0).toFloat() / (t1 - t0)).coerceIn(0f, 1f)
                engine.move(x0 + (x1 - x0) * p)
                if (p >= 1f) slide = null
            }
            if (engine.touching) {
                if (engine.frame(t).any { it is EngineEvent.ChapterEnded }) chapterEnded = true
            }
            for (e in expects) check(e, engine, chapterEnded, t)
            t += frameMs
        }
        check(next == steps.size) { "script steps after the end were not run" }
    }

    private fun check(e: JsonObject, engine: ReadingEngine, chapterEnded: Boolean, t: Long) {
        fun eq(key: String, actual: Any) {
            val want = e[key] ?: return
            val expected: Any = when (actual) {
                is Int -> want.jsonPrimitive.int
                is Long -> want.jsonPrimitive.long
                is Boolean -> want.jsonPrimitive.boolean
                else -> want.jsonPrimitive.content
            }
            assertEquals("t=$t $key", expected, actual)
        }
        eq("index", engine.index)
        eq("status", engine.status.name.lowercase())
        eq("units", engine.unitsRead)
        eq("skips", engine.skips)
        eq("readingMs", engine.readingMs)
        eq("rewinding", engine.rewinding)
        eq("needLift", engine.needLift)
        eq("chapterEnded", chapterEnded)
        val unknown = e.keys - setOf("index", "status", "units", "skips", "readingMs", "rewinding", "needLift", "chapterEnded")
        check(unknown.isEmpty()) { "unknown expect keys $unknown" }
    }
}
