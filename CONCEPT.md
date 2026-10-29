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
 ┌────────────┐    HTTPS (pinned device keys)   ┌─────────────┐
 │ Controller │ ──────────────────────────────> │ Secondary 1 │  page n+1
 │  page n    │ <── long poll: page turns ───── │ HTTP server │
 │            │ <── GET notes ───────────────── │ + mDNS      │
 └────────────┘                                 └─────────────┘
        │                                       ┌─────────────┐
        └─────────────────────────────────────> │ Secondary 2 │  page n+2
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
- A file that leaves the folder takes everything the set added to it with it: place, tick and
  notes.
- A file that is overwritten keeps its place and tick, but loses its notes: they were made for the
  old content.
- A new set starts without notes, even if its PDFs have notes in other sets. Notes reach a song
  only by an explicit act: **Copy notes from…** on that song (from any song with exactly the same
  PDF). Planned feature: a PDF with notes embedded by MTPV, put into the folder, brings its notes
  along.
- When the library moves, a set follows its folder (same place below the library folder, most
  files still there): it is the same view on the same folder.

The PDF does not carry the notes, and the app should not pretend it does: copying a PDF to
another folder copies the input, not the set. User texts (README, Help) say so plainly.

## Building blocks

| Package | Role |
|---|---|
| `net/` | `Protocol.kt`: the HTTPS API a secondary offers. This is the contract between the roles. `Tls.kt`: device keys and pinning. |
| `secondary/` | NanoHTTPD server, mDNS announcement (`_mtpv._tcp`, UUID in TXT), pairing, the shown page and its notes. |
| `controller/` | mDNS discovery with an address scan as fallback, one `SecondaryLink` per secondary that converges it to its target page, the HTTP client. |
| `core/` | Role-independent parts: paging and version rules, PDF cache (keyed by SHA-256), notes model and store, `PdfRenderer` wrapper. |
| `ui/` | Compose screens for both roles, the page view with its notes layer, tap zones and pedal keys. |
| top level | `MtpvApp` holds the active role and resumes or pauses it as MTPV comes to or leaves the screen, `Prefs` persists role and session. |

## Design choices

- **Controller pushes, secondaries hold no session.** The controller computes each secondary's target
  (file, page) and a link retries until the secondary shows it. A secondary that comes back online
  simply converges again; nothing needs to be resynchronized by hand.
- **Versioned display commands.** Each command carries a strictly increasing, persisted version, so a
  late or repeated request can never move a screen backwards.
- **Content-addressed files.** PDFs are identified by their SHA-256 hash, uploaded once and cached
  on both sides.
- **Sets live on the controller only.** A secondary gets single (file, page) targets and never
  learns about sets. Each PDF is copied into the controller's cache, so a file changing in the
  folder cannot change content under a known hash. A set record is keyed by its folder's document
  ID, so it is the same set whether the folder was picked directly or reached through the library.
  The record also keeps a random set ID and the folder's path below the library folder. When the
  library moves, its folders get new document IDs; the record is then re-associated with the folder
  at the same path, if most of its files are there, and keeps its set ID and with it the notes.
- **Little on the page but the page.** Small buttons along the top and bottom edge, nothing else.
  The controller's menu is a drawer, opened by a tap in the middle or a small ☰ button; there is no
  edge swipe, so it never clashes with the pen or page turns.
- **Notes per song.** Notes belong to a song (a file name in a set), keyed by the set ID and a random
  notes ID, so copies of one PDF have notes of their own. A display command names the PDF to render
  and, separately, the song's notes key. A song's notes follow a rename and are deleted when its file
  leaves the folder or is overwritten. A song gets another song's notes only through Copy notes
  from…, which gives it a new notes ID; its old notes are deleted.
- **Single owner for notes.** The controller holds the authoritative copy of all notes. A secondary
  edits only the page it shows; the controller fetches those edits before it gives that secondary
  another page. Strokes are vectors in page-relative coordinates, independent of screen size.
- **Explicit pairing.** A secondary obeys only controllers it has allowed once. Both devices show a
  code derived from both their keys, so matching codes prove no one is in between; then each pins
  the other's key. Several MTPV setups can share one network.
- **On screen only.** A role uses the network only while MTPV is on its device's screen: no
  service, no notifications, nothing in the background. Leaving the screen pauses that device;
  coming back reconnects it like after any break, which the rules above already make safe.
- **Local network only.** HTTPS (TLS 1.3) with device keys pinned at pairing, and mDNS: on a Wi-Fi
  the tablets share if there is one, otherwise on a Wi-Fi Direct network the controller starts. No
  cloud, no Google Play Services.
