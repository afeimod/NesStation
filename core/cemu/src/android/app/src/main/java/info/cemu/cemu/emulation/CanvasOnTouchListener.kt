package info.cemu.cemu.emulation

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import info.cemu.cemu.nativeinterface.NativeInput

class CanvasOnTouchListener(
    val isTV: Boolean,
    // optional coordinate scaling: maps raw view-space touch coordinates into
    // the coordinate space the native side expects (used by the floating GamePad
    // overlay, whose TextureView is scaled down from its fixed 854x480 buffer)
    private val scaleProvider: ((View) -> Pair<Float, Float>)? = null,
) : View.OnTouchListener {
    private var currentPointerId: Int = -1

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)
        if (currentPointerId != -1 && pointerId != currentPointerId) {
            return false
        }
        var x = event.getX(pointerIndex)
        var y = event.getY(pointerIndex)
        scaleProvider?.let { scale ->
            val (scaleX, scaleY) = scale(v)
            x *= scaleX
            y *= scaleY
        }
        val touchX = x.toInt()
        val touchY = y.toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                NativeInput.onTouchDown(touchX, touchY, isTV)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                currentPointerId = -1
                NativeInput.onTouchUp(touchX, touchY, isTV)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                NativeInput.onTouchMove(touchX, touchY, isTV)
                return true
            }
        }
        return false
    }
}