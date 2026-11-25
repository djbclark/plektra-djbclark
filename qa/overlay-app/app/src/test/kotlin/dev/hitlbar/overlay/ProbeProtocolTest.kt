package dev.hitlbar.overlay

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The line qa/tests/test_probe.py reads back: the app and the host agree on the format. */
private val GOLDEN = """{"v":1,"seq":7,"t":12345,"kind":"ic","field":"title","call":"commitText","conn":2,"text":"a \"b\"\\\n","pos":1,"cut":false}"""

class ProbeProtocolTest {
    @Test
    fun aLineIsOneJsonObject() {
        val line = probeLine(
            7, 12345L, "ic",
            listOf(
                "field" to "title", "call" to "commitText", "conn" to 2,
                "text" to "a \"b\"\\\n", "pos" to 1, "cut" to false,
            ),
        )
        assertEquals(GOLDEN, line)
    }

    @Test
    fun valuesOfEveryKindAreEncoded() {
        val line = probeLine(1, 2L, "reset", listOf("field" to null, "texts" to mapOf("body" to "x"), "n" to 3L))
        assertEquals("""{"v":1,"seq":1,"t":2,"kind":"reset","field":null,"texts":{"body":"x"},"n":3}""", line)
    }

    @Test
    fun controlCharactersNeverBreakTheLine() {
        assertEquals(""""\t\r\u0000\u001b\u2028é"""", jsonString("\t\r\u0000\u001b\u2028é"))
    }

    @Test
    fun longTextIsCutWithoutSplittingAPair() {
        assertEquals("abc", capText("abc", 3))
        assertEquals("ab", capText("ab\uD83D\uDE00", 3))
        assertEquals("cd", capText("abcd", 2, keepEnd = true))
        assertEquals("b", capText("\uD83D\uDE00b", 2, keepEnd = true))
    }

    @Test
    fun theBoxesCycleInTabOrder() {
        val order = generateSequence(ProbeField.BODY) { it.next }.take(ProbeField.entries.size + 1).toList()
        assertEquals(ProbeField.entries + ProbeField.BODY, order)
        assertEquals(ProbeField.TITLE, ProbeField.BODY.next)
        assertEquals(ProbeField.URL, ProbeField.of("url"))
        assertNull(ProbeField.of("nope"))
    }

    @Test
    fun aResetNamesTheTrialTheFocusAndTheTexts() {
        val extras = mapOf(
            "qa.trial" to "t1", "qa.focus" to "title", "qa.expect" to "beta", "qa.text.body" to "start",
        )
        val request = parseReset { extras[it] }
        assertEquals(ResetRequest("t1", ProbeField.TITLE, mapOf(ProbeField.BODY to "start"), "beta"), request)
        assertEquals(ResetRequest("", ProbeField.BODY, emptyMap()), parseReset { null })
    }

    @Test
    fun theHeaderShowsThePressedKeys() {
        val keys = PressedKeys(size = 4)
        keys.add("TAB", "\t", 0)
        keys.add("B", "b", 0)
        keys.add("B", "b", 1)
        keys.add("DEL", "", 0)
        assertEquals("\u21E5 b \u232B", keys.render())
        keys.add("SHIFT_LEFT", "", 0)
        keys.add("F1", "", 0)
        assertEquals("b \u232B \u21E7 F1", keys.render())
        keys.clear()
        assertEquals("", keys.render())
    }

    @Test
    fun aKeySeenBeforeTheKeyboardIsKnownOnceAfterIt() {
        val memory = KeyMemory(size = 2)
        val first = KeyId(30, 0, 100, 100, 3, 0)
        memory.remember(first)
        assertFalse(memory.take(first.copy(action = 1)))
        assertTrue(memory.take(first))
        assertFalse(memory.take(first))
        for (time in 1L..3L) memory.remember(first.copy(eventTime = time))
        assertFalse(memory.take(first.copy(eventTime = 1)))
        assertTrue(memory.take(first.copy(eventTime = 3)))
    }

    @Test
    fun causesNestAndEnd() {
        val causes = Causes()
        causes.during(CAUSE_KEY, 4) {
            causes.during(CAUSE_IC, 5) { assertEquals(CAUSE_IC to 5, causes.kind to causes.by) }
            assertEquals(CAUSE_KEY to 4, causes.kind to causes.by)
        }
        assertEquals(CAUSE_APP to 0, causes.kind to causes.by)
    }

    @Test
    fun typesAndOptionsAreNamed() {
        assertEquals("text capSentences multiLine", describeInputType(ProbeField.BODY.inputType))
        assertEquals("text password", describeInputType(ProbeField.PASSWORD.inputType))
        assertEquals("number", describeInputType(InputType.TYPE_CLASS_NUMBER))
        assertEquals(
            "search noExtractUi",
            describeImeOptions(EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI),
        )
    }

    @Test
    fun thePanelLineIsShort() {
        val line = panelLine(
            1_234_567L, "key",
            listOf("stage" to "pre", "name" to "B", "scan" to 48, "evt" to 5L, "ch" to "", "field" to null),
        )
        assertEquals("234567 key pre B scan=48", line)
    }

    @Test
    fun aMovementIsSummedUp() {
        val track = MotionTrack("touch", 0x1002, 4, 10f, 20f, 100L)
        track.add(15f, 20f, 120L, 1)
        track.add(40f, 10f, 150L, 2, hScroll = 1f)
        val summary = track.summary("up").toMap()
        assertEquals(30, summary["dx"])
        assertEquals(-10, summary["dy"])
        assertEquals(50L, summary["ms"])
        assertEquals(2, summary["samples"])
        assertEquals(2, summary["pointers"])
        assertEquals("0x1002", summary["source"])
    }
}
