package ca.mpreg.webgpuviewer.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val Active = Color(0xFFE53935)
private val Found = Color(0xFF1E88E5)
private val Draft = Color(0xFFFFC400)
private val Chip = Color(0xE0202020)
private val PanelColor = Color(0xFF2E7D32)

/** A panel's polygon (x, y pairs, normalised) outlined. */
private fun DrawScope.panelShape(p: FloatArray, stroke: Stroke) {
    if (p.size < 6) return
    val path = Path().apply {
        moveTo(p[0] * size.width, p[1] * size.height)
        for (i in 2 until p.size step 2) lineTo(p[i] * size.width, p[i + 1] * size.height)
        close()
    }
    drawPath(path, PanelColor, style = stroke)
}

private fun DrawScope.panelBox(p: FloatArray, stroke: Stroke) = drawRect(
    color = PanelColor,
    topLeft = Offset(p[0] * size.width, p[1] * size.height),
    size = Size((p[2] - p[0]) * size.width, (p[3] - p[1]) * size.height),
    style = stroke,
)

/** How long a bubble stepped to takes to shed its veil. */
private const val REVEAL_MS = 180

/**
 * Drawn over the page: every bubble found outlined in blue round its shape and the open one in
 * red, where bubbles show below the page or in debug mode; in the view below the page, the
 * bubbles not yet read hidden; a spinner while bubbles are found; and in debug mode their
 * numbers, a toolbar to correct them, a box to draw a missed one, and framing by hand.
 */
@Composable
internal fun BubbleUi(state: ImageViewerState) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val marks = state.marks
        val active = state.markActive
        val camera = state.cameraView
        val debug = state.bubbleDebug
        val outline = state.outlineRect
        val dim = state.dimUpcoming
        // The bubble just stepped to sheds its veil rather than snapping clear.
        val reveal = remember { Animatable(0f) }
        LaunchedEffect(active, marks) {
            reveal.snapTo(1f)
            reveal.animateTo(0f, tween(REVEAL_MS))
        }

        Canvas(Modifier.fillMaxSize()) {
            marks.forEachIndexed { i, b -> if (i != active && !b.panelStop) shape(b.outlineImage, b.bounds, Found) }
            marks.getOrNull(active)?.let { shape(it.outlineImage, it.bounds, Active) }
            // Editing panels: each in the order being set, the picked one red.
            val editing = state.editPanels
            if (state.report == BubbleProblem.PANELS) {
                editing.forEachIndexed { i, p ->
                    if (i != state.panelActive) panelShape(p, Stroke(width = 2.dp.toPx()))
                }
                editing.getOrNull(state.panelActive)?.let { p ->
                    val path = Path().apply {
                        moveTo(p[0] * size.width, p[1] * size.height)
                        for (k in 2 until p.size step 2) lineTo(p[k] * size.width, p[k + 1] * size.height)
                        close()
                    }
                    drawPath(path, Active, style = Stroke(width = 4.dp.toPx()))
                }
            } else if (debug || state.showOutlines) {
                val dashed = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()))
                for (p in state.debugPanels) panelShape(p, Stroke(width = 2.dp.toPx(), pathEffect = dashed))
                for (p in state.debugHandPanels) panelBox(p, Stroke(width = 3.dp.toPx()))
            }
            // Docked but not among the marks - one opened by hand, say: its box at least.
            if (outline != null && active < 0) {
                drawRect(
                    color = Active,
                    topLeft = Offset(outline[0] * size.width, outline[1] * size.height),
                    size = Size((outline[2] - outline[0]) * size.width, (outline[3] - outline[1]) * size.height),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
            // In the view below the page, the bubbles still to come are faded with paper white
            // until reached: there, but too faint to read ahead - not blacked out, as if censored.
            if (camera != null && active >= 0 && state.hideUpcoming && dim > 0f) {
                clipRect(top = camera[3] * size.height, bottom = camera[4] * size.height) {
                    for (i in active until marks.size) {
                        // A panel stop is artwork: never veiled.
                        if (marks[i].panelStop) continue
                        val veil = if (i == active) dim * reveal.value else dim
                        if (veil <= 0.01f) continue
                        val b = marks[i].bounds
                        val shown = floatArrayOf(
                            0.5f + (b[0] - camera[0]) * camera[2],
                            (camera[3] + camera[4]) * 0.5f + (b[1] - camera[1]) * camera[2],
                            0.5f + (b[2] - camera[0]) * camera[2],
                            (camera[3] + camera[4]) * 0.5f + (b[3] - camera[1]) * camera[2],
                        )
                        shape(marks[i].fillImage, shown, Color.White.copy(alpha = veil))
                    }
                }
            }
        }

        if (debug || state.showOutlines) {
            // Panels numbered at their top right, in green, out of the way of the bubbles' numbers.
            val numbered = if (state.report == BubbleProblem.PANELS) state.editPanels else state.debugPanelOrder
            numbered.forEachIndexed { i, p ->
                val density = LocalDensity.current
                val chip = with(density) { 30.dp.toPx() }
                // At the top right of its bounds, inside.
                val right = p.filterIndexed { k, _ -> k % 2 == 0 }.max()
                val top = p.filterIndexed { k, _ -> k % 2 == 1 }.min()
                BasicText(
                    text = "P${i + 1}",
                    style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .offset { IntOffset((right * width - chip).roundToInt(), (top * height).roundToInt()) }
                        .background(
                            if (state.report == BubbleProblem.PANELS && i == state.panelActive) Active else PanelColor,
                            RoundedCornerShape(3.dp),
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            state.debugRects.forEachIndexed { i, r ->
                BasicText(
                    text = "${i + 1}",
                    style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .offset { IntOffset((r[0] * width).roundToInt(), (r[1] * height).roundToInt()) }
                        .background(if (i == state.debugActive) Active else Found, RoundedCornerShape(3.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }

        // Debug: the view below the page framed by hand with two fingers; one swipes or taps on.
        if (debug && camera != null && !state.debugAdding) {
            val density = LocalDensity.current
            val top = camera[3] * height
            val bandHeight = (camera[4] - camera[3]) * height
            Box(
                Modifier
                    .offset { IntOffset(0, top.roundToInt()) }
                    .size(with(density) { width.toDp() }, with(density) { bandHeight.toDp() })
                    .pointerInput(top, width, height) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            // Two fingers frame the view; one swipes from bubble to bubble.
                            var framing = false
                            var travel = Offset.Zero
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } > 1) framing = true
                                val pan = event.calculatePan()
                                val zoom = event.calculateZoom()
                                travel += pan
                                if (framing) state.debugAdjustFrame(pan, zoom)
                                event.changes.fastForEach { if (it.positionChanged()) it.consume() }
                            } while (event.changes.fastAny { it.pressed })
                            val swiped = travel.getDistance() > 3 * slop
                            val onSwipe = state.onGuidedSwipe
                            if (framing) {
                                state.debugCommitFrame()
                            } else if (swiped && onSwipe != null) {
                                val step = if (abs(travel.y) > abs(travel.x)) {
                                    if (travel.y > 0f) 1 else -1
                                } else {
                                    // On in reading order, the way a swipe turns the page on.
                                    if (if (state.isReversed) travel.x > 0f else travel.x < 0f) 1 else -1
                                }
                                onSwipe(step)
                            } else if (!swiped) {
                                val tap = Offset(down.position.x / width, (down.position.y + top) / height)
                                val onTap = state.onGuidedTap
                                if (state.bubbleGuided && onTap != null) {
                                    onTap(tap)
                                } else {
                                    scope.launch { state.tapOnBubble(tap) }
                                }
                            }
                        }
                    },
            )
        }

        if (state.detecting || state.searching) {
            val band = remember(state.detecting, state.searching) { state.dockRegion() }
            Working(
                label = if (state.searching) "Looking for text…" else "Finding bubbles…",
                modifier = if (band != null) {
                    Modifier.offset {
                        IntOffset(0, (((band[0] + band[1]) * 0.5f) * height).roundToInt())
                    }.align(Alignment.TopCenter)
                } else {
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp)
                },
            )
        }

        if (debug) {
            DebugToolbar(state, Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp)) { action ->
                scope.launch {
                    when (action) {
                        DebugAction.EARLIER -> state.debugMove(-1)
                        DebugAction.LATER -> state.debugMove(1)
                        DebugAction.DELETE -> state.debugDelete()
                        DebugAction.ADD -> {
                            state.debugAdding = !state.debugAdding
                        }
                        DebugAction.EXPORT -> state.onDebugExport?.invoke()
                        DebugAction.PANEL -> {
                            state.drawingPanel = !state.drawingPanel
                        }
                        DebugAction.PANEL_PREV -> state.panelStep(-1)
                        DebugAction.PANEL_NEXT -> state.panelStep(1)
                        DebugAction.PANEL_EARLIER -> state.panelMove(-1)
                        DebugAction.PANEL_LATER -> state.panelMove(1)
                        DebugAction.PANEL_DELETE -> state.panelDelete()
                        DebugAction.BUBBLE_PREV -> state.stepBubble(-1)
                        DebugAction.BUBBLE_NEXT -> state.reportStep()
                        DebugAction.DONE -> state.finishReport()
                        DebugAction.CANCEL -> state.cancelReport()
                    }
                }
            }
        }

        if (debug && state.drawingPanel && !state.debugAdding) {
            DrawBox(
                width,
                height,
                initial = null,
                prompt = "Drag a box round the whole panel",
                confirm = "Add panel",
                onBox = { rect -> state.debugPanel(rect) },
                onCancel = { state.drawingPanel = false },
            )
        }

        if (debug && state.debugAdding) {
            DrawBox(
                width,
                height,
                initial = state.debugDraft,
                onBox = { rect ->
                    state.debugDraft = null
                    scope.launch { state.debugAdd(rect) }
                },
                onCancel = {
                    state.debugDraft = null
                    state.debugAdding = false
                },
            )
        }

        state.pressMenu?.let { press ->
            PressMenu(
                Modifier.align(Alignment.Center),
                onFind = {
                    state.pressMenu = null
                    scope.launch { state.findMissed(press) }
                },
                onReport = { state.startReport(it) },
                onCancel = { state.pressMenu = null },
            )
        }

        if (state.sendPrompt > 0) {
            SendPrompt(
                state.sendPrompt,
                Modifier.align(Alignment.Center),
                onSend = { state.answerSendPrompt(true) },
                onDrop = { state.answerSendPrompt(false) },
            )
        }

        state.missPrompt?.let {
            MissPrompt(
                Modifier.align(Alignment.Center),
                onMark = { state.markMissedByHand() },
                onCancel = { state.missPrompt = null },
            )
        }
    }
}

/** [image] - a bubble's shape, white on clear - drawn over [bounds] (normalised) in [color]. */
private fun DrawScope.shape(image: androidx.compose.ui.graphics.ImageBitmap, bounds: FloatArray, color: Color) {
    val x = (bounds[0] * size.width).roundToInt()
    val y = (bounds[1] * size.height).roundToInt()
    val w = ((bounds[2] - bounds[0]) * size.width).roundToInt()
    val h = ((bounds[3] - bounds[1]) * size.height).roundToInt()
    if (w <= 0 || h <= 0) return
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(x, y),
        dstSize = IntSize(w, h),
        colorFilter = ColorFilter.tint(color, BlendMode.SrcIn),
    )
}

/** A spinner and what is under way. */
@Composable
private fun Working(label: String, modifier: Modifier) {
    val turn by rememberInfiniteTransition(label = "working").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "turn",
    )
    Row(
        modifier = modifier
            .background(Chip, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(16.dp)) {
            drawArc(
                color = Color.White,
                startAngle = turn,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        Spacer(Modifier.width(8.dp))
        BasicText(label, style = TextStyle(color = Color.White, fontSize = 13.sp))
    }
}

@Composable
private fun MissPrompt(modifier: Modifier, onMark: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = modifier
            .padding(24.dp)
            .background(Chip, RoundedCornerShape(10.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            "No text found there. Mark it by hand?",
            style = TextStyle(color = Color.White, fontSize = 15.sp),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolChip("Mark it", enabled = true, onClick = onMark)
            ToolChip("Cancel", enabled = true, onClick = onCancel)
        }
    }
}

// KMK -->
/**
 * A long press while stepping through bubbles: look for missed text there, as before, or
 * correct the page's bubbles or panels - see [ImageViewerState.startReport].
 */
@Composable
private fun PressMenu(
    modifier: Modifier,
    onFind: () -> Unit,
    onReport: (BubbleProblem) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = modifier
            .padding(24.dp)
            .background(Chip, RoundedCornerShape(10.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToolChip("Find missed text here", enabled = true, onClick = onFind)
        BasicText(
            "Correct this page",
            style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold),
        )
        BubbleProblem.entries.forEach { problem -> ToolChip(problem.label, enabled = true) { onReport(problem) } }
        ToolChip("Cancel", enabled = true, onClick = onCancel)
    }
}
// KMK <--

// KMK -->
/** The page's bubbles have run out with fixes made on it: send them as one report, or not. */
@Composable
private fun SendPrompt(count: Int, modifier: Modifier, onSend: () -> Unit, onDrop: () -> Unit) {
    Column(
        modifier = modifier
            .padding(24.dp)
            .background(Chip, RoundedCornerShape(10.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            "End of the page. Send a report of the ${if (count == 1) "fix" else "$count fixes"} made on it?",
            style = TextStyle(color = Color.White, fontSize = 15.sp),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            "Your corrections stay either way. Tap again after to turn the page.",
            style = TextStyle(color = Color(0xFFBDBDBD), fontSize = 12.sp),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolChip("Send report", enabled = true, onClick = onSend)
            ToolChip("Don't send", enabled = true, onClick = onDrop)
        }
    }
}
// KMK <--

private enum class DebugAction {
    EARLIER,
    LATER,
    DELETE,
    ADD,
    EXPORT,
    PANEL,
    DONE,
    CANCEL,
    PANEL_PREV,
    PANEL_NEXT,
    PANEL_EARLIER,
    PANEL_LATER,
    PANEL_DELETE,
    BUBBLE_PREV,
    BUBBLE_NEXT,
}

@Composable
private fun DebugToolbar(state: ImageViewerState, modifier: Modifier, onAction: (DebugAction) -> Unit) {
    val open = state.debugActive >= 0
    val report = state.report
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // KMK -->
        if (report != null) {
            BasicText(
                text = "${report.label}: ${report.hint}",
                style = TextStyle(color = Color.White, fontSize = 13.sp),
                modifier = Modifier
                    .background(Chip, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        if (report == BubbleProblem.PANELS) {
            val count = state.editPanels.size
            val picked = state.panelActive in state.editPanels.indices
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolChip(if (picked) "P${state.panelActive + 1}/$count" else "$count panels", enabled = false) {}
                ToolChip("◀", enabled = picked) { onAction(DebugAction.PANEL_PREV) }
                ToolChip("▶", enabled = picked) { onAction(DebugAction.PANEL_NEXT) }
                ToolChip("Earlier", enabled = picked) { onAction(DebugAction.PANEL_EARLIER) }
                ToolChip("Later", enabled = picked) { onAction(DebugAction.PANEL_LATER) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                ToolChip("Delete", enabled = picked) { onAction(DebugAction.PANEL_DELETE) }
                ToolChip(if (state.drawingPanel) "Cancel add" else "Add", enabled = true) {
                    onAction(DebugAction.PANEL)
                }
                ToolChip("Save", enabled = true) { onAction(DebugAction.DONE) }
                ToolChip("Cancel", enabled = true) { onAction(DebugAction.CANCEL) }
            }
            return@Column
        }
        // KMK <--
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val count = state.debugRects.size
            val place = if (open) "${state.debugActive + 1}/$count" else "$count found"
            ToolChip(place, enabled = false) {}
            if (report != null) {
                ToolChip("◀", enabled = open) { onAction(DebugAction.BUBBLE_PREV) }
                ToolChip("▶", enabled = true) { onAction(DebugAction.BUBBLE_NEXT) }
            }
            ToolChip("Earlier", enabled = open) { onAction(DebugAction.EARLIER) }
            ToolChip("Later", enabled = open) { onAction(DebugAction.LATER) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            ToolChip("Delete", enabled = open) { onAction(DebugAction.DELETE) }
            ToolChip(if (state.debugAdding) "Cancel add" else "Add", enabled = true) { onAction(DebugAction.ADD) }
            if (report == null) {
                ToolChip("Export (${state.debugLogged})", enabled = true) { onAction(DebugAction.EXPORT) }
            } else {
                ToolChip("Save", enabled = true) { onAction(DebugAction.DONE) }
                ToolChip("Cancel", enabled = true) { onAction(DebugAction.CANCEL) }
            }
        }
    }
}

@Composable
private fun ToolChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    BasicText(
        text = label,
        style = TextStyle(
            color = if (enabled) Color.White else Color(0xFF9E9E9E),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        ),
        modifier = Modifier
            .background(Chip, RoundedCornerShape(6.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

/**
 * A box drawn by a drag, then adjusted: a corner dragged resizes it, its middle moves it, a
 * drag elsewhere starts it again. Handed over normalised once confirmed.
 */
@Composable
private fun DrawBox(
    width: Float,
    height: Float,
    initial: FloatArray?,
    onBox: (FloatArray) -> Unit,
    onCancel: () -> Unit,
    prompt: String = "Drag a box around the missed text",
    confirm: String = "Add",
) {
    // In pixels: left, top, right, bottom.
    var box by remember(initial) {
        mutableStateOf(initial?.let { floatArrayOf(it[0] * width, it[1] * height, it[2] * width, it[3] * height) })
    }
    val density = LocalDensity.current
    val grab = with(density) { 28.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x22000000))
            .pointerInput(width, height) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val p = down.position
                    val b = box
                    // Which part is held: a corner (0-3), the middle (4), or nothing - a new box.
                    val corners = b?.let {
                        listOf(Offset(it[0], it[1]), Offset(it[2], it[1]), Offset(it[0], it[3]), Offset(it[2], it[3]))
                    }
                    val corner = corners?.indexOfFirst { (it - p).getDistance() <= grab } ?: -1
                    val inside = b != null && p.x in b[0]..b[2] && p.y in b[1]..b[3]
                    val start = b?.copyOf()
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val q = change.position
                        val d = q - p
                        box = when {
                            start != null && corner >= 0 -> start.copyOf().also {
                                if (corner == 0 || corner == 2) it[0] = q.x else it[2] = q.x
                                if (corner == 0 || corner == 1) it[1] = q.y else it[3] = q.y
                            }
                            start != null && inside ->
                                floatArrayOf(start[0] + d.x, start[1] + d.y, start[2] + d.x, start[3] + d.y)
                            else -> floatArrayOf(p.x, p.y, q.x, q.y)
                        }
                        change.consume()
                    } while (event.changes.fastAny { it.pressed })
                    box = box?.let {
                        floatArrayOf(min(it[0], it[2]), min(it[1], it[3]), max(it[0], it[2]), max(it[1], it[3]))
                    }
                }
            },
    ) {
        BasicText(
            text = if (box == null) prompt else "Drag the corners to fit, then $confirm",
            style = TextStyle(color = Color.White, fontSize = 14.sp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
                .background(Chip, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Canvas(Modifier.fillMaxSize()) {
            val b = box ?: return@Canvas
            drawRect(
                color = Draft,
                topLeft = Offset(min(b[0], b[2]), min(b[1], b[3])),
                size = Size(abs(b[2] - b[0]), abs(b[3] - b[1])),
                style = Stroke(width = 3.dp.toPx()),
            )
            for (c in listOf(Offset(b[0], b[1]), Offset(b[2], b[1]), Offset(b[0], b[3]), Offset(b[2], b[3]))) {
                drawCircle(Draft, radius = 9.dp.toPx(), center = c)
            }
        }
        Row(
            Modifier.align(Alignment.Center).padding(top = 160.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val b = box
            val big = b != null && abs(b[2] - b[0]) > 0.02f * width && abs(b[3] - b[1]) > 0.01f * height
            ToolChip(confirm, enabled = big) {
                val r = box ?: return@ToolChip
                onBox(floatArrayOf(r[0] / width, r[1] / height, r[2] / width, r[3] / height))
            }
            ToolChip("Cancel", enabled = true, onClick = onCancel)
        }
    }
}
