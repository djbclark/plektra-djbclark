package dev.hitlbar.overlay

// Pure protocol helpers: no Android classes, so the unit tests run on a plain JVM.
// The host side is qa/plektra_qa/button_bar.py (or the harness repo's copy of it).

const val LOG_TAG = "QAOVERLAY"
const val ACTION_SHOW = "dev.hitlbar.overlay.SHOW"
const val ACTION_HIDE = "dev.hitlbar.overlay.HIDE"
const val EXTRA_TEXT = "text"
const val EXTRA_BUTTONS = "buttons"
const val MAX_TEXT_CHARS = 200

enum class BarButton(val wire: String, val label: String) {
    DONE("done", "Done"),
    NEXT("next", "Next"),
    STOP("stop", "Stop"),
}

/**
 * The buttons named in the "buttons" extra, always in the order Done, Next, Stop so each sits in
 * the same place on every card. Unknown names are dropped. A missing extra means all three.
 */
fun parseButtons(raw: String?): List<BarButton> {
    if (raw == null) return BarButton.entries.toList()
    val wanted = raw.split(',').map { it.trim().lowercase() }.toSet()
    return BarButton.entries.filter { it.wire in wanted }
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

// The only lines the app ever logs. The instruction text is never part of them.
fun shownLine(seq: Int) = "$LOG_TAG shown seq=$seq"
fun hiddenLine(seq: Int) = "$LOG_TAG hidden seq=$seq"
fun buttonLine(button: BarButton, seq: Int) = "$LOG_TAG button=${button.wire} seq=$seq"
fun errorLine(reason: String, seq: Int) = "$LOG_TAG error=$reason seq=$seq"
