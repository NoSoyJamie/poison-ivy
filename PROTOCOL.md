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

Partially understood, and the history of getting here is worth reading
since a couple of earlier conclusions in this file turned out to be
wrong -- corrected below rather than silently rewritten.

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

### Correction: the `0x...8403f7...` status byte is NOT an error indicator

An earlier version of this file claimed a specific status reply byte
pattern (`...8403f7...`) only ever appeared in failed print attempts,
versus `...8403de...` in successful ones, and treated it as the key
diagnostic signal for a long stretch of this project's debugging.

**This was wrong.** A directly comparable pair of `btmon` captures --
one running `replay_print.py` (confirmed to reliably produce a
physical print) and one running the custom-image tool on the same
printer in the same session (confirmed NOT to physically print) --
show `...8403f7...` in the *first* reply of **both** sessions,
identically. The OBEX transfer completes with the same clean success
sequence in both cases too. At the Bluetooth protocol level these two
sessions are not meaningfully different. Do not use this byte as a
success/failure signal; what it actually indicates is still unknown.

### The size/print-success finding (confirmed)

This is the actual, confirmed, actionable discovery, arrived at only
after the above correction forced a rethink of what was actually
different between working and non-working sessions.

Every print that has ever failed to physically produce paper (while
completing a clean Bluetooth exchange) reused this project's captured
channel-2 template -- including its bulk data-like content -- while
announcing (via the size-announce field above) a size very different
from the ~198,784 bytes that data was originally captured alongside.
Every print confirmed to work used either that same original size
unchanged (`replay_print.py`, which doesn't patch anything), or a new
image deliberately re-encoded down to land close to it.

Concretely: a Magic card image that failed to print at its natural
~830KB re-encoded size printed successfully once brought down to
~203KB (JPEG quality 30) with nothing else about the process changed.

Working theory: the channel-2 data isn't literally irrelevant to
image content (an earlier, now-superseded theory in this file claimed
it was), and the printer's physical print engine -- a separate stage
from the Bluetooth transfer, which is why the connection and OBEX
transfer always complete cleanly regardless -- silently fails when
the announced size diverges too far from what that reused data
actually corresponds to. The Bluetooth-level exchange gives no visible
error either way.

**Practical fix, implemented:** `poison_ivy/image_prep.py` now
automatically lowers JPEG quality (via `max_preview_bytes`, default
400KB) to keep the pushed image size within the confirmed-working
range, rather than requiring you to guess a quality setting per
image. See that module's docstring for the current constant.

**Update: the size boundary has now been bracketed.** Binary search
on a real printer (repeated print attempts at decreasing `--max-size`
values, using `akh-225-bontu-s-monument.png` as the test image)
narrowed it to: confirmed working up to 424,063 bytes, confirmed
failing at 432,500 bytes -- a gap under 2%. `DEFAULT_MAX_PREVIEW_BYTES`
is now 400,000, giving a reasonable safety margin below the
confirmed-good point. This was bracketed against one image's
compression curve, not a universal constant -- different content may
shift the true boundary slightly, so treat 400,000 as a well-tested
estimate rather than a hardware-guaranteed number.

**Still open / not confirmed:**
- Why an earlier `gawr.webp` print (~449KB) worked once and then
  later failed on retry with nothing changed. This may indicate a
  second, separate factor (some kind of printer-side state drift)
  layered on top of the size effect, not yet isolated.
- What the channel-2 bulk data chunks actually encode. Still unknown;
  seemingly not literally irrelevant (see above), but not decoded
  either.

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
