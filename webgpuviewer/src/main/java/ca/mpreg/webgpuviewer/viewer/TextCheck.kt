package ca.mpreg.webgpuviewer.viewer

import android.graphics.Bitmap
import android.util.Log
import ca.mpreg.webgpuviewer.renderer.InkMap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Reads what is written in a bubble [BubbleDetect] found, with ML Kit's on-device text
 * recognition: the shape tests can't always tell lettering from a row of hatching or
 * screentone, but a recogniser that finds no letters at all can. Off the main thread only -
 * it waits for the recogniser.
 */
internal object TextCheck {
    /** The crop is enlarged until it is at least this wide - small lettering reads poorly. */
    private const val MIN_WIDTH = 320

    /** At most this much larger. */
    private const val MAX_SCALE = 3f

    /** How long one bubble may take before it is given the benefit of the doubt. */
    private const val TIMEOUT_MS = 1500L

    /** Letters or digits recognised, at least, for it to count as lettering. */
    private const val MIN_CHARS = 2

    /** Letters or digits a loosely found bubble needs read in it. */
    private const val MIN_WORD_CHARS = 3

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Whether ML Kit could be used at all - once it fails, every bubble passes. */
    @Volatile
    private var broken = false

    /**
     * Whether [found] on [map] has lettering in it. True too where the recogniser can't run or
     * doesn't answer in time: it confirms what the shape tests found, never decides alone.
     */
    fun hasText(map: InkMap, found: BubbleDetect.Found): Boolean = read(map, found)?.let(::isLettering) ?: true

    /**
     * Whether recognised [text] reads as words: a few letters, two of them side by side - what
     * a bubble that passed only the loose shape test needs, art being misread as the odd letter.
     */
    fun isWords(text: String): Boolean =
        text.count { it.isLetterOrDigit() } >= MIN_WORD_CHARS && Regex("\\p{L}{2,}").containsMatchIn(text)

    /** Whether recognised [text] holds enough letters or digits to be lettering. */
    fun isLettering(text: String): Boolean = text.count { it.isLetterOrDigit() } >= MIN_CHARS

    /** What the recogniser reads in [found] on [map] - null where it can't run or is too slow. */
    fun read(map: InkMap, found: BubbleDetect.Found): String? {
        if (broken) return null
        return try {
            val bitmap = crop(map, found) ?: return null
            val task = recognizer.process(InputImage.fromBitmap(bitmap, 0))
            val result = Tasks.await(task, TIMEOUT_MS, TimeUnit.MILLISECONDS)
            bitmap.recycle()
            result.text
        } catch (e: java.util.concurrent.TimeoutException) {
            null
        } catch (e: Exception) {
            Log.w("TextCheck", "Text recognition failed", e)
            broken = true
            null
        }
    }

    /** Lettering the recogniser found: its bounds on the map (x0, y0, x1, y1) and what it read. */
    class Block(val box: IntArray, val text: String)

    /**
     * The blocks of lettering the recogniser finds in [window] (x0, y0, x1, y1) of [map] - for
     * lettering with no bubble round it. Empty where it can't run.
     */
    fun blocks(map: InkMap, window: IntArray): List<Block> {
        if (broken) return emptyList()
        val x0 = window[0].coerceIn(0, map.width)
        val y0 = window[1].coerceIn(0, map.height)
        val w = window[2].coerceIn(x0, map.width) - x0
        val h = window[3].coerceIn(y0, map.height) - y0
        if (w <= 0 || h <= 0) return emptyList()
        return try {
            val pixels = IntArray(w * h) { i ->
                val l = map[x0 + i % w, y0 + i / w]
                (0xFF shl 24) or (l shl 16) or (l shl 8) or l
            }
            val small = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            val scale = min(MAX_SCALE, max(1f, BLOCK_MIN_WIDTH.toFloat() / w))
            val bitmap = if (scale > 1f) {
                Bitmap.createScaledBitmap(small, (w * scale).toInt(), (h * scale).toInt(), true).also {
                    if (it !== small) small.recycle()
                }
            } else {
                small
            }
            val task = recognizer.process(InputImage.fromBitmap(bitmap, 0))
            val result = Tasks.await(task, BLOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            bitmap.recycle()
            result.textBlocks.mapNotNull { block ->
                val r = block.boundingBox ?: return@mapNotNull null
                if (!isLettering(block.text)) return@mapNotNull null
                Block(
                    intArrayOf(
                        x0 + (r.left / scale).toInt(),
                        y0 + (r.top / scale).toInt(),
                        x0 + (r.right / scale).toInt(),
                        y0 + (r.bottom / scale).toInt(),
                    ),
                    block.text,
                )
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            emptyList()
        } catch (e: Exception) {
            Log.w("TextCheck", "Text recognition failed", e)
            emptyList()
        }
    }

    /** A window searched for lettering is enlarged until at least this wide. */
    private const val BLOCK_MIN_WIDTH = 640

    /** A window takes longer than one bubble. */
    private const val BLOCK_TIMEOUT_MS = 4000L

    /** The bubble cut from [map], paper outside its shape, enlarged for the recogniser. */
    private fun crop(map: InkMap, found: BubbleDetect.Found): Bitmap? {
        val w = found.width
        val h = found.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val my = found.y0 + y
            for (x in 0 until w) {
                val mx = found.x0 + x
                val inside = (found.mask[y * w + x].toInt() and 0xFF) >= 128
                val l = if (inside && mx in 0 until map.width && my in 0 until map.height) map[mx, my] else 255
                pixels[y * w + x] = (0xFF shl 24) or (l shl 16) or (l shl 8) or l
            }
        }
        val small = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val scale = min(MAX_SCALE, max(1f, MIN_WIDTH.toFloat() / w))
        if (scale <= 1f) return small
        val big = Bitmap.createScaledBitmap(small, (w * scale).toInt(), (h * scale).toInt(), true)
        if (big !== small) small.recycle()
        return big
    }
}
