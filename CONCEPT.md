# MTPV Concept

A brief architectural overview. Build, usage and protocol details: [`mtpv/README.md`](mtpv/README.md).

## Model

One app with two roles, chosen once per device and restored at every start:

- The **controller** owns the session: the song set, the first page shown, and the arrangement of
  screens (an ordered list of slots, one of which is the controller itself). A song set is an
  ordered list of PDFs whose pages follow each other like one long PDF; a single PDF is a set of
  one song, a folder of PDFs a set in the order stored for that folder. The folders of PDFs below
  one library folder are the controller's set library, browsed one level at a time from the menu drawer.
- A **secondary** is a passive screen, like an extra monitor. It shows whatever page the controller
  tells it to and reports page turns and notes back.

Slot *i* shows page *start + i* of that stream, which the controller maps to a file and a page in it; slots past the last page show an empty-set sign (∅), and *start* goes
up to the last page. A page turn on any screen moves *start* by one, so every screen moves by one page.

```
 ┌────────────┐   HTTP (paired, bearer token)   ┌─────────────┐
 │ Controller │ ──────────────────────────────▶ │ Secondary 1 │  page n+1
 │  page n    │ ◀── long poll: page turns ───── │ HTTP server │
 │            │ ◀── GET notes ───────────────── │ + mDNS      │
 └────────────┘                                 └─────────────┘
        │                                       ┌─────────────┐
        └─────────────────────────────────────▶ │ Secondary 2 │  page n+2
                                                └─────────────┘
```

## Building blocks

| Package | Role |
|---|---|
| `net/` | `Protocol.kt`: the HTTP API a secondary offers. This is the contract between the roles. |
| `secondary/` | NanoHTTPD server, mDNS announcement (`_mtpv._tcp`, UUID in TXT), pairing, the shown page and its notes. |
| `controller/` | mDNS discovery, one `SecondaryLink` per secondary that converges it to its target page, the HTTP client. |
| `core/` | Role-independent parts: paging and version rules, PDF cache (keyed by SHA-256), notes model and store, `PdfRenderer` wrapper. |
| `ui/` | Compose screens for both roles, the page view with its notes layer, tap zones and pedal keys. |
| top level | `MtpvApp` holds the active role, `KeepAliveService` keeps the process alive, `Prefs` persists role and session. |

## Design choices

- **Controller pushes, secondaries hold no session.** The controller computes each secondary's target
  (file, page) and a link retries until the secondary shows it. A secondary that comes back online
  simply converges again; nothing needs to be resynchronised by hand.
- **Versioned display commands.** Each command carries a strictly increasing, persisted version, so a
  late or repeated request can never move a screen backwards.
- **Sets live on the controller only.** A secondary gets single (file, page) targets and never
  learns about sets, so song sets needed no protocol change. Each PDF is copied into the
  controller's cache, so a file changing in the folder cannot change content under a known hash.
  A folder's stored order is keyed by its document ID, so it is the same folder whether it was
  picked directly or reached through the library.
- **Nothing on the page but the page.** The controller's menu is a drawer, opened by a tap in the
  middle or a small ☰ button; there is no edge swipe, so it never clashes with the pen or page turns.
- **Content-addressed files.** PDFs are identified by their SHA-256, uploaded once and cached on both
  sides. Notes are keyed by the same hash, so a changed PDF starts with no notes.
- **Single owner for notes.** The controller holds the authoritative copy of all notes. A secondary
  edits only the page it shows; the controller fetches those edits before it gives that secondary
  another page. Strokes are vectors in page-relative coordinates, independent of screen size.
- **Explicit pairing.** A secondary obeys only controllers it has allowed once (code shown on both
  devices, token issued). Several MTPV setups can share one network.
- **Local network only.** Plain HTTP and mDNS on the same Wi-Fi; no cloud, no Google Play Services.

## Why a standalone app

OpenSong (`OpenSongTablet/`) was considered as a base. Its multi-screen features (Nearby, secondary
display, web server) all mirror one page to every device, so the distributed part — discovery,
pairing, file transfer, per-device pages, notes round trip — would have to be written either way.
Putting it into OpenSong would add a permanent fork of a large app, its minSdk 19 constraints and a
full songbook app on every secondary, for a PDF viewer and pedal handling that are small to build.
