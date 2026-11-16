// Compares panels found on the page against panels drawn by hand in the reader's page fixes
// (panels.txt: label|image|rtl|bubble boxes|hand panels, all normalised, ';'-separated).
// For each hand panel: the found panel that overlaps it most, and their overlap (IoU of
// bounds). With -o DIR, writes DIR/<label>.txt: one found panel per line, as polygon points.
import ca.mpreg.webgpuviewer.renderer.InkMap
import ca.mpreg.webgpuviewer.viewer.BubbleDetect
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

private fun boxes(s: String): List<FloatArray> =
    s.split(";").filter { it.isNotBlank() }.map { b -> b.split(",").map { it.toFloat() }.toFloatArray() }

private fun iou(a: FloatArray, b: FloatArray): Float {
    val ix = max(0f, min(a[2], b[2]) - max(a[0], b[0]))
    val iy = max(0f, min(a[3], b[3]) - max(a[1], b[1]))
    val i = ix * iy
    val u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - i
    return if (u <= 0f) 0f else i / u
}

fun main(args: Array<String>) {
    val outDir = args.indexOf("-o").takeIf { it >= 0 }?.let { File(args[it + 1]).apply { mkdirs() } }
    var total = 0f
    var n = 0
    for (line in File(args[0]).readLines().filter { it.isNotBlank() }) {
        val p = line.split("|")
        val img = ImageIO.read(File(p[1]))
        val luma = ByteArray(img.width * img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) luma[y * img.width + x] = img.raster.getSample(x, y, 0).toByte()
        val map = InkMap(luma, img.width, img.height, 1f)
        val w = map.width.toFloat()
        val h = map.height.toFloat()
        val bubbles = boxes(p[3]).map { intArrayOf((it[0] * w).toInt(), (it[1] * h).toInt(), (it[2] * w).toInt(), (it[3] * h).toInt()) }
        val t0 = System.nanoTime()
        val found = BubbleDetect.panelShapes(map, p[2] == "true", bubbles)
        val ms = (System.nanoTime() - t0) / 1e6
        val shapes = found.map { poly -> FloatArray(poly.size) { i -> poly[i] / (if (i % 2 == 0) w else h) } }
        val bounds = shapes.map { s ->
            val xs = s.filterIndexed { i, _ -> i % 2 == 0 }
            val ys = s.filterIndexed { i, _ -> i % 2 == 1 }
            floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
        }
        outDir?.let { d -> File(d, "${p[0]}.txt").writeText(shapes.joinToString("\n") { it.joinToString(",") } + "\n") }
        val hand = boxes(p.getOrElse(4) { "" })
        println("${p[0]}: ${shapes.size} panels in %.0f ms".format(ms))
        for (hp in hand) {
            val best = bounds.indices.maxByOrNull { iou(hp, bounds[it]) }
            val v = best?.let { iou(hp, bounds[it]) } ?: 0f
            total += v
            n++
            println("  hand ${hp.joinToString(",") { "%.2f".format(it) }} -> panel ${best?.plus(1)} IoU %.2f".format(v))
        }
    }
    if (n > 0) println("mean IoU of hand panels with their best found panel: %.3f over $n".format(total / n))
}
