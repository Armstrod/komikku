package ca.mpreg.webgpuviewer.viewer

// KMK -->
/**
 * A problem the reader reports on a page while stepping through its bubbles - see
 * [ImageViewerState.startReport]: what went wrong, under which heading the long-press menu
 * offers it, and how to correct it with the debug tools.
 */
enum class BubbleProblem(val key: String, val group: String, val label: String, val hint: String) {
    MISSED(
        "missed-text",
        "Bubbles",
        "Text was missed",
        "Fit the box round the missed text and Add it, then Done",
    ),
    EXTRA(
        "not-text",
        "Bubbles",
        "Something isn't text",
        "Step to it and Delete it, then Done",
    ),
    ORDER(
        "reading-order",
        "Bubbles",
        "Wrong reading order",
        "Step through and move bubbles Earlier or Later, then Done",
    ),
    SHAPE(
        "bubble-shape",
        "Bubbles",
        "Wrong shape, or two joined",
        "Step to it, Delete it, Add the right box for each, then Done",
    ),
    PANEL(
        "panel",
        "Panels",
        "Panel wrong or missing",
        "Draw the whole panel round its bubbles - green shows the panels found - then Done",
    ),
    FRAMING(
        "framing",
        "View below the page",
        "Framed badly",
        "Step to the bubble and frame the view below with two fingers, then Done",
    ),
    DIMMING(
        "dimming",
        "View below the page",
        "Dimming wrong",
        "Step to where it goes wrong, then Done",
    ),
}
// KMK <--
