package dev.hitlbar.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The overlay bar. One window at most, held by this process-wide object.
 *
 * No foreground service on purpose: while a TYPE_APPLICATION_OVERLAY window is on screen, the
 * system ranks the owning process as visible, so it is not killed in the background (Android 8
 * and later). When no bar is showing there is nothing to keep alive, and the next broadcast
 * starts the process again. A service would only add a notification and a foreground-service
 * type to justify.
 *
 * Focus: FLAG_NOT_FOCUSABLE keeps the keyboard and the field under test focused;
 * FLAG_NOT_TOUCH_MODAL sends touches outside the bar to the app behind it. The bar sits at the
 * top, just below the status bar, so it does not cover the typing area.
 *
 * All calls come from the main thread (a manifest receiver runs there, and so do clicks).
 */
object Bar {
    private val seq = SeqCounter()
    private var view: View? = null
    private var generation = 0

    fun show(context: Context, text: String, buttons: List<BarButton>) {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            log(errorLine("no-permission", seq.next()))
            return
        }
        removeView(app)
        val root = buildView(app, text, buttons)
        try {
            app.getSystemService(WindowManager::class.java).addView(root, layoutParams(app))
        } catch (e: RuntimeException) {
            log(errorLine("window-failed", seq.next()))
            return
        }
        view = root
        val mine = ++generation
        // "shown" only once the bar has been laid out, i.e. is really on screen.
        root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (mine == generation && view === root) log(shownLine(seq.next()))
            }
        })
    }

    /** Always answers "hidden", also when no bar was showing, so the host never waits for it. */
    fun hide(context: Context) {
        removeView(context.applicationContext)
        log(hiddenLine(seq.next()))
    }

    private fun removeView(app: Context) {
        val old = view ?: return
        view = null
        generation++
        try {
            app.getSystemService(WindowManager::class.java).removeViewImmediate(old)
        } catch (e: RuntimeException) {
            // already gone
        }
    }

    private fun layoutParams(app: Context): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.FILL_HORIZONTAL
            y = statusBarHeight(app)
        }

    private fun statusBarHeight(app: Context): Int {
        val id = app.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) app.resources.getDimensionPixelSize(id) else dp(app, 24)
    }

    private fun buildView(app: Context, text: String, buttons: List<BarButton>): View {
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F0161B22"))
            setPadding(dp(app, 12), dp(app, 6), dp(app, 8), dp(app, 6))
        }
        row.addView(
            TextView(app).apply {
                this.text = text
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 4
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        for (button in buttons) {
            row.addView(
                Button(app).apply {
                    this.text = button.label
                    isAllCaps = false
                    // Another overlay drawn over a button must not be able to press it for the person.
                    filterTouchesWhenObscured = true
                    setTextColor(Color.WHITE)
                    minimumWidth = dp(app, 64)
                    minimumHeight = dp(app, 48)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(app, 8).toFloat()
                        setColor(Color.parseColor(color(button)))
                    }
                    setOnClickListener { log(buttonLine(button, seq.next())) }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(app, 8) },
            )
        }
        return row
    }

    private fun color(button: BarButton) = when (button) {
        BarButton.DONE -> "#2E7D32"
        BarButton.NOREC -> "#6D4C41"
        BarButton.REDO -> "#1565C0"
        BarButton.NEXT -> "#455A64"
        BarButton.STOP -> "#C62828"
    }

    private fun dp(app: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), app.resources.displayMetrics).toInt()

    private fun log(line: String) {
        Log.i(LOG_TAG, line)
    }
}
