package dev.hitlbar.overlay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** What the app is, a button to the "draw over other apps" permission page and one to the test bench. */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        // Targeting API 35+ draws edge to edge: keep the content clear of the system bars.
        column.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(pad, pad + bars.top, pad, pad + bars.bottom)
            insets
        }
        column.addView(TextView(this).apply {
            text = "HITL button bar is a test tool for developer devices. When a test harness " +
                "asks it to, it draws a movable, translucent bar above other apps with " +
                "an instruction and small buttons (Done, Redo, Prev, Next, Stop), and reports " +
                "button presses to the log. Drag the instruction to move the bar. The minus button " +
                "minimizes it to a movable bubble. Tap the bubble to reopen it or drag it onto the bottom × to dismiss it. " +
                "It needs the permission to draw over other apps, has " +
                "no network access and keeps no data. Its test bench is a screen of text boxes for " +
                "testing a keyboard; what is typed there is written to the log, so type no real " +
                "secrets."
            textSize = 16f
        })
        status = TextView(this).apply { textSize = 16f; setPadding(0, pad, 0, pad) }
        column.addView(status)
        column.addView(Button(this).apply {
            text = "Open the draw-over-apps setting"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                )
            }
        })
        column.addView(Button(this).apply {
            text = "Open the test bench"
            setOnClickListener { startActivity(Intent(this@MainActivity, BenchActivity::class.java)) }
        })
        setContentView(column)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (Settings.canDrawOverlays(this)) {
            "Draw over other apps: allowed."
        } else {
            "Draw over other apps: NOT allowed. The bar cannot show until you allow it."
        }
    }
}
