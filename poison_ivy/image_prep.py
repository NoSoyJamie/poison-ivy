"""
Prepares an arbitrary source image for the Canon IVY, replicating the
same crop/resize/rotate steps the official app performs (confirmed by
matching output dimensions against a real captured print job, and
against the Canon Ivy 2 community client's published algorithm).

Produces two outputs:
  - preview: 1280x1920, auto-cropped to fill (this is what gets pushed
    to the printer via OBEX as "img.jpg" -- confirmed byte-for-byte
    against a real capture)
  - final:   640x1616, same crop then downscaled and rotated 180
    (this is the size/orientation the print engine itself expects;
    the printer-side raster ENCODING for this is not yet reverse
    engineered -- see print_custom.py for details)

The preview size is exactly a 2:3 aspect ratio. auto_crop (the
default) scales the source to cover that box and crops the overflow
-- fine for photos, but it'll cut into anything that isn't already
2:3, which is a problem for content like Magic: The Gathering card
scans (which are ~5:7, not 2:3). Pass pad_to_2_3=True to letterbox
the source to exactly 2:3 with solid-color bars BEFORE the crop step,
so nothing gets cut off. Once the source is exactly 2:3, auto_crop
becomes a no-op (a pure scale, no cropping), so this is safe to use
unconditionally for card scans.

IMPORTANT -- output size and print success:
channel 2's proprietary command channel still reuses raster data
captured alongside one specific ~198,784-byte original image (see
channel2_template.py). Confirmed by testing: an OBEX-pushed preview
close to that original size prints successfully; one far larger does
not, even though the Bluetooth-level exchange completes cleanly
either way -- the failure appears to be in the printer's print engine
itself, not the data transfer.

The safe boundary has been bracketed via binary search on a real
printer: confirmed working up to 424,063 bytes, confirmed failing at
432,500 bytes, tested against one image (a Magic card scan). This
was one image's compression curve, not a universal constant -- if you
hit failures right around DEFAULT_MAX_PREVIEW_BYTES with very
different content, the true boundary may shift a bit; consider it a
well-tested estimate, not a hardware-guaranteed limit.
"""
from PIL import Image
from io import BytesIO

PREVIEW_WIDTH = 1280
PREVIEW_HEIGHT = 1920
FINAL_WIDTH = 640
FINAL_HEIGHT = 1616
PREVIEW_RATIO = PREVIEW_WIDTH / PREVIEW_HEIGHT  # 2:3 == 0.6667

# Bracketed via binary search on a real printer (akh-225-bontu-s-monument.png,
# repeated print attempts at decreasing --max-size values): confirmed working
# up to 424,063 bytes, confirmed failing at 432,500 bytes -- a gap under 2%.
# 400,000 gives a comfortable safety margin below the confirmed-good point,
# accounting for the fact this was only tested against one image; different
# content may compress differently at a given byte target.
DEFAULT_MAX_PREVIEW_BYTES = 400_000


def pad_to_ratio(image, ratio_w, ratio_h, fill=(255, 255, 255)):
    """
    Adds solid-color bars (centered) so the image's dimensions exactly
    match ratio_w:ratio_h, without cropping any of the original
    content. Returns the image unchanged if it's already that ratio.
    """
    width, height = image.size
    target_ratio = ratio_w / ratio_h
    current_ratio = width / height

    if abs(current_ratio - target_ratio) < 1e-9:
        return image

    if current_ratio > target_ratio:
        # source is wider than the target ratio -> add height (top/bottom bars)
        new_width = width
        new_height = round(width * ratio_h / ratio_w)
    else:
        # source is taller than the target ratio -> add width (left/right bars)
        new_height = height
        new_width = round(height * ratio_w / ratio_h)

    canvas = Image.new("RGB", (new_width, new_height), fill)
    offset = ((new_width - width) // 2, (new_height - height) // 2)
    canvas.paste(image, offset)
    return canvas


def _crop_resize(image, target_w, target_h, auto_crop=True):
    width, height = image.size
    if auto_crop:
        # scale up enough that the image fully covers the target box,
        # then center-crop the overflow (fills the frame, crops edges)
        scale = max(target_w / width, target_h / height)
    else:
        # scale down enough that the whole image fits inside the target
        # box, then pad with black (nothing cropped, may have borders)
        scale = min(target_w / width, target_h / height)

    scaled_w = round(width * scale)
    scaled_h = round(height * scale)
    if (scaled_w, scaled_h) != (width, height):
        image = image.resize((scaled_w, scaled_h), Image.Resampling.LANCZOS)

    offset = ((target_w - scaled_w) // 2, (target_h - scaled_h) // 2)
    canvas = Image.new("RGB", (target_w, target_h))
    canvas.paste(image, offset)
    return canvas


def _encode_jpeg(image, quality):
    with BytesIO() as buf:
        image.save(buf, format="JPEG", quality=quality)
        return buf.getvalue()


def _find_quality_for_size(image, max_bytes, min_quality=10, max_quality=95):
    """
    Binary search for the highest JPEG quality (best-looking result)
    that still keeps the encoded size at or under max_bytes.

    Returns (quality, encoded_bytes). If even min_quality exceeds
    max_bytes, returns min_quality's result anyway (can't go lower)
    along with a warning printed to stdout.
    """
    smallest = _encode_jpeg(image, min_quality)
    if len(smallest) > max_bytes:
        print(f"  WARNING: even quality={min_quality} produces {len(smallest)} bytes, "
              f"over the {max_bytes} byte target -- using it anyway, can't go smaller "
              f"without also shrinking pixel dimensions")
        return min_quality, smallest

    lo, hi = min_quality, max_quality
    best_q, best_bytes = lo, smallest
    while lo <= hi:
        mid = (lo + hi) // 2
        data = _encode_jpeg(image, mid)
        if len(data) <= max_bytes:
            best_q, best_bytes = mid, data
            lo = mid + 1
        else:
            hi = mid - 1
    return best_q, best_bytes


def prepare_image(path, auto_crop=True, quality=95, pad_to_2_3=False, pad_fill=(255, 255, 255),
                   max_preview_bytes=DEFAULT_MAX_PREVIEW_BYTES):
    """
    Load any image format PIL understands and produce the two
    JPEG-encoded byte buffers the printer expects.

    pad_to_2_3: letterbox the source to exactly 2:3 before anything
        else, so auto_crop can't cut into it. Use this for content
        with a fixed aspect ratio you don't want trimmed (card scans,
        screenshots, etc).
    pad_fill: RGB fill color for the padding bars, e.g. (0, 0, 0) for
        black bars instead of the white default.
    max_preview_bytes: if set (the default), automatically lowers JPEG
        quality below the requested `quality` as needed to keep the
        preview under this many bytes -- see the size/print-success
        note in this module's docstring. Pass None to disable and
        always use the exact `quality` requested, e.g. for testing.

    Returns: (preview_jpeg_bytes, final_jpeg_bytes)
    """
    src = Image.open(path)
    src = src.convert("RGB")  # normalize any format/mode (PNG w/ alpha, GIF, etc.)

    if pad_to_2_3:
        src = pad_to_ratio(src, 2, 3, fill=pad_fill)

    preview = _crop_resize(src, PREVIEW_WIDTH, PREVIEW_HEIGHT, auto_crop)

    final = preview.resize((FINAL_WIDTH, FINAL_HEIGHT), Image.Resampling.LANCZOS)
    final = final.rotate(180)

    if max_preview_bytes is not None:
        used_quality, preview_bytes = _find_quality_for_size(preview, max_preview_bytes,
                                                               max_quality=quality)
        if used_quality < quality:
            print(f"  auto-reduced JPEG quality {quality} -> {used_quality} to keep "
                  f"preview under {max_preview_bytes} bytes ({len(preview_bytes)} bytes)")
        final_bytes = _encode_jpeg(final, used_quality)
    else:
        preview_bytes = _encode_jpeg(preview, quality)
        final_bytes = _encode_jpeg(final, quality)

    return preview_bytes, final_bytes


if __name__ == "__main__":
    import sys
    path = sys.argv[1] if len(sys.argv) > 1 else "/home/claude/canon/extracted_image.jpg"
    preview_bytes, final_bytes = prepare_image(path)
    print(f"preview: {len(preview_bytes)} bytes")
    print(f"final:   {len(final_bytes)} bytes")
    with open("/tmp/preview_out.jpg", "wb") as f:
        f.write(preview_bytes)
    with open("/tmp/final_out.jpg", "wb") as f:
        f.write(final_bytes)
    from PIL import Image as I
    print("preview dims:", I.open("/tmp/preview_out.jpg").size)
    print("final dims:  ", I.open("/tmp/final_out.jpg").size)
