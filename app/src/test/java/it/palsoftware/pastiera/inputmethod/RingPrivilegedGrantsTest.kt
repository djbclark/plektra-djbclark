package it.palsoftware.pastiera.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * djb/ring: the ring's broker commands are a closed set. These pin the exact lines, so a change is a
 * visible diff in review, and pin that a bad package name never produces a command.
 */
class RingPrivilegedGrantsTest {

    private val app = "it.palsoftware.pastiera.djb"
    private val listener = "it.palsoftware.pastiera.ring.NotificationRingListener"

    @Test
    fun listenerClassConstantIsTheRingListener() {
        assertEquals(listener, PrivilegedCommand.RING_LISTENER_CLASS)
    }

    @Test
    fun eachGrantIsExactlyOneKnownLine() {
        assertEquals(
            "cmd notification allow_listener $app/$listener",
            PrivilegedCommand.RingGrant.ALLOW_LISTENER.line(app)
        )
        assertEquals(
            "appops set $app USE_FULL_SCREEN_INTENT allow",
            PrivilegedCommand.RingGrant.ALLOW_FULL_SCREEN_INTENT.line(app)
        )
        assertEquals(
            "appops set $app POST_NOTIFICATION allow",
            PrivilegedCommand.RingGrant.ALLOW_POST_NOTIFICATIONS.line(app)
        )
        assertEquals(
            "pm grant $app android.permission.WRITE_SECURE_SETTINGS",
            PrivilegedCommand.RingGrant.WRITE_SECURE_SETTINGS.line(app)
        )
        assertEquals(
            "cmd notification disallow_listener $app/$listener",
            PrivilegedCommand.RingGrant.DISALLOW_LISTENER.line(app)
        )
        assertEquals(
            "appops set $app USE_FULL_SCREEN_INTENT default",
            PrivilegedCommand.RingGrant.RESET_FULL_SCREEN_INTENT.line(app)
        )
    }

    @Test
    fun grantsAreJoinedInOrderIntoOneLine() {
        val line = PrivilegedCommand.RingGrants(
            listOf(
                PrivilegedCommand.RingGrant.ALLOW_LISTENER,
                PrivilegedCommand.RingGrant.ALLOW_POST_NOTIFICATIONS
            )
        ).commandLine(app)
        assertEquals(
            "cmd notification allow_listener $app/$listener; appops set $app POST_NOTIFICATION allow",
            line
        )
    }

    @Test
    fun noPackageNameThatIsNotPlainEverReachesALine() {
        listOf("", "pastiera", "a.b; reboot", "a.b && id", "a.b\nid", "a.b `id`", "a.b|id", "a. b", "a..b").forEach { bad ->
            PrivilegedCommand.RingGrant.entries.forEach { grant ->
                try {
                    PrivilegedCommand.RingGrants(listOf(grant)).commandLine(bad)
                    fail("'$bad' must be refused for $grant")
                } catch (_: IllegalArgumentException) {
                    // expected
                }
            }
        }
    }

    @Test
    fun anEmptyGrantListIsRefused() {
        try {
            PrivilegedCommand.RingGrants(emptyList()).commandLine(app)
            fail("an empty list must be refused")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun noGrantLineCarriesShellMetacharactersBeyondTheJoiner() {
        PrivilegedCommand.RingGrant.entries.forEach { grant ->
            val line = grant.line(app)
            assertFalse(line, line.any { it in "&|`$<>\n\"'\\" })
            assertTrue(line, !line.contains(";"))
        }
    }
}
