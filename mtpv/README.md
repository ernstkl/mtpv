# MTPV – Multi-Tablet PDF Viewer

One app, two roles. A **controller** tablet opens a PDF and turns pages. Each **secondary** tablet
shows one page: the screen to the right of the controller shows the next page, and so on.
A page turn moves every screen by one page.

Requirements and the decision against an OpenSong fork: `../ANALYSIS.md`.

## Build

    ./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
    ./gradlew testDebugUnitTest
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Needs JDK 17+ and the Android SDK (`local.properties` → `sdk.dir`). Android 14+ (minSdk 34).

## Use

1. Install on all tablets, all on the same Wi-Fi.
2. First start: choose **Controller** on one tablet and **Secondary** on the others. The choice is
   remembered, so later starts go straight into the role.
3. Pair each secondary once: on the controller, tap the middle of the screen → **Screens** → under
   *Nearby, not paired* tap **Pair**, then tap **Allow** on the secondary (both show the same code).
   Paired secondaries are added to the right of the controller; **Screens** sets the order (the
   controller can be in any position) and lets you add a secondary by IP if mDNS is blocked.
   Secondaries obey only paired controllers, so a network full of other MTPV devices is fine.
   Then **Open PDF**, or open a PDF with MTPV from a file manager (this also makes the device the controller).
4. Turn pages: tap the left/right third of any screen, controller or secondary, or use a Bluetooth pedal
   on any of them (Page Up/Down, arrow keys, Space/Enter). Secondaries pass the page turn to the controller.
5. Next rehearsal: switch on, start the app on every tablet, carry on. The controller restores the PDF,
   page and arrangement and reconnects; secondaries keep the last page until it does.

Notes: write with the stylus on any screen; fingers keep turning pages. The dot in the bottom-left
corner picks black/red/blue pen, yellow marker or eraser (whole strokes; the stylus eraser end works
too). Notes belong to the PDF's content: a changed PDF starts without notes.

Status dots (top right): green = secondary shows the right page, yellow = sending the PDF, red = unreachable.
On a secondary, long-press for its address and to change the role.

## How it works

- Secondary: HTTP server (NanoHTTPD, port 8765) announced as `_mtpv._tcp` via `NsdManager`,
  with its UUID in the TXT record. API in `net/Protocol.kt`.
- Pairing: the controller asks (`POST /v1/pair`), the secondary shows its name and a 4-digit code,
  and tapping Allow there issues a random token. Every other request needs it
  (`Authorization: Bearer …`). Paired controllers can be removed on the secondary (long press).
- PDFs are identified by SHA-256 and sent once; both roles keep them in `filesDir/pdfs`
  (30 files / 2 GB, least recently used are removed first).
- Every display command carries a version (strictly increasing, persisted); secondaries ignore older ones.
- Notes are vector strokes in page-relative coordinates. The controller keeps the authoritative copy
  (`filesDir/notes/<fileId>/<page>.json`) and sends a page's strokes with it. A secondary saves edits
  at once and marks them dirty; before it gets another page, the controller fetches and saves them
  (a display command is refused with `notes_unsaved` otherwise). If both sides changed a page while a
  secondary was offline, all strokes are kept.
- The controller runs one `SecondaryLink` per secondary that converges it to its target page,
  uploads the PDF if needed and pings every 3 s. It also keeps a long-poll request (`GET /v1/input`)
  open on each secondary, which answers as soon as someone turns a page there.

While a role is active, a foreground service (with a notification) keeps the app running when it is not
on screen, so a secondary keeps serving and the controller keeps receiving page turns.

## Not in this version

Encryption (tokens travel in plain HTTP on the local network), undo, exporting a PDF with the notes
burnt in, NFC as a pairing shortcut, and Wi-Fi Direct.
