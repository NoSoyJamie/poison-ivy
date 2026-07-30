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
"""
from PIL import Image
from io import BytesIO

PREVIEW_WIDTH = 1280
PREVIEW_HEIGHT = 1920
FINAL_WIDTH = 640
FINAL_HEIGHT = 1616


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


def prepare_image(path, auto_crop=True, quality=95):
    """
    Load any image format PIL understands and produce the two
    JPEG-encoded byte buffers the printer expects.

    Returns: (preview_jpeg_bytes, final_jpeg_bytes)
    """
    src = Image.open(path)
    src = src.convert("RGB")  # normalize any format/mode (PNG w/ alpha, GIF, etc.)

    preview = _crop_resize(src, PREVIEW_WIDTH, PREVIEW_HEIGHT, auto_crop)

    final = preview.resize((FINAL_WIDTH, FINAL_HEIGHT), Image.Resampling.LANCZOS)
    final = final.rotate(180)

    def to_jpeg(img):
        with BytesIO() as buf:
            img.save(buf, format="JPEG", quality=quality)
            return buf.getvalue()

    return to_jpeg(preview), to_jpeg(final)


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
