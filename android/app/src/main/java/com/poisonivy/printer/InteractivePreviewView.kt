package com.poisonivy.printer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Shows the print source image and lets the user frame it with
 * standard touch gestures:
 *   - one finger drag = pan
 *   - two-finger pinch = zoom
 *   - two-finger twist = rotate
 *
 * The current framing is exposed as `transform` (see
 * ImagePrep.TransformState) and rendered using
 * ImagePrep.buildTransformMatrix() -- the EXACT same function used to
 * bake the final print bitmap at print time, just targeting this
 * view's own on-screen size instead of the print canvas's, so what's
 * shown here is a faithful preview of what will print.
 *
 * This view does not manage undo/redo or a reset button itself --
 * MainActivity owns that (see its transform history stack), calling
 * resetTransform() / setting `transform` directly to navigate
 * history. `onTransformCommitted` fires once per gesture (on release,
 * only if something actually changed) so the host can push a new
 * undo-history entry -- NOT on every intermediate touch-move frame,
 * which would flood the history with tiny incremental steps.
 *
 * NOTE ON TESTING: this was written without access to a physical
 * Android device or emulator to verify touch handling against. The
 * matrix math (buildTransformMatrix, in ImagePrep) is straightforward
 * and easy to verify by inspection, but the gesture bookkeeping below
 * (tracking pointers across finger-count transitions) is the kind of
 * code that most benefits from real on-device testing/tuning. If
 * gestures feel janky or jump unexpectedly when adding/removing a
 * second finger, that's the most likely place to look first.
 */
class InteractivePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var sourceBitmap: Bitmap? = null
        set(value) {
            field = value
            transform = ImagePrep.TransformState()
            invalidate()
        }

    var backgroundFillColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    var transform: ImagePrep.TransformState = ImagePrep.TransformState()
        set(value) {
            field = value
            invalidate()
        }

    /** Fires once per completed gesture, only if the transform actually changed. */
    var onTransformCommitted: ((ImagePrep.TransformState) -> Unit)? = null

    fun resetTransform() {
        transform = ImagePrep.TransformState()
    }

    private var transformAtGestureStart = transform
    private var dragLastX = 0f
    private var dragLastY = 0f
    private var rotateLastAngle = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newZoom = (transform.zoomScale * detector.scaleFactor).coerceIn(0.3f, 6f)
                transform = transform.copy(zoomScale = newZoom)
                return true
            }
        }
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                transformAtGestureStart = transform
                resetGestureReferences(event)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // Finger count just changed -- re-baseline our reference
                // points against the current pointer set rather than
                // trying to precisely track which index is which finger
                // across the transition. Causes at most a one-frame
                // "no-op" reset, not a visible jump, since we're only
                // updating the REFERENCE, not the transform itself.
                resetGestureReferences(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val newAngle = angleBetweenPointers(event)
                    var delta = newAngle - rotateLastAngle
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    transform = transform.copy(rotationAngle = transform.rotationAngle + delta)
                    rotateLastAngle = newAngle
                } else if (event.pointerCount == 1) {
                    val dx = event.getX(0) - dragLastX
                    val dy = event.getY(0) - dragLastY
                    if (width > 0 && height > 0) {
                        transform = transform.copy(
                            panXFraction = transform.panXFraction + dx / width,
                            panYFraction = transform.panYFraction + dy / height,
                        )
                    }
                    dragLastX = event.getX(0)
                    dragLastY = event.getY(0)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (transform != transformAtGestureStart) {
                    onTransformCommitted?.invoke(transform)
                }
            }
        }
        return true
    }

    private fun resetGestureReferences(event: MotionEvent) {
        if (event.pointerCount >= 1) {
            dragLastX = event.getX(0)
            dragLastY = event.getY(0)
        }
        if (event.pointerCount >= 2) {
            rotateLastAngle = angleBetweenPointers(event)
        }
    }

    private fun angleBetweenPointers(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(backgroundFillColor)
        val bmp = sourceBitmap ?: return
        if (width <= 0 || height <= 0) return
        val matrix = ImagePrep.buildTransformMatrix(bmp.width, bmp.height, width, height, transform)
        canvas.drawBitmap(bmp, matrix, paint)
    }
}
