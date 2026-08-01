package com.poisonivy.printer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Port of poison_ivy/image_prep.py, extended with interactive
 * pinch-zoom/rotate/pan framing (see InteractivePreviewView).
 *
 * See the Python project's PROTOCOL.md for the full story on why
 * output SIZE matters here, not just correctness: channel 2's
 * proprietary command channel reuses raster data captured alongside
 * one specific ~198,784-byte original image, and the printer's
 * physical print engine has been confirmed (via binary search on a
 * real printer) to silently fail when the pushed image size diverges
 * too far from that -- specifically, confirmed working up to 424,063
 * bytes and confirmed failing at 432,500 bytes. DEFAULT_MAX_PREVIEW_BYTES
 * below matches the Python project's bracketed-and-tested default.
 *
 * On rotation/orientation: the printed sheet is always physically
 * 2:3 (2in x 3in), but source images (card scans especially) aren't
 * always already in that orientation, and there's evidence the
 * printer/protocol may have its own fixed orientation expectations
 * independent of anything in the source file. `rotationDegrees` is a
 * coarse 90-degree-snap rotation applied to the source before any
 * interactive framing; there is currently no known-correct default
 * baked in here -- it's 0 until calibrated against a real test print.
 *
 * FRAMING MODEL (as of the interactive zoom/rotate/pan feature):
 *   1. Load the source image, apply EXIF correction, then the coarse
 *      `rotationDegrees` snap. This is prepareSourceBitmap()'s output.
 *   2. Optionally pad that to exactly 2:3 first (padTo2x3), so nothing
 *      from the source is ever lost regardless of framing choices.
 *   3. Frame that source onto the target canvas (whatever size --
 *      the live on-screen preview uses the view's own size, the final
 *      print uses PREVIEW_WIDTH x PREVIEW_HEIGHT) using a TransformState
 *      (zoom/rotate/pan). buildTransformMatrix() and bakeBitmap() do
 *      this, and are used by BOTH the interactive view's live
 *      rendering and the final print bake -- the same math at two
 *      different output resolutions -- so what's on screen is what
 *      prints. TransformState's default (zoom=1, rotation=0, pan=0,0)
 *      exactly reproduces the old fixed "auto-crop to cover" behavior,
 *      so that's what Reset returns to.
 */
object ImagePrep {

    const val PREVIEW_WIDTH = 1280
    const val PREVIEW_HEIGHT = 1920
    const val FINAL_WIDTH = 640
    const val FINAL_HEIGHT = 1616

    const val DEFAULT_MAX_PREVIEW_BYTES = 400_000

    data class PreparedImage(
        val previewJpeg: ByteArray,
        val finalJpeg: ByteArray,
        val usedQuality: Int,
    )

    /**
     * The user-adjustable framing of the source image within the
     * target canvas. zoomScale is a MULTIPLIER on top of the
     * automatic "cover" base scale (1.0 = default cover fit, >1.0 =
     * zoomed in further, <1.0 = zoomed out, potentially revealing
     * fillColor background). rotationAngle is in degrees, additional
     * to (not replacing) the coarse rotationDegrees snap.
     * panXFraction/panYFraction shift the image as a fraction of the
     * canvas's own width/height, so the same value reproduces the
     * same RELATIVE framing regardless of the canvas's actual pixel
     * size (on-screen preview vs full-resolution print).
     */
    data class TransformState(
        val zoomScale: Float = 1f,
        val rotationAngle: Float = 0f,
        val panXFraction: Float = 0f,
        val panYFraction: Float = 0f,
    )

    /**
     * Builds the Matrix that maps `source`'s own pixel coordinates
     * onto a dstW x dstH canvas, applying TransformState on top of
     * the automatic base "cover" scale. Used identically by the live
     * interactive preview (at the view's on-screen size) and the
     * final print bake (at PREVIEW_WIDTH x PREVIEW_HEIGHT) -- same
     * function, different dst size, so the framing matches exactly.
     */
    fun buildTransformMatrix(
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        transform: TransformState,
    ): Matrix {
        val baseScale = maxOf(dstWidth.toFloat() / srcWidth, dstHeight.toFloat() / srcHeight)
        val totalScale = baseScale * transform.zoomScale

        val matrix = Matrix()
        // Move the source's own center to the origin, so scale/rotate
        // below pivot around the image's center rather than its
        // top-left corner.
        matrix.postTranslate(-srcWidth / 2f, -srcHeight / 2f)
        matrix.postScale(totalScale, totalScale)
        matrix.postRotate(transform.rotationAngle)
        // Move to the destination canvas's center, offset by the pan
        // (as a fraction of the DESTINATION size, so it's resolution
        // independent between preview and final bake).
        val dstCenterX = dstWidth / 2f
        val dstCenterY = dstHeight / 2f
        matrix.postTranslate(
            dstCenterX + transform.panXFraction * dstWidth,
            dstCenterY + transform.panYFraction * dstHeight,
        )
        return matrix
    }

    /**
     * Renders `source` onto a new dstW x dstH bitmap using the given
     * transform, filling any area the (possibly zoomed-out/panned)
     * source doesn't cover with fillColor. This is the shared
     * rendering path for both the interactive view's live drawing and
     * the final print bake.
     */
    fun bakeBitmap(source: Bitmap, dstWidth: Int, dstHeight: Int, transform: TransformState, fillColor: Int): Bitmap {
        val matrix = buildTransformMatrix(source.width, source.height, dstWidth, dstHeight, transform)
        val canvas = Bitmap.createBitmap(dstWidth, dstHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(canvas)
        c.drawColor(fillColor)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(source, matrix, paint)
        return canvas
    }

    /**
     * Adds solid-color bars (centered) so the bitmap's dimensions
     * exactly match ratioW:ratioH, without cropping any original
     * content. Returns the same bitmap unchanged if already that ratio.
     */
    fun padToRatio(source: Bitmap, ratioW: Int, ratioH: Int, fillColor: Int): Bitmap {
        val width = source.width
        val height = source.height
        val targetRatio = ratioW.toDouble() / ratioH
        val currentRatio = width.toDouble() / height

        if (Math.abs(currentRatio - targetRatio) < 1e-9) {
            return source
        }

        val newWidth: Int
        val newHeight: Int
        if (currentRatio > targetRatio) {
            newWidth = width
            newHeight = Math.round(width * ratioH.toDouble() / ratioW).toInt()
        } else {
            newHeight = height
            newWidth = Math.round(height * ratioW.toDouble() / ratioH).toInt()
        }

        val canvas = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(canvas)
        c.drawColor(fillColor)
        val offsetX = (newWidth - width) / 2
        val offsetY = (newHeight - height) / 2
        c.drawBitmap(source, offsetX.toFloat(), offsetY.toFloat(), null)
        return canvas
    }

    /**
     * Rotates a bitmap by an arbitrary multiple of 90 degrees
     * (0, 90, 180, or 270; other values are normalized into that set).
     * Returns the same bitmap unchanged for a 0-degree rotation.
     */
    fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return source
        val matrix = Matrix()
        matrix.postRotate(normalized.toFloat())
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    /**
     * Binary search for the highest JPEG quality (best-looking result)
     * that still keeps the encoded size at or under maxBytes.
     */
    private fun findQualityForSize(
        bitmap: Bitmap,
        maxBytes: Int,
        minQuality: Int = 10,
        maxQuality: Int = 95,
    ): Pair<Int, ByteArray> {
        val smallest = encodeJpeg(bitmap, minQuality)
        if (smallest.size > maxBytes) {
            return Pair(minQuality, smallest)
        }

        var lo = minQuality
        var hi = maxQuality
        var bestQ = lo
        var bestBytes = smallest
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val data = encodeJpeg(bitmap, mid)
            if (data.size <= maxBytes) {
                bestQ = mid
                bestBytes = data
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return Pair(bestQ, bestBytes)
    }

    /**
     * Loads the image at `uri`, applies EXIF correction and the coarse
     * rotationDegrees snap, and optionally pads to exactly 2:3. This
     * is the "source" bitmap the interactive preview view frames via
     * pinch/rotate/pan -- NOT yet cropped/scaled to the print canvas.
     */
    fun prepareSourceBitmap(
        context: Context,
        uri: Uri,
        rotationDegrees: Int = 0,
        padTo2x3: Boolean = false,
        padFillColor: Int = Color.WHITE,
    ): Bitmap {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Could not open image at $uri")
        var src = inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
            ?: throw IllegalArgumentException("Could not decode image at $uri")

        src = applyExifOrientation(context, uri, src)
        src = rotateBitmap(src, rotationDegrees)

        if (padTo2x3) {
            src = padToRatio(src, 2, 3, padFillColor)
        }
        return src
    }

    /**
     * Takes an already-prepared source bitmap (see prepareSourceBitmap)
     * and the user's current framing (see TransformState/InteractivePreviewView)
     * and produces the two JPEG buffers the printer expects.
     *
     * @param transform the user's current zoom/rotate/pan framing,
     *   read from the interactive preview view at print time. Its
     *   default reproduces the old fixed "auto-crop to cover" behavior.
     * @param padFillColor also used as the fill color for any canvas
     *   area the framed source doesn't cover (e.g. zoomed out).
     * @param maxPreviewBytes automatically lowers JPEG quality below
     *   `quality` as needed to keep the preview under this many bytes.
     *   Pass null to disable and always use the exact quality given.
     */
    fun prepareImage(
        source: Bitmap,
        transform: TransformState = TransformState(),
        quality: Int = 95,
        padFillColor: Int = Color.WHITE,
        maxPreviewBytes: Int? = DEFAULT_MAX_PREVIEW_BYTES,
    ): PreparedImage {
        val preview = bakeBitmap(source, PREVIEW_WIDTH, PREVIEW_HEIGHT, transform, padFillColor)
        val final = rotateBitmap(
            Bitmap.createScaledBitmap(preview, FINAL_WIDTH, FINAL_HEIGHT, true),
            180,
        )

        val (usedQuality, previewBytes) = if (maxPreviewBytes != null) {
            findQualityForSize(preview, maxPreviewBytes, maxQuality = quality)
        } else {
            Pair(quality, encodeJpeg(preview, quality))
        }
        val finalBytes = encodeJpeg(final, usedQuality)

        return PreparedImage(previewBytes, finalBytes, usedQuality)
    }

    /**
     * Convenience one-shot version: loads from a Uri and prepares in
     * one call, using the default TransformState (equivalent to the
     * old fixed auto-crop behavior). Mainly useful for testing/CLI-style
     * use; MainActivity's normal flow uses prepareSourceBitmap() +
     * the interactive view + this class's prepareImage(source, ...)
     * separately, since framing needs to be interactively adjustable
     * between those two steps.
     */
    fun prepareImage(
        context: Context,
        uri: Uri,
        rotationDegrees: Int = 0,
        transform: TransformState = TransformState(),
        quality: Int = 95,
        padTo2x3: Boolean = false,
        padFillColor: Int = Color.WHITE,
        maxPreviewBytes: Int? = DEFAULT_MAX_PREVIEW_BYTES,
    ): PreparedImage {
        val source = prepareSourceBitmap(context, uri, rotationDegrees, padTo2x3, padFillColor)
        return prepareImage(source, transform, quality, padFillColor, maxPreviewBytes)
    }

    private fun applyExifOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return bitmap
            val exif = androidx.exifinterface.media.ExifInterface(inputStream)
            inputStream.close()
            val orientation = exif.getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            )
            val degrees = when (orientation) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            rotateBitmap(bitmap, degrees)
        } catch (e: Exception) {
            bitmap
        }
    }
}
