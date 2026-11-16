package ca.mpreg.webgpuviewer.viewer

import android.util.Log
import androidx.webgpu.BlendFactor
import androidx.webgpu.BlendOperation
import androidx.webgpu.BufferUsage
import androidx.webgpu.FilterMode
import androidx.webgpu.GPUBindGroupDescriptor
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBlendComponent
import androidx.webgpu.GPUBlendState
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUColor
import androidx.webgpu.GPUColorTargetState
import androidx.webgpu.GPUCommandEncoder
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPUExtent3D
import androidx.webgpu.GPUFragmentState
import androidx.webgpu.GPUPrimitiveState
import androidx.webgpu.GPURenderPassColorAttachment
import androidx.webgpu.GPURenderPassDescriptor
import androidx.webgpu.GPURenderPassEncoder
import androidx.webgpu.GPURenderPipelineDescriptor
import androidx.webgpu.GPUSamplerDescriptor
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import androidx.webgpu.GPUTexelCopyBufferLayout
import androidx.webgpu.GPUTexelCopyTextureInfo
import androidx.webgpu.GPUTexture
import androidx.webgpu.GPUTextureDescriptor
import androidx.webgpu.GPUTextureView
import androidx.webgpu.GPUVertexState
import androidx.webgpu.LoadOp
import androidx.webgpu.PrimitiveTopology.Companion.TriangleList
import androidx.webgpu.StoreOp
import androidx.webgpu.TextureFormat
import androidx.webgpu.TextureUsage
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import ca.mpreg.webgpuviewer.draw.Draw
import ca.mpreg.webgpuviewer.draw.rect
import ca.mpreg.webgpuviewer.renderer.FormatKeyed
import ca.mpreg.webgpuviewer.renderer.Hdr
import ca.mpreg.webgpuviewer.renderer.Image
import ca.mpreg.webgpuviewer.renderer.InkMap
import ca.mpreg.webgpuviewer.renderer.UpscalerArtCnn
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer
import ca.mpreg.webgpuviewer.renderer.destroyAndRelease
import ca.mpreg.webgpuviewer.renderer.endAndRelease
import ca.mpreg.webgpuviewer.renderer.groupLayout
import ca.mpreg.webgpuviewer.renderer.setTransientBindGroup
import ca.mpreg.webgpuviewer.renderer.submitAndRelease
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A speech bubble lifted off the page and shown enlarged over it, the way Google Play Books does -
 * see [ImageViewerState.showBubble]. Everything a frame needs, fixed when the bubble is found.
 */
internal class BubbleOverlay(
    /** The page it was found on - it only draws over that page, at rest. */
    val page: ImagePage.ImageSingle,
    /** The bubble's shape: [maskWidth] x [maskHeight], 255 inside, over [rect]. */
    val mask: ByteArray,
    val maskWidth: Int,
    val maskHeight: Int,
    /** The bubble's bounds on screen, normalised (x1, y1, x2, y2). */
    val rect: FloatArray,
    /** Where it opens to: its centre on screen, and how much larger it gets. */
    val targetX: Float,
    val targetY: Float,
    val zoom: Float,
    /** Screen pixels per image pixel at rest - how much detail the scan has to give. */
    val screenPerImagePixel: Float,
    /** How far the page behind darkens once it is open - none when docked below the page. */
    val dim: Float = 0.45f,
    /**
     * The bubble's own bounds on screen - [rect] less the artwork shown round it, where
     * [rect] was widened to fill the room below the page.
     */
    val bounds: FloatArray = rect,
    /** Artwork round the bubble shows with it - lettering's clean-up is eased off for it. */
    val context: Boolean = false,
    /** The bubble's own shape over [bounds], as [mask] is over [rect] - for its outline. */
    val shape: ByteArray = mask,
    val shapeWidth: Int = maskWidth,
    val shapeHeight: Int = maskHeight,
) {
    /** Docked below the page rather than opened over it. */
    val docked: Boolean get() = dim == 0f

    /** The outline of [shape], white on clear, to be tinted and drawn over [bounds]. */
    val outlineImage: ImageBitmap by lazy { shapeImage(edge = true) }

    /** [shape] filled, white on clear - to hide a bubble not yet read. */
    val fillImage: ImageBitmap by lazy { shapeImage(edge = false) }

    private fun shapeImage(edge: Boolean): ImageBitmap {
        val w = shapeWidth
        val h = shapeHeight
        fun inside(x: Int, y: Int) = x in 0 until w && y in 0 until h && (shape[y * w + x].toInt() and 0xFF) >= 128
        // The fill covers the lettering too: the shape is the bubble's paper, its letters holes
        // in it - everything not reached from the edge without crossing the shape.
        val outside = if (edge) null else outsideOf(w, h, ::inside)
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val on = if (edge) {
                // Inside, with something outside within two pixels: a line a little thicker
                // than one map pixel, which can be a fraction of a screen pixel.
                inside(x, y) && (-2..2).any { d -> !inside(x + d, y) || !inside(x, y + d) }
            } else {
                !outside!![i]
            }
            if (on) -0x1 else 0
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /** Which pixels of a [w] by [h] mask lie outside the shape: reached from the border round it. */
    private fun outsideOf(w: Int, h: Int, inside: (Int, Int) -> Boolean): BooleanArray {
        val out = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var tail = 0
        fun visit(x: Int, y: Int) {
            if (x !in 0 until w || y !in 0 until h) return
            val i = y * w + x
            if (out[i] || inside(x, y)) return
            out[i] = true
            queue[tail++] = i
        }
        for (x in 0 until w) {
            visit(x, 0)
            visit(x, h - 1)
        }
        for (y in 0 until h) {
            visit(0, y)
            visit(w - 1, y)
        }
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            visit(x - 1, y)
            visit(x + 1, y)
            visit(x, y - 1)
            visit(x, y + 1)
        }
        return out
    }

    val centerX: Float get() = (rect[0] + rect[2]) * 0.5f
    val centerY: Float get() = (rect[1] + rect[3]) * 0.5f

    /** 0 closed to 1 open, animated by [ImageViewerState]. */
    @Volatile
    var progress: Float = 0f

    /** What text recognition read in it, where it ran - for the debug log. */
    var text: String? = null

    /** Drawn by hand in debug mode, not found. */
    var manual: Boolean = false

    /** The panel it is in, on screen normalised - what the view below the page aims to show. */
    var panel: FloatArray? = null

    /** How tall its letters are on screen at rest, as a share of the screen's height. 0 unknown. */
    var letterHeight: Float = 0f

    // Render thread only, like everything below.
    private var maskTexture: GPUTexture? = null
    private var maskView: GPUTextureView? = null

    /** Set once [release] has run: a frame captured before it is dropped rather than drawn. */
    var released = false
        private set

    fun maskView(): GPUTextureView {
        maskView?.let { return it }
        val device = WebGpuRenderer.device
        val texture = device.createTexture(
            GPUTextureDescriptor(
                size = GPUExtent3D(maskWidth, maskHeight),
                format = TextureFormat.R8Unorm,
                usage = TextureUsage.TextureBinding or TextureUsage.CopyDst,
            ),
        )
        val data = ByteBuffer.allocateDirect(mask.size).order(ByteOrder.nativeOrder())
        data.put(mask).flip()
        device.queue.writeTexture(
            dataLayout = GPUTexelCopyBufferLayout(
                offset = 0L,
                bytesPerRow = maskWidth,
                rowsPerImage = maskHeight,
            ),
            data = data,
            destination = GPUTexelCopyTextureInfo(texture = texture),
            writeSize = GPUExtent3D(maskWidth, maskHeight),
        )
        maskTexture = texture
        return texture.createView().also { maskView = it }
    }

    /**
     * The bubble's part of the page, enlarged once and kept while it is open - see
     * [BubbleZoom.enlarge]. [sourceScale] is its pixels per screen pixel at rest, from the
     * bubble's top-left corner.
     */
    var source: GPUTexture? = null
    var sourceView: GPUTextureView? = null
    var sourceScale: Float = 1f

    /** A copy ready to be shown again - a released overlay can't be, so a sequence hands out these. */
    fun fresh(): BubbleOverlay = BubbleOverlay(
        page,
        mask,
        maskWidth,
        maskHeight,
        rect,
        targetX,
        targetY,
        zoom,
        screenPerImagePixel,
        dim,
        bounds,
        context,
        shape,
        shapeWidth,
        shapeHeight,
    ).also {
        it.text = text
        it.manual = manual
        it.panel = panel
        it.letterHeight = letterHeight
    }

    /** On the render thread. */
    fun release() {
        released = true
        maskView?.close()
        maskTexture?.destroyAndRelease()
        maskView = null
        maskTexture = null
        sourceView?.close()
        source?.destroyAndRelease()
        sourceView = null
        source = null
    }
}

internal object BubbleZoom {

    /** How far the bubble grows at most, and at least for it to be worth opening. */
    private const val MAX_ZOOM = 3.2f
    private const val MIN_ZOOM = 1.3f

    /** How much of the screen an open bubble may fill across and down. */
    private const val FIT_WIDTH = 0.9f
    private const val FIT_HEIGHT = 0.62f

    /**
     * Docked below the page: it fills the band's width or height, grows further than in place -
     * nothing of the page is hidden behind it - and opens there even at its own size.
     */
    private const val DOCK_FIT_WIDTH = 0.96f
    private const val DOCK_FIT_HEIGHT = 0.9f
    private const val DOCK_MAX_ZOOM = 4.5f
    private const val DOCK_MIN_ZOOM = 1f

    /** Room kept round a docked bubble, as a share of the screen. */
    private const val DOCK_MARGIN = 0.02f

    /**
     * The bubble under the tap at ([tapX], [tapY]) - normalised screen coordinates on a
     * [screenWidth] x [screenHeight] surface - or null where there is none to open. Off the GPU
     * thread: it reads only the images' [InkMap]s and the page's placement.
     */
    fun detect(
        page: ImagePage.ImageSingle,
        screenWidth: Int,
        screenHeight: Int,
        tapX: Float,
        tapY: Float,
        /** Where bubbles open instead of in place - see [overlay]. */
        dock: FloatArray? = null,
        /** Docked, the artwork round it shows too - see [place]. */
        context: Boolean = false,
    ): BubbleOverlay? {
        val hit = imageAt(page, screenWidth, screenHeight, tapX, tapY) ?: return null
        val image = hit.image
        val imageRect = hit.rect
        // In a spread, a bubble cut by the seam is still a bubble: the side's inner edge bounds
        // it instead of disqualifying it.
        val openEdge = when {
            page !is ImagePage.ImageSpread -> BubbleDetect.NO_EDGE
            hit.offsetX < 0f -> BubbleDetect.RIGHT_EDGE
            else -> BubbleDetect.LEFT_EDGE
        }
        val map = image.inkMap ?: return null

        val u = (tapX - imageRect[0]) / (imageRect[2] - imageRect[0])
        val v = (tapY - imageRect[1]) / (imageRect[3] - imageRect[1])
        val found = BubbleDetect.find(
            map,
            (u * map.width).toInt().coerceIn(0, map.width - 1),
            (v * map.height).toInt().coerceIn(0, map.height - 1),
            openEdge,
            letters = BubbleDetect.TAP_LETTERS,
        ) ?: return null
        if (!accepted(TextCheck.read(map, found), found)) return null

        return overlay(page, screenWidth, screenHeight, image, map, imageRect, found, dock, context)
    }

    /**
     * Every bubble on [page], in reading order: right to left where [rightToLeft], else left to
     * right, page by page in a spread, panel by panel within a page - see [PanelZoom.panelOrder]
     * - and top to bottom within a panel. For stepping from one bubble to the next.
     */
    fun detectAll(
        page: ImagePage.ImageSingle,
        screenWidth: Int,
        screenHeight: Int,
        rightToLeft: Boolean,
        /** A bubble to place in the order as well - the open one, found by a tap. */
        include: BubbleOverlay? = null,
        /** Where bubbles open instead of in place - see [overlay]. */
        dock: FloatArray? = null,
        /** Docked, the artwork round each shows too - see [place]. */
        context: Boolean = false,
    ): List<BubbleOverlay> {
        val pages = ArrayList<List<BubbleOverlay>>()
        page.forEachImage { image, offsetX, imageScale ->
            val map = image.inkMap ?: return@forEachImage
            val imageRect = imageRectOf(page, offsetX, imageScale, image, screenWidth, screenHeight)
            val openEdge = openEdgeOf(page, offsetX)
            val placed = ArrayList<BubbleOverlay>()
            val boxes = ArrayList<IntArray>()
            // Found, read and ordered once per image - see [found] - and only placed here.
            for (item in found(map, openEdge, rightToLeft, keyOf?.invoke(image))) {
                val overlay = overlay(page, screenWidth, screenHeight, image, map, imageRect, item.found, dock, context)
                    ?: continue
                // Off screen - the far half of a wide page zoomed to half its width - it can't
                // be read where it sits.
                if (overlay.centerX !in 0f..1f || overlay.centerY !in 0f..1f) continue
                overlay.text = item.text
                overlay.letterHeight = item.letterHeight * (imageRect[3] - imageRect[1]) / map.height
                overlay.panel = item.panel?.let { p ->
                    val sx = (imageRect[2] - imageRect[0]) / map.width
                    val sy = (imageRect[3] - imageRect[1]) / map.height
                    floatArrayOf(
                        imageRect[0] + p[0] * sx,
                        imageRect[1] + p[1] * sy,
                        imageRect[0] + p[2] * sx,
                        imageRect[1] + p[3] * sy,
                    )
                }
                placed += overlay
                boxes += item.box
            }
            val included = include != null && include.centerX >= imageRect[0] && include.centerX < imageRect[2] &&
                include.centerY >= imageRect[1] && include.centerY < imageRect[3] &&
                placed.none { it.bounds.contentEquals(include.bounds) }
            if (include != null && included) {
                // Back from screen to map pixels, as the others were placed.
                val sx = map.width / (imageRect[2] - imageRect[0])
                val sy = map.height / (imageRect[3] - imageRect[1])
                placed += include
                boxes += intArrayOf(
                    ((include.bounds[0] - imageRect[0]) * sx).toInt(),
                    ((include.bounds[1] - imageRect[1]) * sy).toInt(),
                    ((include.bounds[2] - imageRect[0]) * sx).toInt(),
                    ((include.bounds[3] - imageRect[1]) * sy).toInt(),
                )
            }
            // Already in order, unless a tapped bubble has to find its place among them.
            pages += if (included) BubbleDetect.readingOrder(map, boxes, rightToLeft).map { placed[it] } else placed
        }
        // forEachImage walks a spread left side first; a right-to-left book reads the right side first.
        if (rightToLeft) pages.reverse()
        return pages.flatten()
    }

    /** Where [image] of [page] sits on screen, normalised. */
    private fun imageRectOf(
        page: ImagePage.ImageSingle,
        offsetX: Float,
        imageScale: Float,
        image: Image,
        screenWidth: Int,
        screenHeight: Int,
    ): FloatArray {
        val placeX = (page.x + offsetX / screenWidth + WebGpuRenderer.offsetX) / imageScale -
            WebGpuRenderer.offsetX
        val placeY = (page.y + WebGpuRenderer.offsetY) / imageScale - WebGpuRenderer.offsetY
        return image.placement(screenWidth, screenHeight, placeX, placeY, page.scale * imageScale)
    }

    /** Which edge of the image at [offsetX] in [page] is the fold of a spread, if any. */
    private fun openEdgeOf(page: ImagePage.ImageSingle, offsetX: Float) = when {
        page !is ImagePage.ImageSpread -> BubbleDetect.NO_EDGE
        offsetX < 0f -> BubbleDetect.RIGHT_EDGE
        else -> BubbleDetect.LEFT_EDGE
    }

    // KMK -->
    /**
     * [page]'s bubbles as corrected by the reader - [list], in its order, with the panels set
     * on them - kept in place of what was found, in memory and in [store], so the page opens
     * corrected next time. Bubbles found keep their shape; ones drawn by hand are boxes.
     */
    fun keep(
        page: ImagePage.ImageSingle,
        screenWidth: Int,
        screenHeight: Int,
        rightToLeft: Boolean,
        list: List<BubbleOverlay>,
        /** Panels drawn by hand, on screen normalised. */
        panels: List<FloatArray> = emptyList(),
    ) {
        page.forEachImage { image, offsetX, imageScale ->
            val map = image.inkMap ?: return@forEachImage
            val r = imageRectOf(page, offsetX, imageScale, image, screenWidth, screenHeight)
            val openEdge = openEdgeOf(page, offsetX)
            val sx = map.width / (r[2] - r[0])
            val sy = map.height / (r[3] - r[1])
            fun toMap(b: FloatArray) = intArrayOf(
                ((b[0] - r[0]) * sx).toInt().coerceIn(0, map.width - 1),
                ((b[1] - r[1]) * sy).toInt().coerceIn(0, map.height - 1),
                ((b[2] - r[0]) * sx).toInt().coerceIn(1, map.width),
                ((b[3] - r[1]) * sy).toInt().coerceIn(1, map.height),
            )
            val before = synchronized(cache) { cache[map]?.items }.orEmpty()
            val items = list
                .filter { b -> b.centerX >= r[0] && b.centerX < r[2] && b.centerY >= r[1] && b.centerY < r[3] }
                .map { b ->
                    val box = toMap(b.bounds)
                    val was = before.firstOrNull { item -> (0..3).all { abs(item.box[it] - box[it]) <= KEEP_SLACK } }
                    val panel = b.panel?.let { toMap(it) }
                    val letters = b.letterHeight / (r[3] - r[1]) * map.height
                    if (was != null && !b.manual) {
                        Item(was.found, b.text ?: was.text, was.box, panel ?: was.panel, was.letterHeight)
                    } else {
                        val w = (box[2] - box[0]).coerceAtLeast(1)
                        val h = (box[3] - box[1]).coerceAtLeast(1)
                        val mask = ByteArray(w * h) { (-1).toByte() }
                        val found = BubbleDetect.Found(box[0], box[1], w, h, mask, true, letters)
                        Item(found, b.text, intArrayOf(box[0], box[1], box[0] + w, box[1] + h), panel, letters)
                    }
                }
            val drawn = panels
                .filter { p -> (p[0] + p[2]) * 0.5f in r[0]..r[2] && (p[1] + p[3]) * 0.5f in r[1]..r[3] }
                .map { toMap(it) }
            synchronized(cache) { cache[map] = Cached(openEdge, rightToLeft, items, drawn) }
            val key = keyOf?.invoke(image) ?: return@forEachImage
            runCatching { store?.write(key, BubbleFile.encode(map, rightToLeft, items, drawn)) }
                .onFailure { Log.w("BubbleZoom", "Couldn't keep corrected bubbles", it) }
        }
    }

    /** The panels drawn by hand on [page] and kept - see [keep] - on screen normalised. */
    fun panelsOf(page: ImagePage.ImageSingle, screenWidth: Int, screenHeight: Int): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        page.forEachImage { image, offsetX, imageScale ->
            val map = image.inkMap ?: return@forEachImage
            val panels = synchronized(cache) { cache[map]?.panels }.orEmpty()
            if (panels.isEmpty()) return@forEachImage
            val r = imageRectOf(page, offsetX, imageScale, image, screenWidth, screenHeight)
            val sx = (r[2] - r[0]) / map.width
            val sy = (r[3] - r[1]) / map.height
            for (p in panels) {
                out += floatArrayOf(r[0] + p[0] * sx, r[1] + p[1] * sy, r[0] + p[2] * sx, r[1] + p[3] * sy)
            }
        }
        return out
    }
    // KMK <--

    /** A bubble found on an image's map, what was read in it, and its bounds there. */
    class Item(
        val found: BubbleDetect.Found,
        val text: String?,
        val box: IntArray,
        /** The panel round it on the map (x0, y0, x1, y1) - framed with it below the page. */
        var panel: IntArray? = null,
        /** How tall its letters are on the map - see [BubbleDetect.Found.letterHeight]. */
        var letterHeight: Float = found.letterHeight,
    )

    private class Cached(
        val openEdge: Int,
        val rightToLeft: Boolean,
        val items: List<Item>,
        /** Panels drawn by hand on the image, map pixels. */
        val panels: List<IntArray> = emptyList(),
    )

    /** How far, in map pixels, a corrected bubble's box may be from the one found to be it. */
    private const val KEEP_SLACK = 3

    /** Where found bubbles are kept between sessions - see [BubbleStore]. Set by the app. */
    @Volatile
    var store: BubbleStore? = null

    /** The app's key for an image's bubbles in [store] - its book, chapter and page. */
    @Volatile
    var keyOf: ((Image) -> String?)? = null

    /** Each image's bubbles, found, read and ordered once - by its map, which goes with it. */
    private val cache = java.util.WeakHashMap<InkMap, Cached>()

    /**
     * The bubbles on [map] in reading order, each read by text recognition and kept only where
     * that agrees - the slow part of finding them, cached for the image, so a page found
     * ahead of time (see [warm]) opens its first bubble at once.
     */
    fun found(map: InkMap, openEdge: Int, rightToLeft: Boolean, key: String? = null): List<Item> {
        synchronized(cache) {
            cache[map]?.takeIf { it.openEdge == openEdge && it.rightToLeft == rightToLeft }?.let { return it.items }
        }
        // Found in an earlier session, or ahead of time as the chapter downloaded.
        val kept = key?.let { k -> runCatching { store?.read(k) }.getOrNull() }
            ?.let { BubbleFile.decode(it, map, rightToLeft) }
        if (kept != null) {
            synchronized(cache) { cache[map] = Cached(openEdge, rightToLeft, kept.items, kept.panels) }
            return kept.items
        }
        // The whole page read first, on ML Kit's own thread, while the shapes are found here:
        // its blocks confirm each bubble - far quicker than reading every one apart - and what
        // lies outside them all is a caption.
        val pending = TextCheck.startBlocks(map, intArrayOf(0, 0, map.width, map.height))
        val candidates = BubbleDetect.findAll(map, openEdge)
        val blocks = pending?.await()
        val items = ArrayList<Item>()
        val smallBubble = SMALL_BUBBLE * map.width * map.height
        for (found in candidates) {
            val box = intArrayOf(found.x0, found.y0, found.x0 + found.width, found.y0 + found.height)
            val read = blocks?.filter { mostlyInside(it.box, box) }?.takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { it.text }
            val text = when {
                read != null -> read
                // Not read on the page: read alone, enlarged - lettering too small for the
                // page's reading, say. Only where it may well be lettering, as reading is slow.
                blocks == null || found.lettered || found.width * found.height < smallBubble ->
                    TextCheck.read(map, found)
                else -> null
            }
            val ok = if (read != null) {
                if (found.lettered) TextCheck.isLettering(read) else TextCheck.isWords(read)
            } else {
                (blocks == null || found.lettered || text != null) && accepted(text, found)
            }
            if (!ok) continue
            val readHeight = blocks?.filter { mostlyInside(it.box, box) }?.map { it.letterHeight }?.filter { it > 0f }
            items += Item(found, text, box).also { item ->
                // Joined or bold lettering measures poorly as marks: the page's reading of its
                // lines, where there is one, is the better guess then.
                if (!found.lettered || item.letterHeight <= 0f) readHeight?.maxOrNull()?.let { item.letterHeight = it }
            }
        }
        // Lettering with no bubble round it - captions on the artwork, sound effects, spiky
        // shouts the shape tests turn away: what the page's reading holds that no bubble found
        // already does.
        for (caption in captions(map, items.map { it.box }, blocks.orEmpty())) items += caption
        val gutters = BubbleDetect.gutterMap(map)
        BubbleDetect.panelsAround(map, gutters, items.map { it.box }).forEachIndexed { i, p -> items[i].panel = p }
        val ordered = BubbleDetect.readingOrder(map, items.map { it.box }, rightToLeft, gutters).map { items[it] }
        synchronized(cache) { cache[map] = Cached(openEdge, rightToLeft, ordered) }
        if (key != null) {
            runCatching { store?.write(key, BubbleFile.encode(map, rightToLeft, ordered)) }
                .onFailure { Log.w("BubbleZoom", "Couldn't keep found bubbles", it) }
        }
        return ordered
    }

    /**
     * Lettering text recognition reads on [map] outside every bubble [taken] already: blocks
     * near each other joined into one, each kept where it reads as words, as a box round it.
     */
    private fun captions(map: InkMap, taken: List<IntArray>, read: List<TextCheck.Block>): List<Item> {
        val blocks = read
            .filter { TextCheck.isWords(it.text) }
            .map { it.box.copyOf() to (it.text to it.letterHeight) }
            .toMutableList()
        // Join blocks that touch once grown by half a line - one caption the recogniser split.
        var joined = true
        while (joined) {
            joined = false
            outer@ for (i in blocks.indices) {
                for (j in i + 1 until blocks.size) {
                    val a = blocks[i].first
                    val b = blocks[j].first
                    val grow = 0.5f * min(a[3] - a[1], b[3] - b[1])
                    if (a[0] - grow < b[2] && b[0] - grow < a[2] && a[1] - grow < b[3] && b[1] - grow < a[3]) {
                        val box = intArrayOf(min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))
                        val (ta, ha) = blocks[i].second
                        val (tb, hb) = blocks[j].second
                        blocks[i] = box to ((ta + "\n" + tb) to max(ha, hb))
                        blocks.removeAt(j)
                        joined = true
                        break@outer
                    }
                }
            }
        }
        fun covered(box: IntArray) = taken.any { t ->
            val ix = max(0, min(t[2], box[2]) - max(t[0], box[0]))
            val iy = max(0, min(t[3], box[3]) - max(t[1], box[1]))
            ix.toFloat() * iy > 0.5f * (box[2] - box[0]) * (box[3] - box[1])
        }
        return blocks.filter { !covered(it.first) }.map { (b, read) ->
            val (text, letterHeight) = read
            // Room round the lettering, as a caption box would leave.
            val padX = ((b[2] - b[0]) * 0.12f).toInt() + 2
            val padY = ((b[3] - b[1]) * 0.12f).toInt() + 2
            val box = intArrayOf(
                (b[0] - padX).coerceAtLeast(0),
                (b[1] - padY).coerceAtLeast(0),
                (b[2] + padX).coerceAtMost(map.width),
                (b[3] + padY).coerceAtMost(map.height),
            )
            val w = box[2] - box[0]
            val h = box[3] - box[1]
            val found = BubbleDetect.Found(
                box[0],
                box[1],
                w,
                h,
                ByteArray(w * h) { 0xFF.toByte() },
                letterHeight = letterHeight,
            )
            Item(found, text, box)
        }
    }

    /** Whether most of [inner] lies within [outer] - a block of lettering read in a bubble. */
    private fun mostlyInside(inner: IntArray, outer: IntArray): Boolean {
        val ix = max(0, min(inner[2], outer[2]) - max(inner[0], outer[0]))
        val iy = max(0, min(inner[3], outer[3]) - max(inner[1], outer[1]))
        return ix.toFloat() * iy >= 0.5f * (inner[2] - inner[0]) * (inner[3] - inner[1])
    }

    /** A loosely found bubble this small, as a share of the page, is read alone if the page's reading missed it. */
    private const val SMALL_BUBBLE = 0.02f

    /** Whether [page]'s bubbles are all found already - nothing left to wait for. */
    fun isCached(page: ImagePage.ImageSingle): Boolean {
        var all = true
        page.forEachImage { image, _, _ ->
            val map = image.inkMap
            if (map == null || synchronized(cache) { cache[map] == null }) all = false
        }
        return all
    }

    /** Finds [page]'s bubbles ahead of time, off the main thread, for [found]'s cache. */
    fun warm(page: ImagePage.ImageSingle, rightToLeft: Boolean) {
        page.forEachImage { image, offsetX, _ ->
            val map = image.inkMap ?: return@forEachImage
            val openEdge = when {
                page !is ImagePage.ImageSpread -> BubbleDetect.NO_EDGE
                offsetX < 0f -> BubbleDetect.RIGHT_EDGE
                else -> BubbleDetect.LEFT_EDGE
            }
            runCatching { found(map, openEdge, rightToLeft, keyOf?.invoke(image)) }
        }
    }

    /**
     * A bubble missed at ([tapX], [tapY]), looked for harder after a long press there: first as
     * a tap would, then with the shape and lettering tests set aside - text recognition alone
     * deciding - and last as plain lettering with no bubble round it, from the text the
     * recogniser finds near the press. Null where all three find nothing. [stage] names which
     * found it, for the log.
     */
    fun findMissed(
        page: ImagePage.ImageSingle,
        screenWidth: Int,
        screenHeight: Int,
        tapX: Float,
        tapY: Float,
        dock: FloatArray?,
        context: Boolean,
        stage: (String) -> Unit,
    ): BubbleOverlay? {
        detect(page, screenWidth, screenHeight, tapX, tapY, dock, context)?.let {
            stage("bubble")
            return it
        }
        val hit = imageAt(page, screenWidth, screenHeight, tapX, tapY) ?: return null
        val map = hit.image.inkMap ?: return null
        val r = hit.rect
        val mx = ((tapX - r[0]) / (r[2] - r[0]) * map.width).toInt().coerceIn(0, map.width - 1)
        val my = ((tapY - r[1]) / (r[3] - r[1]) * map.height).toInt().coerceIn(0, map.height - 1)
        val loose = BubbleDetect.find(map, mx, my, BubbleDetect.NO_EDGE, letters = 0)
        if (loose != null) {
            val text = TextCheck.read(map, loose)
            if (text != null && TextCheck.isLettering(text)) {
                overlay(page, screenWidth, screenHeight, hit.image, map, r, loose, dock, context)?.let {
                    it.text = text
                    stage("loose bubble")
                    return it
                }
            }
        }
        // Lettering with no bubble: what the recogniser reads in a window round the press.
        val half = (TEXT_WINDOW * maxOf(map.width, map.height)).toInt()
        val window = intArrayOf(
            (mx - half).coerceAtLeast(0),
            (my - half).coerceAtLeast(0),
            (mx + half).coerceAtMost(map.width),
            (my + half).coerceAtMost(map.height),
        )
        val blocks = TextCheck.blocks(map, window)
        val block = blocks.minByOrNull { b ->
            val dx = maxOf(b.box[0] - mx, 0, mx - b.box[2])
            val dy = maxOf(b.box[1] - my, 0, my - b.box[3])
            dx * dx + dy * dy
        } ?: return null
        // Room round the lettering, as a caption box would leave.
        val padX = ((block.box[2] - block.box[0]) * 0.12f).toInt() + 2
        val padY = ((block.box[3] - block.box[1]) * 0.12f).toInt() + 2
        val sx = (r[2] - r[0]) / map.width
        val sy = (r[3] - r[1]) / map.height
        val rect = floatArrayOf(
            r[0] + (block.box[0] - padX) * sx,
            r[1] + (block.box[1] - padY) * sy,
            r[0] + (block.box[2] + padX) * sx,
            r[1] + (block.box[3] + padY) * sy,
        )
        return manual(page, screenWidth, screenHeight, rect, dock, context)?.also {
            it.text = block.text
            it.manual = false
            stage("text")
        }
    }

    /** Half the side of the window searched for lettering round a long press, as a share of the page. */
    private const val TEXT_WINDOW = 0.18f

    /**
     * Where a bubble at [added] (bounds on screen) goes among [existing] ones in reading order,
     * as found on [page]'s first image - so one found later slots in where the finder would
     * have put it.
     */
    fun orderIndex(
        page: ImagePage.ImageSingle,
        screenWidth: Int,
        screenHeight: Int,
        existing: List<FloatArray>,
        added: FloatArray,
        rightToLeft: Boolean,
    ): Int {
        var map: InkMap? = null
        var rect: FloatArray? = null
        page.forEachImage { image, offsetX, imageScale ->
            if (map != null) return@forEachImage
            val m = image.inkMap ?: return@forEachImage
            map = m
            val placeX = (page.x + offsetX / screenWidth + WebGpuRenderer.offsetX) / imageScale -
                WebGpuRenderer.offsetX
            val placeY = (page.y + WebGpuRenderer.offsetY) / imageScale - WebGpuRenderer.offsetY
            rect = image.placement(screenWidth, screenHeight, placeX, placeY, page.scale * imageScale)
        }
        val m = map ?: return existing.size
        val r = rect ?: return existing.size
        fun box(b: FloatArray) = intArrayOf(
            ((b[0] - r[0]) / (r[2] - r[0]) * m.width).toInt(),
            ((b[1] - r[1]) / (r[3] - r[1]) * m.height).toInt(),
            ((b[2] - r[0]) / (r[2] - r[0]) * m.width).toInt(),
            ((b[3] - r[1]) / (r[3] - r[1]) * m.height).toInt(),
        )
        val boxes = existing.map(::box) + listOf(box(added))
        val order = BubbleDetect.readingOrder(m, boxes, rightToLeft)
        // As many of the existing as read before it.
        return order.indexOf(existing.size).coerceAtLeast(0).let { at -> order.take(at).count { it < existing.size } }
    }

    /**
     * Draws the band below the page as a view of it: [camera] is (centre x, centre y, zoom,
     * band top, band bottom) - the page point at the band's centre, normalised at rest, how much
     * larger it is shown, and the band itself. Clipped to the band, over the frame's own page.
     */
    fun drawCamera(encoder: GPUCommandEncoder, dst: GPUTexture, page: ImagePage.ImageSingle, camera: FloatArray) {
        if (page.destroyed || page.scale <= 0f) return
        val w = dst.width
        val h = dst.height
        val top = (camera[3] * h).toInt().coerceIn(0, h)
        val bottom = (camera[4] * h).toInt().coerceIn(top, h)
        if (bottom <= top) return
        Draw.rect(encoder, dst, 0f, camera[3], 1f, camera[4], 0xFF000000.toInt())
        val zoom = camera[2]
        val s0 = page.scale
        // The rest point (cx, cy) lands at the band's centre: solved per axis, as drawCrop does
        // for a target the screen's own size.
        val qx = 0.5f * w
        val qy = (top + bottom) * 0.5f
        val dx = (qx - zoom * camera[0] * w - 0.5f * w + 0.5f * zoom * w) / (s0 * zoom * w)
        val dy = (qy - zoom * camera[1] * h - 0.5f * h + 0.5f * zoom * h) / (s0 * zoom * h)
        val view = dst.createView()
        val pass = encoder.beginRenderPass(
            GPURenderPassDescriptor(
                colorAttachments = arrayOf(
                    GPURenderPassColorAttachment(
                        view = view,
                        loadOp = LoadOp.Load,
                        storeOp = StoreOp.Store,
                        clearValue = GPUColor(0.0, 0.0, 0.0, 0.0),
                    ),
                ),
            ),
        )
        try {
            pass.setScissorRect(0, top, w, bottom - top)
            page.renderPage(pass, dst, dx, dy, zoom, linear = true, masked = false)
        } finally {
            pass.endAndRelease(view)
        }
    }

    /** As [detect]'s tail: the bubble [found] on [image], placed at [imageRect], as an overlay. */
    private fun overlay(
        page: ImagePage.ImageSingle,
        screenWidth: Int,
        screenHeight: Int,
        image: Image,
        map: InkMap,
        imageRect: FloatArray,
        found: BubbleDetect.Found,
        /**
         * The band of screen below the page, top and bottom normalised, where a bubble opens
         * enlarged instead of over the page - the page stays readable above it. Null, or a
         * bubble that wouldn't grow there, opens in place as before.
         */
        dock: FloatArray? = null,
        context: Boolean = false,
    ): BubbleOverlay? {
        val sx = (imageRect[2] - imageRect[0]) / map.width
        val sy = (imageRect[3] - imageRect[1]) / map.height
        val rect = floatArrayOf(
            imageRect[0] + found.x0 * sx,
            imageRect[1] + found.y0 * sy,
            imageRect[0] + (found.x0 + found.width) * sx,
            imageRect[1] + (found.y0 + found.height) * sy,
        )
        return place(
            page,
            screenWidth,
            screenHeight,
            rect,
            imageRect,
            screenPerImagePixel = (imageRect[2] - imageRect[0]) * screenWidth / image.width,
            mask = found.mask,
            maskWidth = found.width,
            maskHeight = found.height,
            dock = dock,
            context = context,
        )
    }

    /**
     * Whether a bubble [found] with [text] read in it is kept: one that passed the full
     * lettering test needs the recogniser not to find nothing, where it ran; one that passed
     * only the loose test needs it to have run and read a few letters.
     */
    private fun accepted(text: String?, found: BubbleDetect.Found): Boolean =
        if (found.lettered) text == null || TextCheck.isLettering(text) else text != null && TextCheck.isWords(text)

    /**
     * A bubble at [rect] on screen, drawn by hand in debug mode - for one the finder missed, or
     * lettering with no bubble round it. Opened as a found one is, its whole box enlarged.
     */
    fun manual(
        page: ImagePage.ImageSingle,
        screenWidth: Int,
        screenHeight: Int,
        rect: FloatArray,
        dock: FloatArray? = null,
        context: Boolean = false,
    ): BubbleOverlay? {
        val hit = imageAt(page, screenWidth, screenHeight, (rect[0] + rect[2]) * 0.5f, (rect[1] + rect[3]) * 0.5f)
            ?: return null
        val side = 16
        return place(
            page,
            screenWidth,
            screenHeight,
            rect,
            hit.rect,
            screenPerImagePixel = (hit.rect[2] - hit.rect[0]) * screenWidth / hit.image.width,
            mask = ByteArray(side * side) { 0xFF.toByte() },
            maskWidth = side,
            maskHeight = side,
            dock = dock,
            context = context,
            minZoom = 1f,
        )?.also { it.manual = true }
    }

    /**
     * Where the bubble at [rect] on screen opens, and how large: in [dock] below the page where
     * there is one and it grows there - across and down from the middle as far as it sat from
     * the page's middle, so where it came from still shows - else in place, pushed on screen.
     */
    private fun place(
        page: ImagePage.ImageSingle,
        screenWidth: Int,
        screenHeight: Int,
        rect: FloatArray,
        imageRect: FloatArray,
        screenPerImagePixel: Float,
        mask: ByteArray,
        maskWidth: Int,
        maskHeight: Int,
        dock: FloatArray?,
        context: Boolean = false,
        minZoom: Float = MIN_ZOOM,
    ): BubbleOverlay? {
        val bubbleWidth = (rect[2] - rect[0]) * screenWidth
        val bubbleHeight = (rect[3] - rect[1]) * screenHeight
        if (bubbleWidth <= 0f || bubbleHeight <= 0f) return null

        // Pushed back inside [from, to] by its half size and a margin - centred when it can't fit.
        fun settle(center: Float, halfSize: Float, margin: Float, from: Float = 0f, to: Float = 1f): Float =
            if (halfSize + margin >= (to - from) * 0.5f) {
                (from + to) * 0.5f
            } else {
                center.coerceIn(from + halfSize + margin, to - halfSize - margin)
            }

        if (dock != null) {
            val dockHeight = (dock[1] - dock[0]) * screenHeight
            val docked = min(
                DOCK_MAX_ZOOM,
                min(DOCK_FIT_WIDTH * screenWidth / bubbleWidth, DOCK_FIT_HEIGHT * dockHeight / bubbleHeight),
            )
            if (docked >= DOCK_MIN_ZOOM && context) {
                // The bubble at that zoom, with as much of the artwork round it as fills the
                // band - the window kept on the page, so it shows page rather than margin.
                val halfW = DOCK_FIT_WIDTH * 0.5f / docked
                val halfH = DOCK_FIT_HEIGHT * 0.5f * (dock[1] - dock[0]) / docked
                fun window(center: Float, half: Float, from: Float, to: Float): Pair<Float, Float> {
                    if (2 * half >= to - from) return from to to
                    val c = center.coerceIn(from + half, to - half)
                    return (c - half) to (c + half)
                }
                val (x0, x1) = window((rect[0] + rect[2]) * 0.5f, halfW, imageRect[0], imageRect[2])
                val (y0, y1) = window((rect[1] + rect[3]) * 0.5f, halfH, imageRect[1], imageRect[3])
                val wide = floatArrayOf(min(x0, rect[0]), min(y0, rect[1]), max(x1, rect[2]), max(y1, rect[3]))
                val side = 16
                return BubbleOverlay(
                    screenPerImagePixel = screenPerImagePixel,
                    page = page,
                    mask = ByteArray(side * side) { 0xFF.toByte() },
                    maskWidth = side,
                    maskHeight = side,
                    rect = wide,
                    targetX = settle((wide[0] + wide[2]) * 0.5f, docked * (wide[2] - wide[0]) * 0.5f, DOCK_MARGIN),
                    targetY = settle(
                        (dock[0] + dock[1]) * 0.5f,
                        docked * (wide[3] - wide[1]) * 0.5f,
                        DOCK_MARGIN,
                        dock[0],
                        dock[1],
                    ),
                    zoom = docked,
                    dim = 0f,
                    bounds = rect,
                    context = true,
                    shape = mask,
                    shapeWidth = maskWidth,
                    shapeHeight = maskHeight,
                )
            }
            if (docked >= DOCK_MIN_ZOOM) {
                // Down the band as far down the page as it sat.
                val pageTop = imageRect[1].coerceAtLeast(0f)
                val pageBottom = imageRect[3].coerceAtMost(1f)
                val down = if (pageBottom > pageTop) {
                    (((rect[1] + rect[3]) * 0.5f - pageTop) / (pageBottom - pageTop)).coerceIn(0f, 1f)
                } else {
                    0.5f
                }
                return BubbleOverlay(
                    screenPerImagePixel = screenPerImagePixel,
                    page = page,
                    mask = mask,
                    maskWidth = maskWidth,
                    maskHeight = maskHeight,
                    rect = rect,
                    targetX = settle((rect[0] + rect[2]) * 0.5f, docked * (rect[2] - rect[0]) * 0.5f, DOCK_MARGIN),
                    targetY = settle(
                        dock[0] + down * (dock[1] - dock[0]),
                        docked * (rect[3] - rect[1]) * 0.5f,
                        DOCK_MARGIN,
                        dock[0],
                        dock[1],
                    ),
                    zoom = docked,
                    dim = 0f,
                )
            }
        }
        val zoom = min(
            MAX_ZOOM,
            min(FIT_WIDTH * screenWidth / bubbleWidth, FIT_HEIGHT * screenHeight / bubbleHeight),
        )
        if (zoom < minZoom) return null

        return BubbleOverlay(
            screenPerImagePixel = screenPerImagePixel,
            page = page,
            mask = mask,
            maskWidth = maskWidth,
            maskHeight = maskHeight,
            rect = rect,
            targetX = settle((rect[0] + rect[2]) * 0.5f, zoom * (rect[2] - rect[0]) * 0.5f, 0.03f),
            targetY = settle((rect[1] + rect[3]) * 0.5f, zoom * (rect[3] - rect[1]) * 0.5f, 0.05f),
            zoom = max(zoom, 1f),
        )
    }

    /**
     * Where [rect] on screen lies on [page]'s images: which one, by [ImagePage.forEachImage]'s
     * order, and its bounds as a share of that image (u0, v0, u1, v1). For the debug log.
     */
    fun onImage(
        page: ImagePage.ImageSingle,
        screenWidth: Int,
        screenHeight: Int,
        rect: FloatArray,
    ): Pair<Int, FloatArray>? {
        val hit = imageAt(page, screenWidth, screenHeight, (rect[0] + rect[2]) * 0.5f, (rect[1] + rect[3]) * 0.5f)
            ?: return null
        var index = -1
        var k = 0
        page.forEachImage { image, _, _ ->
            if (image === hit.image) index = k
            k++
        }
        val r = hit.rect
        fun u(x: Float) = (x - r[0]) / (r[2] - r[0])
        fun v(y: Float) = (y - r[1]) / (r[3] - r[1])
        return index to floatArrayOf(u(rect[0]), v(rect[1]), u(rect[2]), v(rect[3]))
    }

    /** One of [page]'s images and where it sits on screen - see [imageAt]. */
    class Hit(val image: Image, val rect: FloatArray, val offsetX: Float)

    /**
     * The image of [page] under the tap at ([tapX], [tapY]), normalised screen coordinates on a
     * [screenWidth] x [screenHeight] surface, with its placement there; null off every image.
     */
    fun imageAt(
        page: ImagePage.ImageSingle,
        screenWidth: Int,
        screenHeight: Int,
        tapX: Float,
        tapY: Float,
    ): Hit? {
        var hit: Hit? = null
        page.forEachImage { image, offsetX, imageScale ->
            if (hit != null) return@forEachImage
            // As ImageSingle.forEachPlacedImage, at the page's own transform.
            val placeX = (page.x + offsetX / screenWidth + WebGpuRenderer.offsetX) / imageScale -
                WebGpuRenderer.offsetX
            val placeY = (page.y + WebGpuRenderer.offsetY) / imageScale - WebGpuRenderer.offsetY
            val rect = image.placement(
                screenWidth,
                screenHeight,
                placeX,
                placeY,
                page.scale * imageScale,
            )
            if (tapX >= rect[0] && tapX < rect[2] && tapY >= rect[1] && tapY < rect[3]) {
                hit = Hit(image, rect, offsetX)
            }
        }
        return hit
    }

    /** The bounds on screen of [page]'s images where they sit now, together, normalised. */
    fun pageRect(page: ImagePage.ImageSingle, screenWidth: Int, screenHeight: Int): FloatArray? {
        var out: FloatArray? = null
        page.forEachImage { image, offsetX, imageScale ->
            val placeX = (page.x + offsetX / screenWidth + WebGpuRenderer.offsetX) / imageScale -
                WebGpuRenderer.offsetX
            val placeY = (page.y + WebGpuRenderer.offsetY) / imageScale - WebGpuRenderer.offsetY
            val r = image.placement(screenWidth, screenHeight, placeX, placeY, page.scale * imageScale)
            val o = out
            out = if (o == null) {
                r.copyOf()
            } else {
                floatArrayOf(min(o[0], r[0]), min(o[1], r[1]), max(o[2], r[2]), max(o[3], r[3]))
            }
        }
        return out
    }

    /**
     * The lowest edge on screen of [page]'s images where they sit now, normalised - where the
     * room below the page starts. Null before any image is placed.
     */
    fun pageBottom(page: ImagePage.ImageSingle, screenWidth: Int, screenHeight: Int): Float? {
        var bottom: Float? = null
        page.forEachImage { image, offsetX, imageScale ->
            val placeX = (page.x + offsetX / screenWidth + WebGpuRenderer.offsetX) / imageScale -
                WebGpuRenderer.offsetX
            val placeY = (page.y + WebGpuRenderer.offsetY) / imageScale - WebGpuRenderer.offsetY
            val rect = image.placement(screenWidth, screenHeight, placeX, placeY, page.scale * imageScale)
            bottom = max(bottom ?: rect[3], rect[3])
        }
        return bottom
    }

    // Render thread only, from here down.

    private const val UNIFORM_SIZE = 64

    /** How long after the reader opens to warm up - past its first page's decode. */
    private const val WARM_UP_DELAY_MS = 1500L

    /** How long to give ArtCNN's background compile before finishing it in the warm-up. */
    private const val ASYNC_COMPILE_TIMEOUT_MS = 4000L

    /** Largest side of what ArtCNN is given, halo included - keeps its feature maps small. */
    private const val MAX_INPUT = 512

    /**
     * ArtCNN's input side is rounded up to this, so bubbles of about the same size reuse its
     * working textures instead of allocating new ones - see [enlarge].
     */
    private const val INPUT_STEP = 64

    /** How long a bubble waits for its enlargement to finish on the GPU before it opens anyway. */
    private const val GPU_WAIT_TIMEOUT_MS = 1500L

    /** Largest side of the enlarged bubble when ArtCNN can't run and it is drawn directly. */
    private const val MAX_DIRECT = 2048

    private val sampler by lazy {
        WebGpuRenderer.device.createSampler(
            GPUSamplerDescriptor(magFilter = FilterMode.Linear, minFilter = FilterMode.Linear),
        )
    }

    private val byteBuffer = ByteBuffer.allocateDirect(UNIFORM_SIZE).order(ByteOrder.nativeOrder())

    /**
     * Its own instance, not the tile renderer's: that one runs on the tile worker. Kept for the
     * session, so its compiled network outlives any one bubble - see [prewarm].
     */
    private val artCnn by lazy { UpscalerArtCnn() }

    /** Whether [warmUp] has run its tiny pass of ArtCNN's whole path. */
    private var warmed = false

    /**
     * Runs [block] while handing Dawn's events their callbacks: an `...AndAwait` call resolves
     * only when something processes events, and between frames nothing else does - unpumped,
     * ArtCNN's background compile never finished and the warm-up compiled it all over again,
     * holding the render thread for a second or two.
     */
    private suspend fun <R> pumped(block: suspend () -> R): R = coroutineScope {
        val pump = launch {
            while (isActive) {
                WebGpuRenderer.instance.processEvents()
                delay(1)
            }
        }
        try {
            block()
        } finally {
            pump.cancel()
        }
    }

    /**
     * Readies everything the first bubble needs, shortly after the reader opens: ArtCNN's network
     * compiled in the background where the device allows it, then one tiny run of the whole path
     * - its textures, its resolve pipeline, the first dispatch a driver compiles lazily, and this
     * overlay's own pipeline. Whatever of that still blocks then lands while a page is being
     * read, not after a tap.
     */
    suspend fun prewarm() {
        delay(WARM_UP_DELAY_MS)
        withTimeoutOrNull(ASYNC_COMPILE_TIMEOUT_MS) { pumped { artCnn.prewarm() } }
        runCatching { WebGpuRenderer.withContext { device -> warmUp(device) } }
            .onFailure { Log.w("BubbleZoom", "Warm-up failed", it) }
    }

    private fun warmUp(device: GPUDevice) {
        val format = Hdr.frameFormat
        pipelines[format]
        val art = artCnn
        if (!art.supported || warmed) return
        val side = 64
        val input = art.input(side, format) ?: return
        val inputView = art.inputView ?: return
        val encoder = device.createCommandEncoder()
        clearedPass(encoder, inputView) {}
        art.encode(encoder, side)
        val out = texture((side - 2 * art.halo) * art.factor, format)
        val outView = out.createView()
        clearedPass(encoder, outView) { pass -> art.resolve(pass, format) }
        device.queue.submitAndRelease(encoder)
        // Freed once the GPU is done with it - only the compiling and first dispatches mattered.
        outView.close()
        out.destroyAndRelease()
        warmed = true
    }

    /**
     * Enlarges [bubble] before its first frame, on the render thread between frames, so the
     * opening animation runs smoothly from its first step instead of stalling a few frames in.
     */
    fun prepare(bubble: BubbleOverlay, screenWidth: Int, screenHeight: Int) {
        if (bubble.released || bubble.sourceView != null) return
        val device = WebGpuRenderer.device
        val format = Hdr.frameFormat
        val encoder = device.createCommandEncoder()
        enlarge(encoder, screenWidth, screenHeight, format, bubble)
        device.queue.submitAndRelease(encoder)
        bubble.maskView()
        pipelines[format]
    }

    /**
     * Waits, off the render lock, for the GPU to finish the enlargement [prepare] submitted. The
     * network takes a few hundred milliseconds on some phones: opened straight away, the
     * animation's frames queued behind it and it froze for that long just short of full size.
     */
    suspend fun awaitPrepared() {
        withTimeoutOrNull(GPU_WAIT_TIMEOUT_MS) {
            WebGpuRenderer.onDispatcher { device -> pumped { device.queue.onSubmittedWorkDone() } }
        }
    }

    private val pipelines = FormatKeyed { format ->
        val device = WebGpuRenderer.device
        val module = device.createShaderModule(
            GPUShaderModuleDescriptor(shaderSourceWGSL = GPUShaderSourceWGSL(SHADER)),
        )
        // Premultiplied out, like the page it is drawn over.
        val over = GPUBlendComponent(
            srcFactor = BlendFactor.One,
            dstFactor = BlendFactor.OneMinusSrcAlpha,
            operation = BlendOperation.Add,
        )
        device.createRenderPipeline(
            GPURenderPipelineDescriptor(
                vertex = GPUVertexState(module, entryPoint = "vs_main"),
                fragment = GPUFragmentState(
                    module,
                    entryPoint = "fs_main",
                    targets = arrayOf(
                        GPUColorTargetState(
                            format = format,
                            blend = GPUBlendState(color = over, alpha = over),
                        ),
                    ),
                ),
                primitive = GPUPrimitiveState(topology = TriangleList),
            ),
        )
    }

    private fun texture(side: Int, format: Int): GPUTexture =
        WebGpuRenderer.device.createTexture(
            GPUTextureDescriptor(
                size = GPUExtent3D(side, side),
                format = format,
                usage = TextureUsage.RenderAttachment or TextureUsage.TextureBinding,
            ),
        )

    /** A cleared pass over [view], for [block] to draw into. */
    private fun clearedPass(
        encoder: GPUCommandEncoder,
        view: GPUTextureView,
        block: (GPURenderPassEncoder) -> Unit,
    ) {
        val pass = encoder.beginRenderPass(
            GPURenderPassDescriptor(
                colorAttachments = arrayOf(
                    GPURenderPassColorAttachment(
                        view = view,
                        loadOp = LoadOp.Clear,
                        storeOp = StoreOp.Store,
                        clearValue = GPUColor(0.0, 0.0, 0.0, 0.0),
                    ),
                ),
            ),
        )
        try {
            block(pass)
        } finally {
            pass.endAndRelease()
        }
    }

    /**
     * Draws [page] into [dst] ([side] square) at [scale] times its size at rest, with the
     * bubble's top-left corner at pixel ([inset], [inset]). renderPage places the page at
     * `page.x + x` in units of the target's own size, so the offset is solved for that size.
     */
    private fun drawCrop(
        pass: GPURenderPassEncoder,
        dst: GPUTexture,
        side: Int,
        page: ImagePage.ImageSingle,
        bubble: BubbleOverlay,
        screenWidth: Int,
        screenHeight: Int,
        scale: Float,
        inset: Int,
    ) {
        val s0 = page.scale
        fun offset(restPx: Float, screen: Int, pagePos: Float, globalOffset: Float): Float =
            (
                inset - scale * restPx - 0.5f * side + 0.5f * scale * screen +
                    s0 * scale * (pagePos + globalOffset) * (screen - side)
                ) / (s0 * scale * side)
        val dx = offset(bubble.rect[0] * screenWidth, screenWidth, page.x, WebGpuRenderer.offsetX)
        val dy = offset(bubble.rect[1] * screenHeight, screenHeight, page.y, WebGpuRenderer.offsetY)
        page.renderPage(pass, dst, dx, dy, scale, linear = true, masked = false)
    }

    /**
     * The bubble's part of the page, enlarged once for as long as it is open: drawn at the scan's
     * own resolution where the zoom asks for more than that, then doubled by ArtCNN - the network
     * the reader already uses to sharpen zoomed line art - so lettering gains real edges rather
     * than blur. Where ArtCNN can't run, the page is simply drawn at the full zoom.
     */
    private fun enlarge(
        encoder: GPUCommandEncoder,
        w: Int,
        h: Int,
        format: Int,
        bubble: BubbleOverlay,
    ): GPUTextureView? {
        bubble.sourceView?.let { return it }
        val page = bubble.page
        val bubbleSide = max((bubble.rect[2] - bubble.rect[0]) * w, (bubble.rect[3] - bubble.rect[1]) * h)
        if (bubbleSide < 1f) return null

        val art = artCnn
        // A wide window of artwork is too big for ArtCNN's input at a useful scale: drawn
        // directly at the full zoom instead, as the page itself is.
        if (art.supported && bubbleSide * bubble.zoom / art.factor <= MAX_INPUT) {
            val halo = art.halo
            // Native resolution where the zoom reaches past it, and never less than half the zoom
            // - ArtCNN doubles - but small enough to keep its working textures modest.
            val native = 1f / bubble.screenPerImagePixel
            var scale = native.coerceIn(bubble.zoom / art.factor, bubble.zoom)
            scale = min(scale, (MAX_INPUT - 2 * halo) / bubbleSide)
            // Rounded up, and so its working textures - kept from one bubble to the next - are
            // reallocated only when a bubble needs a different size of them.
            val side = min(
                MAX_INPUT,
                ((bubbleSide * scale + 2 * halo).toInt() + INPUT_STEP - 1) / INPUT_STEP * INPUT_STEP,
            )
            val input = art.input(side, format)
            val inputView = art.inputView
            if (input != null && inputView != null) {
                clearedPass(encoder, inputView) { pass ->
                    drawCrop(pass, input, side, page, bubble, w, h, scale, halo)
                }
                art.encode(encoder, side)
                if (art.supported) {
                    val outSide = (side - 2 * halo) * art.factor
                    val out = texture(outSide, format)
                    val outView = out.createView()
                    clearedPass(encoder, outView) { pass -> art.resolve(pass, format) }
                    bubble.source = out
                    bubble.sourceView = outView
                    bubble.sourceScale = scale * art.factor
                    return outView
                }
            }
        }

        val scale = min(bubble.zoom, MAX_DIRECT / bubbleSide)
        val side = (bubbleSide * scale).toInt().coerceAtLeast(1)
        val out = texture(side, format)
        val outView = out.createView()
        clearedPass(encoder, outView) { pass -> drawCrop(pass, out, side, page, bubble, w, h, scale, 0) }
        bubble.source = out
        bubble.sourceView = outView
        bubble.sourceScale = scale
        return outView
    }

    /**
     * Draws [bubble] over [dst], which already holds its page at rest: the page dimmed, then the
     * bubble growing from where it sits to where it opens, cleaned up, with a shadow under it.
     */
    fun draw(encoder: GPUCommandEncoder, dst: GPUTexture, bubble: BubbleOverlay, progress: Float) {
        if (bubble.released || progress <= 0f) return
        val page = bubble.page
        if (page.destroyed || page.scale <= 0f) return
        val source = enlarge(encoder, dst.width, dst.height, dst.format, bubble) ?: return
        val sourceSide = bubble.source?.width ?: return

        val t = progress.coerceAtLeast(0f)
        val zoom = 1f + (bubble.zoom - 1f) * t
        val cx = bubble.centerX + (bubble.targetX - bubble.centerX) * t
        val cy = bubble.centerY + (bubble.targetY - bubble.centerY) * t

        if (bubble.dim > 0f) {
            Draw.rect(encoder, dst, 0f, 0f, 1f, 1f, ((bubble.dim * t.coerceAtMost(1f) * 255).toInt() shl 24))
        }

        byteBuffer.clear()
        byteBuffer.putFloat(bubble.rect[0])
        byteBuffer.putFloat(bubble.rect[1])
        byteBuffer.putFloat(bubble.rect[2])
        byteBuffer.putFloat(bubble.rect[3])
        byteBuffer.putFloat(bubble.centerX)
        byteBuffer.putFloat(bubble.centerY)
        byteBuffer.putFloat(cx)
        byteBuffer.putFloat(cy)
        byteBuffer.putFloat(zoom)
        byteBuffer.putFloat(t.coerceAtMost(1f))
        byteBuffer.putFloat(dst.width.toFloat() / dst.height)
        byteBuffer.putFloat(bubble.sourceScale)
        byteBuffer.putFloat(dst.width.toFloat())
        byteBuffer.putFloat(dst.height.toFloat())
        byteBuffer.putFloat(sourceSide.toFloat())
        // How hard lettering is cleaned up: eased off where artwork shows round it.
        byteBuffer.putFloat(if (bubble.context) 0.15f else 0.6f)
        byteBuffer.flip()
        val device = WebGpuRenderer.device
        val uniforms = device.createBuffer(
            GPUBufferDescriptor(
                size = UNIFORM_SIZE.toLong(),
                usage = BufferUsage.Uniform or BufferUsage.CopyDst,
            ),
        )
        device.queue.writeBuffer(uniforms, 0, byteBuffer)

        val dstView = dst.createView()
        val pass = encoder.beginRenderPass(
            GPURenderPassDescriptor(
                colorAttachments = arrayOf(
                    GPURenderPassColorAttachment(
                        view = dstView,
                        loadOp = LoadOp.Load,
                        storeOp = StoreOp.Store,
                        clearValue = GPUColor(0.0, 0.0, 0.0, 0.0),
                    ),
                ),
            ),
        )
        try {
            val pipeline = pipelines[dst.format]
            pass.setPipeline(pipeline)
            pass.setTransientBindGroup(
                0,
                device.createBindGroup(
                    GPUBindGroupDescriptor(
                        layout = pipeline.groupLayout(),
                        entries = arrayOf(
                            GPUBindGroupEntry(0, buffer = uniforms),
                            GPUBindGroupEntry(1, textureView = source),
                            GPUBindGroupEntry(2, textureView = bubble.maskView()),
                            GPUBindGroupEntry(3, sampler = sampler),
                        ),
                    ),
                ),
            )
            pass.draw(6)
        } finally {
            pass.endAndRelease(dstView)
            uniforms.close()
        }
    }

    private const val SHADER = """
struct Uniforms {
    // The bubble's bounds on screen at rest.
    rect: vec4<f32>,
    // Its centre at rest, and where that centre is now.
    center: vec4<f32>,
    // Zoom now, how open (0-1), surface width over height, source pixels per screen pixel.
    params: vec4<f32>,
    // Surface width and height in pixels, the source's side, how hard to clean it up.
    size: vec4<f32>,
}

@group(0) @binding(0) var<uniform> u: Uniforms;
@group(0) @binding(1) var source: texture_2d<f32>;
@group(0) @binding(2) var shape: texture_2d<f32>;
@group(0) @binding(3) var samp: sampler;

struct VertexOutput {
    @builtin(position) position: vec4<f32>,
    @location(0) uv: vec2<f32>,
}

@vertex
fn vs_main(@builtin(vertex_index) vertex_index: u32) -> VertexOutput {
    var corners = array<vec2<f32>, 6>(
        vec2<f32>(0.0, 0.0),
        vec2<f32>(0.0, 1.0),
        vec2<f32>(1.0, 0.0),
        vec2<f32>(1.0, 0.0),
        vec2<f32>(0.0, 1.0),
        vec2<f32>(1.0, 1.0)
    );
    let pos = corners[vertex_index];
    var out: VertexOutput;
    out.position = vec4<f32>(pos.x * 2.0 - 1.0, 1.0 - pos.y * 2.0, 0.0, 1.0);
    out.uv = pos;
    return out;
}

/// Screen point [uv] traced back through the zoom to the page at rest, where the bubble was found.
fn at_rest(uv: vec2<f32>) -> vec2<f32> {
    return u.center.xy + (uv - u.center.zw) / u.params.x;
}

/// How much of the bubble covers screen point [uv]. Sharpened, since the shape is far coarser
/// than the screen.
fn coverage(uv: vec2<f32>) -> f32 {
    let m = (at_rest(uv) - u.rect.xy) / (u.rect.zw - u.rect.xy);
    if (m.x < 0.0 || m.y < 0.0 || m.x > 1.0 || m.y > 1.0) { return 0.0; }
    return smoothstep(0.4, 0.6, textureSampleLevel(shape, samp, m, 0.0).r);
}

fn luma(c: vec3<f32>) -> f32 { return dot(c, vec3<f32>(0.2126, 0.7152, 0.0722)); }

/// The enlarged page at screen point [uv], cleaned up the way lettering wants: a light unsharp
/// mask for crisp strokes, then paper nudged toward white and ink toward black - partly, so
/// lettering keeps its anti-aliasing, and only where the page is grey, so colour is left alone.
fn page_at(uv: vec2<f32>) -> vec4<f32> {
    let px = (at_rest(uv) - u.rect.xy) * u.size.xy * u.params.w;
    let st = px / u.size.z;
    let d = 1.0 / u.size.z;
    let c = textureSampleLevel(source, samp, st, 0.0);
    let around = (textureSampleLevel(source, samp, st + vec2<f32>(d, 0.0), 0.0) +
        textureSampleLevel(source, samp, st - vec2<f32>(d, 0.0), 0.0) +
        textureSampleLevel(source, samp, st + vec2<f32>(0.0, d), 0.0) +
        textureSampleLevel(source, samp, st - vec2<f32>(0.0, d), 0.0)) * 0.25;
    if (c.a <= 0.0) { return c; }
    // Premultiplied in, straight for the grading.
    var rgb = clamp((c.rgb + 0.15 * (c.rgb - around.rgb)) / c.a, vec3<f32>(0.0), vec3<f32>(1.0));
    let y = luma(rgb);
    let chroma = max(max(rgb.r, rgb.g), rgb.b) - min(min(rgb.r, rgb.g), rgb.b);
    let grey = 1.0 - smoothstep(0.06, 0.18, chroma);
    let graded = vec3<f32>(smoothstep(0.06, 0.94, y));
    rgb = mix(rgb, graded, u.size.w * grey);
    return vec4<f32>(rgb * c.a, c.a);
}

@fragment
fn fs_main(in: VertexOutput) -> @location(0) vec4<f32> {
    let open = u.params.y;
    let cover = coverage(in.uv);

    // A soft shadow below the bubble, lengthening as it lifts.
    let drop = vec2<f32>(0.0, 0.012 * open);
    let spread = vec2<f32>(0.01, 0.01 * u.params.z) * open;
    var shadow = 0.0;
    shadow += coverage(in.uv - drop);
    shadow += coverage(in.uv - drop + vec2<f32>(spread.x, 0.0));
    shadow += coverage(in.uv - drop - vec2<f32>(spread.x, 0.0));
    shadow += coverage(in.uv - drop + vec2<f32>(0.0, spread.y));
    shadow += coverage(in.uv - drop - vec2<f32>(0.0, spread.y));
    let shadow_alpha = 0.5 * open * shadow / 5.0;

    var front = vec4<f32>(0.0);
    if (cover > 0.0) { front = page_at(in.uv) * cover; }
    let alpha = front.a + (1.0 - front.a) * shadow_alpha;
    if (alpha <= 0.001) { discard; }
    return vec4<f32>(front.rgb, alpha);
}
"""
}
