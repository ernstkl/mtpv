# MTPV – Multi-Tablet PDF Viewer

One app, two roles. A **controller** tablet opens a PDF and turns pages. Each **secondary** tablet
shows one page: the screen to the right of the controller shows the next page, and so on.
A page turn moves every screen by one page. It is made for one person with two or three tablets,
like a sheet of music spread over several pages. On a single tablet the controller works on its own
as a sheet music reader: song sets, pedal, notes and dark pages need no secondary.

Architecture overview: [`../CONCEPT.md`](../CONCEPT.md).

## Build

    ./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
    ./gradlew testDebugUnitTest
    adb install -r app/build/outputs/apk/debug/app-debug.apk

For testers, use a signed release build: it is much smaller and faster than the debug build. Create a
key once and keep it safe (updates must be signed with the same key), then put its details into
`keystore.properties` next to `settings.gradle.kts` (ignored by git):

    keytool -genkey -v -keystore ~/mtpv-release.jks -alias mtpv -keyalg RSA -keysize 4096 -validity 10000

    storeFile=/home/<you>/mtpv-release.jks
    storePassword=…
    keyAlias=mtpv
    keyPassword=…

Both passwords are the one you chose when `keytool` asked for it (it asks only once, since the key in a
PKCS12 keystore shares the store password).

`./gradlew assembleRelease` then writes `app/build/outputs/apk/release/app-release.apk`. Without
`keystore.properties` it writes `app-release-unsigned.apk`.

F-Droid builds are reproducible: F-Droid builds the tagged commit itself and publishes the APK signed
with this key, but only if its build matches the signed APK from the GitHub release byte for byte.
A release: raise `versionCode` and `versionName` in `app/build.gradle.kts`, add
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, commit, tag `v<versionName>`, push,
build `assembleRelease` from a clean checkout of the tag, and attach `app-release.apk` as
`mtpv-<versionName>.apk` to a GitHub release of that tag. F-Droid finds the tag by itself.

Needs JDK 17+ and the Android SDK (`local.properties` → `sdk.dir`). Android 10+ (minSdk 29), for both roles. Discovery watches addresses with `registerServiceInfoCallback`
on Android 14+; older controllers resolve once and resolve again when a secondary stops answering.

## Use

1. Install on all tablets, all on the same Wi-Fi (no shared Wi-Fi: see *Direct network* below).
   Only one tablet: choose **Controller** and skip pairing.
2. First start: choose **Controller** on one tablet and **Secondary** on the others. The choice is
   remembered, so later starts go straight into the role. **Device role** in the menu changes it; back
   or Cancel keeps the current role running.
3. Pair each secondary once: on the controller, tap the middle of the screen (or ☰ top left) to open
   the menu → **Screens** → under
   *Nearby, not paired* tap **Pair**, then tap **Allow** on the secondary once you have checked that both show the same 6-digit code.
   Paired secondaries are added to the right of the controller; **Screens** sets the order (the
   controller can be in any position). While **Screens** is open, the controller keeps searching: by
   mDNS, and every 10 s by trying every address of the local network (for networks where mDNS is
   blocked, as on some phone hotspots, or misses tablets, as on Android 11). It also tries every
   address by itself while a paired secondary cannot be reached. Adding a secondary by IP still works too.
   Secondaries obey only paired controllers, so a network full of other MTPV devices is fine.
   A paired secondary is off the market: other controllers do not list it, and it refuses their
   pairing until its controller is removed on it (tap its middle or ☰ → *Paired controllers* →
   **Remove**). So where several people use MTPV in one room, *Nearby* shows only the tablets not
   paired yet. A secondary can be found only while MTPV is on its screen.
   Then **Open single PDF**, or open a PDF with MTPV from a file manager (this also makes the device the controller).
   For a song set, collect one PDF per song in a folder below the **Library folder** (in the menu under **Library**; folders in
   folders are fine, such as Gigs / 2026 / May) and pick it under **Open set from library…**: the PDFs follow each
   other like one long PDF, in alphabetical order (numbers by value, so "2" comes before "10"). Opening
   the same folder again keeps its order; files added since go to the end, removed ones drop out.
   Subfolders are not included. **Songs** lists the set: tap a song
   to show it from the leftmost screen, drag ≡ to change the order, untick a song to leave its pages
   out (it stays in the list, so it can be ticked again). Order and ticks are kept for that folder.
   **Reload folder** picks up added or removed PDFs. The top of the menu shows the song on the leftmost screen.
   **♫** next to ☰ opens **Songs** directly (or the library, without a set); only the controller has it,
   so it also shows which screen is the controller.
   **Open set from library…** browses the library: a folder without subfolders
   opens as a set at a tap, one with subfolders opens to show them (↑ goes back up), and *Show this
   folder as a set* opens the PDFs of the folder shown. Folders starting with
   "." and folders without PDFs are not shown.
   A renamed file keeps its place, tick and notes. If the library moves (say from the SD card to
   internal storage), pick it again under **Library folder**: each set found at the same place in it
   keeps its order, ticks and notes.
   **Library → Export all data…** saves the notes, song order and ticks of all sets, and the note tool
   and dark pages setting, to one zip file (it first fetches unsaved notes from the secondaries). Not the
   PDFs: they stay in the library. **Import all data…** replaces all of them on this device by those in
   such a file, after asking. To move to another install (a new tablet, or the F-Droid build, which needs
   uninstalling first): export, install, import, then pick the library folder again; each set then gets
   its order, ticks and notes back. Paired screens are not in the file: pair them again.
4. Turn pages: tap the left/right third of any screen, controller or secondary, swipe (to the left for the
   next page, like paper), or use a Bluetooth pedal on any of them (Page Up/Down, arrow keys, Space/Enter). Secondaries pass the page turn to the controller.
   Pages turn on until the last page is on the leftmost screen; screens past the end show ∅.
5. MTPV works on a tablet only while it is on that tablet's screen (which MTPV keeps switched on).
   Switch to another app and that tablet pauses: nothing runs in the background, so nothing uses the
   battery after the gig, and there is no notification. Back in MTPV, it reconnects and catches up
   within seconds; notes written on a secondary meanwhile are kept and reach the controller then.
6. Next rehearsal: switch on, start the app on every tablet, carry on. The controller restores the PDF,
   page and arrangement and reconnects.

Direct network: where there is no Wi-Fi the tablets can share (none at the venue, or a guest network
that keeps devices apart), the controller makes its own, by itself. It starts it when a paired screen
cannot be reached for about 10 s, or when **Screens** is opened and nothing can be reached. A paired
secondary has got the network's 16-digit code from the controller and joins when no controller has
reached it for about 15 s. So tablets paired at home find each other at the venue without any tap.
A secondary never paired, with no shared Wi-Fi: tap its middle (or ☰) → **Join direct network…** and
type the code the controller shows (in **Screens** while the network runs, or menu → **Direct
network**); it then appears under *Nearby* for pairing. Wi-Fi has to be switched on, but no Wi-Fi
network is needed, and a home Wi-Fi may stay connected alongside. Once started, the network stays
while MTPV is on the controller's screen. When
MTPV leaves the screen the network stops, on a secondary it is left; both start again at once when
MTPV is back. Menu → **Direct network**:
*Start by itself when needed* (on by default), **Start**/**Stop** by hand (Stop holds until the next
app start), **New code** (secondaries reachable only through the network then need it typed).
**Leave** on a secondary forgets the code until the controller hands it over again. Android asks once
to allow *Nearby devices* (Android 12 and older: location, after a note that MTPV never reads it; if it then fails, switching location on
may help): on the controller once screens are paired, on a secondary once it has a code. The code
only lets a tablet into the network; pairing is still needed to control the screens.

Notes: write with the stylus on any screen; fingers keep turning pages. The tool bar in the
bottom-left corner picks black/red/blue pen, yellow marker or eraser with one tap. The eraser removes whole strokes; the stylus eraser end works too. ↶ removes the
page's strokes one by one, newest first, whichever screen they were drawn on (erasing is not
undone). Without a stylus, tap the square finger switch at the left of the bar (green = on): a
finger then draws like the stylus, and taps and swipes on the page no longer turn it, so use a pedal or switch it off again.
Tool and finger switch are the same on all screens, like one big sheet of paper: what you pick on
one screen is picked on all of them. The stylus draws on every screen whatever the switch says.
Notes belong to the set, not to the PDF: the folder is only what MTPV reads, and MTPV never writes
to it. Order, ticks and notes are kept in the app, so a copy of the PDF in another set, or under
another name in the same set, has notes of its own, and a new set starts without notes. Deleting a
file from the folder, or changing its content, deletes its notes; renaming it keeps them.
**Copy notes from…** in the menu gives the song on the leftmost screen the notes of another song
with exactly the same PDF, in any set (newest first, with set and date); they replace its own.

Page numbers: the bottom right corner of every screen shows where its page is, as "song 2 of 7: page 1 of 3":
the song among the songs shown in the set and the page within that song. A single PDF shows just "page 1 of 3".

Dark pages: **◐** at the top right of any screen swaps light and dark on all screens: white paper turns very dark gray, black notes off-white
(softer than pure black and white, which makes thin lines blur for many eyes).
Colours keep their hue (red notes stay red); the marker tints the black paper instead of the white.
PdfRenderer has no dark mode, so the rendered page is inverted in linear light
(not in sRGB values), which keeps thin lines such as staves and stems as strong as on paper.

Status dots (top right): green = secondary shows the right page, yellow = sending the PDF, red = unreachable.
On a secondary, tap the middle of the screen (or ☰ top left) for its address, paired controllers and **Device role**.

## How it works

- Secondary: HTTPS server (NanoHTTPD, port 8765) announced as `_mtpv._tcp` via `NsdManager`,
  with its UUID in the TXT record. API in `net/Protocol.kt`.
- Keys: each device makes an EC key in the Android Keystore with a self-signed certificate
  (`net/Tls.kt`); the private key never leaves the Keystore. All traffic is TLS 1.3, and both sides
  present their certificate. There is no CA: a device is known by its certificate's SHA-256 fingerprint.
- Pairing: the controller asks (`POST /v1/pair`) with a commitment to a random nonce and gets the
  secondary's nonce, then reveals its own (`POST /v1/pair/{id}/nonce`). Both sides derive a 6-digit
  code from both certificates, as each sees them in its TLS session, and both nonces; the secondary
  shows it with the controller's name and Allow/Deny. With a device in between, the codes differ, and
  the commitment gives it a single guess. On Allow each side pins the other's fingerprint, and the
  secondary accepts every other request only from that client certificate (else `not_paired`).
  Paired controllers can be removed on the secondary (tap the middle or ☰); if the removed one gave
  the page on screen, the screen clears, notes it had not fetched yet included.
  A paired secondary announces `open=0` (TXT record and `GET /v1/info`) and refuses other controllers
  with `already_paired`.
- PDFs are identified by SHA-256 and sent once; both roles keep them in `filesDir/pdfs`
  (100 files / 2 GB, least recently used are removed first; the controller never removes the open set's files).
- A folder is read through the Storage Access Framework (read access only, kept across restarts; one
  grant for the library folder covers every folder below it). Its
  PDFs are copied into the cache; `filesDir/sets/<hash of the folder's document ID>.json` keeps the
  song order and each file's size and date, so unchanged files are not read again, plus a random set
  ID, each song's notes ID and the folder's path below the library folder. A folder without a record
  takes over the one with the same path whose files are mostly there (the library moved). A single
  PDF gets a record of its own, keyed by its document. Secondaries only ever see single files: the
  controller works out each screen's file and page.
- Every display command carries a version (strictly increasing, persisted); secondaries ignore older ones.
- Notes are vector strokes in page-relative coordinates. The controller keeps the authoritative copy
  (`filesDir/notes/<set ID>/<notes ID>/<page>.json`) and sends a page's strokes with it, together
  with that notes key, which the secondary hands back with its edits. A secondary saves edits
  at once and marks them dirty; before it gets another page, the controller fetches and saves them
  (a display command is refused with `notes_unsaved` otherwise). If both sides changed a page while a
  secondary was offline, all strokes are kept.
- The controller runs one `SecondaryLink` per secondary that converges it to its target page,
  uploads the PDF if needed and pings every 3 s. It also keeps a long-poll request (`GET /v1/input`)
  open on each secondary, which answers as soon as someone turns a page or picks a tool there.
- Note tool, finger switch and dark pages work the same way: the controller holds the last ones
  picked on any screen and sends them to every secondary (`POST /v1/tool`).
- Direct network: a Wi-Fi Direct group the controller creates with `WifiP2pConfig`; the secondaries
  join it with the same config, without a system dialog. The code is the group's password and
  defines its name, `DIRECT-mt-MTPV-` plus 4 hex characters from PBKDF2-HMAC-SHA256 of the code (slow,
  so the broadcast name does not help guessing the code). The controller hands the code to each
  paired secondary (`POST /v1/direct`, once per connection). The controller is `192.168.49.1`; a
  secondary reachable both there and on a home Wi-Fi keeps its direct address while it answers.
  Discovery and the address scan work in the group as on any Wi-Fi; a scan runs as soon as a tablet
  joins.
- Folder access: MTPV keeps read access only to the library folder and the open set's folder, and
  gives up the rest (Android allows an app at most 512 such grants).

On screen only: there is no service. Each role uses the network only between the activity's
`onStart` and `onStop` (`resume` and `pause` of the role's runtime). In the background a secondary
stops its server, its mDNS service and the direct network; the controller drops its links, discovery,
scans and the direct network. State on disk (session, display, unsaved notes) is untouched, so coming
back is the same as reconnecting after a break.

Security: everything between the tablets is encrypted (TLS 1.3), PDFs and notes included. Once
paired, the controller accepts only the secondary's pinned key and the secondary obeys only the
controller's. Before pairing, the controller accepts any key: when it looks for secondaries
(`GET /v1/info`), because the answer only lists a tablet and a paired link checks it again over a
pinned connection, and during pairing, because comparing the codes is what checks the key. The keys
are not in backups and do not survive a reinstall: a reinstalled secondary shows up as *new key
(reinstalled?)* under **Screens** and has to be paired again, and so does a reinstalled controller.

## Not in this version

Redo, exporting a PDF with the notes burnt in, and NFC as a pairing shortcut.
