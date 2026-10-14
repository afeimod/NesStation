package info.cemu.cemu.common.settings

enum class GamePadPosition {
    OVERLAY,
    ABOVE,
    BELOW,
    LEFT,
    RIGHT;

    fun isOverlay() = this == OVERLAY
    fun isVertical() = this == ABOVE || this == BELOW
    fun appearsAfterTV() = this == BELOW || this == RIGHT
}