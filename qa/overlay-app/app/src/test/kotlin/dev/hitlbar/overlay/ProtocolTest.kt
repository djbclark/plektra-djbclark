package dev.hitlbar.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProtocolTest {
    @Test
    fun missingButtonsExtraShowsAll() {
        assertEquals(listOf(BarButton.DONE, BarButton.NEXT, BarButton.STOP), parseButtons(null))
    }

    @Test
    fun onlyNamedButtonsInFixedOrder() {
        assertEquals(listOf(BarButton.DONE, BarButton.STOP), parseButtons("stop, DONE"))
        assertEquals(listOf(BarButton.NEXT), parseButtons("next,bogus,;rm"))
        assertEquals(emptyList<BarButton>(), parseButtons(""))
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
        assertEquals("QAOVERLAY error=no-permission seq=6", errorLine("no-permission", 6))
    }

    @Test
    fun seqCountsUp() {
        val seq = SeqCounter()
        assertEquals(listOf(1, 2, 3), List(3) { seq.next() })
    }
}
