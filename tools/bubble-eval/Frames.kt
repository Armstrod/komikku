// Compares the view framing rules against frames set by hand in debug mode (frames.txt from
// prepare.py): mean error, as a share of the view, of centring on the bubble vs on its panel.
import ca.mpreg.webgpuviewer.renderer.InkMap
import ca.mpreg.webgpuviewer.viewer.BubbleDetect
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

fun main(args: Array<String>) {
    val maps = HashMap<String, InkMap>()
    var eBubble = 0.0
    var ePanel = 0.0
    var n = 0
    for (line in File(args[0]).readLines().filter { it.isNotBlank() }) {
        val p = line.split("|")
        val map = maps.getOrPut(p[1]) {
            val img = ImageIO.read(File(p[1]))
            val luma = ByteArray(img.width * img.height)
            for (y in 0 until img.height) for (x in 0 until img.width) luma[y * img.width + x] = img.raster.getSample(x, y, 0).toByte()
            InkMap(luma, img.width, img.height, 1f)
        }
        val w = map.width
        val h = map.height
        val boxes = p[3].split(";").map { s ->
            val b = s.split(",").map { it.toDouble() }
            intArrayOf((b[0] * w).toInt(), (b[1] * h).toInt(), (b[2] * w).toInt(), (b[3] * h).toInt())
        }
        val b = p[4].split(",").map { it.toFloat() }
        val f = p[5].split(",").map { it.toFloat() }
        val box = intArrayOf((b[0] * w).toInt(), (b[1] * h).toInt(), (b[2] * w).toInt(), (b[3] * h).toInt())
        val panel = BubbleDetect.panelsAround(map, BubbleDetect.gutterMap(map), listOf(box))[0]
        val hw = (f[2] - f[0]) / 2
        val hh = (f[3] - f[1]) / 2
        val ux = (f[0] + f[2]) / 2
        val uy = (f[1] + f[3]) / 2
        // As ImageViewerState.frameFor: aimed at a target as far as the bubble stays in view.
        fun aim(t: Float, lo: Float, hi: Float, half: Float) = if (hi - lo >= 2 * half) (lo + hi) / 2 else t.coerceIn(hi - half, lo + half)
        val px = panel?.let { (it[0] + it[2]) / 2f / w } ?: ((b[0] + b[2]) / 2)
        val py = panel?.let { (it[1] + it[3]) / 2f / h } ?: ((b[1] + b[3]) / 2)
        val e1 = abs(aim((b[0] + b[2]) / 2, b[0], b[2], hw) - ux) / (2 * hw) + abs(aim((b[1] + b[3]) / 2, b[1], b[3], hh) - uy) / (2 * hh)
        val e2 = abs(aim(px, b[0], b[2], hw) - ux) / (2 * hw) + abs(aim(py, b[1], b[3], hh) - uy) / (2 * hh)
        eBubble += e1
        ePanel += e2
        n++
        if (args.contains("-v")) println("${p[0]} bubble-centred %.2f panel-aimed %.2f".format(e1, e2))
    }
    println("n=$n mean error (share of view): bubble-centred %.3f  panel-aimed %.3f".format(eBubble / n, ePanel / n))
}
