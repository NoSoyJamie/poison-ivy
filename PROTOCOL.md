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

Mostly understood now, as of a second capture that included a real,
deliberately-triggered error state (no paper loaded) followed by a
genuinely successful print, captured start to finish for comparison
against the original capture this project was first built from.

### Small command frames

Fixed 34 bytes, all beginning with the 4-byte marker `1b 2a 43 41`.
Comparing byte offsets against [dtgreene/ivy2](https://github.com/dtgreene/ivy2)
(a from-scratch Python client for the next-generation IVY 2, built
independently of this project) turned up two exact matches for its
documented command constants, at the same relative offset:

- `0x0101` = `COMMAND_GET_STATUS` (257) -- seen repeated many times in
  every capture, apparently the app polling status continuously while
  connected.
- `0x0103` = `COMMAND_SETTING_ACCESSORY` (259).

This is good evidence the original IVY and IVY 2 share a command
protocol lineage, just with a different preamble (the IVY 2 client
uses a 2-byte start code `0x430F`; this printer's frames start with
the 4-byte `1b 2a 43 41` instead).

One specific 34-byte frame contains a 4-byte big-endian value that
matches the OBEX `Length` header exactly (the size of the photo being
pushed on channel 4). `poison_ivy/channel2_template.py` locates and
patches this field for new images. Byte offset within that frame: the
field starts right after the marker `1b 2a 43 41 00 01 00 00`.

There also appears to be a battery-percentage-like field in the
status replies from the printer: a byte that reads `0x64` (100) near
the start of a real print job and drifts down (`0x63`, `0x56`, `0x37`,
...) over its course, consistent with battery drain from the thermal
head. Not confirmed against an actual displayed battery percentage,
but the values and direction line up.

### The "bulk data chunks" question -- resolved (mostly)

The original capture this project was built from had ~110 chunks of
~980 bytes each (~106KB total) on this channel, after the small
commands and before the size-announce frame. Earlier versions of this
project assumed that was a required part of every print and copied it
verbatim into every session `print.py` sent.

**A second, independently captured session -- a real print that was
confirmed to work, captured end to end via `adb bugreport` -- had
zero bulk data chunks on this channel.** Its channel 2 traffic was
just: mux/session setup, a long run of status polls, one size-announce
command, more status polls, teardown. No large chunks anywhere.

This strongly suggests the bulk data in the first capture was NOT a
normal part of every print, and that `print.py` sending it
unconditionally on every image was actively wrong -- and likely the
cause of failures where the tool would connect and send data
successfully, but the printer would blink red and never physically
print. Supporting evidence: those failures' status replies consistently
showed `...8403f7...`, a byte pattern that has never appeared in any
real capture from the official app -- not during a successful print,
and not even during a real, deliberately-triggered "no paper loaded"
error (which instead shows `...8403de01...`, using the same `de` byte
seen in ordinary successful prints, just with a trailing flag set).
That's consistent with the printer flagging the extra, unexpected data
as invalid, rather than any paper/cover/battery condition.

`poison_ivy/channel2_template.py` now ships the clean, minimal
template (~3.5KB) recovered from the second capture instead of the
original ~110KB one.

**Still open:** why the very first capture had that bulk data at all.
Current best guesses, unconfirmed: something tied to the first
connection after a fresh pairing, or something tied to a freshly
opened pack of paper needing an initial calibration pass (Canon's
ZINK "SMART SHEET" paper is documented elsewhere as needing this).
Notably, the second capture's session *also* involved a fresh paper
load partway through -- the printer visibly ran a blank calibration
page through on its own right after the cover was closed -- and still
showed no extra channel-2 data at all, which argues against the
paper-calibration theory specifically, since that calibration pass
seems to be something the printer does internally rather than
something the app sends extra data for. If you can reproduce the
original bulk-data behavior (e.g. by capturing the very first print
after a completely fresh pairing), that would help settle it -- please
open an issue with the capture if you get one.

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
