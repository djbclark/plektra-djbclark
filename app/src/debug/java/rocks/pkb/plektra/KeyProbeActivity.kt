package rocks.pkb.plektra

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Fork-only, debug-only key probe (never in release). Views A and B are non-editable key readers with an
 * input connection (TYPE_NULL) and a view id. Pick a mode, tap Arm, press a key: N ms after the next
 * initial key DOWN (repeatCount 0, once per Arm, never per repeat) the probe restarts input or moves focus.
 * Start: adb shell am start -n rocks.pkb.plektra.djb/rocks.pkb.plektra.KeyProbeActivity
 */
class KeyProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val lines = ArrayDeque<String>()
    private val held = HashMap<String, LinkedHashSet<String>>()
    private val modes = listOf("off", "restart 1x", "restart 3x (N apart)", "focus -> B", "focus -> text 1")
    private val delays = listOf(100L, 250L, 600L)
    private var mode = 0
    private var delayIdx = 0
    private var armed = false
    private var restartPending = false
    private var restarts = 0
    private var connections = 0
    private lateinit var a: ProbeView
    private lateinit var b: ProbeView
    private lateinit var t1: ProbeEdit
    private lateinit var t2: ProbeEdit
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var aNoId: CheckBox
    private lateinit var bSharesA: CheckBox
    private lateinit var t1SharesA: CheckBox

    private fun stamp() = SystemClock.uptimeMillis()

    private fun log(s: String) {
        val line = "${stamp()} $s"
        Log.d(TAG, line)
        lines.addLast(line)
        while (lines.size > 250) lines.removeFirst()
        if (::logView.isInitialized) {
            logView.text = lines.joinToString("\n")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        updateStatus()
    }

    private fun updateStatus() {
        if (!::status.isInitialized) return
        val h = held.entries.filter { it.value.isNotEmpty() }.joinToString(" ") { "${it.key}=${it.value}" }
        status.text = "mode=${modes[mode]} N=${delays[delayIdx]}ms armed=$armed held: ${h.ifEmpty { "none" }}"
    }

    private fun keyLine(who: String, e: KeyEvent): String {
        val set = held.getOrPut(who) { LinkedHashSet() }
        val name = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
        if (e.action == KeyEvent.ACTION_DOWN) set.add(name) else set.remove(name)
        val canceled = if (e.flags and KeyEvent.FLAG_CANCELED != 0) " CANCELED" else ""
        val pending = if (restartPending) " [restart pending]" else ""
        return "$who ${if (e.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"} $name(${e.keyCode}) scan=${e.scanCode} " +
            "rep=${e.repeatCount} meta=0x${e.metaState.toString(16)} flags=0x${e.flags.toString(16)}$canceled " +
            "down=${e.downTime} ev=${e.eventTime} dev=${e.deviceId} held=$set$pending"
    }

    private fun onKey(who: String, e: KeyEvent) {
        log(keyLine(who, e))
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && armed && mode != 0) {
            armed = false
            val n = delays[delayIdx]
            log("armed once by this DOWN: ${modes[mode]} in $n ms")
            val count = if (mode == 2) 3 else 1
            for (i in 1..count) handler.postDelayed({ act() }, n * i)
        }
    }

    private fun act() {
        val focus = currentFocus
        when (mode) {
            1, 2 -> if (focus != null) {
                restartPending = true
                log("restartInput #${++restarts} requested on ${focus.tag}")
                getSystemService(InputMethodManager::class.java).restartInput(focus)
            }
            3 -> { log("focus -> B"); b.requestFocus() }
            4 -> { log("focus -> text 1"); t1.requestFocus() }
        }
    }

    private fun applyIds() {
        a.id = if (aNoId.isChecked) View.NO_ID else ID_A
        b.id = if (bSharesA.isChecked) ID_A else ID_B
        t1.id = if (t1SharesA.isChecked) ID_A else ID_T1
        log("ids: A=${a.id} B=${b.id} text1=${t1.id} text2=${t2.id}; restarting the focused view to republish them")
        currentFocus?.let { restartPending = true; getSystemService(InputMethodManager::class.java).restartInput(it) }
    }

    private fun button(label: String, click: (Button) -> Unit) = Button(this).apply {
        text = label; isFocusable = false; setOnClickListener { click(this) }
    }

    private fun check(label: String) = CheckBox(this).apply {
        text = label; isFocusable = false; setOnCheckedChangeListener { _, _ -> applyIds() }
    }

    private fun focusLog(v: View) = v.setOnFocusChangeListener { _, f -> log("focus ${if (f) "gained" else "lost"}: ${v.tag}") }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        a = ProbeView("A").also { it.id = ID_A }
        b = ProbeView("B").also { it.id = ID_B }
        t1 = ProbeEdit("text1").also { it.id = ID_T1 }
        t2 = ProbeEdit("text2").also { it.id = ID_T2 }
        listOf(a, b, t1, t2).forEach { focusLog(it) }
        status = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f }
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 10f }
        scroll = ScrollView(this).apply { addView(logView) }
        aNoId = check("A has no id (27g)"); bSharesA = check("B has A's id (27h)"); t1SharesA = check("text 1 has A's id (27i)")
        val note = TextView(this).apply {
            textSize = 11f
            text = "Not observable here: the IME's onStartInput(restarting), the EditorInfo it received, connection " +
                "replacement. See: adb shell dumpsys input_method | rg 'fieldId|packageName|mCurId'; " +
                "adb logcat -s KeyProbe RemoteInputConnectionImpl PlektraInputMethod (the IME's tag; " +
                "it has no key-delivery lines)."
        }
        val row = LinearLayout(this).apply {
            addView(button("mode") { mode = (mode + 1) % modes.size; armed = mode != 0; log("mode ${modes[mode]}") })
            addView(button("N") { delayIdx = (delayIdx + 1) % delays.size; log("N ${delays[delayIdx]}") })
            addView(button("arm") { armed = mode != 0; log("armed=$armed") })
            addView(button("clear log") { lines.clear(); held.clear(); restartPending = false; log("cleared") })
        }
        val views = LinearLayout(this).apply { addView(a, LinearLayout.LayoutParams(0, 160, 1f)); addView(b, LinearLayout.LayoutParams(0, 160, 1f)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            listOf<View>(status, row, aNoId, bSharesA, t1SharesA, views, t1, t2, note).forEach { addView(it) }
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            setOnApplyWindowInsetsListener { v, ins -> v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom); ins }
        }
        setContentView(root)
        a.requestFocus()
        log("KeyProbe ready; focus A")
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    /** A key-reading view that logs every key event and every connection created for it. */
    inner class ProbeView(private val name: String) : View(this@KeyProbeActivity) {
        init {
            tag = name; isFocusable = true; isFocusableInTouchMode = true
            setBackgroundColor(if (name == "A") 0xFFCCE5FF.toInt() else 0xFFFFE0B2.toInt())
        }
        override fun onCheckIsTextEditor() = true
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = EditorInfo.TYPE_NULL
            restartPending = false
            val serial = ++connections
            log("$name onCreateInputConnection #$serial viewId=$id publishes fieldId=${outAttrs.fieldId} package=${outAttrs.packageName}")
            return object : BaseInputConnection(this@ProbeView, false) {
                override fun sendKeyEvent(e: KeyEvent): Boolean {
                    log("$name connection #$serial sendKeyEvent ${KeyEvent.actionToString(e.action)} ${KeyEvent.keyCodeToString(e.keyCode)}")
                    return super.sendKeyEvent(e)
                }
            }
        }
        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            if (e.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e)
            onKey(name, e)
            return true
        }
    }

    /** Single-line field that logs the key events it receives, then handles them as usual. */
    inner class ProbeEdit(private val name: String) : EditText(this@KeyProbeActivity) {
        init { tag = name; setSingleLine(); hint = name }
        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            onKey(name, e)
            return super.dispatchKeyEvent(e)
        }
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            restartPending = false
            return super.onCreateInputConnection(outAttrs).also {
                log("$name onCreateInputConnection viewId=$id publishes fieldId=${outAttrs.fieldId} package=${outAttrs.packageName}")
            }
        }
    }

    private companion object {
        const val TAG = "KeyProbe"
        const val ID_A = 0x7f0f0001
        const val ID_B = 0x7f0f0002
        const val ID_T1 = 0x7f0f0003
        const val ID_T2 = 0x7f0f0004
    }
}
