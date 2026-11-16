package ca.mpreg.webgpuviewer.viewer

import ca.mpreg.webgpuviewer.renderer.InkMap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where an image's found bubbles are kept between sessions - the app decides, by a key it gives
 * each image (see [BubbleZoom.keyOf]): its book, chapter and page, say, so a page found once,
 * or ahead of time while the chapter downloads, opens its bubbles at once ever after.
 */
interface BubbleStore {
    fun read(key: String): ByteArray?

    fun write(key: String, data: ByteArray)
}

/**
 * An image's found bubbles as bytes for a [BubbleStore], and back. Everything is kept as shares
 * of the image, not map pixels, so it fits a map of the same image at another size; and with a
 * coarse print of the image, so bubbles are never laid on a different picture by mistake.
 */
internal object BubbleFile {
    private const val MAGIC = 0x42554231 // "BUB1"
    private const val VERSION = 6

    /** What a file holds: the bubbles, and the panels drawn by hand on the image (map pixels). */
    class Kept(
        val items: List<BubbleZoom.Item>,
        val panels: List<IntArray>,
        val shapes: List<FloatArray>,
        /**
         * The file's version: before 5, panels with no bubbles weren't stops; before 6, panels
         * weren't split where bubbles cross the gutters.
         */
        val version: Int = VERSION,
    )

    /** The print's side: the image as this many cells across and down, each its mean luma. */
    private const val PRINT = 8

    /** How far two prints may differ, on average per cell, and still be the same image. */
    private const val PRINT_TOLERANCE = 14

    /** How far the aspect ratios may differ. */
    private const val ASPECT_TOLERANCE = 0.02f

    fun encode(
        map: InkMap,
        rightToLeft: Boolean,
        items: List<BubbleZoom.Item>,
        panels: List<IntArray> = emptyList(),
        shapes: List<FloatArray> = emptyList(),
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeFloat(map.width.toFloat() / map.height)
            out.write(print(map))
            out.writeBoolean(rightToLeft)
            out.writeInt(items.size)
            val w = map.width.toFloat()
            val h = map.height.toFloat()
            for (item in items) {
                val f = item.found
                out.writeFloat(f.x0 / w)
                out.writeFloat(f.y0 / h)
                out.writeFloat((f.x0 + f.width) / w)
                out.writeFloat((f.y0 + f.height) / h)
                out.writeBoolean(f.lettered)
                out.writeFloat(item.letterHeight / h)
                out.writeBoolean(item.text != null)
                item.text?.let { out.writeUTF(it.take(MAX_TEXT)) }
                val panel = item.panel
                out.writeBoolean(panel != null)
                if (panel != null) {
                    out.writeFloat(panel[0] / w)
                    out.writeFloat(panel[1] / h)
                    out.writeFloat(panel[2] / w)
                    out.writeFloat(panel[3] / h)
                }
                // The shape, as runs of in and out, alternately, starting out.
                out.writeInt(f.width)
                out.writeInt(f.height)
                val runs = ArrayList<Int>()
                var inside = false
                var run = 0
                for (v in f.mask) {
                    val on = (v.toInt() and 0xFF) >= 128
                    if (on != inside) {
                        runs += run
                        run = 0
                        inside = on
                    }
                    run++
                }
                runs += run
                out.writeInt(runs.size)
                for (r in runs) out.writeInt(r)
                // Version 3: a panel stop.
                out.writeBoolean(item.panelStop)
            }
            // Version 2: panels drawn by hand.
            out.writeInt(panels.size)
            for (p in panels) {
                out.writeFloat(p[0] / w)
                out.writeFloat(p[1] / h)
                out.writeFloat(p[2] / w)
                out.writeFloat(p[3] / h)
            }
            // Version 4: the panels as found, polygons.
            out.writeInt(shapes.size)
            for (s in shapes) {
                out.writeInt(s.size / 2)
                for (i in s.indices) out.writeFloat(s[i] / (if (i % 2 == 0) w else h))
            }
        }
        return bytes.toByteArray()
    }

    /**
     * The bubbles in [data], laid on [map] - null where it isn't this image, or isn't readable.
     * Re-ordered where it was found for the other reading direction.
     */
    fun decode(data: ByteArray, map: InkMap, rightToLeft: Boolean): Kept? = runCatching {
        DataInputStream(GZIPInputStream(ByteArrayInputStream(data))).use { input ->
            if (input.readInt() != MAGIC) return null
            val version = input.readInt()
            if (version !in 1..VERSION) return null
            val aspect = input.readFloat()
            if (abs(aspect - map.width.toFloat() / map.height) > ASPECT_TOLERANCE * aspect) return null
            val kept = ByteArray(PRINT * PRINT)
            input.readFully(kept)
            val now = print(map)
            var diff = 0
            for (i in kept.indices) diff += abs((kept[i].toInt() and 0xFF) - (now[i].toInt() and 0xFF))
            if (diff > PRINT_TOLERANCE * kept.size) return null
            val keptRightToLeft = input.readBoolean()
            val count = input.readInt()
            val w = map.width
            val h = map.height
            val items = ArrayList<BubbleZoom.Item>(count)
            repeat(count) {
                val x0 = (input.readFloat() * w).roundToInt()
                val y0 = (input.readFloat() * h).roundToInt()
                val x1 = (input.readFloat() * w).roundToInt()
                val y1 = (input.readFloat() * h).roundToInt()
                val lettered = input.readBoolean()
                val letterHeight = input.readFloat() * h
                val text = if (input.readBoolean()) input.readUTF() else null
                val panel = if (input.readBoolean()) {
                    intArrayOf(
                        (input.readFloat() * w).roundToInt(),
                        (input.readFloat() * h).roundToInt(),
                        (input.readFloat() * w).roundToInt(),
                        (input.readFloat() * h).roundToInt(),
                    )
                } else {
                    null
                }
                val sw = input.readInt()
                val sh = input.readInt()
                val shape = ByteArray(sw * sh)
                var at = 0
                var inside = false
                repeat(input.readInt()) {
                    val run = input.readInt()
                    if (inside && at < shape.size) shape.fill(0xFF.toByte(), at, (at + run).coerceAtMost(shape.size))
                    at += run
                    inside = !inside
                }
                // Laid on this map's size, nearest pixel, then softened as a fresh find is.
                val fw = (x1 - x0).coerceAtLeast(1)
                val fh = (y1 - y0).coerceAtLeast(1)
                val mask = ByteArray(fw * fh) { i ->
                    val sx = (i % fw) * sw / fw
                    val sy = (i / fw) * sh / fh
                    if (sw > 0 && sh > 0) shape[sy * sw + sx] else 0
                }
                val found = BubbleDetect.Found(
                    x0,
                    y0,
                    fw,
                    fh,
                    BubbleDetect.soften(mask, fw, fh),
                    lettered = lettered,
                    letterHeight = letterHeight,
                )
                val panelStop = version >= 3 && input.readBoolean()
                val box = intArrayOf(x0, y0, x0 + fw, y0 + fh)
                items += BubbleZoom.Item(found, text, box, panel, letterHeight, panelStop)
            }
            val panels = if (version >= 2) {
                List(input.readInt()) {
                    intArrayOf(
                        (input.readFloat() * w).roundToInt(),
                        (input.readFloat() * h).roundToInt(),
                        (input.readFloat() * w).roundToInt(),
                        (input.readFloat() * h).roundToInt(),
                    )
                }
            } else {
                emptyList()
            }
            val ordered = if (keptRightToLeft == rightToLeft) {
                items
            } else {
                BubbleDetect.readingOrder(map, items.map { it.box }, rightToLeft).map { items[it] }
            }
            val shapes = if (version >= 4) {
                List(input.readInt()) {
                    val points = input.readInt()
                    FloatArray(points * 2) { i -> input.readFloat() * (if (i % 2 == 0) w else h) }
                }
            } else {
                emptyList()
            }
            Kept(ordered, panels, shapes, version)
        }
    }.getOrNull()

    /** The image as [PRINT] x [PRINT] cells of its mean luma. */
    private fun print(map: InkMap): ByteArray = ByteArray(PRINT * PRINT) { cell ->
        val cx = cell % PRINT
        val cy = cell / PRINT
        val x0 = cx * map.width / PRINT
        val x1 = ((cx + 1) * map.width / PRINT).coerceAtLeast(x0 + 1)
        val y0 = cy * map.height / PRINT
        val y1 = ((cy + 1) * map.height / PRINT).coerceAtLeast(y0 + 1)
        var sum = 0L
        var n = 0
        // Every fourth pixel each way is plenty for a mean.
        for (y in y0 until y1 step 4) {
            for (x in x0 until x1 step 4) {
                sum += map[x, y]
                n++
            }
        }
        (if (n == 0) 255 else (sum / n).toInt()).toByte()
    }

    /** Text kept per bubble, at most - [DataOutputStream.writeUTF] takes 64K bytes. */
    private const val MAX_TEXT = 2000
}
