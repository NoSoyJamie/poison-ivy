# Protocol notes

Reverse-engineered from a Bluetooth HCI snoop log captured on Android
while printing with the official Canon Mini Print app, against a
first-generation Canon IVY (not IVY 2). Everything below was derived
by decoding that capture; none of it comes from Canon documentation.

## Transport

The IVY is **classic Bluetooth (BR/EDR)**, not BLE, despite the
printer also broadcasting some BLE advertising data. It uses standard
SSP pairing (no PIN needed in practice, despite what the printer's own
manual implies about matching MAC digits). It exposes two RFCOMM
services over SDP:

- `Serial Port` (0x1101)
- `OBEX Object Push` (0x1105)

Once connected, the app opens **two RFCOMM channels concurrently** on
the same ACL link:

| RFCOMM server channel | Purpose |
|---|---|
| 2 | Canon's proprietary command/print channel |
| 4 | Standard OBEX Object Push |

(These map to DLCI 4 and DLCI 8 respectively at the RFCOMM framing
level -- DLCI = server_channel * 2 + direction bit.)

## Channel 4: OBEX Object Push

Fully understood, general-purpose, and verified against the capture:
built streams were re-parsed and produced a byte-identical JPEG back
out.

Sequence:

1. **CONNECT** (opcode `0x80`): body is `[version=0x10][flags=0x00][max_packet_size=0xff00]`.
2. **PUT, not final** (opcode `0x02`): headers `Name` (`0x01`, null-terminated UTF-16BE, e.g. `"img.jpg\0"`), `Type` (`0x42`, also UTF-16BE in this app's implementation -- not the ASCII the OBEX spec technically wants, but that's what the app sends), `Length` (`0xC3`, 4-byte big-endian total size).
3. One or more further **PUT, not final** packets, each containing a single `Body` header (`0x48`, 2-byte length prefix + raw bytes). The real app chunked these at 6144 bytes.
4. Final **PUT, final** (opcode `0x82`): a single empty `End-of-Body` header (`0x49`, length 3, no data).

See `poison_ivy/obex_push.py`.

## Channel 2: proprietary command channel

Partially understood.

### Small command frames

Fixed 34 bytes, all beginning with the 4-byte marker `1b 2a 43 41`.
Comparing byte offsets against [dtgreene/ivy2](https://github.com/dtgreene/ivy2)
(a from-scratch Python client for the next-generation IVY 2, built
independently of this project) turned up two exact matches for its
documented command constants, at the same relative offset:

- `0x0101` = `COMMAND_GET_STATUS` (257) -- seen repeated ~130 times in
  the capture, apparently the app polling status while the physical
  print runs.
- `0x0103` = `COMMAND_SETTING_ACCESSORY` (259).

This is good evidence the original IVY and IVY 2 share a command
protocol lineage, just with a different preamble (the IVY 2 client
uses a 2-byte start code `0x430F`; this printer's frames start with
the 4-byte `1b 2a 43 41` instead).

One specific 34-byte frame contains a 4-byte big-endian value that, in
the captured session, matched the OBEX `Length` header exactly
(198784, the size of the photo being pushed on channel 4 in that
session). `poison_ivy/channel2_template.py` locates and patches this
field for new images. Byte offset within that frame: the field starts
right after the marker `1b 2a 43 41 00 01 00 00`.

### Bulk data chunks -- the open question

After the initial commands, the real session sent roughly 110 chunks
of ~980 bytes each (~106KB total) on this same channel. What's known
about them:

- Not a copy of the JPEG -- no JPEG markers (`ff d8`, `ff d9`) appear
  anywhere in the concatenated stream.
- Low byte-entropy (~1.25 bits/byte over the payload region, after
  stripping each chunk's fixed header), much lower than JPEG or any
  other compressed data would show (compressed data is usually close
  to 8 bits/byte). Roughly 43% of bytes are `0x00`.
- Standard decompressors (zlib, gzip, lzma, bz2) all fail against it.
- The [dtgreene/ivy2](https://github.com/dtgreene/ivy2) client's own
  `image.py` produces a second, smaller 640x1616 image (resized from
  the same source, rotated 180 degrees) that isn't obviously present
  here either.

**The current working theory, based on one real test:** these chunks
might not matter for what actually gets printed. A session was sent
with these chunks left completely unmodified (still describing the
content of a *different, earlier* captured photo) while a new, unique
photo was correctly pushed over channel 4 -- and the printer produced
a correct print of the *new* photo. That's consistent with the
printer rasterizing directly from the channel 4 JPEG, and this data
being something else: calibration, a legacy/unused code path, a log
upload, or similar.

This is **not confirmed**. It's one data point from one printer on
one firmware version. If you can gather more captures -- especially
of very simple, mostly-solid-color test images from the *real* Canon
app, which make correlating pixel data to bytes much more tractable
-- that would go a long way toward actually settling this. See
`tools/replay_exact_capture.py` and `CONTRIBUTING` info in the README
for how to capture your own.

## Capturing your own session

1. On Android: enable Developer Options, then "Enable Bluetooth HCI
   snoop log" under Developer Options (or its own Bluetooth submenu
   on some phones).
2. Print something using the real Canon Mini Print app.
3. Pull the log: `adb pull /sdcard/Android/data/btsnoop_hci.log .`
   (path varies by Android version; check
   `/data/misc/bluetooth/logs/btsnoop_hci.log` too).
4. The log is in standard `btsnoop` format (documented, not
   Canon-specific) -- readable with Wireshark, or see this project's
   git history / issues for the from-scratch parser used to build
   this tool if you'd rather not install Wireshark.
