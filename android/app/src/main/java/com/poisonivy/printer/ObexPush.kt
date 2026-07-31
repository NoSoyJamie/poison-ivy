package com.poisonivy.printer

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Port of poison_ivy/obex_push.py. Builds the OBEX CONNECT + PUT byte
 * stream for pushing a file over RFCOMM, generalized from the exact
 * structure recovered from a real capture (Canon IVY, server channel
 * 4 / DLCI 8).
 *
 * This part of the protocol is fully understood and verified against
 * a real capture in the Python project (round-tripped back into a
 * byte-identical JPEG).
 */
object ObexPush {

    private const val CHUNK_SIZE = 6144 // matches what the real app used

    private fun obexPacket(opcode: Int, body: ByteArray): ByteArray {
        val length = body.size + 3
        val out = ByteArrayOutputStream()
        out.write(opcode)
        out.write((length shr 8) and 0xFF)
        out.write(length and 0xFF)
        out.write(body)
        return out.toByteArray()
    }

    private fun headerUnicode(headerId: Int, text: String): ByteArray {
        // null-terminated UTF-16BE string, with the 3-byte header
        // prefix included in its own declared length
        val textBytes = text.toByteArray(Charset.forName("UTF-16BE"))
        val data = textBytes + byteArrayOf(0x00, 0x00)
        val hlen = data.size + 3
        val out = ByteArrayOutputStream()
        out.write(headerId)
        out.write((hlen shr 8) and 0xFF)
        out.write(hlen and 0xFF)
        out.write(data)
        return out.toByteArray()
    }

    private fun headerLength(totalLen: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xC3)
        out.write((totalLen shr 24) and 0xFF)
        out.write((totalLen shr 16) and 0xFF)
        out.write((totalLen shr 8) and 0xFF)
        out.write(totalLen and 0xFF)
        return out.toByteArray()
    }

    /**
     * Returns the full byte stream to send over the RFCOMM socket for
     * the OBEX channel: CONNECT, then chunked PUT packets, then the
     * final empty PUT with End-of-Body.
     */
    fun buildObexPutStream(filename: String, mimeType: String, data: ByteArray): ByteArray {
        val stream = ByteArrayOutputStream()

        // CONNECT: version=0x10, flags=0x00, max packet size=0xff00
        stream.write(obexPacket(0x80, byteArrayOf(0x10, 0x00, 0xFF.toByte(), 0x00)))

        // First PUT packet: Name + Type + Length headers, no body yet
        val headers = ByteArrayOutputStream()
        headers.write(headerUnicode(0x01, filename))
        headers.write(headerUnicode(0x42, mimeType))
        headers.write(headerLength(data.size))
        stream.write(obexPacket(0x02, headers.toByteArray()))

        // Body, chunked
        var pos = 0
        val n = data.size
        while (pos < n) {
            val end = minOf(pos + CHUNK_SIZE, n)
            val piece = data.copyOfRange(pos, end)
            pos = end

            val bodyHeader = ByteArrayOutputStream()
            bodyHeader.write(0x48)
            val hlen = piece.size + 3
            bodyHeader.write((hlen shr 8) and 0xFF)
            bodyHeader.write(hlen and 0xFF)
            bodyHeader.write(piece)
            stream.write(obexPacket(0x02, bodyHeader.toByteArray()))
        }

        // Final PUT: empty End-of-Body, opcode has the "final" bit set
        stream.write(obexPacket(0x82, byteArrayOf(0x49, 0x00, 0x03)))

        return stream.toByteArray()
    }
}
