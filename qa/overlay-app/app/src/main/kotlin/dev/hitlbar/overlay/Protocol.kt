package dev.hitlbar.overlay

// Pure protocol helpers: no Android classes, so the unit tests run on a plain JVM.
// The host side is qa/plektra_qa/button_bar.py (or the harness repo's copy of it).

const val LOG_TAG = "QAOVERLAY"
const val ACTION_SHOW = "dev.hitlbar.overlay.SHOW"
const val ACTION_HIDE = "dev.hitlbar.overlay.HIDE"
const val EXTRA_TEXT = "text"
const val EXTRA_BUTTONS = "buttons"
const val MAX_TEXT_CHARS = 200

/**
 * [wire] is the protocol name, [slot] the button's place in the row (0 is leftmost), [color] its
 * own color. "retry" (Redo) restarts the trial on screen; "redo" (Prev) goes to the one before.
 * The variation selector keeps the triangle from being drawn as an emoji.
 */
enum class BarButton(val wire: String, val label: String, val slot: Int, val color: String) {
    DONE("done", "Done", 0, "#2E7D32"),
    NOREC("norec", "No rec", 1, "#6D4C41"),
    RETRY("retry", "Redo", 1, "#7B1FA2"),
    REDO("redo", "\u25C0\uFE0E Prev", 2, "#1565C0"),
    NEXT("next", "Next", 3, "#455A64"),
    STOP("stop", "Stop", 4, "#C62828"),
}

/** Places in the button row. */
const val BUTTON_SLOTS = 5

/** What replaces a card the moment one of its buttons is tapped (the host sends the same text). */
const val WAIT_TEXT = "Wait a few seconds..."

/** What a card shows when the host names no buttons. */
val DEFAULT_BUTTONS = listOf(BarButton.DONE, BarButton.NEXT, BarButton.STOP)

/**
 * The buttons named in the "buttons" extra, always in the enum's order so each sits in the same
 * place on every card. Unknown names are dropped. A missing extra means Done, Next and Stop.
 */
fun parseButtons(raw: String?): List<BarButton> {
    if (raw == null) return DEFAULT_BUTTONS
    val wanted = raw.split(',').map { it.trim().lowercase() }.toSet()
    return BarButton.entries.filter { it.wire in wanted }
}

/**
 * The button row: each button in its own place, so it never moves from card to card; null is an
 * empty place. Should two buttons want one place, the row is just the buttons.
 */
fun layoutSlots(buttons: List<BarButton>): List<BarButton?> {
    val slots = arrayOfNulls<BarButton>(BUTTON_SLOTS)
    for (button in buttons) {
        if (slots[button.slot] != null) return buttons
        slots[button.slot] = button
    }
    return slots.toList()
}

/** Closing a bubble cancels a live card. Done is safe only on the final card. */
fun dismissButton(buttons: List<BarButton>): BarButton? = when {
    BarButton.STOP in buttons -> BarButton.STOP
    buttons.toSet() == setOf(BarButton.DONE, BarButton.REDO) -> BarButton.DONE
    else -> null
}

/** The instruction text, at most [MAX_TEXT_CHARS] chars, never cutting a surrogate pair. */
fun clampText(raw: String?): String {
    if (raw == null) return ""
    if (raw.length <= MAX_TEXT_CHARS) return raw
    val cut = if (raw[MAX_TEXT_CHARS - 1].isHighSurrogate()) MAX_TEXT_CHARS - 1 else MAX_TEXT_CHARS
    return raw.substring(0, cut)
}

/** The counter in "seq=N". It counts up per log line for as long as the process lives. */
class SeqCounter {
    private var value = 0
    fun next(): Int = ++value
}

// The only lines the bar logs (the test bench has its own, see ProbeProtocol.kt). The instruction text
// is never part of them.
fun shownLine(seq: Int) = "$LOG_TAG shown seq=$seq"
fun hiddenLine(seq: Int) = "$LOG_TAG hidden seq=$seq"
fun buttonLine(button: BarButton, seq: Int) = "$LOG_TAG button=${button.wire} seq=$seq"
fun errorLine(reason: String, seq: Int) = "$LOG_TAG error=$reason seq=$seq"
