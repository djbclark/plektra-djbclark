package rocks.pkb.plektra.inputmethod

/**
 * Apps where the screen trackpad also works with no editable text field in focus.
 *
 * Terminal emulators and SSH clients focus a view whose input type is TYPE_NULL, so the
 * trackpad (which opens only over a real editable field) never appeared there. The user picks
 * the apps; apps not on the list keep the normal behaviour.
 */
internal object ScreenTrackpadTerminalApps {
    /** What the list holds until the user edits it (a stored list, even an empty one, replaces it). */
    val DEFAULT_PACKAGES: Set<String> = linkedSetOf(
        "com.termux",
        "app.getmoshi.android", // Moshi
        "com.stably.orca.mobile", // Orca (not com.facebook.orca)
        "com.android.virtualization.terminal", // Android Terminal
        "in.sunilpaulmathew.ashell",
        "in.hridayan.ashell",
        "org.connectbot",
        "com.sonelli.juicessh",
        "com.server.auditor.ssh.client", // Termius
        "com.termoneplus", // Terminal Emulator (Term-onePlus)
        "tech.ula", // UserLAnd
    )

    fun isListed(packages: Set<String>, packageName: String?): Boolean =
        !packageName.isNullOrEmpty() && packageName in packages

    /**
     * Whether the overlay may exist at all. An editable field always qualifies (today's
     * behaviour). Without one, only a listed app with a live input connection does: the
     * overlay's only job is to send DPAD key events through that connection, and without one it
     * would be a pointless full-screen touch catcher.
     */
    fun mayOpenOverlay(editableFieldFocused: Boolean, hasInputConnection: Boolean, appListed: Boolean): Boolean =
        editableFieldFocused || (appListed && hasInputConnection)
}
