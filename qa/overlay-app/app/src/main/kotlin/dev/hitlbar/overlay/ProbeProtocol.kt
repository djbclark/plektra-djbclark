package dev.hitlbar.overlay

import android.text.InputType
import android.view.inputmethod.EditorInfo

// Pure protocol helpers of the test bench screen (BenchActivity). Only compile-time constants of the
// platform are used, so the unit tests run on a plain JVM. The host side is qa/plektra_qa/probe.py.

const val PROBE_TAG = "QAPROBE"

/** The "v" of every probe line. Raise it when a field changes its meaning. */
const val PROBE_VERSION = 1

/** Longest string value in a line, so that a line stays under logcat's limit. */
const val MAX_VALUE_CHARS = 200

/** Longest field text in a "reset" line, which carries every field at once. */
const val MAX_RESET_CHARS = 40

/**
 * Intent extras that reset the screen: a tag for the trial, the box to focus, the text the person is
 * asked to type (only logged and shown) and one starting text per box.
 */
const val EXTRA_TRIAL = "qa.trial"
const val EXTRA_FOCUS = "qa.focus"
const val EXTRA_EXPECT = "qa.expect"
const val EXTRA_TEXT_PREFIX = "qa.text."

/** Height kept free below the status bar: the button bar lies over it (4 text lines and a button row). */
const val BAR_CLEARANCE_DP = 124

/** Boxes per row of the grid. */
const val GRID_COLUMNS = 3

/** What made the text of a box change: a keyboard call, a key the app handled, a reset, or the app. */
const val CAUSE_IC = "ic"
const val CAUSE_KEY = "key"
const val CAUSE_RESET = "reset"
const val CAUSE_APP = "app"

/**
 * The boxes, in Tab order and in reading order (left to right, top to bottom). [key] is the name in
 * the probe lines and the intent extras; the resource id is "probe_" + [key].
 */
enum class ProbeField(val key: String, val label: String, val inputType: Int, val imeOptions: Int?) {
    BODY(
        "body", "1 Body: multi-line",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        null,
    ),
    TITLE("title", "2 Title: caps", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, null),
    PLAIN("plain", "3 Plain", InputType.TYPE_CLASS_TEXT, null),
    EMAIL("email", "4 Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, null),
    URL("url", "5 URL", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, null),
    NUMBER("number", "6 Number", InputType.TYPE_CLASS_NUMBER, null),
    PASSWORD(
        "password", "7 Password (logged)",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, null,
    ),
    SEARCH("search", "8 Search action", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH),
    NOSUGGEST(
        "nosuggest", "9 No suggestions",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, null,
    ),
    ;

    /** The box Tab goes to from this one; the last goes back to the first. */
    val next: ProbeField get() = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(key: String?): ProbeField? = entries.firstOrNull { it.key == key }
    }
}

/** What an intent asks of the screen. A box that is not named becomes empty. */
data class ResetRequest(
    val trial: String,
    val focus: ProbeField,
    val texts: Map<ProbeField, String>,
    val expect: String = "",
)

/** Reads the extras through [extra]. No or an unknown focus name means the first box. */
fun parseReset(extra: (String) -> String?): ResetRequest {
    val texts = ProbeField.entries.mapNotNull { box ->
        extra(EXTRA_TEXT_PREFIX + box.key)?.let { box to it }
    }.toMap()
    return ResetRequest(
        trial = extra(EXTRA_TRIAL) ?: "",
        focus = ProbeField.of(extra(EXTRA_FOCUS)) ?: ProbeField.entries.first(),
        texts = texts,
        expect = extra(EXTRA_EXPECT) ?: "",
    )
}

/** The hardware keys pressed since the last reset, as one line for the header: typos show at once. */
class PressedKeys(private val size: Int = 60) {
    private val keys = ArrayDeque<String>()

    fun clear() = keys.clear()

    /** A key going down before the keyboard; [name] is its key code name, [typed] what it types. */
    fun add(name: String, typed: String, repeat: Int) {
        if (repeat > 0) return
        keys.addLast(KEY_SIGNS[name] ?: typed.ifEmpty { name })
        while (keys.size > size) keys.removeFirst()
    }

    /** The newest keys last. */
    fun render(): String = keys.joinToString(" ")
}

private val KEY_SIGNS = mapOf(
    "DEL" to "\u232B",
    "FORWARD_DEL" to "\u2326",
    "ENTER" to "\u23CE",
    "NUMPAD_ENTER" to "\u23CE",
    "TAB" to "\u21E5",
    "SPACE" to "\u2423",
    "SHIFT_LEFT" to "\u21E7",
    "SHIFT_RIGHT" to "\u21E7",
    "ALT_LEFT" to "Alt",
    "ALT_RIGHT" to "Alt",
    "CTRL_LEFT" to "Ctrl",
    "CTRL_RIGHT" to "Ctrl",
    "SYM" to "Sym",
    "FUNCTION" to "Fn",
)

/** At most [max] chars from the start (or the end, with [keepEnd]), never half a surrogate pair. */
fun capText(text: String, max: Int = MAX_VALUE_CHARS, keepEnd: Boolean = false): String {
    if (text.length <= max) return text
    if (keepEnd) {
        var from = text.length - max
        if (text[from].isLowSurrogate()) from++
        return text.substring(from)
    }
    val to = if (text[max - 1].isHighSurrogate()) max - 1 else max
    return text.substring(0, to)
}

/** One probe line: a JSON object with "v", "seq", "t" (uptime ms), "kind", then [data] in order. */
fun probeLine(seq: Int, uptimeMs: Long, kind: String, data: List<Pair<String, Any?>>): String {
    val head = listOf("v" to PROBE_VERSION, "seq" to seq, "t" to uptimeMs, "kind" to kind)
    return jsonObject(head + data)
}

private fun jsonObject(pairs: List<Pair<String, Any?>>): String =
    pairs.joinToString(",", "{", "}") { (name, value) -> jsonString(name) + ":" + jsonValue(value) }

private fun jsonValue(value: Any?): String = when (value) {
    null -> "null"
    is Boolean, is Int, is Long -> value.toString()
    is Map<*, *> -> jsonObject(value.entries.map { it.key.toString() to it.value })
    else -> jsonString(value.toString())
}

/** A JSON string: quotes, backslashes, control characters and lone line separators are escaped. */
fun jsonString(text: String): String {
    val out = StringBuilder(text.length + 2).append('"')
    for (c in text) {
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c < ' ' || c == '\u2028' || c == '\u2029' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> out.append(c)
        }
    }
    return out.append('"').toString()
}

/** The same event as one short line of the on-screen panel: the last six digits of the time first. */
fun panelLine(uptimeMs: Long, kind: String, data: List<Pair<String, Any?>>): String {
    val rest = data.filter { (name, value) -> name !in PANEL_HIDDEN && value != null && value != "" }
        .joinToString(" ") { (name, value) ->
            val shown = if (value is String) value.replace("\n", "\u23CE") else value.toString()
            if (name in PANEL_BARE) shown else "$name=$shown"
        }
    return "${(uptimeMs % 1_000_000).toString().padStart(6, '0')} $kind $rest"
}

/** Left out of the panel (they are in the log line). */
private val PANEL_HIDDEN = setOf("down", "evt", "flags", "devName", "conn", "len")

/** Shown in the panel without their name. */
private val PANEL_BARE = setOf("stage", "action", "name", "call", "state", "field", "event")

private val TEXT_VARIATIONS = mapOf(
    InputType.TYPE_TEXT_VARIATION_URI to "uri",
    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS to "email",
    InputType.TYPE_TEXT_VARIATION_PASSWORD to "password",
    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD to "visiblePassword",
    InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT to "webEdit",
)

private val TEXT_FLAGS = listOf(
    InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS to "capChars",
    InputType.TYPE_TEXT_FLAG_CAP_WORDS to "capWords",
    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES to "capSentences",
    InputType.TYPE_TEXT_FLAG_AUTO_CORRECT to "autoCorrect",
    InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE to "autoComplete",
    InputType.TYPE_TEXT_FLAG_MULTI_LINE to "multiLine",
    InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE to "imeMultiLine",
    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS to "noSuggestions",
)

/** An inputType in words, for the header. */
fun describeInputType(type: Int): String {
    val variation = type and InputType.TYPE_MASK_VARIATION
    return when (type and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_NULL -> "null"
        InputType.TYPE_CLASS_TEXT -> {
            val words = listOfNotNull("text", TEXT_VARIATIONS[variation]) +
                TEXT_FLAGS.filter { type and it.first != 0 }.map { it.second }
            words.joinToString(" ")
        }
        InputType.TYPE_CLASS_NUMBER -> "number"
        InputType.TYPE_CLASS_PHONE -> "phone"
        InputType.TYPE_CLASS_DATETIME -> "datetime"
        else -> "other"
    }
}

private val ACTIONS = mapOf(
    EditorInfo.IME_ACTION_UNSPECIFIED to "unspecified",
    EditorInfo.IME_ACTION_NONE to "none",
    EditorInfo.IME_ACTION_GO to "go",
    EditorInfo.IME_ACTION_SEARCH to "search",
    EditorInfo.IME_ACTION_SEND to "send",
    EditorInfo.IME_ACTION_NEXT to "next",
    EditorInfo.IME_ACTION_DONE to "done",
    EditorInfo.IME_ACTION_PREVIOUS to "previous",
)

private val OPTION_FLAGS = listOf(
    EditorInfo.IME_FLAG_NO_ENTER_ACTION to "noEnterAction",
    EditorInfo.IME_FLAG_NAVIGATE_NEXT to "navNext",
    EditorInfo.IME_FLAG_NAVIGATE_PREVIOUS to "navPrevious",
    EditorInfo.IME_FLAG_NO_EXTRACT_UI to "noExtractUi",
    EditorInfo.IME_FLAG_NO_FULLSCREEN to "noFullscreen",
)

fun actionName(action: Int): String = ACTIONS[action] ?: action.toString()

/** imeOptions in words: the action, then the flags. */
fun describeImeOptions(options: Int): String {
    val words = listOf(actionName(options and EditorInfo.IME_MASK_ACTION)) +
        OPTION_FLAGS.filter { options and it.first != 0 }.map { it.second }
    return words.joinToString(" ")
}

/** What identifies one key event on its way from before the keyboard to after it. */
data class KeyId(
    val code: Int,
    val action: Int,
    val eventTime: Long,
    val downTime: Long,
    val device: Int,
    val repeat: Int,
)

/** The key events last seen before the keyboard, so that a later one can say whether it was among them. */
class KeyMemory(private val size: Int = 64) {
    private val seen = LinkedHashSet<KeyId>()

    fun remember(id: KeyId) {
        seen.add(id)
        while (seen.size > size) seen.remove(seen.first())
    }

    /** True once for an event that was remembered. */
    fun take(id: KeyId): Boolean = seen.remove(id)
}

/** Which of the things that can change text is running now, and the "seq" of the line that began it. */
class Causes {
    var kind = CAUSE_APP
        private set
    var by = 0
        private set

    fun <T> during(newKind: String, newBy: Int, block: () -> T): T {
        val oldKind = kind
        val oldBy = by
        kind = newKind
        by = newBy
        try {
            return block()
        } finally {
            kind = oldKind
            by = oldBy
        }
    }
}

/** One trackpad or touch movement, summed up into one line when it ends. */
class MotionTrack(
    private val type: String,
    private val source: Int,
    private val device: Int,
    private val startX: Float,
    private val startY: Float,
    private val startMs: Long,
) {
    private var x = startX
    private var y = startY
    private var lastMs = startMs
    private var samples = 0
    private var pointers = 1
    private var scrollX = 0f
    private var scrollY = 0f

    fun add(newX: Float, newY: Float, atMs: Long, pointerCount: Int, hScroll: Float = 0f, vScroll: Float = 0f) {
        x = newX
        y = newY
        lastMs = atMs
        samples++
        pointers = maxOf(pointers, pointerCount)
        scrollX += hScroll
        scrollY += vScroll
    }

    fun summary(end: String): List<Pair<String, Any?>> = listOf(
        "event" to type,
        "source" to "0x" + source.toString(16),
        "dev" to device,
        "x" to startX.toInt(),
        "y" to startY.toInt(),
        "dx" to (x - startX).toInt(),
        "dy" to (y - startY).toInt(),
        "samples" to samples,
        "pointers" to pointers,
        "scrollX" to scrollX.toInt(),
        "scrollY" to scrollY.toInt(),
        "ms" to lastMs - startMs,
        "end" to end,
    )
}
