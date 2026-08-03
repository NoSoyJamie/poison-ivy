package com.poisonivy.printer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Port of poison_ivy/image_prep.py, extended into a multi-image
 * "sticker sheet" compositor: any number of independently placed,
 * scaled, and rotated images on one shared canvas, rather than a
 * single image filling the whole sheet.
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
 * LAYOUT MODEL: each PlacedImage carries its own position (as a
 * fraction of canvas width/height, so it's resolution independent
 * between the live preview and the full-resolution final print),
 * scale (relative to a sensible auto-fit default size, NOT relative
 * to "cover the whole canvas" like the old single-image model), and
 * rotation. buildPlacedImageMatrix()/bakeComposite() are used
 * identically by the live interactive view (at its own on-screen
 * size) and the final print bake (at PREVIEW_WIDTH x PREVIEW_HEIGHT),
 * so on-screen framing always matches what prints.
 */
object ImagePrep {

    const val PREVIEW_WIDTH = 1280
    const val PREVIEW_HEIGHT = 1920
    const val FINAL_WIDTH = 640
    const val FINAL_HEIGHT = 1616

    const val DEFAULT_MAX_PREVIEW_BYTES = 400_000

    // New images default to filling this fraction of the canvas's
    // shorter dimension along their own longer axis, at scale = 1.0.
    private const val DEFAULT_FIT_FRACTION = 0.4f

    data class PreparedImage(
        val previewJpeg: ByteArray,
        val finalJpeg: ByteArray,
        val usedQuality: Int,
    )

    /**
     * One image placed on the shared canvas. `bitmap` is the
     * EXIF-corrected source (see loadOrientedBitmap) -- not yet scaled
     * or cropped to anything. `id` is a stable identity for
     * selection/deletion/undo bookkeeping, independent of list order.
     */
    data class PlacedImage(
        val id: Long,
        val bitmap: Bitmap,
        val centerXFraction: Float = 0.5f,
        val centerYFraction: Float = 0.5f,
        val scale: Float = 1f,
        val rotationAngle: Float = 0f,
    )

    /**
     * Builds the Matrix mapping `image`'s bitmap's own pixel
     * coordinates onto a dstW x dstH canvas. scale=1.0 corresponds to
     * a default auto-fit size (DEFAULT_FIT_FRACTION of the canvas's
     * shorter dimension along the image's longer axis), NOT to
     * "cover the whole canvas" -- that's the key difference from the
     * old single-image model, since a sticker sheet's images are
     * usually meant to start small, not fill the page.
     */
    fun buildPlacedImageMatrix(image: PlacedImage, dstWidth: Int, dstHeight: Int): Matrix {
        val srcW = image.bitmap.width
        val srcH = image.bitmap.height
        val defaultFitScale = (DEFAULT_FIT_FRACTION * minOf(dstWidth, dstHeight)) / maxOf(srcW, srcH)
        val totalScale = defaultFitScale * image.scale

        val matrix = Matrix()
        matrix.postTranslate(-srcW / 2f, -srcH / 2f)
        matrix.postScale(totalScale, totalScale)
        matrix.postRotate(image.rotationAngle)
        matrix.postTranslate(image.centerXFraction * dstWidth, image.centerYFraction * dstHeight)
        return matrix
    }

    /**
     * Adapts the same pivot-around-a-point idea from the old
     * single-image model to one placed image among many: returns
     * a copy of `referenceImage` with scale/rotation changed to
     * newScale/newRotation, with position solved so that whatever was
     * at screen point (referenceFocusX, referenceFocusY) under
     * referenceImage's old placement ends up at (newFocusX, newFocusY)
     * after the change. referenceFocusX/Y and newFocusX/Y can differ,
     * which is what lets pan fall out of the same calculation as a
     * two-finger gesture's midpoint moves, rather than needing
     * separate pan-tracking.
     */
    fun pivotPlacedImage(
        referenceImage: PlacedImage,
        referenceFocusX: Float,
        referenceFocusY: Float,
        newScale: Float,
        newRotation: Float,
        newFocusX: Float,
        newFocusY: Float,
        dstWidth: Int,
        dstHeight: Int,
    ): PlacedImage {
        val refMatrix = buildPlacedImageMatrix(referenceImage, dstWidth, dstHeight)
        val inverse = Matrix()
        if (!refMatrix.invert(inverse)) {
            return referenceImage.copy(scale = newScale, rotationAngle = newRotation)
        }
        val imagePoint = floatArrayOf(referenceFocusX, referenceFocusY)
        inverse.mapPoints(imagePoint)

        val noPan = referenceImage.copy(
            scale = newScale,
            rotationAngle = newRotation,
            centerXFraction = 0f,
            centerYFraction = 0f,
        )
        val noPanMatrix = buildPlacedImageMatrix(noPan, dstWidth, dstHeight)
        val mapped = floatArrayOf(imagePoint[0], imagePoint[1])
        noPanMatrix.mapPoints(mapped)

        val panPxX = newFocusX - mapped[0]
        val panPxY = newFocusY - mapped[1]

        return referenceImage.copy(
            scale = newScale,
            rotationAngle = newRotation,
            centerXFraction = panPxX / dstWidth,
            centerYFraction = panPxY / dstHeight,
        )
    }

    /**
     * Returns the placed image (if any) whose bounds contain screen
     * point (x, y) on a dstW x dstH canvas, checked in reverse list
     * order so later-added (visually on-top, when overlapping) images
     * are hit-tested first.
     */
    fun hitTestPlacedImage(images: List<PlacedImage>, x: Float, y: Float, dstWidth: Int, dstHeight: Int): PlacedImage? {
        for (image in images.asReversed()) {
            val matrix = buildPlacedImageMatrix(image, dstWidth, dstHeight)
            val inverse = Matrix()
            if (!matrix.invert(inverse)) continue
            val pt = floatArrayOf(x, y)
            inverse.mapPoints(pt)
            if (pt[0] in 0f..image.bitmap.width.toFloat() && pt[1] in 0f..image.bitmap.height.toFloat()) {
                return image
            }
        }
        return null
    }

    /**
     * Screen-space position of `image`'s delete-button anchor (its
     * top-right corner, after scale/rotation/position are applied).
     * Used identically for drawing the button and for hit-testing taps
     * against it, so they can never disagree with each other.
     */
    fun deleteButtonScreenPosition(image: PlacedImage, dstWidth: Int, dstHeight: Int): FloatArray {
        val matrix = buildPlacedImageMatrix(image, dstWidth, dstHeight)
        val corner = floatArrayOf(image.bitmap.width.toFloat(), 0f)
        matrix.mapPoints(corner)
        return corner
    }

    /**
     * The four corners of `image`'s bounds in screen space (top-left,
     * top-right, bottom-right, bottom-left, in that order), already
     * rotated/scaled/positioned -- used to draw its bounding box.
     */
    fun boundingBoxCorners(image: PlacedImage, dstWidth: Int, dstHeight: Int): FloatArray {
        val matrix = buildPlacedImageMatrix(image, dstWidth, dstHeight)
        val w = image.bitmap.width.toFloat()
        val h = image.bitmap.height.toFloat()
        val corners = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        matrix.mapPoints(corners)
        return corners
    }

    /**
     * Renders every placed image onto one dstW x dstH bitmap, in list
     * order (later entries draw on top of earlier ones where they
     * overlap), filling everything else with backgroundColor. Shared
     * by both the live interactive view and the final print bake.
     */
    fun bakeComposite(images: List<PlacedImage>, dstWidth: Int, dstHeight: Int, backgroundColor: Int): Bitmap {
        val canvas = Bitmap.createBitmap(dstWidth, dstHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(canvas)
        c.drawColor(backgroundColor)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        for (image in images) {
            val matrix = buildPlacedImageMatrix(image, dstWidth, dstHeight)
            c.drawBitmap(image.bitmap, matrix, paint)
        }
        return canvas
    }

    /**
     * Rotates a bitmap by an arbitrary multiple of 90 degrees
     * (0, 90, 180, or 270; other values are normalized into that set).
     * Used for EXIF correction on load; per-placed-image rotation uses
     * PlacedImage.rotationAngle (continuous) instead, not this.
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
     * Loads the image at `uri` and applies EXIF correction. This is
     * the bitmap a new PlacedImage wraps -- not yet scaled/positioned
     * to anything, that's handled by the canvas layout (see
     * buildPlacedImageMatrix) and the user's gestures.
     */
    fun loadOrientedBitmap(context: Context, uri: Uri): Bitmap {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Could not open image at $uri")
        var src = inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
            ?: throw IllegalArgumentException("Could not decode image at $uri")
        src = applyExifOrientation(context, uri, src)
        return src
    }

    /**
     * Composites all placed images and produces the two JPEG buffers
     * the printer expects.
     *
     * @param backgroundColor fills any canvas area not covered by an
     *   image -- now a full ARGB color from the color picker, not just
     *   black/white.
     * @param maxPreviewBytes automatically lowers JPEG quality below
     *   `quality` as needed to keep the preview under this many bytes.
     *   Pass null to disable and always use the exact quality given.
     */
    fun prepareImage(
        images: List<PlacedImage>,
        backgroundColor: Int,
        quality: Int = 95,
        maxPreviewBytes: Int? = DEFAULT_MAX_PREVIEW_BYTES,
    ): PreparedImage {
        val preview = bakeComposite(images, PREVIEW_WIDTH, PREVIEW_HEIGHT, backgroundColor)
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
