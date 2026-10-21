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
3. Controller: tap the middle of the screen for the toolbar → **Open PDF**. Secondaries found via mDNS
   are added to the right of the controller automatically; **Screens** sets the order (the controller
   can be in any position) and lets you add a secondary by IP if mDNS is blocked.
4. Turn pages: tap the left/right third of the controller, or use a Bluetooth pedal
   (Page Up/Down, arrow keys, Space/Enter).
5. Next rehearsal: switch on, start the app on every tablet, carry on. The controller restores the PDF,
   page and arrangement and reconnects; secondaries keep the last page until it does.

Status dots (top right): green = secondary shows the right page, yellow = sending the PDF, red = unreachable.
On a secondary, long-press for its address and to change the role.

## How it works

- Secondary: HTTP server (NanoHTTPD, port 8765) announced as `_mtpv._tcp` via `NsdManager`,
  with its UUID in the TXT record. API in `net/Protocol.kt`.
- PDFs are identified by SHA-256 and sent once; both roles keep them in `filesDir/pdfs`
  (30 files / 2 GB, least recently used are removed first).
- Every display command carries a version (strictly increasing, persisted); secondaries ignore older ones.
- The controller runs one `SecondaryLink` per secondary that converges it to its target page,
  uploads the PDF if needed and pings every 3 s.

## Not in this version

Pairing/authentication (anyone on the Wi-Fi can drive a secondary), notes/annotations,
a foreground service (the secondary serves while its screen is on), and Wi-Fi Direct.
