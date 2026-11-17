package ca.mpreg.webgpuviewer.viewer

import ca.mpreg.webgpuviewer.renderer.InkMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finding speech bubbles on a page's [InkMap], and the order they are read in - plain
 * computation on the map, with nothing of the GPU or the screen, for [BubbleZoom].
 */
internal object BubbleDetect {
    /** A bubble is at least this bright where it was tapped, out of 255. */
    private const val MIN_SEED_LUMA = 165

    /** What counts as the bubble's own paper, relative to the brightness where it was tapped. */
    private const val PAPER_FRACTION = 0.82f

    /** How far a tap on the lettering looks for the paper around it, in map pixels. */
    private const val SEED_RADIUS = 10

    /** Largest share of the page a bubble may take - past this it's a white panel, not a bubble. */
    private const val MAX_AREA = 0.12f

    /** Share of a bubble's area its lettering takes, at least and at most. */
    private const val MIN_INK = 0.015f
    private const val MAX_INK = 0.45f

    /** Largest single mark inside a bubble, as a share of it - a letter or a line of them. */
    private const val MAX_MARK = 0.2f

    /**
     * How round a bubble is at least, measured once [CLOSE] has smoothed it: 1 for a circle,
     * about 0.65 with a long tail, under 0.4 for a spiky radio bubble or two joined by a neck.
     * Lettering laid over artwork, flooded through its white halo, stays near 0.2.
     */
    private const val MIN_COMPACTNESS = 0.33f

    /** Share of its bounds a bubble fills at least - two joined by a neck fill about a third. */
    private const val MIN_FILL = 0.3f

    /** The same three limits, eased for a bubble whose lettering passed the full test. */
    private const val MIN_FILL_LINED = 0.2f
    private const val MAX_INK_LINED = 0.6f
    private const val MIN_COMPACTNESS_LINED = 0.18f

    /**
     * Map pixels the shape is closed over before it is measured or cut out: comic lettering
     * often runs into the outline, and each letter that touches it would otherwise bite a notch
     * out of the bubble - out of its outline length, and out of what is enlarged.
     */
    private const val CLOSE = 3

    /** Map pixels added around the bubble, so its own outline comes with it. */
    private const val OUTLINE = 2

    /** What [lettered] asks of a bubble found for the sequence: marks, how alike, and in a row. */
    const val MIN_LETTERS = 3
    private const val LETTER_UNIFORMITY = 0.5f

    /**
     * A tapped bubble is checked the same way, a little more leniently - a finger chose it, and
     * a short "NO!" is still worth opening - but no longer not at all: an outlined helmet or
     * badge has no line of lettering in it.
     */
    const val TAP_LETTERS = 2

    /** Letters are small next to their bubble: at most this share of its height (or width). */
    private const val MAX_LETTER_SIZE = 0.55f

    /** A line of lettering runs across a fair part of its bubble, letters close together. */
    private const val MIN_LINE_SPAN = 0.22f

    /** A line's middle sits at most this share of its bubble's width off the bubble's middle. */
    private const val MAX_LINE_OFFSET = 0.2f

    /** How far a letter's centre may sit off its line's middle, as a share of a letter. */
    private const val LINE_ALIGN = 0.3f

    /** How bright paper is for [findAll]'s patches - what a bubble's own white is at least. */
    private const val PATCH_PAPER = 200

    /**
     * Letters are stout: their narrow side at least this share of their long one, at the median.
     * Hatching - a row of short parallel strokes on a shiny surface - is all thin lines.
     */
    private const val MIN_LETTER_ASPECT = 0.34f

    /** Letters are at least this tall - in map pixels, and as a share of the map's long side. */
    private const val MIN_LETTER_PX = 4f
    private const val MIN_LETTER_SHARE = 0.003f
    private const val MAX_LETTER_GAP = 3f

    /** Room around the bubble's bounds for [CLOSE] and [OUTLINE] to grow into. */
    private const val PAD = 4

    /** A bubble found on an [InkMap]: its bounds there, and its shape within them. */
    class Found(
        val x0: Int,
        val y0: Int,
        val width: Int,
        val height: Int,
        val mask: ByteArray,
        /**
         * Its marks passed the full lettering test - lines of letters on a baseline. Else only
         * the loose one, small marks of about letter size, and text recognition has to agree.
         */
        val lettered: Boolean = true,
        /**
         * How tall its letters are, in map pixels - the median of its marks, so a full stop or
         * a block of lines doesn't count. 0 where unknown.
         */
        val letterHeight: Float = 0f,
    )

    /**
     * Every bubble on [map]: each patch of paper that doesn't reach the page's edge is tried as
     * [find]'s seed, from its brightest pixel, so the same tests decide what is a bubble.
     */
    fun findAll(map: InkMap, openEdge: Int = NO_EDGE, frames: List<IntArray> = emptyList()): List<Found> {
        val out = ArrayList<Found>()
        val leaked = if (frames.isEmpty()) null else BooleanArray(map.width * map.height)
        patches(map, openEdge, null, out, leaked)
        // KMK -->
        // Bubbles open to the gutter - drawn running into the panel's border, as some artists
        // do - flood out into it and are lost above. Within each panel the detector found, its
        // border closes them.
        // Only paper that ran too far is tried again, which keeps it quick.
        if (leaked != null) {
            for (frame in frames.sortedBy { (it[2] - it[0]) * (it[3] - it[1]) }) {
                patches(map, openEdge, frame, out, leaked)
            }
        }
        // KMK <--
        return out
    }

    /**
     * [findAll]'s pass over the patches of paper on [map]: all of them, marking in [leaked] those
     * too big to be a bubble - or, with [clip], only what of those lies inside it, its walls.
     */
    private fun patches(
        map: InkMap,
        openEdge: Int,
        clip: IntArray?,
        out: MutableList<Found>,
        leaked: BooleanArray?,
    ) {
        val w = map.width
        val h = map.height
        val luma = map.luma
        // Bright paper only: a bubble on light grey art would otherwise run into it and be
        // dropped as too big, though a finger's [find] gets it.
        val paper = PATCH_PAPER
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val maxArea = (MAX_AREA * w * h).toInt()
        fun inside(i: Int) = clip == null ||
            (leaked!![i] && i % w in clip[0] until clip[2] && i / w in clip[1] until clip[3])
        for (start in 0 until w * h) {
            if (seen[start] || (luma[start].toInt() and 0xFF) < paper || !inside(start)) continue
            // One patch of paper, four-connected.
            var head = 0
            var tail = 0
            seen[start] = true
            queue[tail++] = start
            var brightest = start
            var x0 = w
            var x1 = -1
            var y0 = h
            var y1 = -1
            while (head < tail) {
                val i = queue[head++]
                val px = i % w
                val py = i / w
                if (px < x0) x0 = px
                if (px > x1) x1 = px
                if (py < y0) y0 = py
                if (py > y1) y1 = py
                if ((luma[i].toInt() and 0xFF) > (luma[brightest].toInt() and 0xFF)) brightest = i
                neighbors(i, px, py, w, h) { n ->
                    if (seen[n] || (luma[n].toInt() and 0xFF) < paper || !inside(n)) return@neighbors
                    seen[n] = true
                    queue[tail++] = n
                }
            }
            // Paper reaching the page's edge is tried too: a caption box cut by the trim is
            // still a caption - find decides, its lettering test keeping gutters out.
            if (tail > maxArea && clip == null && leaked != null) {
                for (k in 0 until tail) leaked[queue[k]] = true
            }
            if (tail > maxArea || tail < 200) continue
            if (x1 - x0 + 1 < 0.03f * w || y1 - y0 + 1 < 0.02f * h) continue
            val found = find(map, brightest % w, brightest / w, openEdge, MIN_LETTERS, clip) ?: continue
            // The same bubble can surface twice where the adaptive threshold splits a patch.
            val duplicate = out.any { other ->
                val ix = max(0, min(found.x0 + found.width, other.x0 + other.width) - max(found.x0, other.x0))
                val iy = max(0, min(found.y0 + found.height, other.y0 + other.height) - max(found.y0, other.y0))
                ix * iy > 0.5f * min(found.width * found.height, other.width * other.height)
            }
            if (!duplicate) out += found
        }
    }

    /** Which side of the map, if any, a bubble may run off - the seam side of a spread's page. */
    const val NO_EDGE = 0
    const val LEFT_EDGE = 1
    const val RIGHT_EDGE = 2

    /**
     * The speech bubble around map pixel ([x], [y]): the bright paper there, flood-filled up to the
     * dark outline that closes it, its lettering filled in, its outline added back. Null for
     * anything that doesn't look like a bubble - open to the page's edge, too big, too thin, or
     * with nothing written in it.
     */
    fun find(
        map: InkMap,
        x: Int,
        y: Int,
        openEdge: Int = NO_EDGE,
        letters: Int = 0,
        /** Walls (x0, y0, x1, y1): the flood stays inside, as within a panel's border. */
        clip: IntArray? = null,
    ): Found? {
        val w = map.width
        val h = map.height

        // A tap on the lettering starts from the brightest paper next to it.
        var seedX = x
        var seedY = y
        var seedLuma = map[x, y]
        if (seedLuma < MIN_SEED_LUMA) {
            for (dy in -SEED_RADIUS..SEED_RADIUS) {
                for (dx in -SEED_RADIUS..SEED_RADIUS) {
                    val px = x + dx
                    val py = y + dy
                    if (px !in 0 until w || py !in 0 until h) continue
                    val l = map[px, py]
                    if (l > seedLuma) {
                        seedLuma = l
                        seedX = px
                        seedY = py
                    }
                }
            }
            if (seedLuma < MIN_SEED_LUMA) return null
        }
        val paper = (seedLuma * PAPER_FRACTION).toInt()

        // Flood the paper, four-connected, giving up once it's too big to be a bubble.
        val maxArea = (MAX_AREA * w * h).toInt()
        val work = scratchFor(w * h)
        val stamp = ++work.stamp
        val mark = work.mark
        val queue = work.queue
        var head = 0
        var tail = 0
        var x0 = seedX
        var x1 = seedX
        var y0 = seedY
        var y1 = seedY
        mark[seedY * w + seedX] = stamp
        queue[tail++] = seedY * w + seedX
        while (head < tail) {
            val i = queue[head++]
            val px = i % w
            val py = i / w
            // Open to the page's edge: a gutter or a borderless panel, not a bubble - except at
            // the open edge, which simply bounds it. Where lettering is asked for, any edge
            // bounds it: a caption box cut off by the page's trim still holds its text, and a
            // gutter or a panel has none to pass the test.
            val atLeft = px == 0
            val atRight = px == w - 1
            val boundedByEdge = letters > 0
            if (!boundedByEdge && (py == 0 || py == h - 1)) return null
            if (!boundedByEdge && ((atLeft && openEdge != LEFT_EDGE) || (atRight && openEdge != RIGHT_EDGE))) {
                return null
            }
            if (px < x0) x0 = px
            if (px > x1) x1 = px
            if (py < y0) y0 = py
            if (py > y1) y1 = py
            neighbors(i, px, py, w, h) { n ->
                if (mark[n] == stamp || (map.luma[n].toInt() and 0xFF) < paper) return@neighbors
                if (clip != null && (n % w !in clip[0] until clip[2] || n / w !in clip[1] until clip[3])) {
                    return@neighbors
                }
                if (tail >= maxArea) return null
                mark[n] = stamp
                queue[tail++] = n
            }
        }
        val paperArea = tail

        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        if (bw < 0.03f * w || bh < 0.02f * h || paperArea < 200) return null

        // Fill the lettering in: everything in the bounds the outside can't reach is bubble.
        val outW = bw + 2 * PAD
        val outH = bh + 2 * PAD
        val ox = x0 - PAD
        val oy = y0 - PAD
        val shape = BooleanArray(outW * outH)
        for (sy in 0 until outH) {
            for (sx in 0 until outW) {
                val mx = ox + sx
                val my = oy + sy
                if (mx in 0 until w && my in 0 until h && mark[my * w + mx] == stamp) shape[sy * outW + sx] = true
            }
        }
        val outside = outsideOf(shape, outW, outH)
        var filledArea = 0
        for (i in shape.indices) if (!outside[i]) filledArea++

        // Round enough to be a bubble, small enough not to be a panel, and with something
        // written in it - but only lettering, which is in small pieces. A panel's artwork is
        // dense, or has a large piece.
        if (filledArea > maxArea) return null
        // What is written in it has to look like lettering, not a face, a glove or the rivets on
        // an outlined piece of armour - for the sequence, which tries every patch of paper, and
        // for a tap as well. Clear lines of lettering earn some slack on the shape below: a
        // spiky shout, or big bold lettering filling its bubble, is still a bubble.
        val reading = if (letters > 0) lettered(shape, outside, outW, outH, letters, minLetterSize(map)) else null
        val lettering = reading?.level ?: STRICT
        if (lettering == NONE) return null
        val lined = letters > 0 && lettering == STRICT
        if (filledArea < (if (lined) MIN_FILL_LINED else MIN_FILL) * bw * bh) return null
        val ink = (filledArea - paperArea).toFloat() / filledArea
        if (ink < MIN_INK || ink > (if (lined) MAX_INK_LINED else MAX_INK)) return null
        if (largestMark(shape, outside, outW, outH) > MAX_MARK * filledArea) return null

        // Closed over the notches lettering bites out of it, then filled again.
        val solid = BooleanArray(outW * outH) { !outside[it] }
        val closed = grow(grow(solid, outW, outH, CLOSE, dilate = true), outW, outH, CLOSE, dilate = false)
        val closedOutside = outsideOf(closed, outW, outH)

        // Compact, as a bubble or a caption box is - not the ragged white halo of lettering laid
        // over artwork, which floods into a shape all edge. Counted edges overshoot a smooth
        // outline's length by about 4/pi, hence the correction; a circle comes out near 1.
        var closedArea = 0
        var edges = 0
        for (sy in 0 until outH) {
            for (sx in 0 until outW) {
                val i = sy * outW + sx
                if (!closedOutside[i]) closedArea++
                if (sx > 0 && closedOutside[i] != closedOutside[i - 1]) edges++
                if (sy > 0 && closedOutside[i] != closedOutside[i - outW]) edges++
            }
        }
        val compactness = 64f * closedArea / (Math.PI.toFloat() * edges * edges)
        if (compactness < (if (lined) MIN_COMPACTNESS_LINED else MIN_COMPACTNESS)) return null

        // Grown by the outline.
        val grown = grow(BooleanArray(outW * outH) { !closedOutside[it] }, outW, outH, OUTLINE, dilate = true)
        val mask = ByteArray(outW * outH) { if (grown[it]) 0xFF.toByte() else 0 }
        return Found(
            ox,
            oy,
            outW,
            outH,
            soften(mask, outW, outH),
            lettered = lettering == STRICT,
            letterHeight = reading?.height ?: 0f,
        )
    }

    /** What of a [w] x [h] frame can be reached from its border without crossing [shape]. */
    private fun outsideOf(shape: BooleanArray, w: Int, h: Int): BooleanArray {
        val outside = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        fun reach(i: Int) {
            if (!shape[i] && !outside[i]) {
                outside[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until w) {
            reach(x)
            reach((h - 1) * w + x)
        }
        for (y in 0 until h) {
            reach(y * w)
            reach(y * w + w - 1)
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) reach(i - 1)
            if (x < w - 1) reach(i + 1)
            if (y > 0) reach(i - w)
            if (y < h - 1) reach(i + w)
        }
        return outside
    }

    /**
     * [shape] dilated or eroded by a square of radius [r], one axis at a time. Past the frame
     * counts as empty, which [PAD] keeps clear of the shape.
     */
    private fun grow(shape: BooleanArray, w: Int, h: Int, r: Int, dilate: Boolean): BooleanArray {
        fun pass(src: BooleanArray, horizontal: Boolean): BooleanArray = BooleanArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var any = false
            var all = true
            for (d in -r..r) {
                val v = if (horizontal) {
                    val tx = x + d
                    tx in 0 until w && src[y * w + tx]
                } else {
                    val ty = y + d
                    ty in 0 until h && src[ty * w + x]
                }
                if (v) any = true else all = false
            }
            if (dilate) any else all
        }
        return pass(pass(shape, horizontal = true), horizontal = false)
    }

    /**
     * Blurs the shape's hard map-pixel steps into a smooth field, so the edge drawn at its
     * midpoint is a curve rather than a staircase once the bubble is enlarged. Two box passes
     * each way, radius 2 - near enough a Gaussian.
     */
    fun soften(mask: ByteArray, w: Int, h: Int): ByteArray {
        var src = FloatArray(mask.size) { (mask[it].toInt() and 0xFF) / 255f }
        val radius = 2
        repeat(2) {
            val horizontal = FloatArray(src.size)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sum = 0f
                    for (d in -radius..radius) sum += src[y * w + (x + d).coerceIn(0, w - 1)]
                    horizontal[y * w + x] = sum / (2 * radius + 1)
                }
            }
            val vertical = FloatArray(src.size)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sum = 0f
                    for (d in -radius..radius) sum += horizontal[(y + d).coerceIn(0, h - 1) * w + x]
                    vertical[y * w + x] = sum / (2 * radius + 1)
                }
            }
            src = vertical
        }
        return ByteArray(src.size) { (src[it] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte() }
    }

    /**
     * Whether the marks inside the bubble read as lettering: at least [minLetters] of them about
     * one size, small next to the bubble, with [minLetters] or more side by side in a line that
     * runs across a fair part of it, on one baseline, letters close together. A face has an eye or two, a glove a few long strokes, an
     * outlined badge a large emblem; none of them is a line of small, evenly spaced marks.
     */
    /**
     * Smallest a letter's height may be on [map], in map pixels: screentone's dots, which the
     * map's min-pooling darkens into neat rows of specks, are far smaller than any lettering.
     */
    private fun minLetterSize(map: InkMap): Float = max(MIN_LETTER_PX, MIN_LETTER_SHARE * max(map.width, map.height))

    /** How well a bubble's marks read as lettering - see [lettered]. */
    private const val NONE = 0
    private const val LOOSE = 1
    private const val STRICT = 2

    /**
     * How well the marks inside a bubble read as lettering: [STRICT] for lines of it, as below;
     * [LOOSE] for at least two marks of about a letter's size - bold or joined lettering, short
     * exclamations - which text recognition then has to confirm; else [NONE].
     */
    /** How well a bubble's marks read as lettering, and how tall its letters are. */
    private class Lettering(val level: Int, val height: Float = 0f)

    private fun lettered(
        paper: BooleanArray,
        outside: BooleanArray,
        w: Int,
        h: Int,
        minLetters: Int,
        minSize: Float,
    ): Lettering {
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val marks = ArrayList<IntArray>()
        // The bubble's own extent, to judge the marks against.
        var bx0 = w
        var bx1 = -1
        var by0 = h
        var by1 = -1
        for (i in paper.indices) {
            if (outside[i]) continue
            val x = i % w
            val y = i / w
            if (x < bx0) bx0 = x
            if (x > bx1) bx1 = x
            if (y < by0) by0 = y
            if (y > by1) by1 = y
        }
        if (bx1 < bx0 || by1 < by0) return Lettering(NONE)
        val bubbleW = bx1 - bx0 + 1
        val bubbleH = by1 - by0 + 1
        for (start in paper.indices) {
            if (paper[start] || outside[start] || seen[start]) continue
            var head = 0
            var tail = 0
            seen[start] = true
            queue[tail++] = start
            var x0 = w
            var x1 = -1
            var y0 = h
            var y1 = -1
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
                neighbors(i, x, y, w, h) { n ->
                    if (paper[n] || outside[n] || seen[n]) return@neighbors
                    seen[n] = true
                    queue[tail++] = n
                }
            }
            // A speck is not a letter.
            if (tail < 3) continue
            marks += intArrayOf(x0, y0, x1, y1)
        }
        if (marks.size < 2) return Lettering(NONE)
        val heights = marks.map { it[3] - it[1] + 1 }.sorted()
        val median = heights[heights.size / 2].toFloat()
        if (median < minSize || median > MAX_LETTER_SIZE * bubbleH) return Lettering(NONE)
        // Lines across only: down the bubble, hatching's strokes stack into columns as neatly
        // as vertical lettering does, and lettered pages read across.
        val inLines = marks.size >= minLetters &&
            lines(marks, across = true, bubbleW, bubbleH, (bx0 + bx1) * 0.5f, minLetters, minSize)
        return Lettering(if (inLines) STRICT else LOOSE, median)
    }

    /**
     * Whether [marks] (x0, y0, x1, y1 each) hold a line of lettering running [across] the bubble -
     * else running down it. "Size" is a mark's extent across the line, "position" along it.
     */
    private fun lines(
        marks: List<IntArray>,
        across: Boolean,
        bubbleW: Int,
        bubbleH: Int,
        /** The bubble's middle along the line. */
        bubbleMid: Float,
        minLetters: Int,
        minSize: Float,
    ): Boolean {
        fun size(m: IntArray) = if (across) m[3] - m[1] + 1 else m[2] - m[0] + 1
        fun center(m: IntArray) = if (across) (m[1] + m[3]) * 0.5f else (m[0] + m[2]) * 0.5f
        fun start(m: IntArray) = if (across) m[0] else m[1]
        fun end(m: IntArray) = if (across) m[2] else m[3]
        val bubbleCross = if (across) bubbleH else bubbleW
        val bubbleAlong = if (across) bubbleW else bubbleH

        val median = marks.map { size(it) }.sorted()[marks.size / 2].toFloat()
        if (median < minSize || median > MAX_LETTER_SIZE * bubbleCross) return false
        val alike = marks.filter { size(it) >= 0.5f * median && size(it) <= 1.7f * median }
        if (alike.size < minLetters || alike.size < LETTER_UNIFORMITY * marks.size) return false
        val aspects = alike.map { m ->
            val mw = m[2] - m[0] + 1
            val mh = m[3] - m[1] + 1
            min(mw, mh).toFloat() / max(mw, mh)
        }.sorted()
        if (aspects[aspects.size / 2] < MIN_LETTER_ASPECT) return false

        // Lines: marks whose centres sit within most of a letter's size of each other.
        val sorted = alike.sortedBy { center(it) }
        var line = ArrayList<IntArray>()
        var sum = 0f
        fun good(l: List<IntArray>): Boolean {
            if (l.size < minLetters) return false
            // Letters sit on one baseline: most centres within a fraction of a letter of the
            // line's middle. Hatching and dots scatter.
            val mid = l.map { center(it) }.sorted()[l.size / 2]
            val aligned = l.filter { abs(center(it) - mid) <= LINE_ALIGN * median }
            if (aligned.size < minLetters || aligned.size < 0.7f * l.size) return false
            val ordered = aligned.sortedBy { start(it) }
            val span = end(ordered.last()) - start(ordered.first()) + 1
            if (span < MIN_LINE_SPAN * bubbleAlong) return false
            // Lettering is centred in its bubble; a few marks off to one side are artwork.
            val lineMid = (start(ordered.first()) + end(ordered.last())) * 0.5f
            if (abs(lineMid - bubbleMid) > MAX_LINE_OFFSET * bubbleAlong) return false
            // Letters sit close; a few marks scattered across a shape do not.
            var gaps = 0f
            for (k in 1 until ordered.size) gaps += max(0, start(ordered[k]) - end(ordered[k - 1]))
            return gaps / (ordered.size - 1) <= MAX_LETTER_GAP * median
        }
        for (m in sorted) {
            if (line.isNotEmpty() && abs(center(m) - sum / line.size) > 0.6f * median) {
                if (good(line)) return true
                line = ArrayList()
                sum = 0f
            }
            line += m
            sum += center(m)
        }
        return good(line)
    }

    /** Size of the largest connected mark inside the bubble: filled in, but not its paper. */
    private fun largestMark(paper: BooleanArray, outside: BooleanArray, w: Int, h: Int): Int {
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var largest = 0
        for (start in paper.indices) {
            if (paper[start] || outside[start] || seen[start]) continue
            var head = 0
            var tail = 0
            seen[start] = true
            queue[tail++] = start
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                neighbors(i, x, y, w, h) { n ->
                    if (paper[n] || outside[n] || seen[n]) return@neighbors
                    seen[n] = true
                    queue[tail++] = n
                }
            }
            if (tail > largest) largest = tail
        }
        return largest
    }


    /** Calls [block] with each of pixel [i]'s four neighbours on a [w] x [h] map - no allocation. */
    private inline fun neighbors(i: Int, x: Int, y: Int, w: Int, h: Int, block: (Int) -> Unit) {
        if (x > 0) block(i - 1)
        if (x < w - 1) block(i + 1)
        if (y > 0) block(i - w)
        if (y < h - 1) block(i + w)
    }

    /** Working arrays for [find], reused from one bubble to the next on each thread. */
    private class Scratch(size: Int) {
        /** A pixel is in the current flood where it holds the current [stamp] - nothing to clear. */
        val mark = IntArray(size)
        val queue = IntArray(size)
        var stamp = 0
    }

    private val scratch = ThreadLocal<Scratch>()

    private fun scratchFor(size: Int): Scratch {
        scratch.get()?.takeIf { it.mark.size == size && it.stamp < Int.MAX_VALUE }?.let { return it }
        return Scratch(size).also { scratch.set(it) }
    }

    // Reading order.

    /** Paper at least this bright, out of 255, is gutter - as [PanelZoom]'s. */
    private const val GUTTER_PAPER = 170

    /** A line is a gutter when at least this share of it is paper - art may touch it. */
    private const val CLEAR_LINE = 0.97f

    /** A gutter at least this share of the piece's side across splits it - a speck doesn't. */
    private const val MIN_GAP = 0.004f

    /** How far a gutter may slant: its rise across the piece, as a share of the piece's width. */
    private const val MAX_SLANT = 0.3f

    /** How deep the cuts go - enough for any real layout. */
    private const val MAX_CUT_DEPTH = 6

    /** A piece smaller than this share of the page across or down isn't cut further. */
    private const val MIN_PIECE = 0.06f

    /**
     * The order [boxes] (x0, y0, x1, y1 on [map]) are read in, as indices into it. Panel by
     * panel first - the page cut along its gutters, slanted ones too, bands top to bottom and
     * panels across a band right to left where [rightToLeft] - each bubble going to the panel
     * holding its centre. Within a panel, top to bottom - except that a bubble further along the
     * reading direction whose top is above the bottom of the one chosen comes first: lettering
     * starts at the top right of a panel (top left left to right), and a bubble there sitting
     * a little lower than one further left is still read before it.
     */
    fun readingOrder(
        map: InkMap,
        boxes: List<IntArray>,
        rightToLeft: Boolean,
        /** The page's [gutterMap], where already found - it is copied, not changed. */
        gutter: BooleanArray? = null,
    ): List<Int> {
        if (boxes.isEmpty()) return emptyList()
        // KMK -->
        val gutters = gutter ?: gutters(map)
        val panels = ordered(map, gutters, panels(map, rightToLeft, boxes, gutters), rightToLeft)
        // KMK <--
        val byPanel = boxes.indices.groupBy { i ->
            val b = boxes[i]
            val cx = (b[0] + b[2]) / 2
            val cy = (b[1] + b[3]) / 2
            panels.indexOfFirst { it.contains(cx, cy) }.let { if (it < 0) panels.size else it }
        }
        val out = ArrayList<Int>()
        for (panel in byPanel.keys.sorted()) out += orderWithin(boxes, byPanel.getValue(panel), rightToLeft)
        return out
    }

    /** [members] of [boxes] in reading order within one panel - see [readingOrder]. */
    private fun orderWithin(boxes: List<IntArray>, members: List<Int>, rightToLeft: Boolean): List<Int> {
        fun top(i: Int) = boxes[i][1]
        fun bottom(i: Int) = boxes[i][3]
        fun centerX(i: Int) = (boxes[i][0] + boxes[i][2]) * 0.5f
        fun ahead(a: Int, of: Int): Boolean {
            // Clearly further along the line, not merely a hair across from it.
            val margin = 0.15f * min(boxes[a][2] - boxes[a][0], boxes[of][2] - boxes[of][0])
            return if (rightToLeft) centerX(a) > centerX(of) + margin else centerX(a) < centerX(of) - margin
        }
        val left = members.toMutableList()
        val out = ArrayList<Int>()
        while (left.isNotEmpty()) {
            var pick = left.minBy { top(it) }
            while (true) {
                val p = pick
                // Further along, and starting above where this one ends: read first. Each move
                // goes further along the line, so this ends.
                val before = left.filter { it != p && ahead(it, p) && top(it) < bottom(p) }
                if (before.isEmpty()) break
                pick = before.minBy { top(it) }
            }
            out += pick
            left.remove(pick)
        }
        return out
    }

    /**
     * A cut across a piece of the page: the line `v = v0 + slope * (u - u0)`, where for a
     * [horizontal] cut u runs along x and v along y, and the other way round otherwise. A piece
     * keeps to one side of it - [side] 1 below or right of it, -1 above or left.
     */
    class Cut(val horizontal: Boolean, val u0: Int, val v0: Float, val slope: Float, val side: Int) {
        fun at(u: Int): Float = v0 + slope * (u - u0)

        fun keeps(x: Int, y: Int): Boolean {
            val u = if (horizontal) x else y
            val v = if (horizontal) y else x
            return side * (v - at(u)) >= 0f
        }
    }

    /** A panel, or a piece of the page on the way to one: its bounds and the cuts around it. */
    class Piece(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val cuts: List<Cut>) {
        fun contains(x: Int, y: Int): Boolean =
            x in x0 until x1 && y in y0 until y1 && cuts.all { it.keeps(x, y) }
    }

    /**
     * The page cut into panels in reading order. An XY-cut, as [PanelZoom.panelOrder]'s, but
     * along gutters that may slant a little: a band of panels drawn at an angle is common, and
     * no straight line runs clear across it. Bands read top to bottom, panels across a band
     * right to left where [rightToLeft]. A page with no gutter across it is one piece.
     */
    fun panels(
        map: InkMap,
        rightToLeft: Boolean,
        bubbles: List<IntArray> = emptyList(),
        gutterMap: BooleanArray? = null,
    ): List<Piece> {
        val gutter = gutterMap?.copyOf() ?: gutters(map)
        // Bubbles often straddle the border between two panels: across them is gutter too.
        val w = map.width
        for (b in bubbles) {
            for (y in max(0, b[1]) until min(map.height, b[3])) {
                for (x in max(0, b[0]) until min(w, b[2])) gutter[y * w + x] = true
            }
        }
        val out = ArrayList<Piece>()
        cut(map, gutter, Piece(0, 0, map.width, map.height, emptyList()), horizontal = true, 0, rightToLeft, out)
        return out
    }

    // KMK -->
    /** A panel split where bubbles cross its gutters: into pieces this big, each way and by area. */
    private const val SPLIT_MIN_SIDE = 0.12f
    private const val SPLIT_MIN_AREA = 0.03f

    /** Whether ([x], [y]) lies in [poly] (x, y pairs). */
    private fun insidePolygon(poly: FloatArray, x: Float, y: Float): Boolean {
        var inside = false
        val n = poly.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[2 * i]
            val yi = poly[2 * i + 1]
            val xj = poly[2 * j]
            val yj = poly[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** A panel holding less artwork than this share of the page is gutter, not a panel. */
    private const val MIN_PANEL_INK = 0.01f

    /**
     * The page's panels as shapes, in reading order - see [panels]: each piece the gutters cut
     * the page into, bounded by those cuts - slanted ones too - and drawn in to its artwork, as
     * polygons (x0, y0, x1, y1, ...) on [map]. Cut from one another, they never overlap.
     */
    fun panelShapes(
        map: InkMap,
        rightToLeft: Boolean,
        bubbles: List<IntArray> = emptyList(),
        gutterMap: BooleanArray? = null,
    ): List<FloatArray> {
        val gutter = gutterMap ?: gutters(map)
        // Cut along the gutters alone: cutting round the bubbles too, as reading order does,
        // splits panels along their bubbles' edges.
        val plain = panels(map, rightToLeft, emptyList(), gutter).mapNotNull { shapeOf(map, gutter, it) }
        // But a bubble breaking out across a gutter blocks the cut there, joining panels. Cut
        // round the bubbles too, a panel is taken as the panel-sized pieces it falls into, each
        // sliver along a bubble's edge put back into the piece beside it.
        val shapes = if (bubbles.isEmpty()) {
            plain
        } else {
            val round = panels(map, rightToLeft, bubbles, gutter).mapNotNull { shapeOf(map, gutter, it) }
            fun sized(b: FloatArray): Boolean {
                val w = b[2] - b[0]
                val h = b[3] - b[1]
                return w >= SPLIT_MIN_SIDE * map.width && h >= SPLIT_MIN_SIDE * map.height &&
                    w * h >= SPLIT_MIN_AREA * map.width * map.height
            }
            plain.flatMap { whole ->
                val parts = round.filter { part ->
                    val b = boundsOf(part)
                    insidePolygon(whole, (b[0] + b[2]) * 0.5f, (b[1] + b[3]) * 0.5f)
                }
                val big = parts.filter { sized(boundsOf(it)) }
                if (big.size < 2) return@flatMap listOf(whole)
                // Each sliver joins the big piece nearest its middle: the two as one box.
                val merged = big.map { it.copyOf() }.toMutableList()
                for (sliver in parts.filter { p -> big.none { it === p } }) {
                    val sb = boundsOf(sliver)
                    val mx = (sb[0] + sb[2]) * 0.5f
                    val my = (sb[1] + sb[3]) * 0.5f
                    val k = merged.indices.minBy { i ->
                        val b = boundsOf(merged[i])
                        val dx = max(0f, max(b[0] - mx, mx - b[2]))
                        val dy = max(0f, max(b[1] - my, my - b[3]))
                        dx * dx + dy * dy
                    }
                    val b = boundsOf(merged[k])
                    val u = floatArrayOf(min(b[0], sb[0]), min(b[1], sb[1]), max(b[2], sb[2]), max(b[3], sb[3]))
                    merged[k] = floatArrayOf(u[0], u[1], u[2], u[1], u[2], u[3], u[0], u[3])
                }
                merged
            }
        }
        return orderPanels(shapes.map { boundsOf(it) }, rightToLeft).map { shapes[it] }
    }

    /** A detected frame takes a cut shape's slanted edges where their bounds overlap this much. */
    private const val FRAME_MATCH = 0.75f

    /** A frame this much inside a bigger one - a share of its area - lies in it. */
    private const val FRAME_INSIDE = 0.85f

    /** ... and spanning this share of the bigger one's width (or height), is a strip across it. */
    private const val FRAME_ACROSS = 0.8f

    /** ... along its edge: within this share of its height (or width) of it. */
    private const val FRAME_EDGE = 0.05f

    /**
     * The page's panels from a detector's [frames] (boxes on [map], most sure first - see
     * PanelModel), in reading order: each frame as the cut shape matching it - see
     * [panelShapes] - where one does, for its slanted edges, else as its box. The detector
     * decides where panels are: it finds borderless panels, bleeds and panels joined by a
     * bubble across their gutter that the cuts can't. No frames: the cut shapes.
     */
    fun panelShapesFrom(
        map: InkMap,
        rightToLeft: Boolean,
        bubbles: List<IntArray>,
        frames: List<FloatArray>,
        gutterMap: BooleanArray? = null,
    ): List<FloatArray> {
        val cuts = panelShapes(map, rightToLeft, bubbles, gutterMap)
        if (frames.isEmpty()) return cuts
        fun area(b: FloatArray) = max(0f, b[2] - b[0]) * max(0f, b[3] - b[1])
        fun overlap(a: FloatArray, b: FloatArray) =
            max(0f, min(a[2], b[2]) - max(a[0], b[0])) * max(0f, min(a[3], b[3]) - max(a[1], b[1]))
        fun iou(a: FloatArray, b: FloatArray): Float {
            val i = overlap(a, b)
            val u = area(a) + area(b) - i
            return if (u <= 0f) 0f else i / u
        }
        // Panels don't overlap: a frame found inside a bigger one, across its whole top or bottom
        // (or side) - a strip of it - is cut off it. One set in a corner or the middle is an
        // inset panel, and both stand. (Slanted panels' boxes overlap by their slant: no rule
        // on overlap alone - it lost real panels.)
        val kept = frames.map { it.copyOf() }
        for (big in kept) {
            for (small in kept) {
                if (small === big || area(small) >= area(big)) continue
                if (overlap(small, big) < FRAME_INSIDE * area(small)) continue
                val bw = big[2] - big[0]
                val bh = big[3] - big[1]
                if (small[2] - small[0] >= FRAME_ACROSS * bw) {
                    if (small[1] - big[1] < FRAME_EDGE * bh) {
                        big[1] = max(big[1], small[3])
                    } else if (big[3] - small[3] < FRAME_EDGE * bh) {
                        big[3] = min(big[3], small[1])
                    }
                } else if (small[3] - small[1] >= FRAME_ACROSS * bh) {
                    if (small[0] - big[0] < FRAME_EDGE * bw) {
                        big[0] = max(big[0], small[2])
                    } else if (big[2] - small[2] < FRAME_EDGE * bw) {
                        big[2] = min(big[2], small[0])
                    }
                }
            }
        }
        val cutBounds = cuts.map { boundsOf(it) }
        val used = HashSet<Int>()
        val shapes = kept.filter { it[2] - it[0] > 1f && it[3] - it[1] > 1f }.map { f ->
            val best = cutBounds.indices.filter { it !in used }.maxByOrNull { iou(f, cutBounds[it]) }
            if (best != null && iou(f, cutBounds[best]) >= FRAME_MATCH) {
                used += best
                cuts[best]
            } else {
                floatArrayOf(f[0], f[1], f[2], f[1], f[2], f[3], f[0], f[3])
            }
        }
        return orderPanels(shapes.map { boundsOf(it) }, rightToLeft).map { shapes[it] }
    }

    /** [pieces] in reading order by their artwork's bounds - see [orderPanels]. */
    private fun ordered(map: InkMap, gutter: BooleanArray, pieces: List<Piece>, rightToLeft: Boolean): List<Piece> {
        val bounds = pieces.map { piece ->
            shapeOf(map, gutter, piece)?.let { boundsOf(it) }
                ?: floatArrayOf(piece.x0.toFloat(), piece.y0.toFloat(), piece.x1.toFloat(), piece.y1.toFloat())
        }
        return orderPanels(bounds, rightToLeft).map { pieces[it] }
    }

    /** A panel is above another where they share less than this share of the shorter's height. */
    private const val ROW_OVERLAP = 0.3f

    /**
     * The order panels with [bounds] (x0, y0, x1, y1) are read in: row by row down the page,
     * right to left along a row where [rightToLeft]. Next is always a panel with none left
     * above it - one is above another where it ends before the other starts, give or take
     * [ROW_OVERLAP], or its middle is higher than the other's top over the same stretch of
     * the page - and of those, the furthest right (left reading left to right). So a
     * tall panel down one side is read before the rows beside it, and a panel never comes
     * before one higher up its own side of the page.
     */
    fun orderPanels(bounds: List<FloatArray>, rightToLeft: Boolean): List<Int> {
        fun above(q: FloatArray, p: FloatArray): Boolean {
            val shorter = min(q[3] - q[1], p[3] - p[1])
            if (q[3] - p[1] < ROW_OVERLAP * shorter) return true
            // A big panel cut off at a slant still sits above one under its slanted edge: its
            // middle higher than the other's top, over the same stretch of the page.
            val across = min(q[2], p[2]) - max(q[0], p[0])
            return (q[1] + q[3]) * 0.5f < p[1] && across > ROW_OVERLAP * min(q[2] - q[0], p[2] - p[0])
        }
        val left = bounds.indices.toMutableList()
        val out = ArrayList<Int>()
        while (left.isNotEmpty()) {
            val free = left.filter { p -> left.none { q -> q != p && above(bounds[q], bounds[p]) } }
                .ifEmpty { listOf(left.minBy { bounds[it][1] }) }
            val next = if (rightToLeft) {
                free.maxBy { bounds[it][0] + bounds[it][2] }
            } else {
                free.minBy { bounds[it][0] + bounds[it][2] }
            }
            out += next
            left.remove(next)
        }
        return out
    }

    private fun boundsOf(poly: FloatArray): FloatArray {
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (i in poly.indices step 2) {
            x0 = min(x0, poly[i])
            x1 = max(x1, poly[i])
            y0 = min(y0, poly[i + 1])
            y1 = max(y1, poly[i + 1])
        }
        return floatArrayOf(x0, y0, x1, y1)
    }

    /** [piece] as a polygon: its bounds clipped by its cuts, drawn in to the artwork in it. */
    private fun shapeOf(map: InkMap, gutter: BooleanArray, piece: Piece): FloatArray? {
        val w = map.width
        var bx0 = Int.MAX_VALUE
        var by0 = Int.MAX_VALUE
        var bx1 = -1
        var by1 = -1
        var ink = 0
        for (y in piece.y0 until piece.y1) {
            for (x in piece.x0 until piece.x1) {
                if (gutter[y * w + x] || !piece.contains(x, y)) continue
                ink++
                if (x < bx0) bx0 = x
                if (x > bx1) bx1 = x
                if (y < by0) by0 = y
                if (y > by1) by1 = y
            }
        }
        if (ink < MIN_PANEL_INK * w * map.height) return null
        var poly = floatArrayOf(
            bx0.toFloat(), by0.toFloat(),
            bx1 + 1f, by0.toFloat(),
            bx1 + 1f, by1 + 1f,
            bx0.toFloat(), by1 + 1f,
        )
        for (cut in piece.cuts) {
            poly = clip(poly) { x, y ->
                val u = if (cut.horizontal) x else y
                val v = if (cut.horizontal) y else x
                cut.side * (v - (cut.v0 + cut.slope * (u - cut.u0)))
            }
            if (poly.size < 6) return null
        }
        return poly
    }

    /** [poly] (x, y pairs) cut to where [side] is at least 0 - Sutherland-Hodgman, one edge. */
    private fun clip(poly: FloatArray, side: (Float, Float) -> Float): FloatArray {
        val out = ArrayList<Float>()
        val n = poly.size / 2
        for (i in 0 until n) {
            val ax = poly[2 * i]
            val ay = poly[2 * i + 1]
            val bx = poly[2 * ((i + 1) % n)]
            val by = poly[2 * ((i + 1) % n) + 1]
            val sa = side(ax, ay)
            val sb = side(bx, by)
            if (sa >= 0f) {
                out += ax
                out += ay
            }
            if ((sa >= 0f) != (sb >= 0f)) {
                val t = sa / (sa - sb)
                out += ax + (bx - ax) * t
                out += ay + (by - ay) * t
            }
        }
        return out.toFloatArray()
    }
    // KMK <--

    /** [gutters], for [panelAround] - found once for a page's bubbles. */
    fun gutterMap(map: InkMap): BooleanArray = gutters(map)

    /** Panels larger than this share of the page are the page, not a panel - no panel then. */
    private const val MAX_PANEL = 0.5f

    /**
     * The panel round each of [boxes] (x0, y0, x1, y1): what its middle reaches without
     * crossing a [gutter], by its bounds - a bubble reaches the panel's artwork through its own
     * outline. Null where that is most of the page - no panel borders to stop it. Each region
     * is flooded once, however many bubbles share it.
     */
    fun panelsAround(map: InkMap, gutter: BooleanArray, boxes: List<IntArray>): List<IntArray?> {
        val w = map.width
        val h = map.height
        // 0 unvisited; -1 part of a region too big to be a panel; else 1 + its index in [bounds].
        val label = IntArray(w * h)
        val bounds = ArrayList<IntArray>()
        val queue = IntArray(w * h)
        val limit = (MAX_PANEL * w * h).toInt()
        fun region(start: Int): IntArray? {
            label[start].let { if (it > 0) return bounds[it - 1] else if (it < 0) return null }
            val id = bounds.size + 1
            var head = 0
            var tail = 0
            label[start] = id
            queue[tail++] = start
            var x0 = start % w
            var x1 = x0
            var y0 = start / w
            var y1 = y0
            var big = false
            while (head < tail) {
                val i = queue[head++]
                val px = i % w
                val py = i / w
                if (px < x0) x0 = px
                if (px > x1) x1 = px
                if (py < y0) y0 = py
                if (py > y1) y1 = py
                neighbors(i, px, py, w, h) { n ->
                    if (label[n] != 0 || gutter[n] || tail >= queue.size) return@neighbors
                    label[n] = id
                    queue[tail++] = n
                }
                if (tail > limit) {
                    big = true
                    break
                }
            }
            if (big || (x1 - x0 + 1).toLong() * (y1 - y0 + 1) > MAX_PANEL * w * h) {
                for (k in 0 until tail) label[queue[k]] = -1
                return null
            }
            val r = intArrayOf(x0, y0, x1 + 1, y1 + 1)
            bounds += r
            return r
        }
        return boxes.map { box ->
            val x = ((box[0] + box[2]) / 2).coerceIn(0, w - 1)
            val y = ((box[1] + box[3]) / 2).coerceIn(0, h - 1)
            val start = y * w + x
            if (gutter[start]) {
                null
            } else {
                region(start)?.let {
                    intArrayOf(min(it[0], box[0]), min(it[1], box[1]), max(it[2], box[2]), max(it[3], box[3]))
                }
            }
        }
    }

    /** The paper reachable from the page's edges, one flag per map pixel - its gutters. */
    private fun gutters(map: InkMap): BooleanArray {
        val w = map.width
        val h = map.height
        val luma = map.luma
        fun paper(i: Int) = (luma[i].toInt() and 0xFF) >= GUTTER_PAPER
        val gutter = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        fun reach(i: Int) {
            if (!gutter[i] && paper(i)) {
                gutter[i] = true
                queue[tail++] = i
            }
        }
        for (px in 0 until w) {
            reach(px)
            reach((h - 1) * w + px)
        }
        for (py in 0 until h) {
            reach(py * w)
            reach(py * w + w - 1)
        }
        while (head < tail) {
            val i = queue[head++]
            val px = i % w
            val py = i / w
            if (px > 0) reach(i - 1)
            if (px < w - 1) reach(i + 1)
            if (py > 0) reach(i - w)
            if (py < h - 1) reach(i + w)
        }
        return gutter
    }

    private fun cut(
        map: InkMap,
        gutter: BooleanArray,
        piece: Piece,
        horizontal: Boolean,
        depth: Int,
        rightToLeft: Boolean,
        out: MutableList<Piece>,
    ) {
        val small = piece.x1 - piece.x0 < MIN_PIECE * map.width || piece.y1 - piece.y0 < MIN_PIECE * map.height
        if (depth < MAX_CUT_DEPTH && !small) {
            for (axisHorizontal in listOf(horizontal, !horizontal)) {
                val lines = split(map, gutter, piece, axisHorizontal)
                if (lines.isEmpty()) continue
                var parts = ArrayList<Piece>()
                val bounds = listOf<Cut?>(null) + lines + listOf<Cut?>(null)
                for (k in 0 until bounds.size - 1) {
                    val before = bounds[k]
                    val after = bounds[k + 1]
                    parts += piece.between(before, after, axisHorizontal)
                }
                if (!axisHorizontal && rightToLeft) parts = ArrayList(parts.reversed())
                for (part in parts) cut(map, gutter, part, !axisHorizontal, depth + 1, rightToLeft, out)
                return
            }
        }
        out += piece
    }

    /** The part of this piece between two cuts across it (null for its own edge). */
    private fun Piece.between(before: Cut?, after: Cut?, horizontal: Boolean): Piece {
        val cuts = this.cuts.toMutableList()
        val uFrom = if (horizontal) x0 else y0
        val uTo = if (horizontal) x1 else y1
        var vFrom = if (horizontal) y0 else x0
        var vTo = if (horizontal) y1 else x1
        if (before != null) {
            cuts += Cut(horizontal, before.u0, before.v0, before.slope, 1)
            vFrom = max(vFrom, min(before.at(uFrom), before.at(uTo - 1)).toInt())
        }
        if (after != null) {
            cuts += Cut(horizontal, after.u0, after.v0, after.slope, -1)
            vTo = min(vTo, max(after.at(uFrom), after.at(uTo - 1)).toInt() + 1)
        }
        return if (horizontal) {
            Piece(x0, vFrom, x1, vTo, cuts)
        } else {
            Piece(vFrom, y0, vTo, y1, cuts)
        }
    }

    /**
     * The gutters crossing [piece] the other way - side to side for [horizontal] - as cuts
     * along their middles, in order. Each candidate line, at every slant up to [MAX_SLANT], is
     * a gutter where nearly all of it inside the piece is paper; runs of such lines at least
     * [MIN_GAP] wide are gutters, the widest kept where they cross.
     */
    private fun split(map: InkMap, gutter: BooleanArray, piece: Piece, horizontal: Boolean): List<Cut> {
        val w = map.width
        val uFrom = if (horizontal) piece.x0 else piece.y0
        val uTo = if (horizontal) piece.x1 else piece.y1
        val vFrom = if (horizontal) piece.y0 else piece.x0
        val vTo = if (horizontal) piece.y1 else piece.x1
        val length = uTo - uFrom
        val depth = vTo - vFrom
        if (length < 4 || depth < 4) return emptyList()
        val minGap = max(2, (depth * MIN_GAP).toInt())
        val maxRise = (MAX_SLANT * length).toInt()
        val riseStep = max(1, maxRise / 12)
        val uStep = max(1, length / 300)

        class Gap(val start: Int, val end: Int, val rise: Int) {
            val mid get() = (start + end) * 0.5f
            val width get() = end - start
            fun line() = Cut(horizontal, uFrom, mid, rise.toFloat() / length, 0)
        }

        // Which of the piece's bounds are in it - its cuts tested once, not per line.
        val bw = piece.x1 - piece.x0
        val bh = piece.y1 - piece.y0
        val inPiece = if (piece.cuts.isEmpty()) {
            null
        } else {
            BooleanArray(bw * bh) { piece.contains(piece.x0 + it % bw, piece.y0 + it / bw) }
        }

        // Every line at one slant counted at once: walking along the piece, each sampled
        // column adds its pixels to the line through them - which line, by its start, shifts
        // with the slant. Then a line is clear where nearly all of it in the piece is paper.
        val gaps = ArrayList<Gap>()
        var rise = -maxRise
        while (rise <= maxRise) {
            // A slanted line starts above (or before) the piece to reach all of it.
            val from = vFrom - max(0, rise)
            val to = vTo - min(0, rise)
            val inside = IntArray(to - from)
            val paper = IntArray(to - from)
            var u = uFrom
            while (u < uTo) {
                val shift = rise * (u - uFrom) / length
                for (v in vFrom until vTo) {
                    val x = if (horizontal) u else v
                    val y = if (horizontal) v else u
                    if (inPiece != null && !inPiece[(y - piece.y0) * bw + (x - piece.x0)]) continue
                    // The line through (u, v) at this slant starts at v - shift.
                    val k = v - shift - from
                    if (k < 0 || k >= inside.size) continue
                    inside[k]++
                    if (gutter[y * w + x]) paper[k]++
                }
                u += uStep
            }
            fun clear(k: Int) = inside[k] * uStep >= length / 2 && paper[k] >= CLEAR_LINE * inside[k]
            var k = 0
            while (k < inside.size) {
                if (!clear(k)) {
                    k++
                    continue
                }
                val start = k
                while (k < inside.size && clear(k)) k++
                // A margin at either end splits nothing.
                val g = Gap(start + from, k + from, rise)
                val lo = min(g.line().at(uFrom), g.line().at(uTo - 1))
                val hi = max(g.line().at(uFrom), g.line().at(uTo - 1))
                if (g.width >= minGap && lo > vFrom + minGap && hi < vTo - minGap) gaps += g
            }
            rise += riseStep
        }
        // The widest first; one that crosses or nears a kept one is the same gutter at another slant.
        val kept = ArrayList<Gap>()
        for (g in gaps.sortedByDescending { it.width }) {
            val a = g.line()
            val clash = kept.any { k ->
                val b = k.line()
                val d0 = a.at(uFrom) - b.at(uFrom)
                val d1 = a.at(uTo - 1) - b.at(uTo - 1)
                // Crossing, or near enough at either end to lie in the same gutter.
                val near = max(g.width, k.width) + minGap * 2
                d0 * d1 <= 0f || abs(d0) < near || abs(d1) < near
            }
            if (!clash) kept += g
        }
        // No sliver of a panel: a cut too near the piece's edge or the last cut kept is dropped.
        val minBand = MIN_PIECE * (if (horizontal) map.height else map.width)
        val lines = ArrayList<Cut>()
        var lastFrom = vFrom.toFloat()
        var lastTo = vFrom.toFloat()
        for (g in kept.sortedBy { it.line().at((uFrom + uTo) / 2) }) {
            val line = g.line()
            val a = line.at(uFrom)
            val b = line.at(uTo - 1)
            if (min(a - lastFrom, b - lastTo) < minBand || min(vTo - a, vTo - b) < minBand) continue
            lines += line
            lastFrom = a
            lastTo = b
        }
        return lines
    }
}
