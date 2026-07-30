#!/usr/bin/env python3
"""
Replays a literal, exactly-captured print job back to the printer --
no image processing, no patching, byte-for-byte what the official app
sent in one real session.

This exists as a sanity check: if you're debugging a failed print with
print.py, run this first. If the exact replay also fails, the problem
is your pairing/connection/RFCOMM setup, not the image pipeline. If
the exact replay succeeds but print.py doesn't, the problem is
something specific to your image or its size.

Usage:
    python replay_exact_capture.py <printer_mac>
"""
import socket
import sys
import threading
import time
import os

HERE = os.path.dirname(os.path.abspath(__file__))
CHANNEL2_FILE = os.path.join(HERE, "channel2_capture.bin")
CHANNEL4_FILE = os.path.join(HERE, "channel4_capture.bin")
SEND_CHUNK = 512


def send_stream(addr, channel, data, label):
    sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
    sock.settimeout(15)
    try:
        print(f"[{label}] connecting on RFCOMM channel {channel}...")
        sock.connect((addr, channel))
        print(f"[{label}] connected. sending {len(data)} bytes...")
        sent = 0
        while sent < len(data):
            piece = data[sent:sent + SEND_CHUNK]
            sock.sendall(piece)
            sent += len(piece)
        print(f"[{label}] all {sent} bytes sent. listening for a reply...")
        sock.settimeout(5)
        try:
            while True:
                chunk = sock.recv(4096)
                if not chunk:
                    break
                print(f"[{label}] received {len(chunk)} bytes: {chunk[:60].hex()}")
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
    if len(sys.argv) != 2:
        print("Usage: python replay_exact_capture.py <printer_mac>")
        sys.exit(1)

    addr = sys.argv[1]

    with open(CHANNEL2_FILE, "rb") as f:
        ch2_data = f.read()
    with open(CHANNEL4_FILE, "rb") as f:
        ch4_data = f.read()

    print(f"Loaded {len(ch2_data)} bytes for channel 2, {len(ch4_data)} bytes for channel 4")
    print(f"Target: {addr}")
    print("Wake the printer now if it isn't already connectable.\n")

    t2 = threading.Thread(target=send_stream, args=(addr, 2, ch2_data, "CH2/print"))
    t4 = threading.Thread(target=send_stream, args=(addr, 4, ch4_data, "CH4/obex"))
    t2.start()
    time.sleep(0.05)
    t4.start()
    t2.join()
    t4.join()

    print("\nDone. This should reproduce the same fixed test photo every time.")


if __name__ == "__main__":
    main()
