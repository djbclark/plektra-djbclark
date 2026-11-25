package dev.hitlbar.overlay

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView

/** The "seq" of the probe lines: it counts up for as long as the process lives. */
private val probeSeq = SeqCounter()

/**
 * The test bench: a grid of text boxes of different kinds to Tab between, and below them a header and
 * a panel that show what happens. Every event is also one logcat line (tag QAPROBE, format in the
 * README), so that the host can tell where a lost character disappeared: before the app saw the key,
 * in the keyboard, or in the app. Only this screen logs such lines.
 *
 * It is an instrument for testing keyboards, and works with any keyboard. Nothing is saved. An intent
 * (also a second start) empties or prefills every box and focuses one; see [parseReset]. The header
 * shows the text the person is asked to type and the keys pressed since then.
 *
 * The panel and the header never take focus and have a fixed place under the boxes, so a new line
 * never moves a box.
 */
class BenchActivity : Activity(), ProbeSink {
    private val causes = Causes()
    private val preIme = KeyMemory()
    private val handler = Handler(Looper.getMainLooper())
    private val lines = ArrayDeque<String>()
    private val editorInfo = HashMap<ProbeField, Pair<Int, Int>>() // inputType to imeOptions, as published
    private val boxes = LinkedHashMap<ProbeField, ProbeEditText>()
    private lateinit var imeLine: TextView
    private lateinit var focusLine: TextView
    private lateinit var keyLine: TextView
    private val pressed = PressedKeys()
    private var expected = ""
    private lateinit var panel: TextView
    private var panelPending = false
    private var connections = 0
    private var ime = ""
    private var touch: MotionTrack? = null
    private var generic: MotionTrack? = null
    private val genericQuiet = Runnable { endGeneric("quiet") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        life("create", "restored" to (savedInstanceState != null))
        // After a restart by the system the boxes keep their restored text.
        if (savedInstanceState == null) reset(intent) else refreshHeader()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        life("new-intent")
        reset(intent)
    }

    override fun onStart() {
        super.onStart()
        life("start")
    }

    override fun onResume() {
        super.onResume()
        life("resume")
        refreshIme()
    }

    override fun onPause() {
        life("pause")
        super.onPause()
    }

    override fun onStop() {
        life("stop")
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        life("destroy")
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        life(if (hasFocus) "window-focus" else "window-blur")
        if (hasFocus) refreshIme()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        emit(
            "config",
            listOf(
                "keyboard" to newConfig.keyboard,
                "keyboardHidden" to newConfig.keyboardHidden,
                "hardKeyboardHidden" to newConfig.hardKeyboardHidden,
                "navigation" to newConfig.navigation,
                "orientation" to newConfig.orientation,
                "widthDp" to newConfig.screenWidthDp,
                "heightDp" to newConfig.screenHeightDp,
                "uiMode" to newConfig.uiMode,
            ),
        )
        refreshIme()
    }

    // -- the probe lines ----------------------------------------------------------------------------

    override fun emit(kind: String, data: List<Pair<String, Any?>>): Int {
        val seq = probeSeq.next()
        val now = SystemClock.uptimeMillis()
        Log.i(PROBE_TAG, probeLine(seq, now, kind, data))
        lines.addFirst(panelLine(now, kind, data))
        while (lines.size > PANEL_LINES) lines.removeLast()
        refreshPanel()
        return seq
    }

    override fun <T> during(cause: String, by: Int, block: () -> T): T = causes.during(cause, by, block)

    override fun cause(): Pair<String, Int> = causes.kind to causes.by

    override fun connectionOpened(box: ProbeField, info: EditorInfo): Int {
        val number = ++connections
        editorInfo[box] = info.inputType to info.imeOptions
        emit(
            "conn",
            listOf(
                "field" to box.key,
                "conn" to number,
                "inputType" to info.inputType,
                "imeOptions" to info.imeOptions,
                "selStart" to info.initialSelStart,
                "selEnd" to info.initialSelEnd,
            ),
        )
        refreshHeader()
        return number
    }

    private fun life(state: String, vararg more: Pair<String, Any?>) {
        emit("life", listOf<Pair<String, Any?>>("state" to state) + more)
    }

    // -- keys and movement --------------------------------------------------------------------------

    override fun keyBeforeIme(event: KeyEvent) {
        preIme.remember(keyId(event))
        emit("key", listOf<Pair<String, Any?>>("stage" to "pre") + keyData(event))
        if (event.action == KeyEvent.ACTION_DOWN) {
            pressed.add(keyName(event.keyCode), typedBy(event), event.repeatCount)
            refreshKeys()
        }
    }

    /** After the keyboard: what it did not consume, and what it sent itself ("pre" false). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val seenBefore = preIme.take(keyId(event))
        val data = listOf<Pair<String, Any?>>("stage" to "post") + keyData(event) + ("pre" to seenBefore)
        val seq = emit("key", data)
        return causes.during(CAUSE_KEY, seq) { super.dispatchKeyEvent(event) }
    }

    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean {
        val seq = emit("shortcut", keyData(event))
        return causes.during(CAUSE_KEY, seq) { super.dispatchKeyShortcutEvent(event) }
    }

    private fun keyId(e: KeyEvent) = KeyId(e.keyCode, e.action, e.eventTime, e.downTime, e.deviceId, e.repeatCount)

    /** What the key types with its modifiers by the system's key map; nothing for a dead key. */
    private fun typedBy(e: KeyEvent): String {
        val typed = e.unicodeChar
        return if (typed > 0) String(Character.toChars(typed)) else ""
    }

    private fun keyData(e: KeyEvent): List<Pair<String, Any?>> {
        @Suppress("DEPRECATION")
        val many = if (e.action == KeyEvent.ACTION_MULTIPLE) e.characters else null
        return listOf(
            "action" to keyActionName(e.action),
            "name" to keyName(e.keyCode),
            "code" to e.keyCode,
            "scan" to e.scanCode,
            "meta" to e.metaState,
            "repeat" to e.repeatCount,
            "dev" to e.deviceId,
            "devName" to (e.device?.name ?: ""),
            "ch" to typedBy(e).ifEmpty { many ?: "" },
            "flags" to e.flags,
            "evt" to e.eventTime,
            "down" to e.downTime,
            "field" to focusedBox()?.key,
        )
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> touch = track("touch", ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touch?.let {
                it.add(ev.x, ev.y, ev.eventTime, ev.pointerCount)
                emit("motion", it.summary(if (ev.actionMasked == MotionEvent.ACTION_UP) "up" else "cancel"))
                touch = null
            }
            else -> touch?.add(ev.x, ev.y, ev.eventTime, ev.pointerCount)
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Trackpad, mouse and scroll movement that is not a touch: one line when it has been quiet. */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val current = generic ?: track("generic", ev).also { generic = it }
        current.add(
            ev.x, ev.y, ev.eventTime, ev.pointerCount,
            ev.getAxisValue(MotionEvent.AXIS_HSCROLL), ev.getAxisValue(MotionEvent.AXIS_VSCROLL),
        )
        handler.removeCallbacks(genericQuiet)
        handler.postDelayed(genericQuiet, MOTION_QUIET_MS)
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun track(type: String, ev: MotionEvent) =
        MotionTrack(type, ev.source, ev.deviceId, ev.x, ev.y, ev.eventTime)

    private fun endGeneric(end: String) {
        val done = generic ?: return
        generic = null
        emit("motion", done.summary(end))
    }

    // -- reset --------------------------------------------------------------------------------------

    private fun reset(intent: Intent) {
        val request = parseReset { intent.getStringExtra(it) }
        val target = boxes.getValue(request.focus)
        causes.during(CAUSE_RESET, 0) {
            for ((box, view) in boxes) {
                view.setText(request.texts[box] ?: "")
                view.setSelection(view.text.length)
            }
            target.requestFocus()
        }
        // A fresh input connection: the keyboard forgets what it was composing.
        getSystemService(InputMethodManager::class.java).restartInput(target)
        expected = request.expect
        pressed.clear()
        refreshKeys()
        refreshIme()
        refreshHeader()
        val texts = boxes.entries.filter { it.value.text.isNotEmpty() }
            .associate { it.key.key to capText(it.value.text.toString(), MAX_RESET_CHARS) }
        emit(
            "reset",
            listOf(
                "trial" to request.trial,
                "field" to focusedBox()?.key,
                "expect" to capText(request.expect),
                "texts" to texts,
            ),
        )
    }

    // -- the screen ---------------------------------------------------------------------------------

    private fun focusedBox(): ProbeField? = (currentFocus as? ProbeEditText)?.box

    private fun refreshIme() {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val info = try {
            packageManager.getPackageInfo(id.substringBefore('/'), 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val version = info?.versionName ?: "?"
        val code = info?.longVersionCode ?: -1L
        val now = "$id $version ($code)"
        if (now != ime) {
            ime = now
            emit("ime", listOf("id" to id, "version" to version, "code" to code))
        }
        refreshHeader()
    }

    private fun refreshHeader() {
        if (!::imeLine.isInitialized) return
        imeLine.text = "IME $ime"
        val box = focusedBox()
        val published = editorInfo[box]
        focusLine.text = when {
            box == null -> "focus: no box"
            published == null -> "focus ${box.key}: no keyboard connection yet"
            else -> "focus ${box.key}: type 0x${published.first.toString(16)} " +
                "${describeInputType(published.first)} | ime 0x${published.second.toString(16)} " +
                describeImeOptions(published.second)
        }
    }

    private fun refreshKeys() {
        if (!::keyLine.isInitialized) return
        val wanted = if (expected.isEmpty()) "" else "type \"${expected.replace("\n", "\u23CE")}\" | "
        keyLine.text = wanted + "keys: " + pressed.render()
        // The newest keys stay in view.
        keyLine.ellipsize = TextUtils.TruncateAt.START
    }

    /** Redraws the panel once per frame, however many lines came in. */
    private fun refreshPanel() {
        if (panelPending || !::panel.isInitialized) return
        panelPending = true
        panel.postOnAnimation {
            panelPending = false
            panel.text = lines.joinToString("\n")
        }
    }

    private fun buildScreen(): View {
        val side = dp(8)
        val root = ProbeRoot(this, this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsets.Type.ime())
            // The boxes hang from the top; only the panel at the bottom gives way to the keyboard.
            v.setPadding(
                side + bars.left, bars.top + dp(BAR_CLEARANCE_DP),
                side + bars.right, maxOf(bars.bottom, keyboard.bottom),
            )
            insets
        }
        for (row in ProbeField.entries.chunked(GRID_COLUMNS)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { place, box ->
                line.addView(
                    cell(box),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (place > 0) marginStart = dp(4)
                    },
                )
            }
            root.addView(line, matchWidth(LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        imeLine = smallText(10f)
        focusLine = smallText(10f)
        keyLine = smallText(12f).apply { typeface = Typeface.MONOSPACE }
        for (line in listOf(keyLine, imeLine, focusLine)) {
            root.addView(line, matchWidth(LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        refreshKeys()
        panel = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setHorizontallyScrolling(true) // one event, one line
            isFocusable = false
            isFocusableInTouchMode = false
        }
        root.addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun cell(box: ProbeField): View {
        val view = ProbeEditText(this, box, this).apply {
            id = resourceId(box)
            inputType = box.inputType
            box.imeOptions?.let { imeOptions = it }
            nextFocusForwardId = resourceId(box.next)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            background = boxBackground()
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setOnFocusChangeListener { _, gained ->
                emit("focus", listOf("field" to box.key, "gained" to gained))
                if (gained) refreshHeader()
            }
            setOnEditorActionListener { _, action, event ->
                emit(
                    "action",
                    listOf("field" to box.key, "action" to actionName(action), "fromKey" to (event != null)),
                )
                box == ProbeField.SEARCH // the search box keeps the focus
            }
        }
        boxes[box] = view
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Labels, not hints: an empty box then reads as empty to the harness.
            addView(smallText(10f).apply { text = box.label })
            addView(view, matchWidth(dp(BOX_HEIGHT_DP)))
        }
    }

    private fun resourceId(box: ProbeField): Int = when (box) {
        ProbeField.BODY -> R.id.probe_body
        ProbeField.TITLE -> R.id.probe_title
        ProbeField.PLAIN -> R.id.probe_plain
        ProbeField.EMAIL -> R.id.probe_email
        ProbeField.URL -> R.id.probe_url
        ProbeField.NUMBER -> R.id.probe_number
        ProbeField.PASSWORD -> R.id.probe_password
        ProbeField.SEARCH -> R.id.probe_search
        ProbeField.NOSUGGEST -> R.id.probe_nosuggest
    }

    private fun boxBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), outline(dp(2), Color.parseColor("#FFB300")))
        addState(intArrayOf(), outline(dp(1), Color.GRAY))
    }

    private fun outline(width: Int, color: Int) = GradientDrawable().apply {
        cornerRadius = dp(4).toFloat()
        setStroke(width, color)
    }

    private fun smallText(size: Float) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun matchWidth(height: Int) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val PANEL_LINES = 40
        const val BOX_HEIGHT_DP = 40
        const val MOTION_QUIET_MS = 150L
    }
}
