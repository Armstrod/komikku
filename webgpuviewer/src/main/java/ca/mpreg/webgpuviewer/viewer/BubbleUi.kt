package ca.mpreg.webgpuviewer.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val Outline = Color(0xFFE53935)
private val Active = Color(0xFFFFC400)
private val Chip = Color(0xE0202020)

/**
 * Drawn over the page: the docked bubble's place outlined in red, and in debug mode every
 * bubble found outlined and numbered, a toolbar to correct them, and a box to draw a missed one.
 */
@Composable
internal fun BubbleUi(state: ImageViewerState) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val outline = state.outlineRect
        val rects = state.debugRects
        val active = state.debugActive
        val debug = state.bubbleDebug

        Canvas(Modifier.fillMaxSize()) {
            fun box(r: FloatArray, color: Color, stroke: Float) = drawRect(
                color = color,
                topLeft = Offset(r[0] * size.width, r[1] * size.height),
                size = Size((r[2] - r[0]) * size.width, (r[3] - r[1]) * size.height),
                style = Stroke(width = stroke),
            )
            if (debug) {
                rects.forEachIndexed { i, r -> if (i != active) box(r, Outline, 3.dp.toPx()) }
                rects.getOrNull(active)?.let { box(it, Active, 4.dp.toPx()) }
            }
            if (outline != null && !(debug && active >= 0)) box(outline, Outline, 3.dp.toPx())
        }

        if (debug) {
            rects.forEachIndexed { i, r ->
                BasicText(
                    text = "${i + 1}",
                    style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .offset { IntOffset((r[0] * width).roundToInt(), (r[1] * height).roundToInt()) }
                        .background(if (i == active) Active else Outline, RoundedCornerShape(3.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
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
                    }
                }
            }
        }

        if (debug && state.debugAdding) {
            DrawBox(width, height) { rect -> scope.launch { state.debugAdd(rect) } }
        }
    }
}

private enum class DebugAction { EARLIER, LATER, DELETE, ADD, EXPORT }

@Composable
private fun DebugToolbar(state: ImageViewerState, modifier: Modifier, onAction: (DebugAction) -> Unit) {
    val open = state.debugActive >= 0
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val count = state.debugRects.size
        val place = if (open) "${state.debugActive + 1}/$count" else "$count found"
        ToolChip(place, enabled = false) {}
        ToolChip("◀ Earlier", enabled = open) { onAction(DebugAction.EARLIER) }
        ToolChip("Later ▶", enabled = open) { onAction(DebugAction.LATER) }
        ToolChip("Delete", enabled = open) { onAction(DebugAction.DELETE) }
        ToolChip(if (state.debugAdding) "Cancel" else "Add", enabled = true) { onAction(DebugAction.ADD) }
        ToolChip("Export (${state.debugLogged})", enabled = true) { onAction(DebugAction.EXPORT) }
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

/** Takes the next drag as a box, drawn as it goes, and hands it over normalised. */
@Composable
private fun DrawBox(width: Float, height: Float, onBox: (FloatArray) -> Unit) {
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x22000000))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        start = it
                        end = it
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        end = change.position
                    },
                    onDragEnd = {
                        val a = start
                        val b = end
                        start = null
                        end = null
                        if (a != null && b != null && width > 0f && height > 0f) {
                            val rect = floatArrayOf(
                                min(a.x, b.x) / width,
                                min(a.y, b.y) / height,
                                max(a.x, b.x) / width,
                                max(a.y, b.y) / height,
                            )
                            if (rect[2] - rect[0] > 0.02f && rect[3] - rect[1] > 0.01f) onBox(rect)
                        }
                    },
                )
            },
    ) {
        BasicText(
            text = "Drag a box around the missed text",
            style = TextStyle(color = Color.White, fontSize = 14.sp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
                .background(Chip, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Canvas(Modifier.fillMaxSize()) {
            val a = start
            val b = end
            if (a != null && b != null) {
                drawRect(
                    color = Active,
                    topLeft = Offset(min(a.x, b.x), min(a.y, b.y)),
                    size = Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
    }
}
