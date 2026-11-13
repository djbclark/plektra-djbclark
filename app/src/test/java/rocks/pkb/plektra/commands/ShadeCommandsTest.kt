package rocks.pkb.plektra.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShadeCommandsTest {
    /** Stands in for the hidden StatusBarManager methods the executor reaches by reflection. */
    class FakeStatusBarService {
        var notificationCalls = 0
        var quickSettingsCalls = 0

        fun expandNotificationsPanel() {
            notificationCalls++
        }

        fun expandSettingsPanel() {
            quickSettingsCalls++
        }
    }

    @Test
    fun shadeCommands_areAssignableOnKeyAndNavSurfaces() {
        val context = RuntimeEnvironment.getApplication()
        val registry = CommandRegistry(context)

        for (surface in listOf(CommandSurface.AssignedKey, CommandSurface.NavMode)) {
            val ids = registry.getCommands(surface)
                .filter { it.source == CommandSourceId.DeviceControl }
                .map { it.id }
            assertTrue("notifications on $surface", "device.shade.notifications" in ids)
            assertTrue("quick settings on $surface", "device.shade.quick_settings" in ids)
        }
        val notifications = requireNotNull(registry.resolve("device.shade.notifications"))
        assertEquals("Open notifications", notifications.label)
        assertEquals(
            CommandLaunchSpec.InternalAction(DeviceControlCommandSource.ACTION_EXPAND_NOTIFICATIONS),
            notifications.launch
        )
        val quickSettings = requireNotNull(registry.resolve("device.shade.quick_settings"))
        assertEquals("Open quick settings", quickSettings.label)
        assertEquals(
            CommandLaunchSpec.InternalAction(DeviceControlCommandSource.ACTION_EXPAND_QUICK_SETTINGS),
            quickSettings.launch
        )
    }

    @Test
    fun notificationsCommand_callsOnlyTheNotificationsPanel() {
        val context = RuntimeEnvironment.getApplication()
        val service = FakeStatusBarService()
        shadowOf(context).setSystemService("statusbar", service)

        val result = CommandExecutor(context, showToast = false)
            .execute(requireNotNull(CommandRegistry(context).resolve("device.shade.notifications")))

        assertTrue(result.isSuccess)
        assertEquals(1, service.notificationCalls)
        assertEquals(0, service.quickSettingsCalls)
    }

    @Test
    fun quickSettingsCommand_callsOnlyTheSettingsPanel() {
        val context = RuntimeEnvironment.getApplication()
        val service = FakeStatusBarService()
        shadowOf(context).setSystemService("statusbar", service)

        val result = CommandExecutor(context, showToast = false)
            .execute(requireNotNull(CommandRegistry(context).resolve("device.shade.quick_settings")))

        assertTrue(result.isSuccess)
        assertEquals(0, service.notificationCalls)
        assertEquals(1, service.quickSettingsCalls)
    }

    @Test
    fun shadeCommand_withoutPlatformSupportOrShizuku_failsCleanly() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).setSystemService("statusbar", Any())

        val result = CommandExecutor(context, showToast = false)
            .execute(requireNotNull(CommandRegistry(context).resolve("device.shade.notifications")))

        assertFalse(result.isSuccess)
        assertEquals(CommandExecutionResult.Failed("Shade unavailable"), result)
    }
}
