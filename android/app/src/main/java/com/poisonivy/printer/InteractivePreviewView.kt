package com.poisonivy.printer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * A multi-image "sticker sheet" canvas: shows all currently placed
 * images (see ImagePrep.PlacedImage), lets the user tap one to select
 * it (shown with a highlighted outline; unselected images show a
 * fainter outline too, per-image, so boundaries are always visible),
 * tap a small delete button at an image's corner to remove it, and
 * manipulate the SELECTED image with a two-finger gesture -- pinch to
 * scale, twist to rotate, and drag the pair of fingers to move it, all
 * from one continuous motion (see the class doc on the old
 * single-image version of this view, in git history, for why this is
 * computed as one unified calculation rather than combining separate
 * zoom/rotate/pan systems).
 *
 * Single-finger touches that aren't a quick tap are left un-acted-on
 * (no requestDisallowInterceptTouchEvent claim) so the enclosing
 * ScrollView is still free to scroll normally with one finger.
 *
 * Rendering uses ImagePrep.buildPlacedImageMatrix()/bakeComposite() --
 * the EXACT same functions used to bake the final print bitmap at
 * print time, just targeting this view's own on-screen size instead
 * of the print canvas's, so what's shown here is a faithful preview
 * of what will print (this view's own bounds MUST be locked to exact
 * 2:3 by the host Activity for that to hold -- see
 * MainActivity.lockPreviewContainerTo2x3).
 *
 * `placedImages` is the live, continuously-updated list (read it any
 * time for the current state). `onImagesChanged` fires once per
 * discrete committed action (a completed gesture, a delete, a 90-
 * degree rotate, a reset) so the host can push a new undo-history
 * snapshot -- NOT on every intermediate gesture frame, which would
 * flood the history.
 *
 * NOTE ON TESTING: written without access to a physical Android
 * device or emulator. The matrix/geometry math (ImagePrep) uses
 * Android's own Matrix invert/mapPoints rather than hand-derived
 * formulas specifically so it's easier to verify by inspection, but
 * the touch/tap/gesture bookkeeping below is exactly the kind of code
 * that most benefits from real on-device tuning -- tap-vs-drag
 * thresholds (tapSlopPx/tapMaxDurationMs) and the delete-button hit
 * radius (deleteButtonRadiusPx) especially, since "does this feel
 * right" is inherently a hands-on judgment call.
 */
class InteractivePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var placedImages: List<ImagePrep.PlacedImage> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var selectedImageId: Long? = null
        private set(value) {
            field = value
            onSelectionChanged?.invoke(value)
            invalidate()
        }

    var backgroundColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    /** Fires once per discrete committed action, with the new full image list. */
    var onImagesChanged: ((List<ImagePrep.PlacedImage>) -> Unit)? = null

    /** Fires whenever the selected image changes (including becoming null). */
    var onSelectionChanged: ((Long?) -> Unit)? = null

    fun selectImage(id: Long?) {
        selectedImageId = id
    }

    fun rotateSelectedImage90() {
        val id = selectedImageId ?: return
        placedImages = placedImages.map {
            if (it.id == id) it.copy(rotationAngle = it.rotationAngle + 90f) else it
        }
        onImagesChanged?.invoke(placedImages)
    }

    fun resetSelectedImage() {
        val id = selectedImageId ?: return
        placedImages = placedImages.map {
            if (it.id == id) {
                it.copy(centerXFraction = 0.5f, centerYFraction = 0.5f, scale = 1f, rotationAngle = 0f)
            } else it
        }
        onImagesChanged?.invoke(placedImages)
    }

    private var imagesAtGestureStart: List<ImagePrep.PlacedImage> = emptyList()

    // Tap detection
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var hadSecondFinger = false
    private val tapSlopPx: Float by lazy { resources.displayMetrics.density * 10f }
    private val tapMaxDurationMs = 300L
    private val deleteButtonRadiusPx: Float by lazy { resources.displayMetrics.density * 16f }

    // Two-finger gesture reference, captured fresh whenever the pointer
    // set changes while 2+ fingers are down.
    private var refImage: ImagePrep.PlacedImage? = null
    private var refFocusX = 0f
    private var refFocusY = 0f
    private var refDistance = 1f
    private var refAngle = 0f
    private var twoFingerGestureActive = false

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val selectedBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#66BB6A") // app accent green
        strokeWidth = 6f
    }
    private val unselectedBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(160, 255, 255, 255)
        strokeWidth = 3f
    }
    private val deleteButtonFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val deleteButtonStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#B00020")
        strokeWidth = 3f
    }
    private val deleteXPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#B00020")
        strokeWidth = 4f
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()
                hadSecondFinger = false
                imagesAtGestureStart = placedImages
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    hadSecondFinger = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    twoFingerGestureActive = true
                    if (selectedImageId == null && width > 0 && height > 0) {
                        val midX = (event.getX(0) + event.getX(1)) / 2f
                        val midY = (event.getY(0) + event.getY(1)) / 2f
                        val hit = ImagePrep.hitTestPlacedImage(placedImages, midX, midY, width, height)
                        if (hit != null) selectedImageId = hit.id
                    }
                    captureTwoFingerReference(event)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = event.pointerCount - 1
                if (remaining >= 2) {
                    captureTwoFingerReference(event)
                } else {
                    twoFingerGestureActive = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFingerGestureActive && event.pointerCount >= 2 && width > 0 && height > 0) {
                    val selId = selectedImageId
                    val ref = refImage
                    if (selId != null && ref != null) {
                        val curDistance = distanceBetweenPointers(event).coerceAtLeast(1f)
                        val curAngle = angleBetweenPointers(event)
                        val curFocusX = (event.getX(0) + event.getX(1)) / 2f
                        val curFocusY = (event.getY(0) + event.getY(1)) / 2f

                        val scaleFactor = curDistance / refDistance
                        val newScale = (ref.scale * scaleFactor).coerceIn(0.1f, 10f)

                        var angleDelta = curAngle - refAngle
                        if (angleDelta > 180f) angleDelta -= 360f
                        if (angleDelta < -180f) angleDelta += 360f
                        val newRotation = ref.rotationAngle + angleDelta

                        val updated = ImagePrep.pivotPlacedImage(
                            referenceImage = ref,
                            referenceFocusX = refFocusX,
                            referenceFocusY = refFocusY,
                            newScale = newScale,
                            newRotation = newRotation,
                            newFocusX = curFocusX,
                            newFocusY = curFocusY,
                            dstWidth = width,
                            dstHeight = height,
                        )
                        placedImages = placedImages.map { if (it.id == selId) updated else it }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                twoFingerGestureActive = false
                parent?.requestDisallowInterceptTouchEvent(false)

                if (!hadSecondFinger) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val dist = Math.sqrt((dx * dx + dy * dy).toDouble())
                    val duration = System.currentTimeMillis() - downTime
                    if (dist < tapSlopPx && duration < tapMaxDurationMs) {
                        handleTap(downX, downY)
                    }
                }

                if (placedImages != imagesAtGestureStart) {
                    onImagesChanged?.invoke(placedImages)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                twoFingerGestureActive = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (placedImages != imagesAtGestureStart) {
                    onImagesChanged?.invoke(placedImages)
                }
            }
        }
        // Always return true so this view keeps receiving the REST of the
        // touch sequence (Android stops delivering later events, like a
        // second finger arriving, to a view that returned false on
        // ACTION_DOWN). Whether the parent ScrollView ALSO gets to
        // intercept and scroll is controlled entirely by
        // requestDisallowInterceptTouchEvent above, not by this value.
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        if (width <= 0 || height <= 0) return

        for (image in placedImages.asReversed()) {
            val pos = ImagePrep.deleteButtonScreenPosition(image, width, height)
            val dx = x - pos[0]
            val dy = y - pos[1]
            if (Math.sqrt((dx * dx + dy * dy).toDouble()) <= deleteButtonRadiusPx) {
                deleteImage(image.id)
                return
            }
        }

        val hit = ImagePrep.hitTestPlacedImage(placedImages, x, y, width, height)
        selectedImageId = hit?.id
    }

    private fun deleteImage(id: Long) {
        placedImages = placedImages.filter { it.id != id }
        if (selectedImageId == id) {
            selectedImageId = null
        }
        onImagesChanged?.invoke(placedImages)
    }

    private fun captureTwoFingerReference(event: MotionEvent) {
        val id = selectedImageId ?: return
        refImage = placedImages.firstOrNull { it.id == id } ?: return
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
        canvas.drawColor(backgroundColor)
        if (width <= 0 || height <= 0) return

        for (image in placedImages) {
            val matrix = ImagePrep.buildPlacedImageMatrix(image, width, height)
            canvas.drawBitmap(image.bitmap, matrix, imagePaint)
        }

        val path = Path()
        for (image in placedImages) {
            val corners = ImagePrep.boundingBoxCorners(image, width, height)
            val isSelected = image.id == selectedImageId
            path.reset()
            path.moveTo(corners[0], corners[1])
            path.lineTo(corners[2], corners[3])
            path.lineTo(corners[4], corners[5])
            path.lineTo(corners[6], corners[7])
            path.close()
            canvas.drawPath(path, if (isSelected) selectedBoxPaint else unselectedBoxPaint)

            val delPos = ImagePrep.deleteButtonScreenPosition(image, width, height)
            canvas.drawCircle(delPos[0], delPos[1], deleteButtonRadiusPx, deleteButtonFillPaint)
            canvas.drawCircle(delPos[0], delPos[1], deleteButtonRadiusPx, deleteButtonStrokePaint)
            val r = deleteButtonRadiusPx * 0.45f
            canvas.drawLine(delPos[0] - r, delPos[1] - r, delPos[0] + r, delPos[1] + r, deleteXPaint)
            canvas.drawLine(delPos[0] - r, delPos[1] + r, delPos[0] + r, delPos[1] - r, deleteXPaint)
        }
    }
}
