# MTPV Concept: Multi-Tablet PDF Viewer

Status: draft, 2026-10-21. Findings come from reading the code only; nothing is built or tested yet.
Planning notes from the claude.ai project: `input-docs/MTPV_Planung.md`.

## Goal

Show a multipage PDF across several Android tablets, **one page per extra device**
(device 1 shows page n, device 2 shows page n+1, …), instead of every device showing the same page.

Open decision: standalone app vs. extending OpenSongApp.

## OpenSongApp source

- The `thebigg73/OpenSongApp` repo is a README-only stub. The real code is
  **github.com/thebigg73/OpenSongTablet** (by Gareth Evans), cloned at `OpenSongTablet/`.
- Java (some Kotlin), GPLv3, minSdk 19, targetSdk 36. Last commit checked: `56f3725` (2026-10-19).
- Package root: `app/src/main/java/com/garethevans/church/opensongtablet/`

## Existing multi-screen features

| Feature | Code | What it does | One page per device? |
|---|---|---|---|
| Nearby | `nearby/` (Google Nearby Connections, `play-services-nearby:19.5.1`) | One host, any number of clients over Bluetooth/WiFi. The host sends the current song plus page; clients follow. | No, all clients mirror the host's page. **Best base to build on.** |
| Secondary display | `secondarydisplay/SecondaryDisplay.java` (Android `Presentation` API), `MainActivity.java` ~5480–5650 | HDMI/cast screens attached to *one* device. It loops over all connected displays. | No, all get `song.getPdfPageCurrent()` (`MainActivity.java:5605`). Also not separate tablets. |
| Web server | `webserver/KtorServer.kt` (Ktor 2.3.12) | Browsers on the LAN view the host's current song. | No, it only serves the current song. |

## How Nearby handles PDF pages today

- **Topology:** host plus clients, strategy configurable (`P2P_CLUSTER` by default, `P2P_STAR`, point-to-point),
  see `nearby/NearbyConnectionManager.java:63` and lines 119–133. Host passthrough/echo is optional.
- **Message format:** `nearby/NearbyJson.java`, a JSON payload. For PDFs, `section` holds the page index.
- **Host sends:**
  - Song load: `NearbySendPayloads` around line 370. A PDF is always sent as a file payload plus JSON info
    (with the `Song` object attached). The client can also use its own copy of the file.
  - Page change: `sendSongSectionPayload()` → `addSongSection()` sets `section = song.getPdfPageCurrent()`
    (`NearbySendPayloads.java:432`).
  - `sendToConnected()` sends the **same** payload to every connected device.
- **Client receives:**
  - `NearbyReceivePayloads.selectSection()` (line ~327) and `setSongSection()` (line ~573)
    copy the host's page **1:1** into `song.setPdfPageCurrent(...)`.
  - The display then updates in `PerformanceFragment.selectSection()` (line ~1949) via `pdfPageAdapter`.
- **Client options:** `nearbyReceiveHost*` preferences (files, scroll, autoscroll, sections, …),
  `nearbyMatchToPDFSong`, set in `NearbyConnectionsFragment`.
- **Per-device addressing exists** (`deviceToAction`, `sendPayloadToSelected`), but it is only used for library sync.

**Takeaway:** the 1:1 page copy on the client is the only thing preventing "one page per device".

## Initial suggestion: client-side page offset (extend OpenSong)

The smallest change keeps the protocol and the host's sending code as they are:

1. **New client preference** `nearbyPdfPageOffset` (int, default 0), loaded in the
   `NearbyReceivePayloads` constructor like the other `nearby*` preferences, with a UI field in
   `NearbyConnectionsFragment`.
2. **Apply the offset for PDFs** in `selectSection()` and `setSongSection()`: the client shows
   `hostPage + offset`. If that page doesn't exist, show a blank page (or the last page; to be decided).
3. **Optional host "step" mode:** next/previous on the host jumps by the number of devices
   (1–3 → 4–6), e.g. via a host preference used in `PerformanceGestures` (lines ~913–981).

Pros: small and local, fits the existing code style, could be offered upstream to the OpenSong author,
and users keep everything else OpenSong offers (sets, pedals, sync).
Cons: depends on Google Play Services (Nearby), and every client has to set its offset by hand.

### Possible refinements

- The host assigns offsets automatically in connection order, using `deviceToAction` / `sendPayloadToSelected`.
- A "spread" layout preset: 2-up or 3-up arrangement, with the host on the left or right.
- Handle page count mismatches when a client uses its own copy of the PDF.

## Alternative: standalone app (not evaluated yet)

To compare against the above: a small dedicated app (e.g. Android `PdfRenderer` plus Nearby, or a
LAN/WebSocket approach). That means less baggage but rebuilding connection handling, file transfer and pedal support.
Note that OpenSong is GPLv3, so reusing its code in a standalone app makes that app GPLv3 too.

## Open questions

1. Standalone vs. OpenSong extension: decide and give reasons.
2. Who turns pages: host only, a pedal on the host, or any device?
3. Page step: should a page turn move by 1 page or by the number of devices?
4. What should devices past the last page show?
5. Do all devices hold the PDF locally, or does the host always send it?
6. How many tablets, and which Android versions?

## Next steps

- [ ] Answer the open questions above
- [ ] `git init` the project folder; fork/branch OpenSongTablet for the prototype
- [ ] Build OpenSongTablet unchanged and test Nearby PDF mirroring on two devices
- [ ] Prototype the client page offset (steps 1–2)
- [ ] Decide on the host step mode (step 3)
