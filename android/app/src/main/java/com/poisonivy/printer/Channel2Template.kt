package com.poisonivy.printer

import android.content.Context

/**
 * Port of poison_ivy/channel2_template.py. Channel 2 (the proprietary
 * command channel) reuses a captured template (bundled as an asset,
 * see assets/channel2_payload.bin -- identical to the Python
 * project's data/channel2_payload.bin) and only patches the one field
 * known to depend on image size.
 *
 * See the Python project's PROTOCOL.md for the full story, including
 * why output size matters for whether the physical print actually
 * happens, not just whether the Bluetooth transfer completes.
 */
object Channel2Template {

    // magic + 0001 00 00
    private val LENGTH_FIELD_MARKER = byteArrayOf(
        0x1b, 0x2a, 0x43, 0x41, 0x00, 0x01, 0x00, 0x00
    )
    private const val LENGTH_FIELD_OFFSET = 7 // length field starts here, 4 bytes big-endian

    private const val ASSET_NAME = "channel2_payload.bin"

    fun loadTemplate(context: Context): ByteArray {
        context.assets.open(ASSET_NAME).use { input ->
            return input.readBytes()
        }
    }

    private fun findMarker(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    data class PatchResult(val patched: ByteArray, val oldLength: Int)

    /**
     * Returns a copy of templateBytes with the size-announce field
     * patched to newLength. Throws IllegalArgumentException if the
     * size-announce command can't be found.
     */
    fun patchTemplate(templateBytes: ByteArray, newLength: Int): PatchResult {
        val pos = findMarker(templateBytes, LENGTH_FIELD_MARKER)
        if (pos == -1) {
            throw IllegalArgumentException("Could not find the size-announce command in the template")
        }

        val fieldStart = pos + LENGTH_FIELD_OFFSET
        val oldLength =
            ((templateBytes[fieldStart].toInt() and 0xFF) shl 24) or
            ((templateBytes[fieldStart + 1].toInt() and 0xFF) shl 16) or
            ((templateBytes[fieldStart + 2].toInt() and 0xFF) shl 8) or
            (templateBytes[fieldStart + 3].toInt() and 0xFF)

        val patched = templateBytes.copyOf()
        patched[fieldStart] = ((newLength shr 24) and 0xFF).toByte()
        patched[fieldStart + 1] = ((newLength shr 16) and 0xFF).toByte()
        patched[fieldStart + 2] = ((newLength shr 8) and 0xFF).toByte()
        patched[fieldStart + 3] = (newLength and 0xFF).toByte()

        return PatchResult(patched, oldLength)
    }
}
