# CLAUDE.md

MTPV shows one PDF across several Android tablets, one page per screen. Architecture: `CONCEPT.md`;
build, usage and protocol summary: `mtpv/README.md`.

## Layout

- `mtpv/` is the Gradle project (Kotlin, Compose). Run Gradle from there:
  `./gradlew assembleDebug`, `./gradlew testDebugUnitTest`.
- `OpenSongTablet/` is a read-only reference clone of upstream OpenSong, its own git repo and ignored
  here. Never edit it; MTPV does not build on it.
- `developer-notes/` holds private planning notes and early drafts, ignored by git. Background only;
  where they disagree with the code, the code wins.
- `fdroid/` holds the draft F-Droid metadata.

## Rules

- `net/Protocol.kt` is the contract between controller and secondary. Change the API there first
  and keep its doc comment in sync. During development all devices run the same app version: no
  fallbacks for older controllers or secondaries.
- Keep the invariants from `CONCEPT.md`: display commands are versioned and never go backwards,
  files and notes are keyed by SHA-256, the controller owns the authoritative notes and fetches a
  secondary's edits before giving it another page.
- Pure logic belongs in `core/` with unit tests (`app/src/test/`); Android-only code stays thin around it.
- minSdk is 29 (secondaries), the controller role needs API 34. Guard newer APIs.
- When behaviour visible to users changes, update `mtpv/README.md` and the About dialog
  (`ui/AboutDialog.kt`) as well.
- Commit messages: `MTPV: <what changed>` in one line, for changes to the app.
