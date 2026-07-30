#!/usr/bin/env python3
"""
Poison Ivy -- prints any image to a Canon IVY mini photo printer from Linux.

    python print.py <printer_mac> <image_path>
    python print.py <printer_mac> <image_path> --pad
    python print.py <printer_mac> <image_path> --pad --pad-color black

Requires the printer already paired and trusted (see README.md).
Uses only the Python standard library's AF_BLUETOOTH socket support
plus Pillow -- no PyBluez, no vendor SDK.

--pad letterboxes the source to exactly 2:3 (the printer's native
aspect ratio) before cropping, so nothing gets cut off. Use this for
content with a fixed aspect ratio you care about preserving -- card
scans, screenshots, anything that isn't already 2:3. Without --pad,
sources that aren't 2:3 get center-cropped to fill the frame, which
is usually right for ordinary photos but wrong for a card scan.

By default, JPEG quality is automatically lowered as needed to keep
the pushed image under --max-size bytes (see image_prep.py's
DEFAULT_MAX_PREVIEW_BYTES and its docstring for why this matters --
channel 2 still reuses raster data captured alongside one specific
image, and large size mismatches have been confirmed to silently
prevent the physical print even though the Bluetooth transfer
completes normally). Use --quality to force an exact quality and
disable this entirely, e.g. for testing where the real limit is.
"""
import argparse
import socket
import sys
import threading
import time
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from poison_ivy import prepare_image, build_obex_put_stream, patch_channel2_template
from poison_ivy.image_prep import DEFAULT_MAX_PREVIEW_BYTES

PAD_COLORS = {
    "white": (255, 255, 255),
    "black": (0, 0, 0),
}

CHANNEL2_TEMPLATE = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "data", "channel2_payload.bin"
)
SEND_CHUNK = 512


def send_stream(addr, channel, data, label, replies):
    sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
    sock.settimeout(15)
    try:
        print(f"[{label}] connecting on RFCOMM channel {channel}...")
        sock.connect((addr, channel))
        print(f"[{label}] connected, sending {len(data)} bytes...")
        sent = 0
        while sent < len(data):
            piece = data[sent:sent + SEND_CHUNK]
            sock.sendall(piece)
            sent += len(piece)
        print(f"[{label}] done sending. listening briefly for a reply...")
        sock.settimeout(5)
        try:
            while True:
                chunk = sock.recv(4096)
                if not chunk:
                    break
                replies.append((label, chunk))
        except socket.timeout:
            pass
    except Exception as e:
        print(f"[{label}] ERROR: {e}")
    finally:
        try:
            sock.close()
        except Exception:
            pass
        print(f"[{label}] closed.")


def send_records(addr, channel, data, record_size, label, replies):
    """
    Like send_stream, but sends each fixed-size record as its own
    separate socket write AND waits for the printer's reply before
    sending the next one.

    This mirrors what the real app actually does: a real capture shows
    channel 2 running as a strict half-duplex request/reply exchange
    (one command, wait for its reply, then the next command) rather
    than a batch of commands fired all at once. Skipping the "wait for
    reply" step appears to desync some internal state on the printer
    -- its replies come back subtly different (and eventually flagged
    invalid, status byte 0xf7) even when the bytes we send are
    byte-identical to a real session.
    """
    if len(data) % record_size != 0:
        print(f"[{label}] WARNING: {len(data)} bytes is not a multiple of "
              f"{record_size} -- falling back to bulk send")
        return send_stream(addr, channel, data, label, replies)

    sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
    sock.settimeout(15)
    try:
        print(f"[{label}] connecting on RFCOMM channel {channel}...")
        sock.connect((addr, channel))
        n_records = len(data) // record_size
        print(f"[{label}] connected, sending {n_records} records of {record_size} bytes "
              f"each (waiting for a reply after each one)...")
        sock.settimeout(5)
        for i in range(n_records):
            record = data[i * record_size:(i + 1) * record_size]
            sock.send(record)
            try:
                reply = sock.recv(4096)
                if reply:
                    replies.append((label, reply))
            except socket.timeout:
                print(f"[{label}] no reply to record {i} within 5s -- continuing anyway")
        print(f"[{label}] done sending all records.")
    except Exception as e:
        print(f"[{label}] ERROR: {e}")
    finally:
        try:
            sock.close()
        except Exception:
            pass
        print(f"[{label}] closed.")


def main():
    parser = argparse.ArgumentParser(description="Print an image to a Canon IVY over Bluetooth.")
    parser.add_argument("mac", help="printer's Bluetooth MAC address")
    parser.add_argument("image_path", help="path to any image file")
    parser.add_argument(
        "--pad", action="store_true",
        help="letterbox the source to exactly 2:3 before cropping, so nothing gets cut off "
             "(use for card scans / anything with a fixed aspect ratio you want preserved)",
    )
    parser.add_argument(
        "--pad-color", choices=PAD_COLORS.keys(), default="white",
        help="fill color for the padding bars when --pad is set (default: white)",
    )
    parser.add_argument(
        "--max-size", type=int, default=DEFAULT_MAX_PREVIEW_BYTES,
        help=f"automatically lower JPEG quality to keep the pushed image under this "
             f"many bytes -- see the size/print-success note in image_prep.py "
             f"(default: {DEFAULT_MAX_PREVIEW_BYTES})",
    )
    parser.add_argument(
        "--quality", type=int, default=None,
        help="force this exact JPEG quality (1-95) and disable auto-sizing entirely "
             "-- for testing/bracketing the size limit, not normal use",
    )
    args = parser.parse_args()

    addr = args.mac
    image_path = args.image_path

    print(f"Loading and preparing {image_path} ...")
    if args.pad:
        print(f"  padding to exactly 2:3 first (fill: {args.pad_color})")
    if args.quality is not None:
        print(f"  auto-sizing DISABLED -- forcing quality={args.quality}")
        preview_jpeg, final_jpeg = prepare_image(
            image_path,
            pad_to_2_3=args.pad,
            pad_fill=PAD_COLORS[args.pad_color],
            quality=args.quality,
            max_preview_bytes=None,
        )
    else:
        preview_jpeg, final_jpeg = prepare_image(
            image_path,
            pad_to_2_3=args.pad,
            pad_fill=PAD_COLORS[args.pad_color],
            max_preview_bytes=args.max_size,
        )
    print(f"  preview (pushed via OBEX): {len(preview_jpeg)} bytes, 1280x1920")
    print(f"  final (unused by channel 2 currently): {len(final_jpeg)} bytes, 640x1616")

    print("Building OBEX stream for channel 4...")
    ch4_stream = build_obex_put_stream("img.jpg", "image/jpeg", preview_jpeg)

    print("Loading and patching channel 2 template...")
    with open(CHANNEL2_TEMPLATE, "rb") as f:
        ch2_template = f.read()
    ch2_stream, old_length = patch_channel2_template(ch2_template, len(preview_jpeg))
    print(f"  patched size-announce field: {old_length} -> {len(preview_jpeg)}")

    print(f"\nTarget: {addr}")
    print("Wake the printer now if it isn't already connectable.")
    print("Make sure it's on a solid power source -- a low battery has been")
    print("observed to make the RFCOMM transfer succeed silently while the")
    print("actual print never fires.\n")

    replies = []
    t2 = threading.Thread(target=send_records, args=(addr, 2, ch2_stream, 34, "CH2/print", replies))
    t4 = threading.Thread(target=send_stream, args=(addr, 4, ch4_stream, "CH4/obex", replies))
    t2.start()
    time.sleep(0.05)
    t4.start()
    t2.join()
    t4.join()

    print(f"\nDone. Collected {len(replies)} reply packet(s) from the printer:")
    for label, chunk in replies:
        print(f"  [{label}] {len(chunk)} bytes: {chunk.hex()}")
    print("If nothing printed, see README.md's Troubleshooting section --")
    print("capturing this run with btmon is the fastest way to debug it.")


if __name__ == "__main__":
    main()
