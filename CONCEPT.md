# MTPV Concept

A brief architectural overview. Build, usage and protocol details: [`mtpv/README.md`](mtpv/README.md).

## Model

MTPV is for one person: a set of devices is the equivalent of a multi-page sheet of paper, usually
two or three tablets. Nobody else waits on that person's screens, which keeps the app simple: it
works only while it is on screen.

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

## A set is a view on a folder

The folder of PDFs (the set's base folder) is **input** to the app, nothing more. MTPV reads it,
copies the PDFs into its own storage and never writes to it. A set is MTPV's view on that folder:
it adds what the plain list of files lacks, and keeps all of it in app storage:

- the order of the songs,
- whether each song is shown or left out,
- the notes. They belong to the set, not to the PDF, because they can depend on the context a
  song is played in ("guitar plays the intro"). They are also tied to the PDF's exact content.

Rules that follow from this:

- A file renamed in the folder is the same song: it keeps its place, tick and notes.
- A file that leaves the folder, or whose content changes, takes everything the set added to it
  with it, notes included.
- A new set starts without notes, even if its PDFs have notes in other sets. Notes reach a song
  only by an explicit act: **Copy notes from…** on that song (from any song with exactly the same
  PDF), or, later, a PDF with notes embedded by MTPV put into the folder (`TODO.md`, 1 and 2).
- When the library moves, a set follows its folder (same place below the library folder, most
  files still there): it is the same view on the same folder.

The PDF does not carry the notes, and the app should not pretend it does: copying a PDF to
another folder copies the input, not the set. User texts (README, About) say so plainly.

## Building blocks

| Package | Role |
|---|---|
| `net/` | `Protocol.kt`: the HTTP API a secondary offers. This is the contract between the roles. |
| `secondary/` | NanoHTTPD server, mDNS announcement (`_mtpv._tcp`, UUID in TXT), pairing, the shown page and its notes. |
| `controller/` | mDNS discovery with an address scan as fallback, one `SecondaryLink` per secondary that converges it to its target page, the HTTP client. |
| `core/` | Role-independent parts: paging and version rules, PDF cache (keyed by SHA-256), notes model and store, `PdfRenderer` wrapper. |
| `ui/` | Compose screens for both roles, the page view with its notes layer, tap zones and pedal keys. |
| top level | `MtpvApp` holds the active role and resumes or pauses it as MTPV comes to or leaves the screen, `Prefs` persists role and session. |

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
  picked directly or reached through the library. Each set record also has a random set ID and its
  path below the library folder; when the library moves, a folder without a record takes over the
  one with the same path, set ID and all.
- **Nothing on the page but the page.** The controller's menu is a drawer, opened by a tap in the
  middle or a small ☰ button; there is no edge swipe, so it never clashes with the pen or page turns.
- **Content-addressed files.** PDFs are identified by their SHA-256, uploaded once and cached on both
  sides.
- **Notes per song.** Notes belong to a song (a file name in a set), keyed by the set ID and a random
  notes ID, so copies of one PDF have notes of their own. A display command names the PDF to render
  and, separately, the song's notes key. A song's notes follow a rename and are deleted when its file
  leaves the folder or changes. A song gets another song's notes only through Copy notes from…,
  which gives it a new notes ID: its old notes are deleted, so late edits to them are dropped.
- **Single owner for notes.** The controller holds the authoritative copy of all notes. A secondary
  edits only the page it shows; the controller fetches those edits before it gives that secondary
  another page. Strokes are vectors in page-relative coordinates, independent of screen size.
- **Explicit pairing.** A secondary obeys only controllers it has allowed once (code shown on both
  devices, token issued). Several MTPV setups can share one network.
- **On screen only.** A role uses the network only while MTPV is on its device's screen: no
  service, no notifications, nothing in the background. Leaving the screen pauses that device;
  coming back reconnects it like after any break, which the rules above already make safe.
- **Local network only.** Plain HTTP and mDNS on the same Wi-Fi; no cloud, no Google Play Services.

## Why a standalone app

OpenSong (`OpenSongTablet/`) was considered as a base. Its multi-screen features (Nearby, secondary
display, web server) all mirror one page to every device, so the distributed part — discovery,
pairing, file transfer, per-device pages, notes round trip — would have to be written either way.
Putting it into OpenSong would add a permanent fork of a large app, its minSdk 19 constraints and a
full songbook app on every secondary, for a PDF viewer and pedal handling that are small to build.
