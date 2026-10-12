package rocks.pkb.plektra.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The broker runs whatever string it is given as the shell user, so the only strings it may be
 * given are the ones [PrivilegedCommand] builds from constants. These tests pin the exact command
 * lines, so a change to one is a visible diff in review, and pin the package-name validation.
 */
class PrivilegedCommandTest {

    private val app = "rocks.pkb.plektra.djb"

    @Test
    fun grantIsExactlyPmGrantOfWriteSecureSettingsToTheAppItself() {
        assertEquals(
            "pm grant rocks.pkb.plektra.djb android.permission.WRITE_SECURE_SETTINGS",
            PrivilegedCommand.GrantWriteSecureSettings.commandLine(app)
        )
    }

    @Test
    fun grantRefusesAnythingThatIsNotAPlainPackageName() {
        listOf(
            "",
            "pastiera",
            "a.b; reboot",
            "a.b && id",
            "a.b\nid",
            "a.b `id`",
            "a.b\$(id)",
            "a.b|id",
            "a. b",
            "1a.b",
            "a..b",
            ".a.b",
            "a.b."
        ).forEach { name ->
            try {
                PrivilegedCommand.GrantWriteSecureSettings.commandLine(name)
                fail("accepted '$name'")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun packageNameValidationAcceptsRealPackageNames() {
        listOf("rocks.pkb.plektra", "rocks.pkb.plektra.djb", "com.a_b.c1").forEach {
            assertTrue(it, PrivilegedCommand.isValidPackageName(it))
        }
    }

    @Test
    fun backlightTimeoutCommandsAreBuiltFromConstantsOnly() {
        assertEquals(
            "service call agui_functional_service 1 s16 \"keyboard_brightness_timeout\"",
            PrivilegedCommand.ReadBacklightTimeout.commandLine(app)
        )
        assertEquals(
            "service call agui_functional_service 2 s16 \"keyboard_brightness_timeout\" s16 \"-1\"",
            PrivilegedCommand.WriteBacklightTimeout(PrivilegedCommand.BacklightTimeout.ALWAYS_ON)
                .commandLine(app)
        )
        assertEquals(
            "service call agui_functional_service 2 s16 \"keyboard_brightness_timeout\" s16 \"30000\"",
            PrivilegedCommand.WriteBacklightTimeout(PrivilegedCommand.BacklightTimeout.STOCK)
                .commandLine(app)
        )
    }

    @Test
    fun theTimeoutValuesAreTheOnlyTwoTheAppEverWrites() {
        assertEquals(
            listOf("-1", "30000"),
            PrivilegedCommand.BacklightTimeout.entries.map { it.wireValue }
        )
    }

    @Test
    fun noCommandLineCarriesShellMetacharacters() {
        val lines = listOf(
            PrivilegedCommand.GrantWriteSecureSettings.commandLine(app),
            PrivilegedCommand.ReadBacklightTimeout.commandLine(app)
        ) + PrivilegedCommand.BacklightTimeout.entries.map {
            PrivilegedCommand.WriteBacklightTimeout(it).commandLine(app)
        }
        lines.forEach { line ->
            // The double quotes around the vendor key and value are the only quoting in use.
            assertFalse(line, line.any { it in ";&|`$<>\\\n\r" })
        }
    }
}
