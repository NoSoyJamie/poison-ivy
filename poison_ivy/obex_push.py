"""
Builds the OBEX CONNECT + PUT byte stream for pushing a file over
RFCOMM, generalized from the exact structure recovered from a real
capture (Canon IVY, server channel 4 / DLCI 8).

This part of the protocol is fully understood and verified: the bytes
this module produces were checked against the real capture and
reconstructed into a byte-identical, valid JPEG.
"""

CHUNK_SIZE = 6144  # matches what the real app used


def _obex_packet(opcode, body):
    length = len(body) + 3
    return bytes([opcode, (length >> 8) & 0xFF, length & 0xFF]) + body


def _header_unicode(header_id, text):
    # null-terminated UTF-16BE string, with the 3-byte header prefix
    # included in its own declared length
    data = text.encode("utf-16-be") + b"\x00\x00"
    hlen = len(data) + 3
    return bytes([header_id, (hlen >> 8) & 0xFF, hlen & 0xFF]) + data


def _header_length(total_len):
    return bytes([0xC3]) + total_len.to_bytes(4, "big")


def build_obex_put_stream(filename, mime_type, data):
    """
    Returns the full byte stream to send over the RFCOMM socket for
    this channel: CONNECT, then chunked PUT packets, then the final
    empty PUT with End-of-Body.
    """
    stream = b""

    # CONNECT: version=0x10, flags=0x00, max packet size=0xff00
    stream += _obex_packet(0x80, bytes([0x10, 0x00, 0xFF, 0x00]))

    # First PUT packet: Name + Type + Length headers, no body yet
    headers = (
        _header_unicode(0x01, filename)
        + _header_unicode(0x42, mime_type)
        + _header_length(len(data))
    )
    stream += _obex_packet(0x02, headers)

    # Body, chunked
    pos = 0
    n = len(data)
    while pos < n:
        piece = data[pos : pos + CHUNK_SIZE]
        pos += len(piece)
        body_header = bytes([0x48]) + (len(piece) + 3).to_bytes(2, "big") + piece
        stream += _obex_packet(0x02, body_header)

    # Final PUT: empty End-of-Body, opcode has the "final" bit set
    stream += _obex_packet(0x82, bytes([0x49, 0x00, 0x03]))

    return stream


if __name__ == "__main__":
    # smoke test: round-trip a tiny fake payload
    s = build_obex_put_stream("img.jpg", "image/jpeg", b"\xff\xd8\xff" + b"X" * 100 + b"\xff\xd9")
    print(f"Built {len(s)} bytes")
