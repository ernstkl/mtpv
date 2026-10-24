# MTPV – Multi-Tablet PDF Viewer

One app, two roles. A **controller** tablet opens a PDF and turns pages. Each **secondary** tablet
shows one page: the screen to the right of the controller shows the next page, and so on.
A page turn moves every screen by one page.

Architecture overview: [`../CONCEPT.md`](../CONCEPT.md).

## Build

    ./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
    ./gradlew testDebugUnitTest
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Needs JDK 17+ and the Android SDK (`local.properties` → `sdk.dir`). Android 10+ (minSdk 29), for both roles. Discovery watches addresses with `registerServiceInfoCallback`
on Android 14+; older controllers resolve once and resolve again when a secondary stops answering.

## Use

1. Install on all tablets, all on the same Wi-Fi.
2. First start: choose **Controller** on one tablet and **Secondary** on the others. The choice is
   remembered, so later starts go straight into the role. **Device role** in the menu changes it; back
   or Cancel keeps the current role running.
3. Pair each secondary once: on the controller, tap the middle of the screen (or ☰ top left) to open
   the menu → **Screens** → under
   *Nearby, not paired* tap **Pair**, then tap **Allow** on the secondary (both show the same code).
   Paired secondaries are added to the right of the controller; **Screens** sets the order (the
   controller can be in any position) and lets you add a secondary by IP if mDNS is blocked.
   Secondaries obey only paired controllers, so a network full of other MTPV devices is fine.
   Then **Open single PDF**, or open a PDF with MTPV from a file manager (this also makes the device the controller).
   For a song set, collect one PDF per song in a folder below the **Library folder** (in the menu; folders in
   folders are fine, such as Gigs / 2026 / May) and pick it under **Open set from library…**: the PDFs follow each
   other like one long PDF, in alphabetical order (numbers by value, so "2" comes before "10"). Opening
   the same folder again keeps its order; files added since go to the end, removed ones drop out.
   Files without pages are left out. Subfolders are not included. **Songs** lists the set: tap a song
   to show it from the leftmost screen, drag ≡ to change the order, untick a song to leave its pages
   out (it stays in the list, so it can be ticked again). Order and ticks are kept for that folder.
   **Reload folder** picks up added or removed PDFs. The top of the menu shows the song on the leftmost screen.
   **♫** next to ☰ opens **Songs** directly (or the library, without a set); only the controller has it,
   so it also shows which screen is the controller.
   **Open set from library…** browses the library: a folder without subfolders
   opens as a set at a tap, one with subfolders opens to show them (↑ goes back up), and *Show this
   folder as a set* opens the PDFs of the folder shown. The open set is marked. Folders starting with
   "." and folders without PDFs are not shown. A set keeps its order and ticks however it was opened.
4. Turn pages: tap the left/right third of any screen, controller or secondary, or use a Bluetooth pedal
   on any of them (Page Up/Down, arrow keys, Space/Enter). Secondaries pass the page turn to the controller.
   Pages turn on until the last page is on the leftmost screen; screens past the end show ∅.
5. When MTPV comes (back) to the front on any screen, the other screens follow: where another app
   is in front, a notification "MTPV: tap to show" appears. (Android does not let an app jump to
   the front by itself.)
6. Next rehearsal: switch on, start the app on every tablet, carry on. The controller restores the PDF,
   page and arrangement and reconnects; secondaries keep the last page until it does. If a file of a
   set read from a folder has gone from the PDF cache, the controller reads the folder again.

Notes: write with the stylus on any screen; fingers keep turning pages. The tool bar in the
bottom-left corner picks black/red/blue pen, yellow marker or eraser with one tap; the selected one
is underlined. The eraser removes whole strokes; the stylus eraser end works too. ↶ removes the
page's strokes one by one, newest first, whichever screen they were drawn on (erasing is not
undone). Without a stylus, tap the square finger switch at the left of the bar (green = on): a
finger then draws like the stylus, and taps on the page no longer turn it, so use a pedal or switch it off again.
Tool and finger switch are the same on all screens, like one big sheet of paper: what you pick on
one screen is picked on all of them. The stylus draws on every screen whatever the switch says. Notes belong to the PDF's content: a changed PDF starts without notes.

Dark pages: **◐** at the top right of any screen swaps light and dark on all screens, so white paper turns black.
Colours keep their hue (red notes stay red); the marker tints the black paper instead of the white.
The setting is kept across restarts. PdfRenderer has no dark mode, so the page is drawn through a colour matrix.

Status dots (top right): green = secondary shows the right page, yellow = sending the PDF, red = unreachable.
On a secondary, tap the middle of the screen (or ☰ top left) for its address, paired controllers and **Device role**.

## How it works

- Secondary: HTTP server (NanoHTTPD, port 8765) announced as `_mtpv._tcp` via `NsdManager`,
  with its UUID in the TXT record. API in `net/Protocol.kt`.
- Pairing: the controller asks (`POST /v1/pair`), the secondary shows its name and a 4-digit code,
  and tapping Allow there issues a random token. Every other request needs it
  (`Authorization: Bearer …`). Paired controllers can be removed on the secondary (tap the middle or ☰).
- PDFs are identified by SHA-256 and sent once; both roles keep them in `filesDir/pdfs`
  (100 files / 2 GB, least recently used are removed first; the controller never removes the open set's files).
- A folder is read through the Storage Access Framework (read access only, kept across restarts; one
  grant for the library folder covers every folder below it). Its
  PDFs are copied into the cache; `filesDir/sets/<hash of the folder's document ID>.json` keeps the
  song order and each file's size and date, so unchanged files are not read again. Secondaries only
  ever see single files: the controller works out each screen's file and page.
- Every display command carries a version (strictly increasing, persisted); secondaries ignore older ones.
- Notes are vector strokes in page-relative coordinates. The controller keeps the authoritative copy
  (`filesDir/notes/<fileId>/<page>.json`) and sends a page's strokes with it. A secondary saves edits
  at once and marks them dirty; before it gets another page, the controller fetches and saves them
  (a display command is refused with `notes_unsaved` otherwise). If both sides changed a page while a
  secondary was offline, all strokes are kept.
- The controller runs one `SecondaryLink` per secondary that converges it to its target page,
  uploads the PDF if needed and pings every 3 s. It also keeps a long-poll request (`GET /v1/input`)
  open on each secondary, which answers as soon as someone turns a page or picks a tool there.
- Bringing MTPV to the front: when MTPV comes to the front on the controller, it sends
  `POST /v1/front` to each secondary; on a secondary, it reports a `front` event over the long poll
  and the controller passes it on. A screen not showing MTPV posts a heads-up notification; tapping
  it brings MTPV to the front without waking the others again.
- Note tool, finger switch and dark pages work the same way: the controller holds the last ones
  picked on any screen and sends them to every secondary (`POST /v1/tool`).
- Folder access: MTPV keeps read access only to the library folder and the open set's folder, and
  gives up the rest (Android allows an app at most 512 such grants).

While a role is active, a foreground service (with a notification) keeps the app running when it is not
on screen, so a secondary keeps serving and the controller keeps receiving page turns.

## Not in this version

Encryption (tokens travel in plain HTTP on the local network), redo, exporting a PDF with the notes
burnt in, NFC as a pairing shortcut, and Wi-Fi Direct.
