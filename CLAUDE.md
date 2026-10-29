# CLAUDE.md

MTPV shows one PDF across several Android tablets, one page per screen. Architecture: `CONCEPT.md`;
build, usage and protocol summary: `mtpv/README.md`.

## Layout

- `mtpv/` is the Gradle project (Kotlin, Compose). Run Gradle from there:
  `./gradlew assembleDebug`, `./gradlew testDebugUnitTest`.
- `developer-notes/` holds private planning notes (`TODO.md`, wishlist) and early drafts. It is its
  own local git repo with no remote, ignored here; commit changes to it there, never in this repo.
  Background only; where they disagree with the code, the code wins.
- `fdroid/` holds the draft F-Droid metadata.

## Rules

- `net/Protocol.kt` is the contract between controller and secondary. Change the API there first
  and keep its doc comment in sync. Raise `Protocol.API_VERSION` with every change to the API: the
  controller works only with secondaries of its own version.
- Keep the invariants from `CONCEPT.md`: display commands are versioned and never go backwards,
  files are keyed by SHA-256 and notes by song (set ID and notes ID), the controller owns the
  authoritative notes and fetches a secondary's edits before giving it another page.
- Pure logic belongs in `core/` with unit tests (`app/src/test/`); Android-only code stays thin around it.
- minSdk is 29 for both roles. Guard newer APIs.
- When behavior visible to users changes, update `mtpv/README.md` and the Help dialog
  (`ui/HelpDialog.kt`) as well.
- Commit messages: `MTPV: <what changed>` in one line, for changes to the app.
