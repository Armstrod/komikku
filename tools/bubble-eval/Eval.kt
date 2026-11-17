// Replays bubble debug logs (see prepare.py) through BubbleDetect: recall of the corrected
// bubbles, extra finds, reading order on the corrected boxes, and time per page.
// Line format of the pages file: label|image path|rtl 0/1|final boxes|found boxes, boxes as
// x0,y0,x1,y1 shares of the image separated by ";".
import ca.mpreg.webgpuviewer.renderer.InkMap
import ca.mpreg.webgpuviewer.viewer.BubbleDetect
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

fun iou(a: IntArray, b: IntArray): Float {
    val ix = max(0, min(a[2], b[2]) - max(a[0], b[0])); val iy = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    val i = ix.toFloat() * iy; val u = (a[2]-a[0]).toFloat()*(a[3]-a[1]) + (b[2]-b[0]).toFloat()*(b[3]-b[1]) - i
    return if (u <= 0) 0f else i / u
}
// overlap relative to the smaller: detection merging two bubbles still "covers"
fun cover(a: IntArray, b: IntArray): Float {
    val ix = max(0, min(a[2], b[2]) - max(a[0], b[0])); val iy = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    val i = ix.toFloat() * iy; val s = min((a[2]-a[0]).toFloat()*(a[3]-a[1]), (b[2]-b[0]).toFloat()*(b[3]-b[1]))
    return if (s <= 0) 0f else i / s
}
fun main(args: Array<String>) {
    val verbose = args.contains("-v")
    val lines = File(args[0]).readLines().filter { it.isNotBlank() }
    var tFind = 0L; var tOrder = 0L; var strictFp = 0; var strictHit = 0; var gtTotal = 0; var hit = 0; var det = 0; var fp = 0; var orderExact = 0; var orderPages = 0; var inversions = 0; var pairs = 0
    for (line in lines) {
        val parts = line.split("|")
        val label = parts[0]
        val img = ImageIO.read(File(parts[1]))
        val w = img.width; val h = img.height
        val luma = ByteArray(w*h); val r = img.raster
        for (y in 0 until h) for (x in 0 until w) luma[y*w+x] = r.getSample(x, y, 0).toByte()
        val map = InkMap(luma, w, h, 1f)
        val rtl = parts[2] == "1"
        val gt = ArrayList<IntArray>()
        if (parts[3].isNotEmpty()) for (bs in parts[3].split(";")) { val b = bs.split(",").map { it.toDouble() }
            gt += intArrayOf((b[0]*w).toInt(), (b[1]*h).toInt(), (b[2]*w).toInt(), (b[3]*h).toInt()) }
        // The panel detector's frames, where prepare.py's frames step wrote them (image.frames).
        val framesFile = File(parts[1] + ".frames")
        val frames = if (framesFile.exists()) framesFile.readLines().filter { it.isNotBlank() }.map { l ->
            val b = l.split(",").map { it.toDouble() }
            intArrayOf((b[0]*w).toInt().coerceAtLeast(0), (b[1]*h).toInt().coerceAtLeast(0), (b[2]*w).toInt().coerceAtMost(w), (b[3]*h).toInt().coerceAtMost(h))
        } else emptyList()
        val t0 = System.nanoTime(); val all = BubbleDetect.findAll(map, frames = if (System.getenv("NOFRAMES") != null) emptyList() else frames); tFind += System.nanoTime() - t0; val found = all.map { intArrayOf(it.x0, it.y0, it.x0+it.width, it.y0+it.height) }
        if (System.getenv("DUMP") == label) for (f in found) println("  found " + f.map { it.toFloat() / (if (f.indexOf(it) % 2 == 0) w else h) }.joinToString(",") { "%.3f".format(it) })
        gtTotal += gt.size; det += found.size
        val missed = ArrayList<Int>()
        for ((gi, g) in gt.withIndex()) { if (found.any { cover(it, g) > 0.5f && iou(it, g) > 0.2f }) hit++ else missed += gi }
        val extra = found.filter { f -> gt.none { cover(f, it) > 0.5f } }
        fp += extra.size; strictFp += all.filterIndexed { i, f -> f.lettered && gt.none { cover(found[i], it) > 0.5f } }.size
        strictHit += gt.count { g -> all.withIndex().any { (i, f) -> f.lettered && cover(found[i], g) > 0.5f && iou(found[i], g) > 0.2f } }
        // ordering on ground-truth boxes
        val t1 = System.nanoTime(); val order = BubbleDetect.readingOrder(map, gt, rtl); tOrder += System.nanoTime() - t1
        if (gt.size >= 2) { orderPages++; if (order == gt.indices.toList()) orderExact++
            for (a in order.indices) for (b in a+1 until order.size) { pairs++; if (order[a] > order[b]) inversions++ } }
        if (verbose) println("$label  gt=${gt.size} found=${found.size} missed=${missed.map{it+1}} extra=${extra.size} order=${order.map{it+1}}")
    }
    println("recall ${hit}/${gtTotal} = ${"%.2f".format(hit*100f/gtTotal)}%  found $det, extra $fp")
    println("time per page: find ${tFind / 1000000 / lines.size} ms, order ${tOrder / 1000000 / lines.size} ms")
    println("strict tier: recall $strictHit, extra $strictFp")
    println("order exact $orderExact/$orderPages pages, inversions $inversions/$pairs pairs")
}
