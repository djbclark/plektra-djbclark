package dev.hitlbar.overlay

internal data class OverlayPosition(val x: Int, val y: Int)

internal data class OverlayArea(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(1)

    fun clamp(position: OverlayPosition, width: Int, height: Int): OverlayPosition =
        OverlayPosition(
            position.x.coerceIn(left, (right - width).coerceAtLeast(left)),
            position.y.coerceIn(top, (bottom - height).coerceAtLeast(top)),
        )
}
