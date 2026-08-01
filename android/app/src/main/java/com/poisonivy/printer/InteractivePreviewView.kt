package com.poisonivy.printer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Shows the print source image and lets the user frame it with
 * two-finger touch gestures -- pinch to zoom, twist to rotate, and
 * drag the pair of fingers to pan, ALL from the same continuous
 * two-finger motion (not separate gestures you have to do one at a
 * time). Single-finger touches are deliberately left un-acted-on (no
 * pan, no requestDisallowInterceptTouchEvent claim) so the enclosing
 * ScrollView is still free to intercept and scroll normally with one
 * finger, including when this preview fills most or all of the
 * visible screen.
 *
 * The current framing is exposed as `transform` (see
 * ImagePrep.TransformState) and rendered using
 * ImagePrep.buildTransformMatrix() -- the EXACT same function used to
 * bake the final print bitmap at print time, just targeting this
 * view's own on-screen size instead of the print canvas's, so what's
 * shown here is a faithful preview of what will print.
 *
 * HOW THE COMBINED GESTURE WORKS: rather than combining Android's
 * ScaleGestureDetector (for zoom) with separate hand-rolled rotation
 * tracking -- two independent systems that only loosely cooperate,
 * which is what made an earlier version of this feel stiff / like
 * only one of zoom-or-rotate worked at a time -- this computes zoom,
 * rotation, AND pan together from the same two raw pointer positions
 * every frame:
 *   1. When a second finger touches down, capture a "reference":
 *      the current transform, plus the current two-finger distance,
 *      angle, and midpoint.
 *   2. On every subsequent move (while still 2 fingers down), compare
 *      the CURRENT distance/angle/midpoint against that reference to
 *      get a scale factor and rotation delta, and use
 *      ImagePrep.pivotTransform to solve for the pan that keeps
 *      whatever was under the reference midpoint anchored under the
 *      CURRENT midpoint -- which naturally produces panning too, as
 *      an emergent property of the midpoint itself moving, with no
 *      separate pan-tracking code needed.
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
 * matrix math (buildTransformMatrix/pivotTransform, in ImagePrep) is
 * straightforward to verify by inspection using Android's own
 * Matrix invert/mapPoints rather than hand-derived formulas, but the
 * gesture bookkeeping below (tracking pointers across finger-count
 * transitions) is the kind of code that most benefits from real
 * on-device testing/tuning. If gestures feel janky or jump when a
 * third finger touches down (an edge case this doesn't specially
 * handle -- it just re-baselines against whichever two pointers
 * happen to be at index 0/1), that's the most likely place to look.
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

    // Two-finger gesture reference, captured fresh whenever the pointer
    // set changes while 2+ fingers are down.
    private var refTransform = ImagePrep.TransformState()
    private var refFocusX = 0f
    private var refFocusY = 0f
    private var refDistance = 1f
    private var refAngle = 0f
    private var twoFingerGestureActive = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Deliberately do NOT claim the gesture yet, and do
                // nothing else here -- a single finger touching down
                // should be free to become a normal ScrollView scroll.
                // We only start actually doing anything once a second
                // finger arrives, below.
                transformAtGestureStart = transform
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    // Now we're doing real two-finger manipulation --
                    // claim the gesture from the ScrollView from this
                    // point forward.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    twoFingerGestureActive = true
                    captureTwoFingerReference(event)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = event.pointerCount - 1
                if (remaining >= 2) {
                    // Still 2+ fingers after this one lifts -- re-baseline
                    // against whichever pointers remain.
                    captureTwoFingerReference(event)
                } else {
                    // Dropping below 2 fingers ends two-finger manipulation.
                    // Release the ScrollView claim so a single remaining
                    // finger can scroll normally again.
                    twoFingerGestureActive = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val bmp = sourceBitmap
                if (twoFingerGestureActive && bmp != null && event.pointerCount >= 2 &&
                    width > 0 && height > 0
                ) {
                    val curDistance = distanceBetweenPointers(event).coerceAtLeast(1f)
                    val curAngle = angleBetweenPointers(event)
                    val curFocusX = (event.getX(0) + event.getX(1)) / 2f
                    val curFocusY = (event.getY(0) + event.getY(1)) / 2f

                    val scaleFactor = curDistance / refDistance
                    val newZoom = (refTransform.zoomScale * scaleFactor).coerceIn(0.3f, 6f)

                    var angleDelta = curAngle - refAngle
                    if (angleDelta > 180f) angleDelta -= 360f
                    if (angleDelta < -180f) angleDelta += 360f
                    val newRotation = refTransform.rotationAngle + angleDelta

                    // Solving for pan against the REFERENCE focus/transform
                    // (captured once, at gesture/transition start) rather
                    // than the previous frame's transform is what lets
                    // zoom + rotate + the midpoint moving (pan) all resolve
                    // correctly together in one calculation.
                    transform = ImagePrep.pivotTransform(
                        referenceTransform = refTransform,
                        referenceFocusX = refFocusX,
                        referenceFocusY = refFocusY,
                        newZoom = newZoom,
                        newRotation = newRotation,
                        newFocusX = curFocusX,
                        newFocusY = curFocusY,
                        srcWidth = bmp.width,
                        srcHeight = bmp.height,
                        dstWidth = width,
                        dstHeight = height,
                    )
                }
                // Single-finger moves are intentionally ignored here -- with
                // requestDisallowInterceptTouchEvent never having been
                // called for a single-finger touch, those events reach the
                // ScrollView too and it handles its own scrolling normally.
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                twoFingerGestureActive = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (transform != transformAtGestureStart) {
                    onTransformCommitted?.invoke(transform)
                }
            }
        }
        // Always return true (claim the event) so this view keeps
        // receiving the REST of the touch sequence -- Android stops
        // delivering later events (like the second finger arriving) to
        // a view that returned false on ACTION_DOWN, which would have
        // silently broken two-finger detection entirely. Whether the
        // parent ScrollView ALSO gets to intercept and scroll is
        // controlled entirely by requestDisallowInterceptTouchEvent
        // above, not by this return value.
        return true
    }

    private fun captureTwoFingerReference(event: MotionEvent) {
        refTransform = transform
        refFocusX = (event.getX(0) + event.getX(1)) / 2f
        refFocusY = (event.getY(0) + event.getY(1)) / 2f
        refDistance = distanceBetweenPointers(event).coerceAtLeast(1f)
        refAngle = angleBetweenPointers(event)
    }

    private fun distanceBetweenPointers(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
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
