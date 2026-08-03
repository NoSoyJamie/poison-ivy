# Poison Ivy (Android)

A simple Android app version of the [Poison Ivy](https://github.com/NoSoyJamie/poison-ivy)
Python project: prints to a Canon IVY mini photo printer over Bluetooth,
straight from your phone's own photo picker.

**Status: written but not yet built or tested on a real device.** This
was put together without access to an Android SDK, emulator, or
physical device -- see [Known limitations](#known-limitations) below
before you assume anything here is proven. The underlying protocol
logic (image prep, OBEX push, channel-2 template patching) is a
faithful, careful port of the Python project's code, which *is*
proven against a real printer. What's genuinely new and unverified is
the Android-specific plumbing around it: Bluetooth permissions,
device discovery, and -- most importantly -- whether
`createRfcommSocketToServiceRecord` actually resolves to the right
RFCOMM channels on this printer (see below).

## What it does

- Lists your phone's paired Bluetooth devices (and can actively scan
  for/pair new ones) so you can pick the printer.
- **Sticker sheet layout**: add any number of images (the system
  picker's multi-select) and arrange them independently on one shared
  2:3 canvas -- tap an image to select it (highlighted in green), tap
  the small button on its corner to delete it, and use a two-finger
  gesture (drag/pinch/twist) to move, scale, and rotate the selected
  image, all from one continuous motion.
- The preview renders using the exact same functions used to build
  what actually gets sent to the printer (`ImagePrep.buildPlacedImageMatrix`
  / `bakeComposite`), at the same 2:3 proportions as the real print --
  what you see is what prints, including every image's individual
  placement.
- A "Rotate selected 90°" button for a quick, precise coarse snap on
  top of the interactive fine rotation, and a "Reset" button that
  returns just the selected image to its default centered placement.
  Undo/redo cover the whole layout (add, delete, move/scale/rotate),
  not just one image at a time.
- Background color is a full picker (RGB sliders + live swatch), not
  just black/white.
- Max output size in bytes, still defaulting to 400,000 (the Python
  project's tested-safe value) -- this applies to the whole composited
  sheet, not per image.

## Calibrating rotation

The printed sheet is always physically 2:3, but source images aren't
always already in the orientation you want, and there's reason to
think the printer/protocol has its own fixed orientation expectations
independent of the source file (the very first image this whole
project's protocol was reverse-engineered from was itself stored
sideways relative to how a person would naturally view it -- see the
Python project's history). This app can't determine the "correct"
rotation on its own since it was written without the ability to test
an actual print.

To calibrate: place one image with an obvious "up" (text works well),
print it at 0° rotation, and see how it comes out physically. If it's
wrong, note which way (90° clockwise, 180°, etc.) and rotate that many
times -- either via **Rotate selected 90°** for quick snaps, or the
interactive two-finger twist for anything finer -- before your next
print, and it should come out right from then on. Since rotation is
now a per-image property rather than one global setting, there's no
single default to bake in for this app the way there was before --
each new image starts at 0° and needs the same calibration offset
applied if you know one.

## Known limitations

1. **Never built or run.** I don't have an Android toolchain in the
   environment I wrote this in. The Kotlin has been checked carefully
   by hand (balanced braces, correct imports, consistent types) but
   that's not the same as a compiler actually accepting it. Expect to
   fix at least a few small build errors on first import into Android
   Studio -- that's normal for hand-written code that's never been
   compiled, not a sign anything is fundamentally wrong.

2. **The RFCOMM channel resolution is the biggest open question.**
   The Python project connects to hardcoded RFCOMM channel numbers (2
   and 4) using raw sockets, which Android's public API doesn't
   support directly. This app instead connects via the standard
   Bluetooth service UUIDs the printer itself advertises over SDP
   ("Serial Port" 0x1101 and "OBEX Object Push" 0x1105, confirmed via
   `bluetoothctl info` in the Python project's own debugging) and lets
   Android's Bluetooth stack resolve the actual channel. This *should*
   land on channels 2 and 4 respectively, since that's what those
   services corresponded to when captured -- but it's untested. If
   printing fails in a way that looks like a connection problem (can't
   connect, or connects to the wrong thing), see the documented
   fallback in `BluetoothPrinter.kt` (an unofficial but commonly-used
   reflection-based method to specify the channel number directly).

3. **The gesture and tap handling is the newest, least battle-tested
   part of this app**, now including tap-to-select, tap-to-delete, and
   per-image two-finger manipulation on top of the pinch/rotate/pan
   from before. The underlying matrix/geometry math in `ImagePrep`
   (`buildPlacedImageMatrix`, `pivotPlacedImage`, `hitTestPlacedImage`,
   `deleteButtonScreenPosition`) uses Android's own Matrix
   invert/mapPoints rather than hand-derived formulas specifically so
   it's easy to verify by inspection, but things like the tap-vs-drag
   thresholds and the delete button's hit radius in
   `InteractivePreviewView` are inherently hands-on judgment calls that
   most benefit from real on-device tuning. If tapping an image
   sometimes selects the wrong one, or the delete button is fiddly to
   hit, start there.

4. There is no longer a "flip preview sideways for display" toggle
   (an earlier version of this app had one) -- it was removed because
   it's fundamentally incompatible with accurate interactive editing:
   the live preview container must stay exactly 2:3 (matching the
   real print canvas) for the same pinch/pan/rotate values to frame
   the image identically in the preview and the final print. Rotating
   the preview's display shape would silently break that guarantee.

5. There is no longer a "pad to 2:3" option (an earlier single-image
   version of this app had one) -- it doesn't translate cleanly to the
   multi-image sticker-sheet model, where each image is independently
   sized/positioned by the user rather than automatically cropped to
   fill the whole canvas. If you want a specific image's edges fully
   preserved without cropping, just scale it down (pinch) until its
   whole bounding box is visible, rather than relying on automatic
   padding.

## Building it

You'll need [Android Studio](https://developer.android.com/studio)
(free). Rough steps:

1. Install Android Studio, let it install the Android SDK during
   first-run setup if you don't have one already.
2. Open Android Studio -> **Open** -> select this project's root
   folder (the one with `settings.gradle.kts` in it).
3. Let Gradle sync. It'll download dependencies over the internet the
   first time -- give it a few minutes.
4. If Gradle complains about a missing wrapper jar, use **File ->
   Sync Project with Gradle Files**, or **File -> New -> Import
   Project** and let Android Studio regenerate the wrapper.
5. Fix whatever build errors show up (see note above -- there will
   probably be a few small ones on a first-ever compile of hand-written
   code). Paste them back if you want help.
6. Connect your Android phone via USB with Developer Options + USB
   debugging on (same as the `adb` setup from the Python project), or
   use an emulator (though Bluetooth doesn't work in most emulators --
   you'll want a real device to actually test printing).
7. Click Run.

## First run on your phone

1. Pair the printer via Android's normal Bluetooth settings first if
   you haven't already (same printer, same pairing process as always
   -- nothing printer-specific about this step).
2. Open the app, tap **Find printer**, select it from the list.
3. Tap **Pick image**, choose a photo.
4. Set options if needed (pad for card scans, adjust max size).
5. Tap **Print**.

## Relationship to the Python project

This started as a straight port of the same reverse-engineered
protocol, and the core Bluetooth/protocol layer still is:

| Python | Android (Kotlin) | Purpose |
|---|---|---|
| `poison_ivy/obex_push.py` | `ObexPush.kt` | OBEX byte stream builder |
| `poison_ivy/channel2_template.py` | `Channel2Template.kt` | Size-field patcher |
| `data/channel2_payload.bin` | `assets/channel2_payload.bin` | Same file, copied as-is |
| `print.py` | `BluetoothPrinter.kt` | Connection/send logic |

**`ImagePrep.kt` has since diverged** from `image_prep.py`: the
Android app grew into a multi-image sticker-sheet compositor
(independent per-image position/scale/rotation, tap-to-select,
delete buttons, a full color-picker background) that the Python CLI
tool doesn't have and was never asked to have. If you want that
functionality in the Python tool too, that's a separate, real chunk
of work, not a quick port -- ask if you want it.

If you bracket a different size limit, decode more of the channel-2
protocol, or fix a bug in the shared protocol layer above, please
port the fix to the other -- those are meant to stay in sync. See the
Python project's `PROTOCOL.md` for the full technical writeup;
nothing protocol-specific is duplicated here beyond what's needed for
code comments.
