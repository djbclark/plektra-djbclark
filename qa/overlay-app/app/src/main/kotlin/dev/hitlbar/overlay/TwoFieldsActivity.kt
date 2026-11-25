package dev.hitlbar.overlay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Two plain text fields and nothing else: a second app, apart from the one a suite normally types
 * into, to tell a keyboard fault from an app's own bug. The cursor starts in the lower field (the
 * body), so Tab moves it to the upper one (the title), as in a note editor. Every start empties both
 * fields. Nothing is saved.
 */
class TwoFieldsActivity : Activity() {
    private lateinit var upper: EditText
    private lateinit var lower: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val belowBar = (130 * density).toInt() // the button bar lies over the top of the screen
        upper = field(R.id.probe_title, InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        lower = field(R.id.probe_body, InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        lower.minLines = 3
        lower.nextFocusForwardId = R.id.probe_title
        // Labels, not hints: an empty field then reads as empty to the harness.
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Title (Tab moves here)"))
            addView(upper)
            addView(label("Body (starts here)"))
            addView(lower)
        }
        column.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(pad, bars.top + belowBar, pad, pad + bars.bottom)
            insets
        }
        setContentView(column)
        reset()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        reset()
    }

    private fun label(text: String) = TextView(this).also { it.text = text }

    private fun field(id: Int, flags: Int) = EditText(this).also {
        it.id = id
        it.inputType = InputType.TYPE_CLASS_TEXT or flags
    }

    private fun reset() {
        upper.text.clear()
        lower.text.clear()
        lower.requestFocus()
    }
}
