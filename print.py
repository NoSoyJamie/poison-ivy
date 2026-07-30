#!/usr/bin/env python3
"""
Poison Ivy -- prints any image to a Canon IVY mini photo printer from Linux.

    python print.py <printer_mac> <image_path>

Requires the printer already paired and trusted (see README.md).
Uses only the Python standard library's AF_BLUETOOTH socket support
plus Pillow -- no PyBluez, no vendor SDK.
"""
import socket
import sys
import threading
import time
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from poison_ivy import prepare_image, build_obex_put_stream, patch_channel2_template

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


def main():
    if len(sys.argv) != 3:
        print("Usage: python print.py <printer_mac> <image_path>")
        sys.exit(1)

    addr = sys.argv[1]
    image_path = sys.argv[2]

    print(f"Loading and preparing {image_path} ...")
    preview_jpeg, final_jpeg = prepare_image(image_path)
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
    t2 = threading.Thread(target=send_stream, args=(addr, 2, ch2_stream, "CH2/print", replies))
    t4 = threading.Thread(target=send_stream, args=(addr, 4, ch4_stream, "CH4/obex", replies))
    t2.start()
    time.sleep(0.05)
    t4.start()
    t2.join()
    t4.join()

    print(f"\nDone. Collected {len(replies)} reply packet(s) from the printer.")
    print("If nothing printed, see README.md's Troubleshooting section --")
    print("capturing this run with btmon is the fastest way to debug it.")


if __name__ == "__main__":
    main()
