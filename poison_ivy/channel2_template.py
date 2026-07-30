"""
Channel 2 (RFCOMM server channel 2, the proprietary command channel --
DLCI 4 in the original capture) is where the interesting open question
in this project used to live. As of the second capture (a genuinely
successful print, captured start to finish including a deliberate
"no paper loaded" error state beforehand for comparison), it's mostly
resolved:

What's understood:
  - Small 34-byte command frames, all prefixed with the same 4-byte
    marker. Two command values were confirmed by cross-referencing the
    Canon Ivy 2 community client (dtgreene/ivy2 on GitHub), which
    documents the same command constants for the next-generation
    printer: 0x0101 = get status, 0x0103 = get/set settings.
  - One specific 34-byte frame announces a size that matches the OBEX
    Length header exactly (the size of the photo being pushed over
    channel 4). This module locates and patches that field for a new
    image.
  - A genuinely successful real print (captured end to end) showed
    channel 2 consisting of ONLY: a mux/session setup, a long run of
    repeated status-poll commands (0x0101) before and after the one
    size-announce command, and teardown. No large data chunks at all.
  - The FIRST capture this project was originally built from (see
    tools/) DID include ~106KB of low-entropy "raster-looking" bulk
    data after its size-announce command, which earlier versions of
    this code assumed was necessary and copied verbatim into every
    session. Comparing against the second (clean) capture strongly
    suggests that assumption was wrong: that bulk data is not part of
    normal print flow. The current best theory is that it was
    specific to whatever unusual condition applied to that first
    session (possibly a one-time calibration/sync tied to first
    connection after pairing, or something related to freshly-loaded
    paper) rather than something every print needs. Sending it
    unconditionally on every print, as this project used to do, is
    the likely cause of `print.py` runs that connected and sent data
    successfully but the printer blinked red and nothing printed --
    the printer's status replies during those failures showed a
    distinct error byte (`...8403f7...`) that never appears in either
    a real successful print OR a real "no paper loaded" error state,
    both of which show `...8403de...` instead. That's consistent with
    the printer flagging the extra, unexpected bulk data as invalid,
    not a paper/cover/battery condition.
  - This module now ships the minimal, clean template (~3.5KB) instead
    of the old ~110KB one. If you still have the old template lying
    around from before this fix, it's worth discarding.

Still open / not fully certain:
  - Why the very first capture included that bulk data at all. If you
    can reproduce it (e.g. by capturing the very first print after a
    fresh pairing, or the very first print on a freshly-opened pack of
    paper), that would help confirm or rule out either theory. Open an
    issue with the capture if you get one.
  - The exact meaning of most other fields in the 34-byte command
    frames beyond the ones described above (there's a byte that looks
    like it might be tracking battery percentage during a print job --
    see PROTOCOL.md).
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
