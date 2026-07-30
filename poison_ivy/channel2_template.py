"""
Channel 2 (RFCOMM server channel 2, the proprietary command channel --
DLCI 4 in the original capture) carries a mostly-understood command
sequence, but with one important correction to earlier conclusions in
this file -- read the whole thing, not just the "what's understood"
list, if you're trying to make sense of past debugging notes.

What's understood:
  - Small 34-byte command frames, all prefixed with the same 4-byte
    marker. Two command values were confirmed by cross-referencing the
    Canon Ivy 2 community client (dtgreene/ivy2 on GitHub), which
    documents the same command constants for the next-generation
    printer: 0x0101 = get status, 0x0103 = get/set settings.
  - One specific 34-byte frame announces a size that matches the OBEX
    Length header exactly (the size of the photo being pushed over
    channel 4). This module locates and patches that field for a new
    image -- that's this module's whole job.

Correction: a status-reply byte this project previously treated as an
error indicator (`...8403f7...` vs `...8403de...`) turned out to
appear identically in both successful and failed real prints when
compared directly. It is NOT a reliable success/failure signal --
don't use it to judge whether a print worked.

The actual confirmed factor is size proximity: this project ships a
~112KB template (`data/channel2_payload.bin`) captured alongside one
specific ~198,784-byte source image, including a large block of
still-undeciphered data after the size-announce command. Physical
print success has been confirmed to depend on how close the NEW
image's size is to that original ~198,784 bytes -- an image re-encoded
down to ~203KB printed successfully; the same image at its natural
~830KB size did not, with an otherwise identical, cleanly-completing
Bluetooth exchange either way. See PROTOCOL.md for the full writeup.

Practical consequence: `poison_ivy/image_prep.py` now automatically
re-encodes images to stay close to that original size by default
(see its `max_preview_bytes` / `DEFAULT_MAX_PREVIEW_BYTES`), rather
than leaving you to discover this the hard way per image.
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
