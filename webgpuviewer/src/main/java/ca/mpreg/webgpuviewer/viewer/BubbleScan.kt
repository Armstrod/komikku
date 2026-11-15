package ca.mpreg.webgpuviewer.viewer

import ca.mpreg.webgpuviewer.renderer.Image
import ca.mpreg.webgpuviewer.renderer.InkMap
import java.nio.ByteBuffer

/**
 * Bubble finding for the app outside the reader: where found bubbles are kept, how the reader's
 * images are keyed there, and finding an image's bubbles ahead of time - while its chapter
 * downloads, say - so they are ready the first time it is opened.
 */
object BubbleScan {
    /** Where found bubbles are kept between sessions; null keeps them for the session only. */
    var store: BubbleStore?
        get() = BubbleZoom.store
        set(value) {
            BubbleZoom.store = value
        }

    /** The key in [store] of an image the reader shows - null for one not to keep. */
    var keyOf: ((Image) -> String?)?
        get() = BubbleZoom.keyOf
        set(value) {
            BubbleZoom.keyOf = value
        }

    /**
     * Finds the bubbles on an image given as 8-bit RGBA [pixels], [width] x [height], and keeps
     * them in [store] under [key] - unless already kept there. Slow: off the main thread. The
     * number of bubbles found.
     */
    fun scan(pixels: ByteBuffer, width: Int, height: Int, key: String, rightToLeft: Boolean = true): Int {
        val map = InkMap.from(pixels, width, height) ?: return 0
        return BubbleZoom.found(map, BubbleDetect.NO_EDGE, rightToLeft, key).size
    }
}
