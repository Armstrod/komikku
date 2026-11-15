package ca.mpreg.webgpuviewer.viewer

import android.graphics.Bitmap
import ca.mpreg.webgpuviewer.renderer.InkMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * What bubble debug mode saw and what the reader corrected, page by page, for working out the
 * rules bubbles are found and ordered by: each page's bubbles as found, as left after the
 * corrections, the corrections themselves, and the page's images, greyscale, to check them
 * against. Kept for the app's session; [export] writes it all out as one zip.
 */
object BubbleDebugLog {
    /** A bubble in the log: which of the page's images, and its bounds as a share of it. */
    class Box(val image: Int, val bounds: FloatArray, val text: String?, val manual: Boolean)

    private class PageRecord(
        val label: String,
        val rightToLeft: Boolean,
        val found: List<Box>,
        val images: List<ByteArray>,
        val imageSizes: List<IntArray>,
    ) {
        var final: List<Box> = found
        val edits = ArrayList<String>()
        var updated = System.currentTimeMillis()
    }

    private val pages = LinkedHashMap<String, PageRecord>()

    /** Pages logged so far. */
    val size: Int @Synchronized get() = pages.size

    /** Whether [label] is logged already - its found bubbles and images are kept from the first time. */
    @Synchronized
    fun has(label: String): Boolean = pages.containsKey(label)

    /** Starts [label]'s record: the bubbles [found] on it, and its [maps] saved as images. */
    fun start(label: String, rightToLeft: Boolean, found: List<Box>, maps: List<InkMap>) {
        val images = maps.map { jpeg(it) }
        val sizes = maps.map { intArrayOf(it.width, it.height) }
        synchronized(this) {
            if (pages.containsKey(label)) return
            pages[label] = PageRecord(label, rightToLeft, found, images, sizes)
        }
    }

    /** [label]'s bubbles as they stand after [edit] - "move 3 -> 2", "delete 4", "add at 2". */
    @Synchronized
    fun edit(label: String, edit: String, now: List<Box>) {
        val record = pages[label] ?: return
        record.edits += edit
        record.final = now
        record.updated = System.currentTimeMillis()
    }

    @Synchronized
    fun clear() = pages.clear()

    /** Everything logged, as a zip in [dir]: log.json and the pages' images. */
    fun export(dir: File): File {
        dir.mkdirs()
        val out = File(dir, "bubble-debug-${System.currentTimeMillis()}.zip")
        val snapshot = synchronized(this) { pages.values.toList() }
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            val log = JSONObject()
            log.put("version", 1)
            log.put("exported", System.currentTimeMillis())
            val list = JSONArray()
            snapshot.forEachIndexed { n, record ->
                val names = record.images.indices.map { "images/page-${(n + 1).toString().padStart(3, '0')}-$it.jpg" }
                record.images.forEachIndexed { i, bytes ->
                    zip.putNextEntry(ZipEntry(names[i]))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                list.put(
                    JSONObject().apply {
                        put("page", record.label)
                        put("rightToLeft", record.rightToLeft)
                        put("images", JSONArray(names))
                        put("imageSizes", JSONArray(record.imageSizes.map { JSONArray(it.toList()) }))
                        put("found", boxes(record.found))
                        put("final", boxes(record.final))
                        put("edits", JSONArray(record.edits))
                        put("corrected", record.edits.isNotEmpty())
                    },
                )
            }
            log.put("pages", list)
            zip.putNextEntry(ZipEntry("log.json"))
            zip.write(log.toString(2).toByteArray())
            zip.closeEntry()
        }
        return out
    }

    private fun boxes(list: List<Box>): JSONArray = JSONArray().apply {
        list.forEachIndexed { order, box ->
            put(
                JSONObject().apply {
                    put("order", order + 1)
                    put("image", box.image)
                    put("bounds", JSONArray(box.bounds.map { (it * 10000f).roundToInt() / 10000.0 }))
                    put("text", box.text ?: JSONObject.NULL)
                    put("manual", box.manual)
                },
            )
        }
    }

    /** [map] as a greyscale JPEG - what detection saw, at its resolution. */
    private fun jpeg(map: InkMap): ByteArray {
        val pixels = IntArray(map.width * map.height) { i ->
            val l = map.luma[i].toInt() and 0xFF
            (0xFF shl 24) or (l shl 16) or (l shl 8) or l
        }
        val bitmap = Bitmap.createBitmap(pixels, map.width, map.height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            bitmap.recycle()
            stream.toByteArray()
        }
    }

    private const val JPEG_QUALITY = 85
}
