package app.holdread.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.holdread.engine.CursorMode
import app.holdread.engine.EngineRules
import app.holdread.engine.Status
import app.holdread.ui.LocalReaderColors
import app.holdread.ui.ReaderColors

/** Horizontal padding of the page text. The track mirrors it, so a word's x on the track equals its x in the text. */
private val PagePadding = 22.dp
/** The track is inset from the screen edge by this much; track x is shifted to match. */
private val TrackInset = 8.dp

object ReaderTags {
    const val TRACK = "track"
    const val PAGE = "page"
    const val STATUS = "status"
    const val SETTINGS = "settings"
}

@Composable
fun ReaderScreen(state: ReaderState, modifier: Modifier = Modifier) {
    val c = LocalReaderColors.current
    var showSettings by remember { mutableStateOf(false) }

    // run the engine once per frame while a finger is on the track
    LaunchedEffect(state.fingerDown) {
        if (state.fingerDown) while (true) withFrameMillis { t -> state.frame(t) }
    }

    Column(modifier.fillMaxSize().background(c.bg)) {
        Header(state, c, onSettings = { showSettings = true })
        Box(Modifier.fillMaxWidth().height(3.dp).background(c.line)) {
            val total = state.chapter.words.size.coerceAtLeast(1)
            Box(
                Modifier.fillMaxWidth((state.index.toFloat() / total).coerceIn(0f, 1f)).height(3.dp).background(c.accent),
            )
        }
        Page(state, c, Modifier.weight(1f))
        Footer(state, c)
    }

    if (showSettings) SettingsSheet(state, onDismiss = { showSettings = false })
}

@Composable
private fun Header(state: ReaderState, c: ReaderColors, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                state.book.title + " · " + state.book.author,
                color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                state.chapter.title,
                color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onSettings, modifier = Modifier.testTag(ReaderTags.SETTINGS)) {
            Text("設定", color = c.accent)
        }
    }
}

@Composable
private fun Page(state: ReaderState, c: ReaderColors, modifier: Modifier) {
    val scroll = rememberScrollState()
    BoxWithConstraints(modifier.fillMaxWidth().background(c.paper).testTag(ReaderTags.PAGE)) {
        val viewport = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val topPad = with(density) { (viewport * 0.45f).toDp() }
        val bottomPad = with(density) { (viewport * 0.55f).toDp() }
        // read the version so the band moves once text has been measured
        val version = state.layoutVersion
        val box = if (version >= 0) state.currentLineBox() else null

        // keep the line being read in the middle of the screen
        LaunchedEffect(box?.top, state.layoutVersion) {
            val b = state.currentLineBox() ?: return@LaunchedEffect
            val target = (b.top + b.bottom) / 2 - viewport * 0.45f
            scroll.animateScrollTo(target.toInt().coerceAtLeast(0))
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .drawBehind {
                    val b = box ?: return@drawBehind
                    val pad = 4.dp.toPx()
                    drawRect(c.band, Offset(0f, b.top - pad), Size(size.width, b.bottom - b.top + 2 * pad))
                    drawRect(c.accent, Offset(0f, b.top - pad), Size(3.dp.toPx(), b.bottom - b.top + 2 * pad))
                },
        ) {
            Spacer(Modifier.height(topPad))
            state.chapter.blocks.forEachIndexed { bi, block ->
                // only the block holding the cursor (and blocks it crosses) see a changing value
                val local = (state.index - block.firstWord).coerceIn(-1, block.words.size)
                val ahead = state.status == Status.AHEAD && local in block.words.indices
                val text = remember(block, local, ahead, c) { styledBlock(block, local, ahead, c) }
                Text(
                    text,
                    style = blockStyle(block.heading, c),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PagePadding)
                        .padding(bottom = if (block.heading) 12.dp else 16.dp)
                        .onGloballyPositioned { state.onBlockPositioned(bi, it.positionInParent().y) },
                    onTextLayout = { state.onBlockLayout(bi, it) },
                )
            }
            Text(
                if (state.chapterIndex < state.book.chapters.size - 1) "— 本章完 · 提起手指，會去下一章 —" else "— 全書完 —",
                color = c.muted, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(bottomPad))
        }
    }
}

private fun blockStyle(heading: Boolean, c: ReaderColors) = TextStyle(
    fontFamily = FontFamily.Serif,
    fontSize = if (heading) 26.sp else 21.sp,
    lineHeight = if (heading) 1.35.em else 1.85.em,
    fontWeight = if (heading) FontWeight.SemiBold else FontWeight.Normal,
    color = c.ink,
)

/** Read words greyed, the next word underlined (red when the finger ran ahead). */
private fun styledBlock(block: PreparedBlock, local: Int, ahead: Boolean, c: ReaderColors): AnnotatedString =
    buildAnnotatedString {
        append(block.text)
        for ((k, w) in block.words.withIndex()) {
            when {
                k < local -> addStyle(SpanStyle(color = c.read), w.start, w.end)
                k == local -> addStyle(
                    SpanStyle(
                        color = if (ahead) c.bad else c.ink,
                        textDecoration = TextDecoration.Underline,
                        background = if (ahead) c.bad.copy(alpha = 0.10f) else c.accent.copy(alpha = 0.12f),
                    ),
                    w.start, w.end,
                )
            }
        }
    }

@Composable
private fun Footer(state: ReaderState, c: ReaderColors) {
    Column(
        Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().padding(top = 10.dp, bottom = 12.dp),
    ) {
        Track(state, c)
        StatusRow(state, c)
    }
}

@Composable
private fun Track(state: ReaderState, c: ReaderColors) {
    val density = LocalDensity.current
    val padPx = with(density) { (PagePadding - TrackInset).toPx() }
    val bad = state.status == Status.AHEAD
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(74.dp)
            .padding(start = TrackInset, end = TrackInset, bottom = 8.dp)
            .background(if (state.fingerDown) c.band else c.paper, shape)
            .border(1.5.dp, if (bad) c.bad else c.accent.copy(alpha = if (state.fingerDown) 1f else 0.6f), shape)
            .testTag(ReaderTags.TRACK)
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    state.onFingerDown(down.position.x - padPx)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            state.onFingerMove(change.position.x - padPx)
                            change.consume()
                        }
                    } finally {
                        state.onFingerUp()
                    }
                }
            },
    ) {
        Text(
            "👆 喺度由左至右 slide · 每格 = 中間嗰行嘅字",
            color = c.muted, fontSize = 11.sp,
            modifier = Modifier.padding(start = 12.dp, top = 6.dp),
        )
        // read the version so the track redraws when the layout changes
        val version = state.layoutVersion
        val line = state.currentLine
        val index = state.index
        val layout = state.layout
        val fingerX = state.fingerX
        val showCaret = state.fingerDown && state.settings.cursor == CursorMode.POSITION
        Canvas(Modifier.fillMaxSize()) {
            if (version < 0) return@Canvas
            val h = 26.dp.toPx()
            val top = size.height / 2 - 10.dp.toPx()
            for (i in line) {
                val l = layout.left(i) + padPx
                val w = (layout.right(i) - layout.left(i)).coerceAtLeast(6.dp.toPx())
                val color = when {
                    i < index -> c.read.copy(alpha = 0.55f)
                    i == index -> if (bad) c.bad else c.accent
                    else -> c.line
                }
                drawRoundRect(color, Offset(l, top), Size(w, h), CornerRadius(7.dp.toPx()))
            }
            if (showCaret) {
                drawRect(c.accent, Offset(fingerX + padPx - 1.5.dp.toPx(), 8.dp.toPx()), Size(3.dp.toPx(), size.height - 16.dp.toPx()))
            }
        }
    }
}

@Composable
private fun StatusRow(state: ReaderState, c: ReaderColors) {
    val (text, color) = when {
        state.bookFinished -> "全書讀完 🎉" to c.ok
        state.needLift -> "下一章：提起手指" to c.muted
        else -> when (state.status) {
            Status.IDLE -> "軌道 slide 讀" to c.muted
            Status.GO -> "讀緊 ✓" to c.ok
            Status.FAST -> "慢少少，cursor 追緊你" to c.bad
            Status.AHEAD -> "跳咗字！返去底線嗰隻字" to c.bad
            Status.REWIND -> "↩ 換行：手指拉返去最左" to c.ok
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(c.paper, RoundedCornerShape(14.dp))
            .border(1.dp, if (color == c.muted) c.line else color, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag(ReaderTags.STATUS),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text, color = color, fontSize = 14.sp)
        Text(
            (state.liveRate?.let { "$it /分鐘" } ?: "上限 ${state.settings.wpm} /分鐘") +
                if (state.skips > 0) " · 跳字 ${state.skips}" else "",
            color = c.muted, fontSize = 14.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(state: ReaderState, onDismiss: () -> Unit) {
    val c = LocalReaderColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.paper) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
            Text("設定", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("速度上限", color = c.ink)
                Text("${state.settings.wpm} /分鐘", color = c.accent, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = state.settings.wpm.toFloat(),
                onValueChange = { v ->
                    val wpm = (Math.round(v / 10f) * 10).coerceIn(EngineRules.MIN_WPM, EngineRules.MAX_WPM)
                    state.updateSettings(state.settings.copy(wpm = wpm))
                },
                valueRange = EngineRules.MIN_WPM.toFloat()..EngineRules.MAX_WPM.toFloat(),
            )
            Text(
                "手指快過呢個速度，cursor 唔會追上嚟。英文以「字／分鐘」計；中文以「個字／分鐘」計。",
                color = c.muted, fontSize = 12.sp,
            )
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = c.line)
            Text("Cursor 點樣行", color = c.ink)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.settings.cursor == CursorMode.POSITION,
                    onClick = { state.updateSettings(state.settings.copy(cursor = CursorMode.POSITION)) },
                    label = { Text("跟手指位置") },
                )
                FilterChip(
                    selected = state.settings.cursor == CursorMode.WORD,
                    onClick = { state.updateSettings(state.settings.copy(cursor = CursorMode.WORD)) },
                    label = { Text("逐個詞跳") },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = c.line)
            Text("章節", color = c.ink)
            state.book.chapters.forEachIndexed { i, ch ->
                TextButton(onClick = { state.goToChapter(i); onDismiss() }) {
                    Text(ch.title, color = if (i == state.chapterIndex) c.accent else c.ink)
                }
            }
        }
    }
}
