package ca.mpreg.webgpuviewer.viewer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import ca.mpreg.webgpuviewer.renderer.InkMap
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// KMK -->
/**
 * The page's panels as a trained detector sees them: YOLO26n trained on Manga109-s
 * (assets/panel-yolo26n.onnx, see its NOTICE), run with ONNX Runtime on the page's [InkMap].
 * On the pages Dave corrected it matched his panels at 0.90 against the gutter cuts' 0.81 -
 * borderless panels, bleeds and bubbles across gutters included - but gives boxes; see
 * [BubbleDetect.panelShapesFrom] for how the two are put together.
 */
object PanelModel {
    private const val ASSET = "panel-yolo26n.onnx"

    /** The model's input: a square this many pixels a side, the page letterboxed into it. */
    private const val SIZE = 640

    /** The letterbox's padding, as the model was trained with. */
    private const val PAD = 114f / 255f

    /** A frame the model is at least this sure of - its scores were all but certain or nothing. */
    private const val MIN_SCORE = 0.25f

    /** The model's class for a panel frame; it reports others too. */
    private const val FRAME = 0

    @Volatile private var context: Context? = null
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var failed = false

    /** Where the model is read from; the session itself is made the first time it's needed. */
    fun init(context: Context) {
        if (this.context == null) this.context = context.applicationContext
    }

    @Synchronized
    private fun session(): OrtSession? {
        session?.let { return it }
        if (failed) return null
        val ctx = context ?: return null
        return runCatching {
            val bytes = ctx.assets.open(ASSET).use { it.readBytes() }
            val e = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            env = e
            e.createSession(bytes, options).also { session = it }
        }.onFailure {
            failed = true
            Log.w("PanelModel", "Panel detector unavailable", it)
        }.getOrNull()
    }

    /**
     * The frames on [map], as boxes (x0, y0, x1, y1) in its pixels, most sure first; null where
     * the model can't run - the gutter cuts alone serve then.
     */
    @Synchronized
    fun frames(map: InkMap): List<FloatArray>? {
        val s = session() ?: return null
        val e = env ?: return null
        return runCatching {
            val w = map.width
            val h = map.height
            val scale = SIZE.toFloat() / max(w, h)
            val nw = (w * scale).roundToInt().coerceIn(1, SIZE)
            val nh = (h * scale).roundToInt().coerceIn(1, SIZE)
            val px = (SIZE - nw) / 2
            val py = (SIZE - nh) / 2
            val plane = FloatArray(SIZE * SIZE) { PAD }
            // Bilinear, as the model's training resized pages.
            for (y in 0 until nh) {
                val sy = ((y + 0.5f) / scale - 0.5f).coerceIn(0f, (h - 1).toFloat())
                val y0 = sy.toInt()
                val y1 = min(y0 + 1, h - 1)
                val fy = sy - y0
                for (x in 0 until nw) {
                    val sx = ((x + 0.5f) / scale - 0.5f).coerceIn(0f, (w - 1).toFloat())
                    val x0 = sx.toInt()
                    val x1 = min(x0 + 1, w - 1)
                    val fx = sx - x0
                    val top = map[x0, y0] * (1 - fx) + map[x1, y0] * fx
                    val bottom = map[x0, y1] * (1 - fx) + map[x1, y1] * fx
                    plane[(py + y) * SIZE + px + x] = (top * (1 - fy) + bottom * fy) / 255f
                }
            }
            // Grey, so the same in red, green and blue.
            val input = FloatBuffer.allocate(3 * SIZE * SIZE)
            repeat(3) { input.put(plane) }
            input.rewind()
            OnnxTensor.createTensor(e, input, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
                s.run(mapOf(s.inputNames.first() to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val rows = (result[0].value as Array<Array<FloatArray>>)[0]
                    rows.filter { it[4] >= MIN_SCORE && it[5].roundToInt() == FRAME }
                        .sortedByDescending { it[4] }
                        .map { r ->
                            floatArrayOf(
                                ((r[0] - px) / scale).coerceIn(0f, w.toFloat()),
                                ((r[1] - py) / scale).coerceIn(0f, h.toFloat()),
                                ((r[2] - px) / scale).coerceIn(0f, w.toFloat()),
                                ((r[3] - py) / scale).coerceIn(0f, h.toFloat()),
                            )
                        }
                        .filter { it[2] - it[0] > 1f && it[3] - it[1] > 1f }
                }
            }
        }.onFailure { Log.w("PanelModel", "Panel detection failed", it) }.getOrNull()
    }
}
// KMK <--
