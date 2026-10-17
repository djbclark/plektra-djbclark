package rocks.pkb.plektra.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTrackpadTerminalAppsTest {

    @Test
    fun defaultListHoldsTheTerminalAndSshApps() {
        assertEquals(
            setOf(
                "com.termux",
                "app.getmoshi.android",
                "com.stably.orca.mobile",
                "com.android.virtualization.terminal",
                "in.sunilpaulmathew.ashell",
                "in.hridayan.ashell",
                "org.connectbot",
                "com.sonelli.juicessh",
                "com.server.auditor.ssh.client",
                "com.termoneplus",
                "tech.ula",
            ),
            ScreenTrackpadTerminalApps.DEFAULT_PACKAGES
        )
        // Facebook Messenger's package is easy to confuse with Orca's.
        assertFalse("com.facebook.orca" in ScreenTrackpadTerminalApps.DEFAULT_PACKAGES)
    }

    @Test
    fun membershipIsAnExactPackageMatch() {
        val list = setOf("com.termux", "org.connectbot")

        assertTrue(ScreenTrackpadTerminalApps.isListed(list, "com.termux"))
        assertFalse(ScreenTrackpadTerminalApps.isListed(list, "com.termux.api"))
        assertFalse(ScreenTrackpadTerminalApps.isListed(list, "com.termu"))
        assertFalse(ScreenTrackpadTerminalApps.isListed(list, "COM.TERMUX"))
        assertFalse(ScreenTrackpadTerminalApps.isListed(list, null))
        assertFalse(ScreenTrackpadTerminalApps.isListed(list, ""))
        assertFalse(ScreenTrackpadTerminalApps.isListed(emptySet(), "com.termux"))
    }

    @Test
    fun listedAppWithoutAnEditableFieldOpensOnlyWithAnInputConnection() {
        assertTrue(ScreenTrackpadTerminalApps.mayOpenOverlay(editableFieldFocused = false, hasInputConnection = true, appListed = true))
        assertFalse(ScreenTrackpadTerminalApps.mayOpenOverlay(editableFieldFocused = false, hasInputConnection = false, appListed = true))
    }

    @Test
    fun nonListedAppWithoutAnEditableFieldNeverOpens() {
        assertFalse(ScreenTrackpadTerminalApps.mayOpenOverlay(editableFieldFocused = false, hasInputConnection = true, appListed = false))
        assertFalse(ScreenTrackpadTerminalApps.mayOpenOverlay(editableFieldFocused = false, hasInputConnection = false, appListed = false))
    }

    @Test
    fun editableFieldBehaviourIsUnchangedWhetherOrNotTheAppIsListed() {
        assertTrue(ScreenTrackpadTerminalApps.mayOpenOverlay(editableFieldFocused = true, hasInputConnection = true, appListed = false))
        assertTrue(ScreenTrackpadTerminalApps.mayOpenOverlay(editableFieldFocused = true, hasInputConnection = true, appListed = true))
    }
}
