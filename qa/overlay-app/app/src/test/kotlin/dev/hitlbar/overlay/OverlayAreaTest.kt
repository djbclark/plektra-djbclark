package dev.hitlbar.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayAreaTest {
    @Test fun clampsDraggingToTheUsableDisplay() {
        val area = OverlayArea(6, 30, 400, 650)
        assertEquals(OverlayPosition(6, 30), area.clamp(OverlayPosition(-10, 2), 100, 48))
        assertEquals(OverlayPosition(300, 602), area.clamp(OverlayPosition(390, 630), 100, 48))
        assertEquals(OverlayPosition(80, 200), area.clamp(OverlayPosition(80, 200), 300, 120))
    }

    @Test fun anchorsOversizedCardsToTheTopLeft() {
        val area = OverlayArea(6, 30, 100, 120)
        assertEquals(OverlayPosition(6, 30), area.clamp(OverlayPosition(400, 400), 120, 120))
    }
}
