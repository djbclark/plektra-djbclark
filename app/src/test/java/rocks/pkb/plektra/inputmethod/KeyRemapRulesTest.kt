package rocks.pkb.plektra.inputmethod

import android.view.KeyEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The rule model, its compiled table and its stored form. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyRemapRulesTest {

    private val shiftAsEnter = KeyRemapRule(KeySource.scan(54, KeyEvent.KEYCODE_SHIFT_RIGHT), KeyEvent.KEYCODE_ENTER)
    private val enterAsShift = KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_ENTER), KeyEvent.KEYCODE_SHIFT_RIGHT)

    @Test
    fun targetFor_matchesScanCodeBeforeKeyCode() {
        val rules = KeyRemapRules.compile(
            listOf(
                KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_ALT_RIGHT), KeyEvent.KEYCODE_SYM),
                shiftAsEnter,
                enterAsShift
            )
        )

        assertEquals(KeyEvent.KEYCODE_ENTER, rules.targetFor(KeyEvent.KEYCODE_SHIFT_RIGHT, 54))
        // The scan code names the key, so the key-code rule for what it sends does not apply.
        assertEquals(KeyEvent.KEYCODE_ENTER, rules.targetFor(KeyEvent.KEYCODE_ALT_RIGHT, 54))
        assertEquals(KeyEvent.KEYCODE_SYM, rules.targetFor(KeyEvent.KEYCODE_ALT_RIGHT, 100))
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, rules.targetFor(KeyEvent.KEYCODE_ENTER, 28))
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, rules.targetFor(KeyEvent.KEYCODE_ENTER, 0))
        // No rule, and a key code is no scan code.
        assertEquals(KeyEvent.KEYCODE_A, rules.targetFor(KeyEvent.KEYCODE_A, 30))
        assertEquals(54, rules.targetFor(54, 0))
    }

    @Test
    fun compile_keepsValidRulesInOrderAndTheFirstOfEachSource() {
        val rules = KeyRemapRules.compile(
            listOf(
                shiftAsEnter,
                KeyRemapRule(KeySource.scan(54), KeyEvent.KEYCODE_TAB), // same key again
                enterAsShift,
                KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_ENTER, scanCode = 28), KeyEvent.KEYCODE_TAB), // same key again
                KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_A), KeyEvent.KEYCODE_A), // onto itself
                KeyRemapRule(KeySource.scan(0, KeyEvent.KEYCODE_A), KeyEvent.KEYCODE_B), // no scan code
                KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_UNKNOWN), KeyEvent.KEYCODE_B),
                KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_A), KeyEvent.getMaxKeyCode() + 1),
                KeyRemapRule(KeySource.key(-5), KeyEvent.KEYCODE_B)
            )
        )

        assertEquals(listOf(shiftAsEnter, enterAsShift), rules.rules)
        assertSame(KeyRemapRules.NONE, KeyRemapRules.compile(emptyList()))
        assertTrue(KeyRemapRules.NONE.isEmpty)
    }

    @Test
    fun lockKeys_areNeitherSourcesNorTargets() {
        for (lockKey in listOf(KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_SCROLL_LOCK)) {
            assertFalse(KeyRemapRules.isValid(KeyRemapRule(KeySource.key(lockKey), KeyEvent.KEYCODE_CTRL_LEFT)))
            assertFalse(KeyRemapRules.isValid(KeyRemapRule(KeySource.scan(58, lockKey), KeyEvent.KEYCODE_CTRL_LEFT)))
            assertFalse(KeyRemapRules.isValid(KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_CTRL_LEFT), lockKey)))
        }
        // A scan-code rule that does not say which key it names cannot make a lock key a source.
        val byScanCode = KeyRemapRules.compile(listOf(KeyRemapRule(KeySource.scan(58), KeyEvent.KEYCODE_CTRL_LEFT)))
        assertEquals(KeyEvent.KEYCODE_CAPS_LOCK, byScanCode.targetFor(KeyEvent.KEYCODE_CAPS_LOCK, 58))
        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, byScanCode.targetFor(KeyEvent.KEYCODE_ESCAPE, 58))
    }

    @Test
    fun table_appliesAProfilesRulesToItsBuiltInKeyboardOnly() {
        val table = KeyRemapJson.decode(KeyRemapJson.encode(mapOf("titan2" to listOf(shiftAsEnter, enterAsShift)))).table()

        fun rulesFor(profileId: String, kind: DeviceSpecific.InputDeviceKind) =
            table.rulesFor(DeviceSpecific.ResolvedInputProfile(profileId, kind, autoDetected = true))

        assertEquals(listOf(shiftAsEnter, enterAsShift), rulesFor("titan2", DeviceSpecific.InputDeviceKind.BUILT_IN).rules)
        assertSame(KeyRemapRules.NONE, rulesFor("titan2", DeviceSpecific.InputDeviceKind.ACCESSORY))
        assertSame(KeyRemapRules.NONE, rulesFor("titan2", DeviceSpecific.InputDeviceKind.UNKNOWN))
        assertSame(KeyRemapRules.NONE, rulesFor("key2", DeviceSpecific.InputDeviceKind.BUILT_IN))
        assertEquals(listOf(shiftAsEnter, enterAsShift), table.rules("titan2"))
        assertTrue(table.rules("key2").isEmpty())
        assertFalse(table.isEmpty)
        assertTrue(KeyRemapTable.EMPTY.isEmpty)
    }

    @Test
    fun profileIds_areStoredLowerCase() {
        // DeviceSpecific reports the Q25 as "Q25" and takes "q25" as the manual profile.
        val table = KeyRemapJson.decode(KeyRemapJson.encode(mapOf("Q25" to listOf(enterAsShift)))).table()

        assertEquals(listOf(enterAsShift), table.rules("q25"))
        assertEquals(
            listOf(enterAsShift),
            table.rulesFor(DeviceSpecific.ResolvedInputProfile("Q25", DeviceSpecific.InputDeviceKind.BUILT_IN, true)).rules
        )
    }

    @Test
    fun json_roundTripsRulesPerProfile() {
        val stored = mapOf(
            "titan2" to listOf(shiftAsEnter, enterAsShift),
            "key2" to listOf(KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_ALT_RIGHT, scanCode = 100), KeyEvent.KEYCODE_SYM)),
            "mp01" to emptyList()
        )

        val json = KeyRemapJson.encode(stored)
        val decoded = KeyRemapJson.decode(json)

        assertEquals(KeyRemapJson.VERSION, JSONObject(json).getInt("v"))
        assertTrue(decoded.writable)
        assertEquals(stored - "mp01", decoded.rulesByProfile)
        assertEquals(json, KeyRemapJson.encode(decoded.rulesByProfile))
    }

    @Test
    fun json_dropsWhatItCannotUse() {
        val json = """
            {"v":1,"profiles":{
              "titan2":{"rules":[
                {"match":"scan","scan":54,"key":60,"to":66},
                {"match":"chord","key":61,"to":62},
                {"match":"key","key":115,"to":113},
                {"match":"key","key":66},
                "not a rule",
                {"match":"key","key":66,"to":60}]},
              "key2":{"rules":"none"},
              "mp01":{"rules":[{"match":"key","key":115,"to":113}]},
              "other":7}}
        """.trimIndent()

        val decoded = KeyRemapJson.decode(json)

        assertEquals(mapOf("titan2" to listOf(shiftAsEnter, enterAsShift)), decoded.rulesByProfile)
        assertTrue(decoded.writable)
    }

    @Test
    fun json_readsDamagedOrMissingStoresAsNoRules() {
        for (json in listOf(null, "", "  ", "{", "[]", "null", "{\"v\":1}", "{\"v\":1,\"profiles\":[]}")) {
            val decoded = KeyRemapJson.decode(json)
            assertTrue("$json", decoded.rulesByProfile.isEmpty())
            assertTrue("$json", decoded.writable)
            assertSame(KeyRemapTable.EMPTY, decoded.table())
        }
    }

    @Test
    fun json_leavesANewerVersionAlone() {
        val newer = """{"v":2,"profiles":{"titan2":{"rules":[{"match":"key","key":66,"to":60}]}}}"""

        val decoded = KeyRemapJson.decode(newer)

        assertFalse(decoded.writable)
        assertTrue(decoded.table().isEmpty)
    }
}
