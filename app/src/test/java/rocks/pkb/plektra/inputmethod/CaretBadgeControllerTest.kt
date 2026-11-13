package rocks.pkb.plektra.inputmethod

import android.graphics.Matrix
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.CursorAnchorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.ui.CaretBadgeView

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CaretBadgeControllerTest {
    @Test
    fun themeColorsFollowChangesUnlessCustomColorsAreEnabled() {
        ShadowSettings.setCanDrawOverlays(true)
        val service = Robolectric.buildService(InputMethodService::class.java).create().get()
        var themeColors = 0xFF123456.toInt() to 0xFF654321.toInt()
        val controller = CaretBadgeController(service) { themeColors }
        val snapshot = StatusBarController.StatusSnapshot(
            capsLockEnabled = false, shiftOneShot = true, shiftPhysicallyPressed = false,
            ctrlLatchActive = false, ctrlOneShot = false, ctrlPhysicallyPressed = false,
            ctrlLatchFromNavMode = false, altLatchActive = false, altOneShot = false,
            altPhysicallyPressed = false, symPage = 0
        )
        val caret = CursorAnchorInfo.Builder().setMatrix(Matrix())
            .setInsertionMarkerLocation(100f, 100f, 115f, 120f, CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION)
            .build()
        val customArmed = 0xFF112233.toInt()
        val customLocked = 0xFF445566.toInt()
        SettingsManager.setCaretBadgeArmedColor(service, customArmed)
        SettingsManager.setCaretBadgeLockedColor(service, customLocked)
        try {
            assertFalse(SettingsManager.getCaretBadgeCustomColors(service))
            controller.onEditorStarted()
            controller.onSnapshot(snapshot, true)
            controller.onCursorAnchorInfo(caret)
            val view = CaretBadgeController::class.java.getDeclaredField("badgeView").apply {
                isAccessible = true
            }.get(controller) as CaretBadgeView
            assertEquals(themeColors.first, view.armedColor)
            assertEquals(themeColors.second, view.lockedColor)

            themeColors = 0xFFABCDEF.toInt() to 0xFFFEDCBA.toInt()
            controller.onSnapshot(snapshot, true)
            assertEquals(themeColors.first, view.armedColor)
            assertEquals(themeColors.second, view.lockedColor)

            SettingsManager.setCaretBadgeCustomColors(service, true)
            controller.onSnapshot(snapshot, true)
            assertEquals(customArmed, view.armedColor)
            assertEquals(customLocked, view.lockedColor)

            SettingsManager.setCaretBadgeCustomColors(service, false)
            controller.onSnapshot(snapshot, true)
            assertEquals(themeColors.first, view.armedColor)
            assertEquals(themeColors.second, view.lockedColor)
            assertEquals(customArmed, SettingsManager.getCaretBadgeArmedColor(service))
            assertEquals(customLocked, SettingsManager.getCaretBadgeLockedColor(service))
        } finally {
            controller.destroy()
            ShadowSettings.setCanDrawOverlays(false)
        }
    }

    @Test
    fun badgeIsDisabledByDefault() {
        val service = Robolectric.buildService(InputMethodService::class.java).create().get()
        assertFalse(SettingsManager.getCaretModifierBadgeEnabled(service))
    }

    @Test
    fun lateCursorUpdateAfterEditorGoneKeepsBadgeHidden() {
        ShadowSettings.setCanDrawOverlays(true)
        val service = Robolectric.buildService(InputMethodService::class.java).create().get()
        val controller = CaretBadgeController(service)
        val attached = CaretBadgeController::class.java.getDeclaredField("attached").apply {
            isAccessible = true
        }
        val caret = CursorAnchorInfo.Builder()
            .setMatrix(Matrix())
            .setInsertionMarkerLocation(100f, 100f, 115f, 120f, CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION)
            .build()
        try {
            controller.onEditorStarted()
            val snapshot = StatusBarController.StatusSnapshot(
                capsLockEnabled = false, shiftOneShot = true, shiftPhysicallyPressed = false,
                ctrlLatchActive = false, ctrlOneShot = false, ctrlPhysicallyPressed = false,
                ctrlLatchFromNavMode = false, altLatchActive = false, altOneShot = false,
                altPhysicallyPressed = false, symPage = 0
            )
            controller.onSnapshot(snapshot, true)
            controller.onCursorAnchorInfo(caret)
            assertTrue(attached.getBoolean(controller))
            controller.onEditorGone()
            assertFalse(attached.getBoolean(controller))
            controller.onCursorAnchorInfo(caret)
            assertFalse("Late cursor update must not show the old editor's badge", attached.getBoolean(controller))
            controller.onSnapshot(snapshot, true)
            controller.onCursorAnchorInfo(caret)
            assertFalse("Late status update must not reactivate the badge", attached.getBoolean(controller))

            controller.onEditorStarted()
            controller.onSnapshot(snapshot, true)
            assertFalse("The next editor must wait for its own caret", attached.getBoolean(controller))
            controller.onCursorAnchorInfo(caret)
            assertTrue("The badge must work in the next editor", attached.getBoolean(controller))
        } finally {
            controller.destroy()
            ShadowSettings.setCanDrawOverlays(false)
        }
    }
}
