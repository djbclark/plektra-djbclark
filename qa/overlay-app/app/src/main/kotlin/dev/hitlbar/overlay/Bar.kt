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
 * Layout: the instruction takes the full width, with one thin row of small buttons under it.
 *
 * Waiting: a tap replaces the card at once with [WAIT_TEXT] and no buttons, so it cannot be pressed
 * twice, until the next SHOW or HIDE. A card with no buttons (that one, or a SHOW that names none)
 * lets touches through to the app behind it, so the host can drive the screen with the bar up.
 * Android 12 and later drop such touches unless the window is at most 0.8 opaque.
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
            app.getSystemService(WindowManager::class.java)
                .addView(root, layoutParams(app, passThrough = buttons.isEmpty()))
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

    private fun layoutParams(app: Context, passThrough: Boolean): WindowManager.LayoutParams =
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
            if (passThrough) {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                alpha = PASS_THROUGH_ALPHA
            }
        }

    private fun statusBarHeight(app: Context): Int {
        val id = app.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) app.resources.getDimensionPixelSize(id) else dp(app, 24)
    }

    private fun buildView(app: Context, text: String, buttons: List<BarButton>): View {
        val column = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0161B22"))
            setPadding(dp(app, 8), dp(app, 4), dp(app, 8), dp(app, 4))
        }
        val label = TextView(app).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 4
            if (buttons.isEmpty()) gravity = Gravity.CENTER_HORIZONTAL
        }
        column.addView(label, matchWidth(LinearLayout.LayoutParams.WRAP_CONTENT))
        if (buttons.isEmpty()) return column
        val row = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
        var answered = false
        layoutSlots(buttons).forEachIndexed { place, button ->
            val cell = if (button == null) {
                View(app)
            } else {
                smallButton(app, button).apply {
                    setOnClickListener {
                        if (answered) return@setOnClickListener
                        answered = true
                        log(buttonLine(button, seq.next())) // first: the host acts on this line
                        waitInPlace(app, column, label, row)
                    }
                }
            }
            row.addView(
                cell,
                LinearLayout.LayoutParams(0, dp(app, BUTTON_HEIGHT_DP), 1f).apply {
                    if (place > 0) marginStart = dp(app, 6)
                },
            )
        }
        column.addView(row, matchWidth(LinearLayout.LayoutParams.WRAP_CONTENT))
        return column
    }

    private fun smallButton(app: Context, button: BarButton) = Button(app).apply {
        text = button.label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        maxLines = 1
        // Another overlay drawn over a button must not be able to press it for the person.
        filterTouchesWhenObscured = true
        setTextColor(Color.WHITE)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        stateListAnimator = null
        background = GradientDrawable().apply {
            cornerRadius = dp(app, 8).toFloat()
            setColor(Color.parseColor(button.color))
        }
    }

    /** The tapped card turns into the wait card without waiting for the host. */
    private fun waitInPlace(app: Context, root: View, label: TextView, row: View) {
        row.visibility = View.GONE
        label.text = WAIT_TEXT
        label.gravity = Gravity.CENTER_HORIZONTAL
        if (view !== root) return
        try {
            app.getSystemService(WindowManager::class.java)
                .updateViewLayout(root, layoutParams(app, passThrough = true))
        } catch (e: RuntimeException) {
            // already gone
        }
    }

    private fun matchWidth(height: Int) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)

    private fun dp(app: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), app.resources.displayMetrics).toInt()

    private fun log(line: String) {
        Log.i(LOG_TAG, line)
    }

    /** Height of a button; its width is one fifth of the bar. */
    private const val BUTTON_HEIGHT_DP = 40

    /** The most opaque a window may be and still pass touches through (Android 12+). */
    private const val PASS_THROUGH_ALPHA = 0.8f
}
