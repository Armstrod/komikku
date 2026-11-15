package ca.mpreg.webgpuviewer.viewer

import android.content.res.Resources
import android.util.Log
import android.view.Surface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.webgpu.GPUColor
import androidx.webgpu.GPUCommandEncoder
import androidx.webgpu.GPURenderPassColorAttachment
import androidx.webgpu.GPURenderPassDepthStencilAttachment
import androidx.webgpu.GPURenderPassDescriptor
import androidx.webgpu.GPURenderPassEncoder
import androidx.webgpu.GPUTexture
import androidx.webgpu.LoadOp
import androidx.webgpu.StoreOp
import ca.mpreg.webgpuviewer.draw.Draw
import ca.mpreg.webgpuviewer.draw.clear
import ca.mpreg.webgpuviewer.filter.FilterChain
import ca.mpreg.webgpuviewer.renderer.Downscaler
import ca.mpreg.webgpuviewer.renderer.DownscalerBox
import ca.mpreg.webgpuviewer.renderer.FrameResult
import ca.mpreg.webgpuviewer.renderer.Hdr
import ca.mpreg.webgpuviewer.renderer.Rescaler
import ca.mpreg.webgpuviewer.renderer.TileRenderer
import ca.mpreg.webgpuviewer.renderer.Upscaler
import ca.mpreg.webgpuviewer.renderer.UpscalerArtCnn
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.Companion.dispatcher
import ca.mpreg.webgpuviewer.renderer.endAndRelease
import ca.mpreg.webgpuviewer.transition.Transition
import ca.mpreg.webgpuviewer.transition.TransitionBasic
import ca.mpreg.webgpuviewer.transition.TurnGesture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

open class ImageViewerState(var isVertical: Boolean = false, var isReversed: Boolean = false) {
    val renderer = WebGpuRenderer()

    internal val tiles = TileRenderer { invalidate() }

    /**
     * How a high-quality tile that magnifies the page is resized - see [Rescaler]. Assigning
     * drops the tiles already generated. [UpscalerArtCnn] doubles through a network first.
     */
    var upscaler: Upscaler
        get() = tiles.upscaler
        set(value) {
            tiles.upscaler = value
        }

    /** How a high-quality tile that shrinks the page is resized. [DownscalerBox] is the only one. */
    var downscaler: Downscaler
        get() = tiles.downscaler
        set(value) {
            tiles.downscaler = value
        }

    /**
     * Post-processing over the finished frame - assign [FilterChain.filters] to run some. Wired
     * to [invalidate] here, so changing a filter's settings redraws by itself.
     */
    val filters: FilterChain = renderer.filters.also { chain ->
        chain.onInvalidate = { invalidate() }
    }

    var scope: CoroutineScope? = null

    var animationJob: Job? = null

    val width get() = renderer.width
    val height get() = renderer.height

    /** Top padding clearing the display cutout, in pixels. Set by ImageViewer while [avoidCutout]. */
    var cutoutTopPx: Float = 0f

    val viewportHeight: Float
        get() = height - if (avoidCutout) cutoutTopPx else 0f

    var dpi = Resources.getSystem().displayMetrics.densityDpi / 100f
    var density: Density =
        Density(density = Resources.getSystem().displayMetrics.density, fontScale = 1f)

    /**
     * Whether a double tap zooms. Off leaves the gesture inert - the page neither zooms in nor
     * returns home - for a reader that would rather double tap did nothing at all.
     */
    var doubleTapZoomEnabled: Boolean = true

    /**
     * How long a double tap's zoom takes, in milliseconds: 500 and over springs as it always has,
     * 1 and under is instant, anything between eases over that long.
     */
    var doubleTapZoomMillis: Int = 500

    internal fun doubleTapZoomSpec(): AnimationSpec<Float> = when {
        doubleTapZoomMillis >= 500 -> spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.002f)
        doubleTapZoomMillis <= 1 -> snap()
        else -> tween(doubleTapZoomMillis, easing = FastOutSlowInEasing)
    }

    /** Whether two fingers scale the page. Off leaves the page at whatever scale it is on. */
    var pinchZoomEnabled: Boolean = true

    /** When true, images will be positioned/scaled to avoid the display cutout. */
    var avoidCutout: Boolean by mutableStateOf(false)

    /** Shift below the cutout always, rather than only when the image would overlap it. */
    var alwaysAvoidCutout: Boolean by mutableStateOf(false)

    private var suppressPageChange = false

    var pageOffset = 0f
        set(value) {
            if (value.isNaN() || value.isInfinite()) return
            var v = value
            var pageDelta = 0

            if (!suppressPageChange) {
                // Guards a haveNext/havePrev stuck true against a pathological page provider.
                var guard = 0
                while (v >= 1f && haveNext && guard++ < 1000) {
                    pageDelta += 1
                    v -= 1f
                }
                guard = 0
                while (v <= -1f && havePrev && guard++ < 1000) {
                    pageDelta -= 1
                    v += 1f
                }
            }

            // Numeric test first: haveNext/havePrev reach fetchPage, which resolves a page and
            // may trim a cache to do it, and every animation frame sets this with |v| under 1.
            if (v > 1f && !haveNext) v = 1f
            if (v < -1f && !havePrev) v = -1f

            val settling = field != 0f && v == 0f

            field = v

            if (pageDelta != 0) {
                dropBubble()
                autoOpenPending = true
                // Dragged all the way over: the turn that a release or a tap reports happened
                // under the finger instead.
                if (turnHeld) onPageTurn?.runCatching { invoke() }
                onPageChange?.runCatching { invoke(if (isReversed) -pageDelta else pageDelta) }
            }

            // Rotate rather than invalidate: onPageChange already moved what backs getPage, so a
            // slot often holds a valid render of the new current page.
            if (settling) {
                val current = getPage(0)
                if (current != null) Transition.rotateCacheOnPageChange(current) else Transition.invalidateCache()
                if (autoOpenPending) {
                    autoOpenPending = false
                    val wanted = bubbleAutoOpen || bubbleDebug || bubbleDock || bubbleGuided
                    if (bubbleZoomEnabled && wanted) scheduleAutoOpen()
                }
            }
        }

    private fun setPageOffsetDirect(value: Float) {
        suppressPageChange = true
        pageOffset = value
        suppressPageChange = false
    }

    private var turn = 0

    fun animatePageTurn(direction: Int) {
        animationJob?.cancel()
        // cancel() doesn't wait: a turn started while the last one unwinds would otherwise have
        // its own transitionFromPage - set just before this call - cleared by that finally.
        val id = ++turn
        dropBubble()
        autoOpenPending = true
        turnHeld = false
        turnAuto = true
        onPageTurn?.runCatching { invoke() }
        animationJob = scope?.launch {
            setPageOffsetDirect(direction.toFloat())
            invalidate()
            try {
                Animatable(direction.toFloat()).animateTo(
                    0f,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        visibilityThreshold = 0.002f,
                    ),
                ) {
                    setPageOffsetDirect(value)
                    invalidate()
                }
            } finally {
                // Cancelled or not: getPage provides the right page from here.
                if (turn == id) transitionFromPage = null
            }
            // Outside the try - a cancelled turn leaves the offset to its successor.
            setPageOffsetDirect(0f)
            invalidate()
        }
    }

    val havePrev get() = getPage(if (isReversed) 1 else -1) != null
    val haveNext get() = getPage(if (isReversed) -1 else 1) != null

    var fetchPage: ((Int) -> ImagePage?)? = null

    /** Once per outage, on the render dispatcher - see [FrameResult.Unavailable]. */
    var onRenderUnavailable: ((String?) -> Unit)? = null

    var onPageChange: ((Int) -> Unit)? = null

    /**
     * A page is turning over: let go past the middle, sent by a tap or key, or dragged all the
     * way. Once per turn, as it starts - for a sound to go with the motion. A drag let go short
     * of the middle settles back and isn't one.
     */
    var onPageTurn: (() -> Unit)? = null
    var onTap: ((Offset) -> Unit)? = null
    var onLongTap: ((Offset) -> Unit)? = null

    /** Override for the "from" page during far navigation animation */
    var transitionFromPage: ImagePage? = null

    /** Whether a double tap on a speech bubble opens it enlarged - see [showBubble]. */
    var bubbleZoomEnabled: Boolean = true

    /**
     * Whether the double tap itself looks for a bubble before zooming. Off, the reader opens
     * bubbles some other way - by calling [showBubble] from its own gesture.
     */
    var bubbleZoomOnDoubleTap: Boolean = true

    /**
     * Whether a double tap on artwork zooms to the panel under it - see [zoomToPanel] - rather
     * than zooming in by a fixed step.
     */
    var panelZoomEnabled: Boolean = false

    /**
     * Bubbles open in the room below the page rather than over it, the page resting at the top
     * of the screen to make that room - see [dockRegion]. Where a page leaves too little room,
     * they open over it as before.
     */
    var bubbleDock: Boolean = false

    /**
     * Turning the page forward steps through its bubbles first - see [guidedNext] - and an open
     * bubble's taps and swipes go to [onGuidedTap] and [onGuidedSwipe] instead of closing it.
     */
    var bubbleGuided: Boolean = false

    /** Docked below the page, a bubble shows with the artwork round it rather than cut out. */
    var bubbleContext: Boolean = true

    /** With [bubbleGuided], a page turned to opens its first bubble by itself. */
    var bubbleAutoOpen: Boolean = false

    /** A tap while a bubble is open, with [bubbleGuided] - normalised screen coordinates. */
    var onGuidedTap: ((Offset) -> Unit)? = null

    /** A swipe while a bubble is open, with [bubbleGuided]: 1 on in reading order, -1 back. */
    var onGuidedSwipe: ((Int) -> Unit)? = null

    /** A page turn has begun; the page it lands on may open its first bubble once at rest. */
    private var autoOpenPending = false
    private var autoOpenJob: Job? = null

    /** The page whose bubbles have all been read - the next step forward turns it. */
    private var guidedDonePage: ImagePage? = null

    /** A guided step in flight: steps arriving meanwhile are swallowed rather than queued. */
    private val guidedBusy = AtomicBoolean(false)

    /**
     * The band below [page], top and bottom normalised, where [bubbleDock] opens bubbles - null
     * when docking is off or the page leaves too little room below it.
     */
    fun dockRegion(page: ImagePage? = getPage(0)): FloatArray? {
        if (!bubbleDock) return null
        val single = page as? ImagePage.ImageSingle ?: return null
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        val bottom = BubbleZoom.pageBottom(single, w, h) ?: return null
        val top = bottom + DOCK_GAP
        val end = 1f - DOCK_BOTTOM_MARGIN
        if (end - top < DOCK_MIN_HEIGHT) return null
        return floatArrayOf(top, end)
    }

    /** The speech bubble open over the page, if any - see [BubbleZoom]. */
    @Volatile
    internal var bubble: BubbleOverlay? = null
        private set(value) {
            field = value
            // Docked below the page, where it came from is outlined on the page - see BubbleUi.
            outlineRect = value?.takeIf { it.dim == 0f }?.bounds
            if (value == null || !cameraMode(value)) {
                cameraJob?.cancel()
                setCamera(null)
            }
            publishDebug()
        }

    /** The open bubble's bounds on the page, normalised, while it is docked below it. */
    var outlineRect: FloatArray? by mutableStateOf(null)
        private set

    /**
     * Debug mode: the page's bubbles outlined and numbered, the open one editable - moved,
     * deleted, or one added by drawing a box - and every page and correction logged to
     * [BubbleDebugLog] for export.
     */
    var bubbleDebug: Boolean by mutableStateOf(false)

    /** Names the page on screen for the debug log - its book, chapter and number. */
    var pageLabel: (() -> String?)? = null

    /** The debug toolbar's export: the app writes [BubbleDebugLog] out and shares it. */
    var onDebugExport: (() -> Unit)? = null

    /** Debug: the page's bubbles in order, their bounds on screen normalised; the open one's place. */
    var debugRects: List<FloatArray> by mutableStateOf(emptyList())
        private set
    var debugActive: Int by mutableStateOf(-1)
        private set

    /** Debug: the next drag on the page draws a missed bubble - see [debugAdd]. */
    var debugAdding: Boolean by mutableStateOf(false)

    /** Debug: pages logged, shown on the export button. */
    var debugLogged: Int by mutableStateOf(0)
        private set

    /** Debug: the page whose corrections are being logged, its label, and how they stand. */
    private var debugPage: ImagePage? = null
    private var debugLabel: String? = null

    /**
     * The page's bubbles as found, outlined on it - blue, the open one red - where they are
     * shown below the page or in debug mode; see BubbleUi.
     */
    internal var marks: List<BubbleOverlay> by mutableStateOf(emptyList())
        private set
    var markActive: Int by mutableStateOf(-1)
        private set

    private fun publishDebug() {
        val seq = sequence?.takeIf { sequencePage === getPage(0) }
        val open = bubble
        val active = if (seq == null || open == null) {
            -1
        } else {
            seq.indexOfFirst { it === open || it.rect.contentEquals(open.rect) }
        }
        val shown = if (seq != null && (bubbleDebug || bubbleDock)) seq else emptyList()
        if (marks != shown) marks = shown
        markActive = if (shown.isEmpty()) -1 else active
        if (!bubbleDebug || seq == null) {
            if (debugRects.isNotEmpty()) debugRects = emptyList()
            debugActive = -1
            return
        }
        debugRects = seq.map { it.bounds }
        debugActive = active
    }

    /** Finding the page's bubbles is under way - shown in the room below the page. */
    var detecting: Boolean by mutableStateOf(false)
        private set

    /** Looking for lettering missed at a long press - see [findMissed]. */
    var searching: Boolean by mutableStateOf(false)
        private set

    /** A long press found nothing: where, while the reader is asked whether to mark it by hand. */
    var missPrompt: Offset? by mutableStateOf(null)

    /** Debug mode turned on for one page from [missPrompt], off again when it turns. */
    private var debugTemporary = false

    /** Debug: a box to start [debugAdding] from, normalised - round a long press, say. */
    var debugDraft: FloatArray? by mutableStateOf(null)

    // The view of the page below it - see BubbleZoom.drawCamera.

    /**
     * The view of the page shown below it, docked with [bubbleContext]: (centre x, centre y,
     * zoom, band top, band bottom), normalised at rest. Moves from bubble to bubble.
     */
    @Volatile
    internal var camera: FloatArray? = null
        private set

    /** [camera], for drawing over it - see BubbleUi. */
    var cameraView: FloatArray? by mutableStateOf(null)
        private set

    private var cameraJob: Job? = null

    /** Debug: how the reader framed a bubble by hand, by page and bounds - centre and zoom. */
    private val framings = HashMap<String, FloatArray>()

    private fun frameKey(b: BubbleOverlay) = "${System.identityHashCode(b.page)}:${b.bounds.joinToString(",")}"

    /** Whether [b] is shown as a moving view of the page below it rather than lifted off it. */
    private fun cameraMode(b: BubbleOverlay) = b.docked && bubbleContext

    private fun setCamera(c: FloatArray?) {
        camera = c
        cameraView = c
        invalidate()
    }

    /** What of the page at rest [c] shows: (x0, y0, x1, y1), normalised. */
    private fun viewOf(c: FloatArray): FloatArray {
        val halfX = 0.5f / c[2]
        val halfY = 0.5f * (c[4] - c[3]) / c[2]
        return floatArrayOf(c[0] - halfX, c[1] - halfY, c[0] + halfX, c[1] + halfY)
    }

    /**
     * Where the view goes to show [b]: zoomed so it fills most of the band - room left round it
     * for the artwork - and kept on the page. One already in view, at near that zoom, keeps
     * the view where it is; a framing set by hand in debug mode wins.
     */
    private fun frameFor(b: BubbleOverlay, from: FloatArray?): FloatArray? {
        val dock = dockRegion(b.page) ?: return null
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        framings[frameKey(b)]?.let { return floatArrayOf(it[0], it[1], it[2], dock[0], dock[1]) }
        val bw = (b.bounds[2] - b.bounds[0]) * w
        val bh = (b.bounds[3] - b.bounds[1]) * h
        if (bw <= 0f || bh <= 0f) return null
        val dockHeight = (dock[1] - dock[0]) * h
        val zoom = min(CAMERA_MAX_ZOOM, min(CAMERA_FIT_WIDTH * w / bw, CAMERA_FIT_HEIGHT * dockHeight / bh))
            .coerceAtLeast(1f)
        if (from != null && from[2] >= zoom * CAMERA_KEEP) {
            val v = viewOf(from)
            if (b.bounds[0] >= v[0] && b.bounds[1] >= v[1] && b.bounds[2] <= v[2] && b.bounds[3] <= v[3]) return from
        }
        val pageRect = BubbleZoom.pageRect(b.page, w, h) ?: floatArrayOf(0f, 0f, 1f, 1f)
        // Kept within [lo, hi] where it fits, else centred on them.
        fun keep(center: Float, half: Float, lo: Float, hi: Float) =
            if (hi - lo <= 2 * half) (lo + hi) * 0.5f else center.coerceIn(lo + half, hi - half)
        // Aimed at its panel's middle, as far as the bubble stays in view - as much of the
        // panel as the zoom allows, the way frames set by hand in debug mode came out.
        fun aim(target: Float, lo: Float, hi: Float, half: Float) =
            if (hi - lo >= 2 * half) (lo + hi) * 0.5f else target.coerceIn(hi - half, lo + half)
        val halfX = 0.5f / zoom
        val halfY = 0.5f * (dock[1] - dock[0]) / zoom
        val panel = b.panel
        val targetX = panel?.let { (it[0] + it[2]) * 0.5f } ?: ((b.bounds[0] + b.bounds[2]) * 0.5f)
        val targetY = panel?.let { (it[1] + it[3]) * 0.5f } ?: ((b.bounds[1] + b.bounds[3]) * 0.5f)
        return floatArrayOf(
            keep(aim(targetX, b.bounds[0], b.bounds[2], halfX), halfX, pageRect[0], pageRect[2]),
            keep(aim(targetY, b.bounds[1], b.bounds[3], halfY), halfY, pageRect[1], pageRect[3]),
            zoom,
            dock[0],
            dock[1],
        )
    }

    /** Moves the view to [to] - glides there from where it is, or starts there. */
    private fun moveCamera(to: FloatArray) {
        val from = camera
        cameraJob?.cancel()
        if (from == null || doubleTapZoomMillis <= 1) {
            setCamera(to)
            return
        }
        cameraJob = scope?.launch {
            Animatable(0f).animateTo(1f, tween(CAMERA_MOVE_MS, easing = FastOutSlowInEasing)) {
                val t = value
                setCamera(
                    floatArrayOf(
                        from[0] + (to[0] - from[0]) * t,
                        from[1] + (to[1] - from[1]) * t,
                        from[2] * (to[2] / from[2]).pow(t),
                        to[3],
                        to[4],
                    ),
                )
            }
        }
    }

    /** Debug: the view below the page moved by a drag of [pan] pixels and a pinch of [zoomBy]. */
    fun debugAdjustFrame(pan: Offset, zoomBy: Float) {
        val c = camera ?: return
        if (bubble == null || width <= 0 || height <= 0) return
        cameraJob?.cancel()
        val zoom = (c[2] * zoomBy).coerceIn(CAMERA_MIN_ZOOM, CAMERA_LIMIT_ZOOM)
        setCamera(floatArrayOf(c[0] - pan.x / (zoom * width), c[1] - pan.y / (zoom * height), zoom, c[3], c[4]))
    }

    /** Debug: the view as adjusted is how the open bubble should be framed - kept and logged. */
    fun debugCommitFrame() {
        val c = camera ?: return
        val b = bubble ?: return
        framings[frameKey(b)] = c.copyOf(3)
        val page = b.page
        val seq = sequence.orEmpty()
        val at = seq.indexOfFirst { it.rect.contentEquals(b.rect) }
        logEdit(page, "frame ${at + 1}")
    }

    /**
     * A long press where lettering was missed: looked for harder there - see
     * [BubbleZoom.findMissed] - put in the reading order where it belongs, logged, and opened.
     * Where nothing is found, [missPrompt] asks whether to mark it by hand.
     */
    suspend fun findMissed(tap: Offset): Boolean = withFrameClock {
        if (!guidedBusy.compareAndSet(false, true)) return@withFrameClock false
        try {
            if (pageOffset != 0f) return@withFrameClock false
            val page = getPage(0) as? ImagePage.ImageSingle ?: return@withFrameClock false
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return@withFrameClock false
            val dock = dockRegion(page)
            val context = bubbleContext
            var stage = ""
            searching = true
            val found = try {
                withContext(Dispatchers.Default) {
                    runCatching { BubbleZoom.findMissed(page, w, h, tap.x, tap.y, dock, context) { stage = it } }
                        .onFailure { Log.w("ImageViewerState", "Missed bubble search failed", it) }
                        .getOrNull()
                }
            } finally {
                searching = false
            }
            if (getPage(0) !== page || pageOffset != 0f) return@withFrameClock false
            if (found == null) {
                missPrompt = tap
                return@withFrameClock false
            }
            val current = bubble?.takeIf { it.page === page }
            val seq = (if (current != null) sequenceFor(page, current) else sequenceOf(page)).toMutableList()
            startDebugLog(page, seq)
            val existing = seq.firstOrNull { overlap(it.bounds, found.bounds) > 0.6f }
            val target = existing ?: found.also {
                val at = BubbleZoom.orderIndex(page, w, h, seq.map { s -> s.bounds }, found.bounds, isReversed)
                seq.add(at, found)
                sequence = seq
                logEdit(page, "long press: found $stage at ${at + 1}")
            }
            if (current != null) switchBubble(current, target.fresh()) else open(target.fresh())
            true
        } finally {
            guidedBusy.set(false)
        }
    }

    /** How much of the smaller of [a] and [b] (bounds) they share. */
    private fun overlap(a: FloatArray, b: FloatArray): Float {
        val ix = max(0f, min(a[2], b[2]) - max(a[0], b[0]))
        val iy = max(0f, min(a[3], b[3]) - max(a[1], b[1]))
        val small = min((a[2] - a[0]) * (a[3] - a[1]), (b[2] - b[0]) * (b[3] - b[1]))
        return if (small <= 0f) 0f else ix * iy / small
    }

    /** [missPrompt] answered yes: debug mode for this page, a box round the press to adjust. */
    fun markMissedByHand() {
        val tap = missPrompt ?: return
        missPrompt = null
        if (!bubbleDebug) {
            bubbleDebug = true
            debugTemporary = true
        }
        debugDraft = floatArrayOf(
            (tap.x - 0.12f).coerceAtLeast(0f),
            (tap.y - 0.06f).coerceAtLeast(0f),
            (tap.x + 0.12f).coerceAtMost(1f),
            (tap.y + 0.06f).coerceAtMost(1f),
        )
        debugAdding = true
        refreshDebug()
    }

    private var bubbleJob: Job? = null

    /** True while a bubble is open, or closing: the next touch only closes it. */
    val bubbleShown: Boolean get() = bubble != null

    /**
     * Opens the speech bubble under [tap] (normalised screen coordinates) enlarged over the page,
     * as Google Play Books does. False where there is none - the page isn't at rest, or nothing
     * there looks like a bubble - so the caller can zoom as usual instead.
     */
    suspend fun showBubble(tap: Offset): Boolean {
        if (!bubbleZoomEnabled || pageOffset != 0f || bubble != null) return false
        val page = getPage(0) as? ImagePage.ImageSingle ?: return false
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return false
        val found = withContext(Dispatchers.Default) {
            runCatching { BubbleZoom.detect(page, w, h, tap.x, tap.y, dockRegion(page), bubbleContext) }
                .onFailure { Log.w("ImageViewerState", "Bubble detection failed", it) }
                .getOrNull()
        } ?: return false
        // The page may have turned while it looked.
        if (getPage(0) !== page || pageOffset != 0f || bubble != null) return false
        open(found)
        return true
    }

    /** Opens [found], closed until now, over its page. */
    private suspend fun open(found: BubbleOverlay) {
        if (cameraMode(found)) {
            val frame = frameFor(found, null)
            if (frame != null) {
                bubble = found
                found.progress = 1f
                moveCamera(frame)
                return
            }
        }
        val w = width
        val h = height
        // Shown at progress 0 - nothing drawn - while it is enlarged, then animated: enlarging
        // inside the animation would stall its first frames and make it jump.
        bubble = found
        runCatching {
            WebGpuRenderer.withContext { BubbleZoom.prepare(found, w, h) }
            BubbleZoom.awaitPrepared()
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w("ImageViewerState", "Bubble preparation failed", it)
        }
        if (bubble === found) animateBubble(found, 1f)
    }

    /**
     * One step forward through the page's bubbles: the first opens, then each next in reading
     * order. True when it stepped; false once there are none left - the open one closes - so
     * the caller turns the page instead.
     */
    suspend fun guidedNext(): Boolean = withFrameClock { guidedNextLocked() }

    private suspend fun guidedNextLocked(): Boolean {
        if (!guidedBusy.compareAndSet(false, true)) return true
        try {
            val current = bubble
            if (current != null) {
                val page = current.page
                val all = sequenceFor(page, current)
                if (bubble !== current) return true
                val at = indexIn(all, current)
                if (at + 1 in all.indices && getPage(0) === page && pageOffset == 0f) {
                    switchBubble(current, all[at + 1].fresh())
                    return true
                }
                dropBubble()
                guidedDonePage = page
                return false
            }
            if (!bubbleZoomEnabled || pageOffset != 0f) return false
            val page = getPage(0) as? ImagePage.ImageSingle ?: return false
            if (page === guidedDonePage || !page.atHomeScale) return false
            awaitInk(page, INK_WAIT_STEP_MS)
            val all = sequenceOf(page)
            if (getPage(0) !== page || pageOffset != 0f || bubble != null) return true
            if (all.isEmpty()) {
                guidedDonePage = page
                return false
            }
            open(all[0].fresh())
            return true
        } finally {
            guidedBusy.set(false)
        }
    }

    /**
     * One step back: the previous bubble, or the whole page again from the first. False with
     * no bubble open, so the caller turns back a page.
     */
    suspend fun guidedPrev(): Boolean = withFrameClock { guidedPrevLocked() }

    /**
     * Runs [block] with the viewer's frame clock: the reader calls in from a scope of its own,
     * which has none, and the bubbles' animations need one to run at all.
     */
    private suspend fun <T> withFrameClock(block: suspend () -> T): T {
        if (currentCoroutineContext()[MonotonicFrameClock] != null) return block()
        val clock = scope?.coroutineContext?.get(MonotonicFrameClock) ?: return block()
        return withContext(clock) { block() }
    }

    private suspend fun guidedPrevLocked(): Boolean {
        if (!guidedBusy.compareAndSet(false, true)) return true
        try {
            val current = bubble ?: return false
            val page = current.page
            val all = sequenceFor(page, current)
            if (bubble !== current) return true
            val at = indexIn(all, current)
            if (at - 1 in all.indices && getPage(0) === page && pageOffset == 0f) {
                switchBubble(current, all[at - 1].fresh())
            } else {
                dismissBubble()
            }
            guidedDonePage = null
            return true
        } finally {
            guidedBusy.set(false)
        }
    }

    /** Where [current] sits in [all]: itself, the sequence's own find of it, or the nearest. */
    private fun indexIn(all: List<BubbleOverlay>, current: BubbleOverlay): Int =
        all.indexOfFirst { it === current || it.rect.contentEquals(current.rect) }
            .takeIf { it >= 0 } ?: all.indices.minByOrNull {
            abs(all[it].centerX - current.centerX) + abs(all[it].centerY - current.centerY)
        } ?: -1

    /** Whether every image of [page] has the map bubbles are found on. */
    private fun inkReady(page: ImagePage.ImageSingle): Boolean {
        var any = false
        var all = true
        page.forEachImage { image, _, _ ->
            any = true
            if (image.inkMap == null) all = false
        }
        return any && all
    }

    /** Waits up to [timeoutMs] for [page] to be decoded, placed and mapped. */
    private suspend fun awaitInk(page: ImagePage.ImageSingle, timeoutMs: Long) {
        var waited = 0L
        while (waited < timeoutMs && (!inkReady(page) || page.animationJob?.isActive == true)) {
            delay(INK_POLL_MS)
            waited += INK_POLL_MS
        }
    }

    /**
     * Once the page turned to is ready: with [bubbleAutoOpen] its first bubble opens, and in
     * [bubbleDebug] its bubbles are found and outlined straight away.
     */
    private fun scheduleAutoOpen() {
        autoOpenJob?.cancel()
        autoOpenJob = scope?.launch {
            val page = getPage(0) as? ImagePage.ImageSingle ?: return@launch
            awaitInk(page, INK_WAIT_AUTO_MS)
            if (getPage(0) !== page || pageOffset != 0f || bubble != null || !page.atHomeScale) return@launch
            if (bubbleAutoOpen) {
                guidedNext()
            } else {
                // Found now, so they are outlined and the first tap opens one at once.
                sequenceOf(page)
            }
        }
    }

    private var prefetchJob: Job? = null

    /**
     * The next page's bubbles found ahead of time, while this one is read - the slow part,
     * cached with its images, so its first bubble opens as soon as it is turned to.
     */
    private fun prefetchNext() {
        if (!bubbleZoomEnabled || !(bubbleGuided || bubbleDock || bubbleDebug)) return
        val forward = if (isReversed) -1 else 1
        // The next two pages, nearest first - a quick reader is a page ahead of the finder.
        val ahead = (1..PREFETCH_PAGES).mapNotNull { getPage(forward * it) as? ImagePage.ImageSingle }
        if (ahead.isEmpty()) return
        val rtl = isReversed
        prefetchJob?.cancel()
        prefetchJob = scope?.launch {
            for (next in ahead) {
                var waited = 0L
                while (!inkReady(next) && waited < PREFETCH_WAIT_MS) {
                    delay(INK_POLL_MS)
                    waited += INK_POLL_MS
                }
                if (!inkReady(next) || BubbleZoom.isCached(next)) continue
                withContext(Dispatchers.Default) { BubbleZoom.warm(next, rtl) }
            }
        }
    }

    /** Debug mode turned on, or the reader opened: the page on screen is scanned now. */
    fun refreshDebug() {
        publishDebug()
        if (bubbleDebug && sequencePage !== getPage(0)) scheduleAutoOpen()
    }

    /** The page's first scan in debug mode: what was found is logged, before any correction. */
    private fun startDebugLog(page: ImagePage.ImageSingle, found: List<BubbleOverlay>) {
        val label = pageLabel?.invoke() ?: return
        debugPage = page
        debugLabel = label
        if (BubbleDebugLog.has(label)) return
        val maps = ArrayList<ca.mpreg.webgpuviewer.renderer.InkMap>()
        page.forEachImage { image, _, _ -> image.inkMap?.let { maps += it } }
        val boxes = logBoxes(page, found)
        val rtl = isReversed
        scope?.launch(Dispatchers.Default) {
            BubbleDebugLog.start(label, rtl, boxes, maps)
            debugLogged = BubbleDebugLog.size
            // All the lettering the recogniser finds on the page, bubble or not - the starting
            // point for finding lettering with no bubble round it.
            val ocr = maps.flatMapIndexed { i, map ->
                TextCheck.blocks(map, intArrayOf(0, 0, map.width, map.height)).map { b ->
                    BubbleDebugLog.Ocr(
                        i,
                        floatArrayOf(
                            b.box[0].toFloat() / map.width,
                            b.box[1].toFloat() / map.height,
                            b.box[2].toFloat() / map.width,
                            b.box[3].toFloat() / map.height,
                        ),
                        b.text,
                    )
                }
            }
            BubbleDebugLog.ocr(label, ocr)
        }
    }

    private fun logBoxes(page: ImagePage.ImageSingle, list: List<BubbleOverlay>): List<BubbleDebugLog.Box> {
        val w = width
        val h = height
        return list.mapNotNull { b ->
            val (image, bounds) = BubbleZoom.onImage(page, w, h, b.bounds) ?: return@mapNotNull null
            val frame = framings[frameKey(b)]?.let { f ->
                val dock = dockRegion(page) ?: return@let null
                BubbleZoom.onImage(page, w, h, viewOf(floatArrayOf(f[0], f[1], f[2], dock[0], dock[1])))?.second
            }
            BubbleDebugLog.Box(image, bounds, b.text, b.manual, frame)
        }
    }

    /** Logs [edit] on the debug page, with its bubbles as they now stand. */
    private fun logEdit(page: ImagePage.ImageSingle, edit: String) {
        val label = debugLabel?.takeIf { debugPage === page } ?: return
        BubbleDebugLog.edit(label, edit, logBoxes(page, sequence.orEmpty()))
    }

    /** Debug: the open bubble moves [delta] places in the reading order. */
    suspend fun debugMove(delta: Int) = withFrameClock {
        if (!guidedBusy.compareAndSet(false, true)) return@withFrameClock
        try {
            val current = bubble ?: return@withFrameClock
            val page = current.page
            val seq = sequenceFor(page, current).toMutableList()
            val at = indexIn(seq, current)
            val to = at + delta
            if (at < 0 || to !in seq.indices) return@withFrameClock
            seq.add(to, seq.removeAt(at))
            sequence = seq
            logEdit(page, "move ${at + 1} -> ${to + 1}")
        } finally {
            guidedBusy.set(false)
        }
    }

    /** Debug: the open bubble isn't one - it leaves the order and the next opens. */
    suspend fun debugDelete() = withFrameClock {
        if (!guidedBusy.compareAndSet(false, true)) return@withFrameClock
        try {
            val current = bubble ?: return@withFrameClock
            val page = current.page
            val seq = sequenceFor(page, current).toMutableList()
            val at = indexIn(seq, current)
            if (at < 0) return@withFrameClock
            seq.removeAt(at)
            sequence = seq
            logEdit(page, "delete ${at + 1}")
            if (at in seq.indices) switchBubble(current, seq[at].fresh()) else dismissBubble()
        } finally {
            guidedBusy.set(false)
        }
    }

    /**
     * Debug: a bubble the finder missed, drawn as [rect] on screen (normalised), goes in after
     * the open one - or last with none open - and opens.
     */
    suspend fun debugAdd(rect: FloatArray) = withFrameClock {
        debugAdding = false
        if (!guidedBusy.compareAndSet(false, true)) return@withFrameClock
        try {
            if (pageOffset != 0f) return@withFrameClock
            val page = getPage(0) as? ImagePage.ImageSingle ?: return@withFrameClock
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return@withFrameClock
            val added = BubbleZoom.manual(page, w, h, rect, dockRegion(page), bubbleContext) ?: return@withFrameClock
            val current = bubble?.takeIf { it.page === page }
            val seq = (if (current != null) sequenceFor(page, current) else sequenceOf(page)).toMutableList()
            val at = if (current != null) indexIn(seq, current) + 1 else seq.size
            seq.add(at.coerceIn(0, seq.size), added)
            sequence = seq
            logEdit(page, "add at ${at + 1}")
            if (current != null) switchBubble(current, added.fresh()) else open(added.fresh())
        } finally {
            guidedBusy.set(false)
        }
    }

    /** Closes the open bubble back into the page. */
    fun dismissBubble() {
        val current = bubble ?: return
        if (cameraMode(current)) {
            bubbleJob?.cancel()
            bubble = null
            releaseBubble(current)
            return
        }
        animateBubble(current, 0f) {
            if (bubble === current) {
                bubble = null
                releaseBubble(current)
            }
        }
    }

    /**
     * A tap while a bubble is open. On the bubble itself, or where no other bubble lies, it
     * closes; on another bubble, that one opens in its place, so reading on needs one tap per
     * bubble rather than a close and a double tap.
     */
    suspend fun tapOnBubble(tap: Offset) {
        val current = bubble ?: return
        val halfWidth = (current.rect[2] - current.rect[0]) * current.zoom * 0.5f
        val halfHeight = (current.rect[3] - current.rect[1]) * current.zoom * 0.5f
        val onBubble = abs(tap.x - current.targetX) <= halfWidth && abs(tap.y - current.targetY) <= halfHeight
        if (onBubble || current.progress < 1f) {
            dismissBubble()
            return
        }
        val page = current.page
        val w = width
        val h = height
        val found = if (w > 0 && h > 0) {
            withContext(Dispatchers.Default) {
                runCatching { BubbleZoom.detect(page, w, h, tap.x, tap.y, dockRegion(page), bubbleContext) }
                    .onFailure { Log.w("ImageViewerState", "Bubble detection failed", it) }
                    .getOrNull()
            }
        } else {
            null
        }
        if (bubble !== current) return
        if (found == null || found.rect.contentEquals(current.rect) || getPage(0) !== page || pageOffset != 0f) {
            dismissBubble()
            return
        }
        switchBubble(current, found)
    }

    /** The page's bubbles in reading order, found once per page - see [stepBubble]. */
    private var sequence: List<BubbleOverlay>? = null
        set(value) {
            field = value
            publishDebug()
        }
    private var sequencePage: ImagePage? = null

    private suspend fun sequenceFor(page: ImagePage.ImageSingle, current: BubbleOverlay): List<BubbleOverlay> {
        // Cached, unless the open bubble isn't in it: then it is placed in the order as well.
        sequence?.takeIf { sequencePage === page && it.any { b -> b === current || b.rect.contentEquals(current.rect) } }
            ?.let { return it }
        val w = width
        val h = height
        detecting = !BubbleZoom.isCached(page)
        val found = if (w > 0 && h > 0) {
            withContext(Dispatchers.Default) {
                runCatching {
                    BubbleZoom.detectAll(
                        page,
                        w,
                        h,
                        isReversed,
                        include = current,
                        dock = dockRegion(page),
                        context = bubbleContext,
                    )
                }
                    .onFailure { Log.w("ImageViewerState", "Bubble sequence failed", it) }
                    .getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }
        detecting = false
        sequencePage = page
        sequence = found
        if (bubbleDebug) startDebugLog(page, found)
        prefetchNext()
        return found
    }

    /** The page's bubbles in reading order, with none open yet - cached as [sequenceFor]'s are. */
    private suspend fun sequenceOf(page: ImagePage.ImageSingle): List<BubbleOverlay> {
        sequence?.takeIf { sequencePage === page }?.let { return it }
        val w = width
        val h = height
        detecting = !BubbleZoom.isCached(page)
        val found = if (w > 0 && h > 0) {
            withContext(Dispatchers.Default) {
                runCatching {
                    BubbleZoom.detectAll(page, w, h, isReversed, dock = dockRegion(page), context = bubbleContext)
                }
                    .onFailure { Log.w("ImageViewerState", "Bubble sequence failed", it) }
                    .getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }
        detecting = false
        sequencePage = page
        sequence = found
        if (bubbleDebug) startDebugLog(page, found)
        prefetchNext()
        return found
    }

    /**
     * Opens the bubble [delta] places on from the open one in reading order - the next for 1,
     * the previous for -1 - and closes it past either end. The order comes from the page's
     * panels, so it can be wrong on an unusual layout; a tap on any bubble puts it right.
     */
    suspend fun stepBubble(delta: Int) {
        val current = bubble ?: return
        if (current.progress < 1f) return
        val page = current.page
        val all = sequenceFor(page, current)
        if (bubble !== current) return
        if (all.isEmpty() || getPage(0) !== page || pageOffset != 0f) {
            dismissBubble()
            return
        }
        // The open bubble is in the order, itself or as the sequence's own find of it.
        val at = all.indexOfFirst { it === current || it.rect.contentEquals(current.rect) }
            .takeIf { it >= 0 } ?: all.indices.minByOrNull {
            abs(all[it].centerX - current.centerX) + abs(all[it].centerY - current.centerY)
        } ?: 0
        val target = at + delta
        if (target !in all.indices) {
            dismissBubble()
            return
        }
        switchBubble(current, all[target].fresh())
    }

    /** Closes [current] quickly and opens [next] in its place - the next in reading order, or a tapped one. */
    private suspend fun switchBubble(current: BubbleOverlay, next: BubbleOverlay) {
        if (cameraMode(next)) {
            // The view glides on to it rather than one bubble closing and the next opening.
            val frame = frameFor(next, camera.takeIf { cameraMode(current) })
            if (frame != null) {
                bubbleJob?.cancel()
                if (bubble !== current) return
                bubble = next
                next.progress = 1f
                releaseBubble(current)
                moveCamera(frame)
                return
            }
        }
        val w = width
        val h = height
        // The open one drops back quickly, then the next rises as a fresh one would.
        bubbleJob?.cancel()
        Animatable(current.progress).animateTo(0f, animationSpec = tween(120, easing = FastOutSlowInEasing)) {
            current.progress = value
            invalidate()
        }
        if (bubble !== current) return
        bubble = next
        releaseBubble(current)
        runCatching {
            WebGpuRenderer.withContext { BubbleZoom.prepare(next, w, h) }
            BubbleZoom.awaitPrepared()
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w("ImageViewerState", "Bubble preparation failed", it)
        }
        if (bubble === next) animateBubble(next, 1f)
    }

    /**
     * Zooms the page to frame the panel under [tap] (normalised screen coordinates), where
     * [panelZoomEnabled] and the page is at rest with a panel there - see [PanelZoom]. False
     * otherwise, so the caller can zoom as usual instead.
     */
    suspend fun zoomToPanel(tap: Offset): Boolean {
        if (!panelZoomEnabled || pageOffset != 0f || bubble != null) return false
        val page = getPage(0) as? ImagePage.ImageSingle ?: return false
        if (!page.atHomeScale) return false
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return false
        val rect = withContext(Dispatchers.Default) {
            runCatching { PanelZoom.detect(page, w, h, tap.x, tap.y) }
                .onFailure { Log.w("ImageViewerState", "Panel detection failed", it) }
                .getOrNull()
        } ?: return false
        if (getPage(0) !== page || pageOffset != 0f || !page.atHomeScale) return false

        // Scaled to fit with a little room, about the screen's centre, then moved so the panel's
        // centre lands there: on screen a page point sits at 0.5 + scale * (page.x + k) for a k
        // of its own, so a point now at c moves to the centre when page.x drops by (c - 0.5) / scale.
        val panelWidth = (rect[2] - rect[0]) * w
        val panelHeight = (rect[3] - rect[1]) * h
        if (panelWidth <= 0f || panelHeight <= 0f) return false
        val fit = min(w / panelWidth, viewportHeight / panelHeight) * PANEL_FRAME
        val targetScale = (page.scale * fit).coerceIn(page.minScale, page.maxScale)
        val centerX = (rect[0] + rect[2]) * 0.5f
        val centerY = (rect[1] + rect[3]) * 0.5f
        val targetX = (page.x - (centerX - 0.5f) / page.scale).coerceIn(page.minX(targetScale), page.maxX(targetScale))
        val targetY = (page.y - (centerY - 0.5f) / page.scale).coerceIn(page.minY(targetScale), page.maxY(targetScale))
        // Paced like the double tap zoom it stands in for, so zooming in and back out match.
        page.animateTo(
            targetX = targetX,
            targetY = targetY,
            targetScale = targetScale,
            animationSpec = doubleTapZoomSpec(),
        )
        return true
    }

    /** Drops the open bubble at once, for a page that is going away. */
    private fun dropBubble() {
        sequencePage = null
        sequence = null
        debugAdding = false
        debugDraft = null
        missPrompt = null
        if (debugTemporary) {
            debugTemporary = false
            bubbleDebug = false
        }
        guidedDonePage = null
        autoOpenJob?.cancel()
        val current = bubble ?: return
        bubbleJob?.cancel()
        bubble = null
        releaseBubble(current)
        invalidate()
    }

    private fun animateBubble(target: BubbleOverlay, to: Float, then: () -> Unit = {}) {
        bubbleJob?.cancel()
        bubbleJob = scope?.launch {
            // Paced like the double tap zoom; at its normal setting a quick spring with a
            // little overshoot on the way out.
            val spec: AnimationSpec<Float> = when {
                doubleTapZoomMillis <= 1 -> snap()
                doubleTapZoomMillis < 500 -> tween(doubleTapZoomMillis, easing = FastOutSlowInEasing)
                else -> spring(
                    dampingRatio = if (to > 0f) 0.8f else 1f,
                    stiffness = 900f,
                    visibilityThreshold = 0.002f,
                )
            }
            Animatable(target.progress).animateTo(to, animationSpec = spec) {
                target.progress = value
                invalidate()
            }
            then()
            invalidate()
        }
    }

    /**
     * Its GPU resources go on the render thread, after any frame already drawing with them.
     * ArtCNN's working textures stay, for the next bubble.
     */
    private fun releaseBubble(overlay: BubbleOverlay) {
        post { overlay.release() }
    }

    // One instance for this state's lifetime, so [cleanup] can tell its own from a successor's.
    private val invalidateCallback: () -> Unit = { invalidate() }

    /**
     * The page [index] steps from current. [isReversed] plays no part here - [fetchPage] and
     * [onPageChange] are what decide what a step actually means.
     */
    fun getPage(index: Int): ImagePage? {
        return fetchPage?.invoke(index)?.also { it.attach(this, scope, invalidateCallback) }
    }

    /**
     * What the last [captureRenderState] drew with, and so what [ImagePage.isOnScreen] answers
     * from. Off the last frame, not re-fetched: changing which pages show draws its own frame.
     */
    @Volatile
    protected var onScreenPages: List<ImagePage> = emptyList()

    internal fun isOnScreen(page: ImagePage): Boolean = onScreenPages.any { it.covers(page) }

    @Synchronized
    fun init(scope: CoroutineScope, surface: Surface, width: Int, height: Int) {
        this.renderer.init(scope, surface, width, height)
        this.scope = scope
        // The paged viewer only - the continuous one has no bubble zoom.
        if (bubbleZoomEnabled && this !is ImageViewerContinuousState) post { BubbleZoom.prewarm() }
        Hdr.requestFrame = invalidateCallback

        // On [dispatcher] and drained under the lock, as [post] itself would have run them.
        scope.launch(dispatcher) {
            val pending = synchronized(this@ImageViewerState) {
                pendingPostInit.toList().also { pendingPostInit.clear() }
            }
            pending.forEach { it() }
        }
    }

    @Synchronized
    fun resize(width: Int, height: Int) {
        renderer.resize(width, height)
        invalidate()
    }

    var firstPos = Offset.Zero
    var currentPos = Offset.Zero

    /** A finger is on the page turn right now - [currentPos] is where it is. */
    @Volatile
    var turnHeld = false

    /** [pageOffset] when the finger last let go of a turn - where its settle started from. */
    @Volatile
    var turnReleaseOffset = 0f

    /** The turn in flight came from [animatePageTurn] (a tap or key), not a drag. */
    @Volatile
    var turnAuto = false

    var transition: Transition = if (isVertical) TransitionBasic.Vertical else TransitionBasic

    // Anything changed since the last frame, however many invalidates said so.
    private val dirty = AtomicBoolean(true)

    @Volatile
    private var reportedUnavailable = false

    @Volatile
    private var retries = 0

    // Wakes [collect] when there is nothing to draw. Buffered, so no send races [dirty]'s check.
    private val renderWake = Channel<Unit>(Channel.CONFLATED)

    fun invalidate() {
        dirty.set(true)
        renderWake.trySend(Unit)
    }

    /**
     * Draw at most one frame per display frame, for as long as anything is [dirty].
     *
     * A frame loop, not a pass per invalidate: a fling step, a fade step, a decode and the tile
     * worker all land in one frame. [renderWake] carries no count, so a draw clearing [dirty]
     * doesn't leave that frame's remaining invalidates to wait out a frame each.
     *
     * Nothing may be awaited between the frame wait and the capture, the draw included: this
     * registers as an awaiter mid-draw to land in the next frame's batch, behind the animation,
     * and registering after that batch has gone out costs a frame every frame.
     */
    suspend fun collect() = coroutineScope {
        val frameClock = currentCoroutineContext()[MonotonicFrameClock]
        var drawing: Job? = null
        while (true) {
            // Paced either way: called without a clock, the loop would spin on whatever is dirty.
            if (frameClock != null) frameClock.withFrameNanos { } else delay(8)
            // Still drawing: leave [dirty] set for the next frame.
            if (drawing?.isActive == true) continue
            // Anything invalidating from here belongs to the next frame.
            if (!dirty.getAndSet(false)) {
                renderWake.receive()
                continue
            }
            // Drop the wake this frame's invalidate left, or going idle costs a spurious one.
            renderWake.tryReceive()
            // Captured here, drawn on the GPU thread below - see this function's doc.
            val snapshot = captureRenderState() ?: continue
            drawing = launch(dispatcher) {
                when (
                    renderer.render { encoder, texture ->
                        renderSnapshot(encoder, texture, snapshot)
                    }
                ) {
                    FrameResult.Drawn -> {
                        reportedUnavailable = false
                        retries = 0
                    }

                    // Past the cap, park until something else invalidates.
                    FrameResult.Retry -> if (++retries <= MAX_RETRIES) invalidate()

                    // No invalidate: leaving [dirty] clear parks the loop on [renderWake] rather
                    // than spinning the frame clock, and a later resize still wakes it.
                    FrameResult.Unavailable -> if (!reportedUnavailable) {
                        reportedUnavailable = true
                        val reason = WebGpuRenderer.unavailableReason ?: "no render surface"
                        Log.e("ImageViewerState", "Nothing can be drawn: $reason")
                        onRenderUnavailable?.runCatching { invoke(reason) }
                    }
                }
            }
        }
    }

    protected open fun captureRenderState(): Any? {
        val currentPage = getPage(0) ?: run {
            onScreenPages = emptyList()
            return EmptySnapshot
        }
        val offset = pageOffset
        val adjacentPage = when {
            offset == 0f -> null
            // Use override if set (for far navigation)
            transitionFromPage != null -> transitionFromPage
            offset > 0f -> getPage(if (isReversed) -1 else 1)
            else -> getPage(if (isReversed) 1 else -1)
        }
        // Only prewarms while at rest - see renderSnapshot - so a turn in flight needs no lookup.
        val nextPage = if (offset == 0f) getPage(1) else null
        onScreenPages = listOfNotNull(currentPage, adjacentPage)
        return RenderSnapshot(
            currentPage, adjacentPage, nextPage, offset, transition, firstPos, currentPos,
            TurnGesture(turnHeld, turnAuto, turnReleaseOffset),
            bubble?.takeIf { offset == 0f && it.page === currentPage },
            bubble?.progress ?: 0f,
            camera?.takeIf { offset == 0f && bubble?.page === currentPage },
        )
    }

    private object EmptySnapshot

    private companion object {
        const val MAX_RETRIES = 10

        /** How much of the screen a framed panel takes across or down. */
        const val PANEL_FRAME = 0.94f

        /** The docked band: its gap below the page, room kept clear at the bottom, least height. */
        const val DOCK_GAP = 0.01f
        const val DOCK_BOTTOM_MARGIN = 0.035f
        const val DOCK_MIN_HEIGHT = 0.14f

        /** How long a step or an auto-open waits for the page to be ready, and how often it looks. */
        const val INK_WAIT_STEP_MS = 1000L
        const val INK_WAIT_AUTO_MS = 4000L
        const val INK_POLL_MS = 100L

        /** How long the next page is waited on to decode before it is found ahead of time. */
        const val PREFETCH_WAIT_MS = 15000L

        /** How many pages ahead are found while one is read. */
        const val PREFETCH_PAGES = 2

        /** The view below the page: how much of the band a bubble fills, how far it zooms. */
        const val CAMERA_FIT_WIDTH = 0.8f
        const val CAMERA_FIT_HEIGHT = 0.7f
        const val CAMERA_MAX_ZOOM = 3.5f

        /** A bubble already in view stays put while the view is at least this share of its zoom. */
        const val CAMERA_KEEP = 0.75f

        /** How long the view takes to glide from one bubble to the next. */
        const val CAMERA_MOVE_MS = 380

        /** How far a hand framing in debug mode may zoom out and in. */
        const val CAMERA_MIN_ZOOM = 0.5f
        const val CAMERA_LIMIT_ZOOM = 8f
    }

    private class RenderSnapshot(
        val currentPage: ImagePage,
        val adjacentPage: ImagePage?,
        val nextPage: ImagePage?,
        val offset: Float,
        val transition: Transition,
        val firstPos: Offset,
        val currentPos: Offset,
        val gesture: TurnGesture,
        val bubble: BubbleOverlay?,
        val bubbleProgress: Float,
        val camera: FloatArray?,
    )

    /**
     * Run [block] against a render pass over [texture], ending it afterwards either way - a draw
     * that throws still closes the pass, and [WebGpuRenderer.render] makes it a dropped frame.
     * Owned here because only the whole frame's contents decide where a pass starts and ends.
     *
     * Always clears: `getCurrentTexture` rotates buffers, so loading would show stale content.
     */
    protected fun renderPass(
        encoder: GPUCommandEncoder,
        texture: GPUTexture,
        clearColor: Int = 0,
        block: (GPURenderPassEncoder) -> Unit,
    ) {
        val targetView = texture.createView()
        val pass = encoder.beginRenderPass(
            GPURenderPassDescriptor(
                colorAttachments = arrayOf(
                    GPURenderPassColorAttachment(
                        view = targetView,
                        loadOp = LoadOp.Clear,
                        storeOp = StoreOp.Store,
                        clearValue = GPUColor(
                            ((clearColor shr 16) and 0xFF) / 255.0,
                            ((clearColor shr 8) and 0xFF) / 255.0,
                            (clearColor and 0xFF) / 255.0,
                            ((clearColor ushr 24) and 0xFF) / 255.0,
                        ),
                    ),
                ),
                // Fresh each frame: the tile blit marks what it covered so masked draws skip
                // re-shading it. Discarded afterward - nothing reads it across frames.
                depthStencilAttachment = GPURenderPassDepthStencilAttachment(
                    view = tiles.stencilViewFor(texture),
                    stencilLoadOp = LoadOp.Clear,
                    stencilStoreOp = StoreOp.Discard,
                    stencilClearValue = 0,
                ),
            ),
        )
        try {
            block(pass)
        } finally {
            pass.endAndRelease(targetView)
        }
    }

    protected open suspend fun renderSnapshot(
        encoder: GPUCommandEncoder,
        texture: GPUTexture,
        snapshot: Any,
    ) {
        if (snapshot === EmptySnapshot) {
            Draw.clear(encoder, texture, 0)
            return
        }
        val s = snapshot as RenderSnapshot
        tiles.newFrame()
        val page = s.currentPage

        if (s.adjacentPage != null && s.offset != 0f) {
            s.transition.render(
                page, s.adjacentPage, encoder, texture, s.offset, s.firstPos, s.currentPos, tiles,
                s.gesture,
            )
            return
        }

        val covered = page.drawLive(encoder, texture, tiles)
        val shownBubble = s.bubble
        if (s.camera != null && shownBubble != null) {
            BubbleZoom.drawCamera(encoder, texture, shownBubble.page, s.camera)
        } else if (shownBubble != null) {
            BubbleZoom.draw(encoder, texture, shownBubble, s.bubbleProgress)
        }

        // Once the current page's tiles settle, prewarm the next page's, so a transition into it
        // starts mostly sharp. Gated on atHome: the tile cache is keyed by (x, y, scale).
        if (covered && page is ImagePage.ImageSingle && page.atHome) {
            val next = s.nextPage as? ImagePage.ImageSingle
            if (next != null && next.highQuality && !next.isAnimated && next.atHome) {
                tiles.prewarm(next, texture)
            }
        }
    }

    private val pendingPostInit = mutableListOf<(suspend () -> Unit)>()

    @Synchronized
    fun post(fn: suspend () -> Unit) {
        val activeScope = scope
        if (activeScope?.isActive == true) {
            activeScope.launch(dispatcher) {
                fn()
            }
        } else {
            pendingPostInit.add(fn)
        }
    }

    fun cleanup() {
        animationJob?.cancel()
        bubbleJob?.cancel()
        autoOpenJob?.cancel()
        bubble?.let { overlay ->
            bubble = null
            overlay.release()
        }
        onScreenPages = emptyList()
        // Held by an object that outlives this state, so it has to be dropped by hand - but only
        // if it is still ours: a replacement viewer inits before the one it replaces cleans up.
        if (Hdr.requestFrame === invalidateCallback) Hdr.requestFrame = null
        Transition.releasePagesOf(this)
        tiles.cleanup()
        renderer.cleanup()
    }
}
