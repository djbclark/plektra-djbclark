package dev.hitlbar.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A non-focusable overlay. Interactive cards move or collapse. Passive cards and
 * the temporary dismissal target let touches through to the app behind them.
 * All state is accessed on the main thread.
 */
object Bar {
    private data class Card(val text: String, val buttons: List<BarButton>)
    private data class DropTarget(val view: View, val area: OverlayArea) {
        fun contains(x: Float, y: Float): Boolean =
            x >= area.left && x < area.right && y >= area.top && y < area.bottom
    }

    private val seq = SeqCounter()
    private var view: View? = null
    private var card: Card? = null
    private var dropTarget: DropTarget? = null
    private var collapsed = false
    private var barPosition: OverlayPosition? = null
    private var bubblePosition: OverlayPosition? = null

    fun show(context: Context, text: String, buttons: List<BarButton>) {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            log(errorLine("no-permission", seq.next()))
            return
        }
        val item = Card(text, buttons)
        card = item
        render(app, item, acknowledge = true)
    }

    /** Always answers "hidden", also when no bar was showing, so the host never waits for it. */
    fun hide(context: Context) {
        card = null
        collapsed = false
        removeView(context.applicationContext)
        log(hiddenLine(seq.next()))
    }

    private fun render(app: Context, item: Card, acknowledge: Boolean = false) {
        removeView(app)
        val bubble = collapsed && item.buttons.isNotEmpty()
        val wm = app.getSystemService(WindowManager::class.java)
        val area = availableArea(wm, app)
        val width = if (bubble) dp(app, BUBBLE_SIZE_DP) else {
            minOf(dp(app, 360), (area.width * 0.88f).roundToInt()).coerceAtLeast(1)
        }
        val layout = layoutParams(width.coerceAtMost(area.width), item.buttons.isEmpty())
        val root = if (bubble) buildBubble(app, item) else buildBar(app, item)
        val start = if (bubble) bubblePosition ?: barPosition else barPosition
        val initial = start ?: OverlayPosition(area.left + (area.width - layout.width) / 2, area.top)
        val position = area.clamp(initial, layout.width, 0)
        layout.x = position.x
        layout.y = position.y
        try {
            wm.addView(root, layout)
        } catch (_: RuntimeException) {
            log(errorLine("window-failed", seq.next()))
            return
        }
        view = root
        root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (view !== root) return
                move(app, root, layout, position, bubble)
                if (acknowledge) log(shownLine(seq.next()))
            }
        })
    }

    private fun removeView(app: Context) {
        hideDropTarget(app)
        val old = view ?: return
        view = null
        try {
            app.getSystemService(WindowManager::class.java).removeViewImmediate(old)
        } catch (_: RuntimeException) {
            // Already gone.
        }
    }

    private fun availableArea(wm: WindowManager, app: Context): OverlayArea {
        val metrics = wm.currentWindowMetrics
        val bounds = metrics.bounds
        val bars = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        val edge = dp(app, 4)
        return OverlayArea(bounds.left + bars.left + edge, bounds.top + bars.top + edge,
            bounds.right - bars.right - edge, bounds.bottom - bars.bottom - edge)
    }

    private fun layoutParams(width: Int, passThrough: Boolean) =
        WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            fitInsetsTypes = 0    // x and y already account for the usable display's insets
            if (passThrough) {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                alpha = PASS_THROUGH_ALPHA
            }
        }

    private fun move(app: Context, root: View, layout: WindowManager.LayoutParams,
                     requested: OverlayPosition, bubble: Boolean) {
        if (view !== root) return
        val wm = app.getSystemService(WindowManager::class.java)
        val position = availableArea(wm, app).clamp(requested, layout.width, root.height)
        if (bubble) bubblePosition = position else barPosition = position
        if (position.x == layout.x && position.y == layout.y) return
        layout.x = position.x
        layout.y = position.y
        try {
            wm.updateViewLayout(root, layout)
        } catch (_: IllegalArgumentException) {
            // The host hid the window before the layout update.
        }
    }

    private fun makeDraggable(app: Context, handle: View, root: View, onDrop: (() -> Unit)? = null) {
        val slop = ViewConfiguration.get(app).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var origin = OverlayPosition(0, 0)
        var dragging = false
        var singleTouch = false
        handle.filterTouchesWhenObscured = true
        handle.setOnTouchListener { v, event ->
            if (view !== root) return@setOnTouchListener false
            val layout = root.layoutParams as WindowManager.LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    origin = OverlayPosition(layout.x, layout.y)
                    dragging = false
                    singleTouch = true
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                    singleTouch = false
                    hideDropTarget(app)
                }
                MotionEvent.ACTION_MOVE -> if (singleTouch) {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        if (onDrop != null) showDropTarget(app)
                    }
                    if (dragging) {
                        val position = OverlayPosition(origin.x + dx.roundToInt(), origin.y + dy.roundToInt())
                        move(app, root, layout, position, bubble = onDrop != null)
                        highlightDropTarget(app, event.rawX, event.rawY)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dismiss = singleTouch && dragging &&
                        dropTarget?.contains(event.rawX, event.rawY) == true
                    hideDropTarget(app)
                    if (dismiss) onDrop?.invoke()
                    if (singleTouch && !dragging && v.isClickable) v.performClick()
                }
            }
            true
        }
    }

    private fun showDropTarget(app: Context) {
        val wm = app.getSystemService(WindowManager::class.java)
        val area = availableArea(wm, app)
        val size = dp(app, 64)
        val position = area.clamp(OverlayPosition(area.left + (area.width - size) / 2,
            area.bottom - size - dp(app, 12)), size, size)
        val target = TextView(app).apply {
            text = "×"
            textSize = 29f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = surface(app, 32)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val layout = layoutParams(size, passThrough = true).apply {
            height = size
            x = position.x
            y = position.y
        }
        try {
            wm.addView(target, layout)
            dropTarget = DropTarget(target, OverlayArea(
                position.x, position.y, position.x + size, position.y + size,
            ))
        } catch (_: RuntimeException) {
            // Dragging still works when the target window cannot be shown.
        }
    }

    private fun highlightDropTarget(app: Context, x: Float, y: Float) {
        val target = dropTarget ?: return
        val inside = target.contains(x, y)
        if (target.view.isSelected == inside) return
        target.view.isSelected = inside
        target.view.background = surface(app, 32, if (inside) "#D0A52D34" else BAR_SURFACE)
    }

    private fun hideDropTarget(app: Context) {
        val target = dropTarget ?: return
        dropTarget = null
        try {
            app.getSystemService(WindowManager::class.java).removeViewImmediate(target.view)
        } catch (_: IllegalArgumentException) {
            // Already gone.
        }
    }

    private fun answer(app: Context, item: Card, button: BarButton, dismiss: Boolean = false) {
        if (card !== item) return
        // The host acts on this line. The old card must never send a second answer.
        log(buttonLine(button, seq.next()))
        collapsed = false
        if (dismiss) {
            card = null
            removeView(app)
        } else {
            val wait = Card(WAIT_TEXT, emptyList())
            card = wait
            render(app, wait)
        }
    }

    private fun buildBar(app: Context, item: Card): View {
        val column = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            background = surface(app, 12)
            setPadding(dp(app, 8), dp(app, 4), dp(app, 8), dp(app, 4))
        }
        val header = LinearLayout(app).apply { gravity = Gravity.CENTER_VERTICAL }
        val label = TextView(app).apply {
            text = item.text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 6
            if (item.buttons.isEmpty()) gravity = Gravity.CENTER_HORIZONTAL
        }
        header.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (item.buttons.isNotEmpty()) {
            val minimize = control(app, "−", "Minimize QA bar", 18).apply {
                setOnClickListener {
                    collapsed = true
                    render(app, item)
                }
            }
            header.addView(minimize, LinearLayout.LayoutParams(dp(app, 42), dp(app, 42)))
            makeDraggable(app, label, column)
        }
        column.addView(header, matchWidth(LinearLayout.LayoutParams.WRAP_CONTENT))
        if (item.buttons.isEmpty()) return column
        val row = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
        layoutSlots(item.buttons).forEachIndexed { place, button ->
            val cell = if (button == null) View(app) else smallButton(app, button).apply {
                setOnClickListener { answer(app, item, button) }
            }
            row.addView(cell, LinearLayout.LayoutParams(0, dp(app, BUTTON_HEIGHT_DP), 1f).apply {
                if (place > 0) marginStart = dp(app, 4)
            })
        }
        column.addView(row, matchWidth(LinearLayout.LayoutParams.WRAP_CONTENT))
        return column
    }

    private fun buildBubble(app: Context, item: Card): View {
        val row = LinearLayout(app).apply { gravity = Gravity.CENTER_VERTICAL }
        val bubble = control(app, "QA", "Open QA bar. ${item.text}", 14).apply {
            background = surface(app, 24)
            setOnClickListener {
                collapsed = false
                render(app, item)
            }
        }
        row.addView(bubble, LinearLayout.LayoutParams(dp(app, BUBBLE_SIZE_DP), dp(app, BUBBLE_SIZE_DP)))
        val terminate = dismissButton(item.buttons)
        val close = {
            if (terminate != null) {
                answer(app, item, terminate, dismiss = true)
            } else {
                card = null
                collapsed = false
                removeView(app)
            }
        }
        makeDraggable(app, bubble, row, onDrop = close)
        return row
    }

    private fun control(app: Context, glyph: String, description: String, size: Int) = Button(app).apply {
        text = glyph
        contentDescription = description
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
        filterTouchesWhenObscured = true
        stateListAnimator = null
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        background = null
    }

    private fun surface(app: Context, radius: Int, color: String = BAR_SURFACE) = GradientDrawable().apply {
        cornerRadius = dp(app, radius).toFloat()
        setColor(Color.parseColor(color))
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
            alpha = 128
        }
    }

    private fun matchWidth(height: Int) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)

    private fun dp(app: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), app.resources.displayMetrics).toInt()

    private fun log(line: String) {
        Log.i(LOG_TAG, line)
    }

    private const val BAR_SURFACE = "#80161B22"
    private const val BUBBLE_SIZE_DP = 48
    private const val BUTTON_HEIGHT_DP = 40

    /** The most opaque a window may be and still pass touches through (Android 12+). */
    private const val PASS_THROUGH_ALPHA = 0.8f
}
