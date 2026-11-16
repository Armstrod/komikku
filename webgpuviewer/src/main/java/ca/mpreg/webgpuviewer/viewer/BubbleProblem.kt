package ca.mpreg.webgpuviewer.viewer

// KMK -->
/**
 * What the reader corrects on a page while reading - see [ImageViewerState.startReport]: the
 * long-press menu's choices, and the hint shown while correcting.
 */
enum class BubbleProblem(val key: String, val label: String, val hint: String) {
    BUBBLES(
        "bubbles",
        "Edit bubbles",
        "Tap below the page to step. Earlier / Later move the red one, Delete removes it, Add draws " +
            "a missed one. Save when the order is right",
    ),
    PANELS(
        "panels",
        "Edit panels",
        "◀ ▶ pick a panel (red). Earlier / Later move it in the order, Delete removes it, Add draws " +
            "one. Save when every panel is right",
    ),
}
// KMK <--
