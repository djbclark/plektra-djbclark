package dev.hitlbar.overlay

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.TextAttribute
import android.widget.EditText
import android.widget.LinearLayout

// The views of the test bench that report what reaches them. All calls come on the main thread.

/** Where the views report to. */
interface ProbeSink {
    /** Logs one event and returns its "seq". */
    fun emit(kind: String, data: List<Pair<String, Any?>>): Int

    /** Runs [block]; text changes inside it are put down to [cause] and the line [by]. */
    fun <T> during(cause: String, by: Int, block: () -> T): T

    /** A keyboard asked [box] for a new connection. Returns the connection's number. */
    fun connectionOpened(box: ProbeField, info: EditorInfo): Int

    /** The cause running now and the line that began it. */
    fun cause(): Pair<String, Int>

    /** A hardware key on its way to the keyboard. */
    fun keyBeforeIme(event: KeyEvent)
}

/** The screen's root: the only place that sees a key before the keyboard does. */
class ProbeRoot(context: Context, private val sink: ProbeSink) : LinearLayout(context) {
    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        sink.keyBeforeIme(event)
        return super.dispatchKeyEventPreIme(event)
    }
}

/** A text box that reports its text changes, cursor moves and every call the keyboard makes on it. */
class ProbeEditText(context: Context, val box: ProbeField, private val sink: ProbeSink) : EditText(context) {
    // False while the base class's constructor runs, which already calls onSelectionChanged.
    private var ready = false

    init {
        addTextChangedListener(object : TextWatcher {
            private var removed = ""

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                removed = s?.subSequence(start, start + count)?.toString() ?: ""
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val whole = s?.toString() ?: ""
                val inserted = whole.substring(start, start + count)
                val (cause, by) = sink.cause()
                val cut = removed.length > MAX_VALUE_CHARS || inserted.length > MAX_VALUE_CHARS
                sink.emit(
                    "text",
                    listOf(
                        "field" to box.key,
                        "cause" to cause,
                        "by" to by,
                        "start" to start,
                        "removed" to capText(removed),
                        "inserted" to capText(inserted),
                        "text" to capText(whole, keepEnd = true),
                        "len" to whole.length,
                        "cut" to cut,
                    ),
                )
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
        ready = true
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!ready) return
        sink.emit("sel", listOf("field" to box.key, "start" to selStart, "end" to selEnd))
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val target = super.onCreateInputConnection(outAttrs) ?: return null
        return ProbeConnection(target, box, sink.connectionOpened(box, outAttrs), sink)
    }
}

/**
 * Logs every call a keyboard makes that can change the text, the cursor or the focus, before passing
 * it on. Reads (getTextBeforeCursor and the like) are not logged. The TextAttribute forms of Android
 * 13 and later go straight to the target, so they are wrapped too.
 */
class ProbeConnection(
    target: InputConnection,
    private val box: ProbeField,
    private val number: Int,
    private val sink: ProbeSink,
) : InputConnectionWrapper(target, false) {
    private fun <T> call(name: String, vararg args: Pair<String, Any?>, block: () -> T): T {
        val head = listOf<Pair<String, Any?>>("field" to box.key, "call" to name, "conn" to number)
        val seq = sink.emit("ic", head + args)
        return sink.during(CAUSE_IC, seq, block)
    }

    private fun textArgs(text: CharSequence?, position: Int): Array<Pair<String, Any?>> {
        val value = text?.toString() ?: ""
        return arrayOf("text" to capText(value), "pos" to position, "cut" to (value.length > MAX_VALUE_CHARS))
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
        call("commitText", *textArgs(text, newCursorPosition)) { super.commitText(text, newCursorPosition) }

    override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
        call("commitText", *textArgs(text, newCursorPosition)) {
            super.commitText(text, newCursorPosition, textAttribute)
        }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
        call("setComposingText", *textArgs(text, newCursorPosition)) {
            super.setComposingText(text, newCursorPosition)
        }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
        call("setComposingText", *textArgs(text, newCursorPosition)) {
            super.setComposingText(text, newCursorPosition, textAttribute)
        }

    override fun setComposingRegion(start: Int, end: Int): Boolean =
        call("setComposingRegion", "start" to start, "end" to end) { super.setComposingRegion(start, end) }

    override fun setComposingRegion(start: Int, end: Int, textAttribute: TextAttribute?): Boolean =
        call("setComposingRegion", "start" to start, "end" to end) {
            super.setComposingRegion(start, end, textAttribute)
        }

    override fun finishComposingText(): Boolean = call("finishComposingText") { super.finishComposingText() }

    override fun replaceText(
        start: Int,
        end: Int,
        text: CharSequence,
        newCursorPosition: Int,
        textAttribute: TextAttribute?,
    ): Boolean =
        call("replaceText", "start" to start, "end" to end, *textArgs(text, newCursorPosition)) {
            super.replaceText(start, end, text, newCursorPosition, textAttribute)
        }

    override fun commitCompletion(text: CompletionInfo?): Boolean =
        call("commitCompletion", "text" to capText(text?.text?.toString() ?: "")) { super.commitCompletion(text) }

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean =
        call("commitCorrection", "text" to capText(correctionInfo?.newText?.toString() ?: "")) {
            super.commitCorrection(correctionInfo)
        }

    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean =
        call("commitContent") { super.commitContent(inputContentInfo, flags, opts) }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        call("deleteSurroundingText", "before" to beforeLength, "after" to afterLength) {
            super.deleteSurroundingText(beforeLength, afterLength)
        }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        call("deleteSurroundingTextInCodePoints", "before" to beforeLength, "after" to afterLength) {
            super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        }

    // The key itself comes back later, to the app, as a "key" line that was not seen before the keyboard.
    override fun sendKeyEvent(event: KeyEvent): Boolean {
        val typed = event.unicodeChar
        return call(
            "sendKeyEvent",
            "action" to keyActionName(event.action),
            "name" to keyName(event.keyCode),
            "code" to event.keyCode,
            "meta" to event.metaState,
            "ch" to if (typed > 0) String(Character.toChars(typed)) else "",
        ) { super.sendKeyEvent(event) }
    }

    override fun setSelection(start: Int, end: Int): Boolean =
        call("setSelection", "start" to start, "end" to end) { super.setSelection(start, end) }

    override fun performEditorAction(editorAction: Int): Boolean =
        call("performEditorAction", "action" to actionName(editorAction)) { super.performEditorAction(editorAction) }

    override fun performContextMenuAction(id: Int): Boolean =
        call("performContextMenuAction", "id" to id) { super.performContextMenuAction(id) }

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean =
        call("performPrivateCommand", "action" to capText(action ?: "")) { super.performPrivateCommand(action, data) }

    override fun clearMetaKeyStates(states: Int): Boolean =
        call("clearMetaKeyStates", "meta" to states) { super.clearMetaKeyStates(states) }

    override fun beginBatchEdit(): Boolean = call("beginBatchEdit") { super.beginBatchEdit() }

    override fun endBatchEdit(): Boolean = call("endBatchEdit") { super.endBatchEdit() }

    override fun closeConnection() {
        call("closeConnection") { super.closeConnection() }
    }
}

fun keyName(code: Int): String = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

@Suppress("DEPRECATION") // ACTION_MULTIPLE still arrives from some keyboards
fun keyActionName(action: Int): String = when (action) {
    KeyEvent.ACTION_DOWN -> "down"
    KeyEvent.ACTION_UP -> "up"
    KeyEvent.ACTION_MULTIPLE -> "multiple"
    else -> action.toString()
}
