This project is 100% certified Claude AI slop, it works for what I want it for, but use it at your own risk.

# Poison Ivy 🌿

Print to a Canon IVY mini photo printer from Linux over Bluetooth,
without Canon's app, without Windows or macOS, and without a driver
that Canon never shipped in the first place.

The name is a double meaning: it's for the Ivy printer, and it's a
dig at Canon for how unnecessarily hard this was to get working.

**Status:** personal project, works for me, reverse-engineered
against exactly one printer (an original Canon IVY, not IVY 2) and
one Linux setup. Not affiliated with Canon in any way. Use at your
own risk -- see [Caveats](#caveats--open-questions) below before you
trust it with something you care about.

## What this does

1. Loads any image format Pillow understands (JPEG, PNG, WebP, GIF,
   BMP, ...).
2. Crops and resizes it the way the official app does: scale-to-cover
   then center-crop to 1280x1920.
3. Connects to the printer over classic Bluetooth (RFCOMM) and sends
   it, using a protocol recovered by reverse-engineering a real
   Bluetooth capture of the official Android app.

No Canon software, no Windows/macOS requirement, no PyBluez -- just
Python's standard library `socket` module (`AF_BLUETOOTH`) and
Pillow.

## Quick start

```bash
# Bluetooth stack (Debian/Ubuntu)
sudo apt install bluetooth bluez

# or on Arch
sudo pacman -S bluez-utils

python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

Pair the printer (only needed once):

```bash
bluetoothctl
```
```
agent on
default-agent
scan on
```
Wait for `Canon (xx:xx) Mini Printer` to show up, note its MAC
address, then:
```
pair AA:BB:CC:DD:EE:FF
trust AA:BB:CC:DD:EE:FF
scan off
exit
```

Print something:

```bash
python print.py AA:BB:CC:DD:EE:FF /path/to/your/image.png
```

Keep the printer on solid power while testing -- a low battery has
been observed to let the Bluetooth transfer complete successfully
while the actual print silently never fires.

## Why I built this

Canon ships a Bluetooth photo printer with zero Linux support, no
public protocol documentation, and a license agreement that literally
forbids reverse engineering their app. There's no CUPS driver, no
IPP/AirPrint support, USB is charging-only, and the printer only
talks to Canon's own mobile app over a proprietary Bluetooth protocol.

I use these to print [Magic: The Gathering](https://magic.wizards.com/)
proxy cards, and wanted that to work from my own machine. This is the
result.

## How it was built

Short version: a lot of dead ends, then a Bluetooth HCI snoop capture
of the real app printing a real photo, and a from-scratch parser to
pull the actual protocol out of it.

The path there:

- Confirmed via USB probing that the printer's USB port really is
  charging-only (no response on any endpoint, matches Canon's own
  manual).
- Paired over classic Bluetooth (not BLE) using `bluetoothctl`;
  pairing turned out to just need standard SSP, no PIN despite what
  the manual implies.
- Used `btmon` to watch raw HCI traffic while attempting a manual
  RFCOMM connection, which showed the printer advertising both
  `Serial Port` and `OBEX Object Push` SDP services.
- Rather than guess at a command protocol blind, captured a real
  print job's full Bluetooth traffic straight from the Android app
  (HCI snoop log), then wrote a parser from scratch: btsnoop file
  format -> HCI ACL reassembly -> L2CAP channel demux -> RFCOMM
  framing (validated against RFCOMM's own FCS checksum, which is
  what caught and fixed an early parsing bug around credit-based
  flow control) -> the two logical channels underneath.
- One channel turned out to be textbook OBEX Object Push (confirmed
  by re-decoding it back into a byte-identical JPEG). The other is
  a Canon-proprietary command protocol, partially decoded by
  cross-referencing byte offsets against
  [dtgreene/ivy2](https://github.com/dtgreene/ivy2), an independent
  community client for the next-generation IVY 2 printer -- two of
  its documented command constants matched exactly.
- Built a tool that replays an exact real capture (byte-for-byte) as
  a baseline, then a general tool that reprocesses a new image through
  the same pipeline and only patches the one protocol field known to
  depend on image size.
- Along the way, discovered (not yet fully confirmed) that the
  proprietary channel's bulk raster-looking data may not actually be
  load-bearing for the printed output -- see
  [PROTOCOL.md](PROTOCOL.md) for the details and what's still open.

Full protocol writeup, including exact byte layouts, is in
[PROTOCOL.md](PROTOCOL.md).

## Caveats / open questions

- **Tested on one printer, one firmware version.** The original IVY,
  not IVY 2, IVY CLIQ, or IVY CLIQ+. Other models/firmware may behave
  differently or not work at all.
- **The proprietary channel isn't fully understood.** See
  [PROTOCOL.md](PROTOCOL.md#bulk-data-chunks----the-open-question).
  The current code reuses a real captured session's bulk data
  unmodified, patching only the one field known to matter. This has
  produced correct prints of new, unique images in testing, but it's
  not been verified across many images or edge cases (very large
  images, unusual aspect ratios, printer error states, low paper,
  etc).
- **No error handling to speak of.** If the printer rejects the job,
  is out of paper, has its cover open, or the battery is low, this
  won't tell you clearly -- you'll just get no print. `print.py`
  prints back whatever raw reply bytes it receives, but they aren't
  decoded yet.
- **Pairing/connection quirks weren't all root-caused.** Some early
  `ConnectionAttemptFailed` errors during development went away after
  restarting the Bluetooth service; it's unclear whether that was
  printer-side, kernel/BlueZ-side, or just a stale pairing state.
- If you get this working on a different Canon Mini Print printer
  model, or find a case where it breaks, please open an issue --
  ideally with a `btmon` capture of the failure. See
  [PROTOCOL.md](PROTOCOL.md#capturing-your-own-session) for how to
  capture a real session from the official app to compare against.

## Repo layout

```
print.py                        CLI entrypoint
poison_ivy/
  image_prep.py                 resize/crop to match the app's own output
  obex_push.py                  general OBEX PUT builder (channel 4)
  channel2_template.py          patches the size field in a captured
                                 channel-2 session (channel 2)
data/
  channel2_payload.bin          the captured channel-2 template used above
tools/
  replay_exact_capture.py       resends one full real session byte-for-byte,
                                 useful for isolating connection issues from
                                 image-pipeline issues
  channel2_capture.bin          )  raw bytes from that one real
  channel4_capture.bin          )  captured session
PROTOCOL.md                     full technical protocol writeup
```

## Acknowledgments

[dtgreene/ivy2](https://github.com/dtgreene/ivy2) -- an independent,
from-scratch Python client for the Canon IVY 2. Its published command
constants and image-preparation logic were an invaluable cross-check
while decoding this printer's protocol, even though the two printers
don't speak byte-identical protocols.

## License

[PolyForm Noncommercial 1.0.0](LICENSE). Plain-language summary (the
license text is what actually governs, this is just a summary): free
to use, modify, and share for any noncommercial purpose -- personal
projects, hobby use, research, education, nonprofits -- but companies
can't use it to make money. If you want a commercial license, ask.

Before publishing your own copy of this repo, put your name (or
handle) in the `Required Notice:` line at the top of the `LICENSE`
file.
