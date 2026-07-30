"""
Channel 2 (RFCOMM server channel 2, the proprietary command channel --
DLCI 4 in the original capture) is where the interesting open question
in this project still lives.

What's understood:
  - Small 34-byte command frames, all prefixed with the same 4-byte
    marker. Two command values were confirmed by cross-referencing the
    Canon Ivy 2 community client (dtgreene/ivy2 on GitHub), which
    documents the same command constants for the next-generation
    printer: 0x0101 = get status, 0x0103 = get/set settings.
  - One specific 34-byte frame announces a size that, in the captured
    session, matched the OBEX Length header exactly (the size of the
    photo being pushed over channel 4). This module locates and
    patches that field for a new image.

What's NOT understood, and the most interesting open question here:
  - After the small commands, the real session sent ~110 chunks of
    ~980 bytes each (~106KB total) on this same channel. These are
    not a copy of the JPEG (no JPEG markers anywhere in them) and
    have unusually low byte-entropy for anything compressed --
    more consistent with a sparse/structured format than an image
    codec.
  - Surprising result from testing: sending a session with these
    chunks left completely UNCHANGED (still describing the original
    captured photo) alongside a correctly-updated, different photo
    pushed over channel 4 -- produced a correct, matching print of
    the NEW photo. That suggests these chunks may not be load-bearing
    for what actually gets printed at all: the printer likely
    rasterizes from the channel 4 JPEG itself, and this data might be
    calibration data, a stale/legacy code path, logging, or something
    else that doesn't depend on image content.
  - This has only been confirmed on a handful of prints from one
    printer. It's not yet verified across many images, sizes, or
    firmware versions. Treat it as a strong lead, not a settled fact.
    If you hit a case where it matters, please open an issue with a
    btmon capture.

This module patches only the one field we're sure about (the size
field) and otherwise passes the captured template through unchanged.
"""

LENGTH_FIELD_MARKER = bytes.fromhex("1b2a434100010000")  # magic + 0001 00 00
LENGTH_FIELD_OFFSET = 7  # length field starts here, 4 bytes big-endian


def patch_channel2_template(template_bytes, new_length):
    """
    Returns (patched_bytes, old_length_that_was_replaced).
    Raises ValueError if the size-announce command can't be found.
    """
    pos = template_bytes.find(LENGTH_FIELD_MARKER)
    if pos == -1:
        raise ValueError("Could not find the size-announce command in the template")

    field_start = pos + LENGTH_FIELD_OFFSET
    old_length = int.from_bytes(template_bytes[field_start : field_start + 4], "big")

    patched = bytearray(template_bytes)
    patched[field_start : field_start + 4] = new_length.to_bytes(4, "big")

    return bytes(patched), old_length
