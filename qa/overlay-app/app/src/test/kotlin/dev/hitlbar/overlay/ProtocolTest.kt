package dev.hitlbar.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test fun bubbleDismissNeverConfirmsTheStartCard() {
        assertEquals(null, dismissButton(listOf(BarButton.DONE)))
        assertEquals(null, dismissButton(listOf(BarButton.DONE, BarButton.NOREC)))
        assertEquals(BarButton.STOP, dismissButton(listOf(BarButton.DONE, BarButton.NOREC, BarButton.STOP)))
        assertEquals(BarButton.DONE, dismissButton(listOf(BarButton.DONE, BarButton.REDO)))
    }

    @Test
    fun missingButtonsExtraShowsAll() {
        assertEquals(listOf(BarButton.DONE, BarButton.NEXT, BarButton.STOP), parseButtons(null))
    }

    @Test
    fun onlyNamedButtonsInFixedOrder() {
        assertEquals(listOf(BarButton.DONE, BarButton.STOP), parseButtons("stop, DONE"))
        assertEquals(listOf(BarButton.DONE, BarButton.REDO), parseButtons("redo,done"))
        assertEquals(listOf(BarButton.DONE, BarButton.NOREC), parseButtons("norec,done"))
        assertEquals(listOf(BarButton.NEXT), parseButtons("next,bogus,;rm"))
        assertEquals(emptyList<BarButton>(), parseButtons(""))
    }

    @Test
    fun trialCardButtonsParseInRowOrder() {
        assertEquals(
            listOf(BarButton.DONE, BarButton.RETRY, BarButton.REDO, BarButton.NEXT, BarButton.STOP),
            parseButtons("stop,next,redo,retry,done"),
        )
    }

    @Test
    fun aButtonKeepsItsPlaceFromCardToCard() {
        assertEquals(
            listOf(BarButton.DONE, null, null, BarButton.NEXT, BarButton.STOP),
            layoutSlots(parseButtons(null)),
        )
        assertEquals(
            listOf(BarButton.DONE, BarButton.RETRY, null, BarButton.NEXT, BarButton.STOP),
            layoutSlots(parseButtons("done,retry,next,stop")),
        )
        assertEquals(BUTTON_SLOTS, layoutSlots(BarButton.entries - BarButton.NOREC).filterNotNull().size)
    }

    @Test
    fun twoButtonsForOnePlaceAreAllStillShown() {
        val both = listOf(BarButton.NOREC, BarButton.RETRY)
        assertEquals(both, layoutSlots(both))
    }

    @Test
    fun everyButtonHasItsOwnNameLabelAndColor() {
        val all = BarButton.entries
        assertEquals(all.size, all.map { it.wire }.toSet().size)
        assertEquals(all.size, all.map { it.label }.toSet().size)
        assertEquals(all.size, all.map { it.color }.toSet().size)
        assertTrue(all.all { it.slot in 0 until BUTTON_SLOTS })
    }

    @Test
    fun textIsCappedAt200Chars() {
        assertEquals("", clampText(null))
        assertEquals("hello", clampText("hello"))
        assertEquals(200, clampText("x".repeat(500)).length)
    }

    @Test
    fun capNeverSplitsASurrogatePair() {
        val emoji = "\uD83D\uDE00"
        val clamped = clampText("a".repeat(199) + emoji)
        assertEquals(199, clamped.length)
        assertFalse(clamped.last().isHighSurrogate())
    }

    @Test
    fun logLinesMatchTheHostProtocol() {
        assertEquals("QAOVERLAY shown seq=1", shownLine(1))
        assertEquals("QAOVERLAY hidden seq=2", hiddenLine(2))
        assertEquals("QAOVERLAY button=done seq=3", buttonLine(BarButton.DONE, 3))
        assertEquals("QAOVERLAY button=next seq=4", buttonLine(BarButton.NEXT, 4))
        assertEquals("QAOVERLAY button=stop seq=5", buttonLine(BarButton.STOP, 5))
        assertEquals("QAOVERLAY button=retry seq=7", buttonLine(BarButton.RETRY, 7))
        assertEquals("QAOVERLAY button=redo seq=8", buttonLine(BarButton.REDO, 8))
        assertEquals("QAOVERLAY error=no-permission seq=6", errorLine("no-permission", 6))
    }

    @Test
    fun seqCountsUp() {
        val seq = SeqCounter()
        assertEquals(listOf(1, 2, 3), List(3) { seq.next() })
    }
}
