package com.poisonivy.printer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Port of poison_ivy/image_prep.py. Prepares an arbitrary source
 * image for the Canon IVY, replicating the same crop/resize steps
 * the Python tool (and the official app) use.
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
            // source is wider than the target ratio -> add height (top/bottom bars)
            newWidth = width
            newHeight = Math.round(width * ratioH.toDouble() / ratioW).toInt()
        } else {
            // source is taller than the target ratio -> add width (left/right bars)
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
     * Scales `source` to fill (autoCrop=true) or fit (autoCrop=false)
     * a targetW x targetH box, center-cropping or center-padding as
     * needed, mirroring Python's _crop_resize.
     */
    private fun cropResize(source: Bitmap, targetW: Int, targetH: Int, autoCrop: Boolean): Bitmap {
        val width = source.width
        val height = source.height

        val scale = if (autoCrop) {
            maxOf(targetW.toDouble() / width, targetH.toDouble() / height)
        } else {
            minOf(targetW.toDouble() / width, targetH.toDouble() / height)
        }

        val scaledW = Math.round(width * scale).toInt()
        val scaledH = Math.round(height * scale).toInt()

        val scaledBitmap = if (scaledW != width || scaledH != height) {
            Bitmap.createScaledBitmap(source, scaledW, scaledH, true)
        } else {
            source
        }

        val canvas = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val c = Canvas(canvas)
        c.drawColor(Color.BLACK)
        val offsetX = (targetW - scaledW) / 2
        val offsetY = (targetH - scaledH) / 2
        c.drawBitmap(scaledBitmap, offsetX.toFloat(), offsetY.toFloat(), null)
        return canvas
    }

    private fun rotate180(source: Bitmap): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(180f)
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
            // Even minimum quality exceeds the limit; nothing more we can do
            // without also shrinking pixel dimensions.
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
     * Loads an image from a content Uri (e.g. from the system image
     * picker) and produces the two JPEG buffers the printer expects.
     *
     * @param padTo2x3 letterbox the source to exactly 2:3 before
     *   anything else, so autoCrop can't cut into it. Use for content
     *   with a fixed aspect ratio you don't want trimmed (card scans).
     * @param padFillColor fill color for the padding bars, e.g.
     *   Color.BLACK instead of the white default.
     * @param maxPreviewBytes automatically lowers JPEG quality below
     *   `quality` as needed to keep the preview under this many bytes.
     *   Pass null to disable and always use the exact quality given.
     */
    fun prepareImage(
        context: Context,
        uri: Uri,
        autoCrop: Boolean = true,
        quality: Int = 95,
        padTo2x3: Boolean = false,
        padFillColor: Int = Color.WHITE,
        maxPreviewBytes: Int? = DEFAULT_MAX_PREVIEW_BYTES,
    ): PreparedImage {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Could not open image at $uri")
        var src = inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
            ?: throw IllegalArgumentException("Could not decode image at $uri")

        // Respect EXIF orientation, same as PIL effectively does via
        // ImageOps.exif_transpose in a fuller pipeline -- Android's
        // BitmapFactory does NOT auto-rotate, so do it explicitly.
        src = applyExifOrientation(context, uri, src)

        if (padTo2x3) {
            src = padToRatio(src, 2, 3, padFillColor)
        }

        val preview = cropResize(src, PREVIEW_WIDTH, PREVIEW_HEIGHT, autoCrop)
        val final = rotate180(
            Bitmap.createScaledBitmap(preview, FINAL_WIDTH, FINAL_HEIGHT, true)
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
            val matrix = Matrix()
            when (orientation) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bitmap
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            // If EXIF reading fails for any reason, just use the bitmap as-is
            // rather than failing the whole print.
            bitmap
        }
    }
}
