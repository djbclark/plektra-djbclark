package rocks.pkb.plektra.inputmethod

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.statusbar.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CornerButtonThemeTest {
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "mdpi")
    fun outerButtonFollowsInnerLedWithConfiguredClearanceAtMdpi() {
        assertOuterButtonLedClearance()
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "xhdpi")
    fun outerButtonFollowsInnerLedWithConfiguredClearanceAtXhdpi() {
        assertOuterButtonLedClearance()
    }

    private fun assertOuterButtonLedClearance() {
        val context = RuntimeEnvironment.getApplication()
        val density = context.resources.displayMetrics.density
        val geometry = KeyboardBarGeometry(context).apply {
            radii = 100 to 100
            ledsVisible = true
            ledLayout = rocks.pkb.plektra.inputmethod.ui.ModifierLedLayouts.TITAN_2_ELITE
            ledRowTop = 90f
            ledRowRadius = 8f
            leftSurface = KeyboardBarGeometry.OuterSurface(android.graphics.RectF(0f, 90f, 150f, 204f), 8f)
        }
        val surface = android.graphics.Region().apply {
            setPath(geometry.surfacePath(1000f, 204f, geometry.leftSurface!!.bounds, 8f, true, false),
                android.graphics.Region(0, 0, 1000, 204))
        }
        val segment = geometry.ledLayout.segments.first { it.x < 0.5f }
        val paint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = geometry.ledStrokeWidth(segment)
        }
        val painted = android.graphics.Path()
        paint.getFillPath(geometry.ledRailPath(1000f, 204f, segment), painted)
        val rail = android.graphics.Region().apply {
            setPath(painted, android.graphics.Region(0, 0, 1000, 204))
        }
        for (x in 125..140) {
            val buttonBottom = (90 until 204).last { surface.contains(x, it) }
            val railTop = (90 until 204).first { rail.contains(x, it) }
            // Pixel indices add one pixel to the empty space between the painted regions.
            val emptyPixels = railTop - buttonBottom - 1
            assertEquals("2.2 dp button-to-LED clearance at x=$x, density=$density",
                2.2f * density, emptyPixels.toFloat(), 1f)
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun ledOnlyRailsStartAlongDisplayEdgeWithoutHorizontalHook() {
        val geometry = KeyboardBarGeometry(RuntimeEnvironment.getApplication()).apply {
            radii = 100 to 100
            ledLayout = rocks.pkb.plektra.inputmethod.ui.ModifierLedLayouts.TITAN_2_ELITE
        }
        for (segment in geometry.ledLayout.segments) {
            val measure = android.graphics.PathMeasure(geometry.ledRailPath(1076f, 123f, segment), false)
            val tangent = FloatArray(2)
            org.junit.Assert.assertTrue(measure.getPosTan(0.1f, null, tangent))
            org.junit.Assert.assertTrue("LED rail starts vertically: ${tangent.toList()}",
                kotlin.math.abs(tangent[0]) < 0.1f && tangent[1] > 0.9f)
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun narrowOuterSlotKeepsAdjacentVariationKeyAndEveryRailCapInsideItsSlot() {
        val context = RuntimeEnvironment.getApplication()
        val geometry = KeyboardBarGeometry(context).apply {
            radii = 100 to 100
            ledLayout = rocks.pkb.plektra.inputmethod.ui.ModifierLedLayouts.TITAN_2_ELITE
            ledRowTop = 90f
            ledRowRadius = 10.5f
            leftSurface = KeyboardBarGeometry.OuterSurface(android.graphics.RectF(0f, 90f, 102f, 195f), 10.5f)
            rightSurface = KeyboardBarGeometry.OuterSurface(android.graphics.RectF(898f, 90f, 1000f, 195f), 10.5f)
        }
        val neighbor = android.graphics.RectF(109f, 90f, 187f, 195f)
        fun region(path: android.graphics.Path) = android.graphics.Region().apply {
            setPath(path, android.graphics.Region(0, 0, 1000, 204))
        }
        val original = region(geometry.surfacePath(1000f, 204f, neighbor, 10.5f, false, false))
        for (multiplier in listOf(1, 2, 4, 6)) {
            SettingsManager.setTitan2EliteTopCornerMultiplier(context, multiplier)
            geometry.ledsVisible = true
            org.junit.Assert.assertTrue("Adjacent variation key is unchanged at $multiplier", android.graphics.Region(original).apply {
                op(region(geometry.surfacePath(1000f, 204f, neighbor, 10.5f, false, false)), android.graphics.Region.Op.XOR)
            }.isEmpty)
            org.junit.Assert.assertTrue("LED gestures do not invade the adjacent slot", region(geometry.ledExclusionPath(1000f, 204f)).apply {
                op(android.graphics.Rect(109, 90, 187, 195), android.graphics.Region.Op.INTERSECT)
            }.isEmpty)
            val outer = region(geometry.surfacePath(1000f, 204f, geometry.leftSurface!!.bounds, 10.5f, true, false))
            for (segment in geometry.ledLayout.segments.filter { it.x < 0.5f }) {
                val path = geometry.ledRailPath(1000f, 204f, segment)
                org.junit.Assert.assertFalse("Rail remains visible at $multiplier", path.isEmpty)
                val bounds = android.graphics.RectF()
                path.computeBounds(bounds, true)
                org.junit.Assert.assertTrue("Round cap stays in owned slot", bounds.right + geometry.ledStrokeWidth(segment) / 2f <= 102.01f)
                val painted = android.graphics.Path()
                android.graphics.Paint().apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = geometry.ledStrokeWidth(segment)
                    strokeCap = android.graphics.Paint.Cap.ROUND
                }.getFillPath(path, painted)
                org.junit.Assert.assertTrue("Shared contour leaves room for actual rail paint", region(painted).apply {
                    op(outer, android.graphics.Region.Op.INTERSECT)
                }.isEmpty)
            }
        }
    }

    @Test
    fun plannedContourDoesNotReserveAStartupStraightStripBeforeInsetsArrive() {
        val geometry = KeyboardBarGeometry(RuntimeEnvironment.getApplication())
        assertEquals(14, geometry.reservedLedHeight(14))
        geometry.ledsVisible = true
        assertEquals(0, geometry.reservedLedHeight(14))
        geometry.radii = 100 to 100
        assertEquals(0, geometry.reservedLedHeight(14))
        geometry.followDisplayContour = false
        assertEquals(14, geometry.reservedLedHeight(14))
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun ledToggleKeepsUnintersectedInteriorIconPlacementAndSize() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(activity, true)
        val chrome = ImeChromeLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            bottomCornerRadiiPx = 100 to 100
            softwareKeyboardModeActive = true
        }
        val frame = android.widget.FrameLayout(activity)
        val icon = android.widget.ImageView(activity).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setImageBitmap(android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888))
            KeyboardBarRowGeometry.style(this, Color.GRAY, Color.BLUE, 12f, null, 0)
        }
        frame.addView(icon, android.widget.FrameLayout.LayoutParams(200, 100).apply {
            leftMargin = 300; topMargin = 300
        })
        chrome.addView(frame, android.widget.LinearLayout.LayoutParams(1000, 400))
        activity.setContentView(chrome)
        for (visible in listOf(false, true, false, true)) {
            chrome.contourIntegratedIndicators = visible
            chrome.measure(android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY))
            chrome.layout(0, 0, 1000, 400)
            chrome.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(android.widget.ImageView.ScaleType.CENTER, icon.scaleType)
            assertEquals(200, icon.width)
            assertEquals(100, icon.height)
            org.junit.Assert.assertFalse(icon.background is CurvedCornerButtonDrawable)
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun ledsOnlyReshapeOwnedOuterSlotsAndPreserveInteriorBottom() {
        val context = RuntimeEnvironment.getApplication()
        val geometry = KeyboardBarGeometry(context).apply {
            radii = 100 to 100
            ledRowTop = 300f
            ledRowRadius = 12f
            ledLayout = rocks.pkb.plektra.inputmethod.ui.ModifierLedLayouts.TITAN_2_ELITE
            leftSurface = KeyboardBarGeometry.OuterSurface(android.graphics.RectF(0f, 300f, 160f, 400f), 12f)
            rightSurface = KeyboardBarGeometry.OuterSurface(android.graphics.RectF(840f, 300f, 1000f, 400f), 12f)
            hardwareTopRounding = false
        }
        fun surface(bounds: android.graphics.RectF, left: Boolean = false): android.graphics.Region =
            android.graphics.Region().apply {
                setPath(geometry.surfacePath(1000f, 400f, bounds, 12f, left, false),
                    android.graphics.Region(0, 0, 1000, 400))
            }
        val upperBounds = android.graphics.RectF(0f, 40f, 160f, 140f)
        val interiorBounds = android.graphics.RectF(200f, 300f, 800f, 400f)
        val lowerBounds = android.graphics.RectF(0f, 300f, 160f, 400f)
        val upper = surface(upperBounds, true)
        val interior = surface(interiorBounds)
        val outer = surface(lowerBounds, true)
        geometry.ledsVisible = true
        org.junit.Assert.assertTrue("Upper key width and theme corners stay identical",
            android.graphics.Region(upper).apply { op(surface(upperBounds, true), android.graphics.Region.Op.XOR) }.isEmpty)
        org.junit.Assert.assertTrue("Interior key height and shape stay identical",
            android.graphics.Region(interior).apply { op(surface(interiorBounds), android.graphics.Region.Op.XOR) }.isEmpty)
        org.junit.Assert.assertTrue("Interior content reaches the physical straight bottom", geometry.contains(1000f, 400f, 500f, 399f))
        val clipped = surface(lowerBounds, true)
        org.junit.Assert.assertFalse("Intersecting outer key loses the local LED envelope",
            android.graphics.Region(outer).apply { op(clipped, android.graphics.Region.Op.DIFFERENCE) }.isEmpty)
        val center = KeyboardBarGeometry.centroid(clipped, android.graphics.PointF())
        org.junit.Assert.assertTrue("Center follows the remaining visible key", center.x > lowerBounds.centerX())
        org.junit.Assert.assertTrue("The same center is touchable", geometry.contains(1000f, 400f, center.x, center.y))
        val leds = android.graphics.Region().apply {
            setPath(geometry.ledExclusionPath(1000f, 400f), android.graphics.Region(0, 0, 1000, 400))
        }
        org.junit.Assert.assertTrue(android.graphics.Region(clipped).apply { op(leds, android.graphics.Region.Op.INTERSECT) }.isEmpty)
        for (segment in geometry.ledLayout.segments) {
            val painted = android.graphics.Path()
            android.graphics.Paint().apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = geometry.ledStrokeWidth(segment)
                strokeCap = android.graphics.Paint.Cap.ROUND
            }.getFillPath(geometry.ledRailPath(1000f, 400f, segment), painted)
            val rail = android.graphics.Region().apply { setPath(painted, android.graphics.Region(0, 0, 1000, 400)) }
            org.junit.Assert.assertTrue("Painted rail and its end cap are fully excluded",
                rail.apply { op(leds, android.graphics.Region.Op.DIFFERENCE) }.isEmpty)
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun hardwareLedCurlSharesTheUpperTransitionAndSoftwareRailsIgnoreItsMultiplier() {
        val context = RuntimeEnvironment.getApplication()
        val geometry = KeyboardBarGeometry(context).apply {
            radii = 100 to 100
            ledRowTop = 200f
            ledRowRadius = 12f
            ledLayout = rocks.pkb.plektra.inputmethod.ui.ModifierLedLayouts.TITAN_2_ELITE
            leftSurface = KeyboardBarGeometry.OuterSurface(android.graphics.RectF(0f, 200f, 102f, 400f), 12f)
            rightSurface = KeyboardBarGeometry.OuterSurface(android.graphics.RectF(898f, 200f, 1000f, 400f), 12f)
        }
        val segment = rocks.pkb.plektra.inputmethod.ui.ModifierLedLayouts.TITAN_2_ELITE.segments.first()
        fun rail(multiplier: Int): FloatArray {
            SettingsManager.setTitan2EliteTopCornerMultiplier(context, multiplier)
            return geometry.ledRailPath(1000f, 400f, segment).approximate(0.1f)
        }
        val one = rail(1)
        org.junit.Assert.assertFalse("Hardware curl responds to the configured multiplier", one.contentEquals(rail(6)))
        geometry.hardwareTopRounding = false
        val software = rail(1)
        for (multiplier in listOf(2, 4, 6)) org.junit.Assert.assertArrayEquals(software, rail(multiplier), 0.001f)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun softwareCornersIgnoreHardwareTopMultiplierAndKeepDisplayBoundary() {
        val context = RuntimeEnvironment.getApplication()
        val geometry = KeyboardBarGeometry(context).apply {
            radii = 100 to 100
            ledsVisible = true
            hardwareTopRounding = false
        }
        fun region(multiplier: Int): android.graphics.Region {
            SettingsManager.setTitan2EliteTopCornerMultiplier(context, multiplier)
            return android.graphics.Region().apply {
                setPath(geometry.surfacePath(1000f, 150f, android.graphics.RectF(0f, 0f, 150f, 150f),
                    12f, true, false), android.graphics.Region(0, 0, 1000, 150))
            }
        }
        val regular = region(1)
        for (multiplier in listOf(2, 4, 6)) {
            org.junit.Assert.assertTrue("Software top must not depend on hardware multiplier",
                android.graphics.Region(regular).apply { op(region(multiplier), android.graphics.Region.Op.XOR) }.isEmpty)
        }
        org.junit.Assert.assertTrue(regular.contains(15, 5))
        org.junit.Assert.assertFalse("Physical lower corner remains excluded", regular.contains(1, 140))
        val points = geometry.edgeContour(1000f, 150f, 0f, true)
        assertEquals("Software LEDs use the physical display boundary",
            rocks.pkb.plektra.T2eCornerCalibration.read(context).offsetPx +
                rocks.pkb.plektra.T2eCornerCalibration.read(context).shiftXPx, points.first().x, 1f)
    }

    @Test
    fun firstVisibleRowSuppliesTheLedTopAndAnOverlayCanReplaceIt() {
        val context = RuntimeEnvironment.getApplication()
        val chrome = ImeChromeLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val wrapper = android.widget.FrameLayout(context)
        val variations = android.widget.LinearLayout(context)
        val symbol = TextView(context)
        KeyboardBarRowGeometry.style(symbol, Color.GRAY, Color.BLUE, 8f, null, 0)
        variations.addView(symbol, android.widget.LinearLayout.LayoutParams(120, 100))
        wrapper.addView(variations, android.widget.FrameLayout.LayoutParams(1000, 100).apply { topMargin = 20 })
        KeyboardBarRowGeometry.bind(variations, listOf(symbol))
        chrome.addView(wrapper, android.widget.LinearLayout.LayoutParams(1000, 120))
        fun layout() {
            chrome.measure(android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(120, android.view.View.MeasureSpec.EXACTLY))
            chrome.layout(0, 0, 1000, 120)
        }
        layout()
        assertEquals(20, KeyboardBarRowGeometry.bottomRowTop(chrome))
        assertEquals(8f, KeyboardBarRowGeometry.bottomRowRadius(chrome)!!, 0.01f)
        val menu = android.widget.LinearLayout(context)
        wrapper.addView(menu, android.widget.FrameLayout.LayoutParams(1000, 120))
        KeyboardBarRowGeometry.bind(menu, emptyList())
        layout()
        assertEquals(0, KeyboardBarRowGeometry.bottomRowTop(chrome))
        menu.visibility = android.view.View.GONE
        assertEquals(20, KeyboardBarRowGeometry.bottomRowTop(chrome))
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun drawnVariationGlyphsCenterInTheVisibleOuterSurfaces() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(activity, true)
        val chrome = ImeChromeLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            bottomCornerRadiiPx = 100 to 100
            contourIntegratedIndicators = true
        }
        activity.setContentView(chrome)
        fun glyph(text: String, left: Boolean): TextView = TextView(activity).apply {
            this.text = text
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            KeyboardBarRowGeometry.style(this, Color.DKGRAY, Color.BLUE, 8f, null, 0,
                left = left, right = !left)
        }
        val left = glyph("»", true)
        val right = glyph("%", false)
        val middle = glyph("«", true).apply {
            KeyboardBarRowGeometry.style(this, Color.DKGRAY, Color.BLUE, 8f, null, 0)
        }
        chrome.addView(left, android.widget.LinearLayout.LayoutParams(120, 100))
        chrome.addView(middle, android.widget.LinearLayout.LayoutParams(760, 100))
        chrome.addView(right, android.widget.LinearLayout.LayoutParams(120, 100))
        repeat(3) {
            chrome.measure(android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(100, android.view.View.MeasureSpec.EXACTLY))
            chrome.layout(0, 0, 1000, 100)
            chrome.viewTreeObserver.dispatchOnPreDraw()
        }
        for (button in listOf(left, middle, right)) {
            val bitmap = android.graphics.Bitmap.createBitmap(button.width, button.height, android.graphics.Bitmap.Config.ARGB_8888)
            button.draw(android.graphics.Canvas(bitmap))
            val ink = android.graphics.Rect()
            for (y in 0 until button.height) for (x in 0 until button.width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.red(pixel) > 220 && Color.green(pixel) > 220 && Color.blue(pixel) > 220) {
                    ink.union(x, y, x + 1, y + 1)
                }
            }
            org.junit.Assert.assertFalse("Glyph must actually be rendered", ink.isEmpty)
            val center = (button.background as? CurvedCornerButtonDrawable)?.visibleCenter()
                ?: android.graphics.PointF(button.width / 2f, button.height / 2f)
            assertEquals("${button.text}: horizontal optical center", center.x, ink.exactCenterX(), 2f)
            assertEquals("${button.text}: vertical optical center", center.y, ink.exactCenterY(), 2f)
        }
    }

    @Test
    fun roundedDisplayPreservesButtonThemeAndRefreshesBadgeColors() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(context, true)
        val host = StatusBarButtonHost(context, StatusBarButtonRegistry())
        host.themeOverride = StatusBarButtonStyles.ThemeOverride(Color.YELLOW, Color.MAGENTA, Color.BLACK)
        val hosted = requireNotNull(host.getOrCreateButton(StatusBarButtonId.Clipboard, 40, StatusBarCallbacks(), 80, 40))
        val badge = hosted.button.getTag(R.id.tag_badge_view) as TextView
        assertEquals(Color.BLACK, badge.currentTextColor)
        val background = hosted.button.background.current as GradientDrawable
        assertEquals(Color.YELLOW, background.color!!.defaultColor)
        assertEquals(StatusBarButtonStyles.cornerRadiusForSize(40), background.cornerRadius)

        host.themeOverride = StatusBarButtonStyles.ThemeOverride(Color.BLUE, Color.CYAN, Color.WHITE)
        assertEquals(Color.WHITE, badge.currentTextColor)
        assertEquals(Color.BLUE, (hosted.button.background.current as GradientDrawable).color!!.defaultColor)
    }
    @Test
    fun outerIconMovesWithoutLedSurfaceAndRemainsStableAcrossRedrawAndDisable() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(activity, true)
        val chrome = ImeChromeLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            bottomCornerRadiiPx = 100 to 100
        }
        activity.setContentView(chrome)
        val host = StatusBarButtonHost(activity, StatusBarButtonRegistry())
        host.themeOverride = StatusBarButtonStyles.ThemeOverride(Color.GRAY, Color.BLUE, Color.WHITE)
        val hosted = requireNotNull(host.getOrCreateButton(StatusBarButtonId.Hamburger, 80, StatusBarCallbacks(), 120, 100))
        host.setOuterEdge(StatusBarButtonId.Hamburger, StatusBarButtonPosition.LEFT)
        chrome.addView(hosted.container, android.widget.LinearLayout.LayoutParams(120, 100))
        val button = hosted.button as android.widget.ImageView
        fun layoutAndDraw(): FloatArray {
            chrome.measure(android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(100, android.view.View.MeasureSpec.EXACTLY))
            chrome.layout(0, 0, 1000, 100)
            chrome.viewTreeObserver.dispatchOnPreDraw()
            return FloatArray(9).also { button.imageMatrix.getValues(it) }
        }
        val first = layoutAndDraw()
        org.junit.Assert.assertTrue(first[android.graphics.Matrix.MTRANS_X] >= (button.width - button.drawable.intrinsicWidth) / 2f)
        repeat(3) { org.junit.Assert.assertArrayEquals(first, layoutAndDraw(), 0.001f) }
        host.setOuterEdge(StatusBarButtonId.Hamburger, null)
        layoutAndDraw()
        assertEquals(android.widget.ImageView.ScaleType.CENTER, button.scaleType)
        host.setOuterEdge(StatusBarButtonId.Hamburger, StatusBarButtonPosition.LEFT)
        org.junit.Assert.assertArrayEquals(first, layoutAndDraw(), 0.001f)
        SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(activity, false)
        layoutAndDraw()
        assertEquals(android.widget.ImageView.ScaleType.CENTER, button.scaleType)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun outerIconCentersInTheVisibleShapeEvenWhenItFitsTheOriginalRectangle() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(activity, true)
        val chrome = ImeChromeLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            bottomCornerRadiiPx = 100 to 100
        }
        activity.setContentView(chrome)
        val host = StatusBarButtonHost(activity, StatusBarButtonRegistry())
        host.themeOverride = StatusBarButtonStyles.ThemeOverride(Color.GRAY, Color.BLUE, Color.WHITE)
        val hosted = requireNotNull(host.getOrCreateButton(StatusBarButtonId.Hamburger, 80, StatusBarCallbacks(), 250, 100))
        host.setOuterEdge(StatusBarButtonId.Hamburger, StatusBarButtonPosition.LEFT)
        chrome.addView(hosted.container, android.widget.LinearLayout.LayoutParams(250, 100))
        chrome.measure(android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(100, android.view.View.MeasureSpec.EXACTLY))
        chrome.layout(0, 0, 1000, 100)
        chrome.viewTreeObserver.dispatchOnPreDraw()
        val icon = hosted.button as android.widget.ImageView
        val matrix = FloatArray(9).also { icon.imageMatrix.getValues(it) }
        val center = (icon.background as CurvedCornerButtonDrawable).visibleCenter()
        assertEquals(center.x, matrix[android.graphics.Matrix.MTRANS_X] + icon.drawable.intrinsicWidth / 2f, 0.01f)
        assertEquals(center.y, matrix[android.graphics.Matrix.MTRANS_Y] + icon.drawable.intrinsicHeight / 2f, 0.01f)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun microphoneRetainsThemedContourAcrossRecordingAndThemeChanges() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(context, true)
        val host = StatusBarButtonHost(context, StatusBarButtonRegistry())
        host.themeOverride = StatusBarButtonStyles.ThemeOverride(Color.GRAY, Color.BLUE, Color.WHITE)
        val hosted = requireNotNull(host.getOrCreateButton(StatusBarButtonId.Microphone, 80, StatusBarCallbacks(), 120, 100))
        host.setOuterEdge(StatusBarButtonId.Microphone, StatusBarButtonPosition.RIGHT)
        hosted.container.layout(0, 0, 120, 100)
        hosted.button.layout(0, 0, 120, 100)
        fun color(): Int {
            org.junit.Assert.assertTrue(hosted.button.background is CurvedCornerButtonDrawable)
            val bitmap = android.graphics.Bitmap.createBitmap(120, 100, android.graphics.Bitmap.Config.ARGB_8888)
            hosted.button.background.setBounds(0, 0, 120, 100)
            hosted.button.background.draw(android.graphics.Canvas(bitmap))
            return bitmap.getPixel(60, 50)
        }
        assertEquals(Color.GRAY, color())
        repeat(2) {
            host.setMicrophoneActive(true)
            assertEquals(StatusBarButtonStyles.RECOGNITION_RED, color())
            host.updateMicrophoneAudioLevel(0f)
            assertEquals(Color.rgb(255, 50, 50), color())
            host.themeOverride = StatusBarButtonStyles.ThemeOverride(Color.GREEN, Color.BLUE, Color.WHITE)
            assertEquals(Color.rgb(255, 50, 50), color())
            host.setMicrophoneActive(false)
            assertEquals(Color.GREEN, color())
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun lowRowFindsRoomAndContourIgnoresBottomPaddingAndUpdatesUpperCorners() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(activity, true)
        SettingsManager.setTitan2EliteTopCornerMultiplier(activity, 2)
        val chrome = ImeChromeLayout(activity).apply { bottomCornerRadiiPx = 100 to 100 }
        activity.setContentView(chrome)
        val button = android.widget.ImageView(activity)
        chrome.addView(button, android.widget.LinearLayout.LayoutParams(94, 80))
        chrome.layout(0, 0, 1000, 80)
        button.layout(0, 0, 94, 80)
        val background = CurvedCornerButtonDrawable(button, Color.GRAY, Color.BLUE, 10f, null, 0, leftEdge = true)
        button.background = background
        val center = background.contentCenter(54f, 54f, 57f, 40f, true)
        org.junit.Assert.assertTrue("Low rows need an upward correction: $center / ${chrome.width}x${chrome.height} / ${button.width}x${button.height}", center.y < 40f)
        org.junit.Assert.assertTrue("Low rows need an inward correction", center.x > 57f)
        org.junit.Assert.assertTrue(center.scale <= 1f)
        assertEquals(center, background.contentCenter(54f, 54f, 57f, 40f, true))
        fun pixels(): IntArray {
            val bitmap = android.graphics.Bitmap.createBitmap(94, 80, android.graphics.Bitmap.Config.ARGB_8888)
            background.setBounds(0, 0, 94, 80)
            background.draw(android.graphics.Canvas(bitmap))
            return IntArray(94 * 80).also { bitmap.getPixels(it, 0, 94, 0, 0, 94, 80) }
        }
        SettingsManager.setTitan2EliteMaxIconShrink(activity, 0)
        val unshrunk = background.contentCenter(90f, 75f, 57f, 40f, true)
        assertEquals(1f, unshrunk.scale)
        org.junit.Assert.assertFalse("Oversized ink cannot fit the contour without shrinking", unshrunk.fits)
        org.junit.Assert.assertTrue(unshrunk.x - 45f >= 0f && unshrunk.x + 45f <= 94f)
        org.junit.Assert.assertTrue(unshrunk.y - 37.5f >= 0f && unshrunk.y + 37.5f <= 80f)
        SettingsManager.setTitan2EliteMaxIconShrink(activity, 20)
        org.junit.Assert.assertTrue(background.contentCenter(90f, 75f, 57f, 40f, true).scale >= 0.8f)
        SettingsManager.setTitan2EliteMaxIconShrink(activity, 90)
        org.junit.Assert.assertTrue(background.contentCenter(90f, 75f, 57f, 40f, true).scale < 0.8f)
        SettingsManager.setTitan2EliteTopCornerMultiplier(activity, 1)
        assertEquals(1, SettingsManager.getTitan2EliteTopCornerMultiplier(activity))
        val singleCorners = pixels()
        SettingsManager.setTitan2EliteTopCornerMultiplier(activity, 2)
        org.junit.Assert.assertFalse(singleCorners.contentEquals(pixels()))
        val beforePadding = pixels()
        chrome.setPadding(0, 0, 0, 20)
        org.junit.Assert.assertArrayEquals(beforePadding, pixels())
        for (multiplier in listOf(1, 4, 6)) {
            SettingsManager.setTitan2EliteTopCornerMultiplier(activity, multiplier)
            val strongerCorners = pixels()
            org.junit.Assert.assertFalse(beforePadding.contentEquals(strongerCorners))
            // Outer rounding must not change the corner facing the middle.
            for (y in 0 until 12) for (x in 82 until 94) {
                val before = beforePadding[y * 94 + x]
                val after = strongerCorners[y * 94 + x]
                org.junit.Assert.assertTrue("Inner corner changed at $x,$y ($multiplier)",
                    kotlin.math.abs(Color.alpha(before) - Color.alpha(after)) <= 1)
            }
            for (y in 0 until 80) {
                val filled = (0 until 94).filter { Color.alpha(strongerCorners[y * 94 + it]) > 0 }
                if (filled.isNotEmpty()) {
                    assertEquals("No holes in the rounded edge ($multiplier, row $y)",
                        filled.last() - filled.first() + 1, filled.size)
                }
            }
        }
        SettingsManager.setTitan2EliteTopCornerMultiplier(activity, 2)
        org.junit.Assert.assertArrayEquals(beforePadding, pixels())
    }

    @Test
    fun languagePaddingAndSizeStayStableAndRestoreWhenMovedInward() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(activity, true)
        val chrome = ImeChromeLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            bottomCornerRadiiPx = 100 to 100
        }
        activity.setContentView(chrome)
        val host = StatusBarButtonHost(activity, StatusBarButtonRegistry())
        host.themeOverride = StatusBarButtonStyles.ThemeOverride(Color.GRAY, Color.BLUE, Color.WHITE)
        val hosted = requireNotNull(host.getOrCreateButton(StatusBarButtonId.Language, 80, StatusBarCallbacks(), 94, 80))
        val button = hosted.button as android.widget.TextView
        button.text = "DE"
        val originalSize = button.textSize
        host.setOuterEdge(StatusBarButtonId.Language, StatusBarButtonPosition.LEFT)
        chrome.addView(hosted.container, android.widget.LinearLayout.LayoutParams(94, 80))
        fun draw(): List<Float> {
            chrome.measure(android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(80, android.view.View.MeasureSpec.EXACTLY))
            chrome.layout(0, 0, 1000, 80)
            chrome.viewTreeObserver.dispatchOnPreDraw()
            return listOf(button.paddingLeft.toFloat(), button.paddingBottom.toFloat(), button.textSize)
        }
        val initial = draw()
        assertEquals(0f, initial[0], 0.01f)
        repeat(4) { assertEquals(initial, draw()) }
        host.setOuterEdge(StatusBarButtonId.Language, null)
        val inner = draw()
        org.junit.Assert.assertFalse(button.background is CurvedCornerButtonDrawable)
        repeat(4) { assertEquals(inner, draw()) }
        assertEquals(originalSize, button.textSize)
    }

}
