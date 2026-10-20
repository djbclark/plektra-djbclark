package rocks.pkb.plektra.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rocks.pkb.plektra.BuildConfig
import rocks.pkb.plektra.inputDeviceSettingEntries

class UpdateCheckerFlavorLogicTest {
    @Test
    fun buildVariantAndServiceCapabilityAgree() {
        val reduced = BuildConfig.FLAVOR in setOf("stableNoAccessibility", "nightlyNoAccessibility")
        assertEquals(if (reduced) "no-accessibility" else "full", BuildConfig.APK_VARIANT)
        assertEquals(!reduced, BuildConfig.ENABLE_ACCESSIBILITY_SERVICE)
        assertEquals(!reduced, inputDeviceSettingEntries().any { it.id == "clicks.buttons.launcher_button" })
    }

    @Test
    fun calVerOrdersMonthsAndYears() {
        assertTrue(compareReleaseVersions("26.09", "26.10")!! < 0)
        assertTrue(compareReleaseVersions("26.10", "27.01")!! < 0)
        assertTrue(compareReleaseVersions("26.10", "26.10.1")!! < 0)
    }

    @Test
    fun stableChannelSelectsHighestNewerStable() {
        val releases = listOf(
            stable("v26.10", "Plektra 26.10"),
            stable("v27.01", "Plektra 27.01"),
            stable("v26.10.1", "Plektra 26.10.1"),
            nightly("nightly/v27.01-nightly.02.101500Z")
        )
        val result = findNewerStableRelease(releases, "26.09")
        requireNotNull(result)
        assertEquals("v27.01", result.tagName)
    }

    @Test
    fun stableChannelIgnoresCurrentOlderPrereleaseAndUnknown() {
        val releases = listOf(
            stable("v26.09", "Plektra 26.09"),
            stable("v26.08", "Plektra 26.08"),
            nightly("nightly/v26.10-nightly.01.010000Z"),
            stable("latest", "Latest")
        )
        assertNull(findNewerStableRelease(releases, "26.09"))
    }

    @Test
    fun nightlyChannelSelectsNewestCanonicalNightly() {
        val releases = listOf(
            nightly("nightly/v26.09-nightly.26.122900Z"),
            nightly("nightly/v26.09-nightly.26.142900Z"),
            stable("v26.10", "Plektra 26.10")
        )
        val result = findNewerNightlyRelease(releases, "26.09-nightly.26.120000Z")
        requireNotNull(result)
        assertEquals("nightly/v26.09-nightly.26.142900Z", result.tagName)
    }

    @Test
    fun releaseNotesUseCalVerBase() {
        assertEquals("26.09", normalizeReleaseNotesVersion("26.09-nightly.26.142900Z"))
        assertEquals("26.10.1", normalizeReleaseNotesVersion("26.10.1"))
    }

    private fun stable(tag: String, name: String) = GitHubRelease(
        tagName = tag,
        name = name,
        prerelease = false,
        draft = false,
        htmlUrl = "https://example.invalid/$tag"
    )

    private fun nightly(tag: String) = GitHubRelease(
        tagName = tag,
        name = tag,
        prerelease = true,
        draft = false,
        htmlUrl = "https://example.invalid/$tag"
    )
}
