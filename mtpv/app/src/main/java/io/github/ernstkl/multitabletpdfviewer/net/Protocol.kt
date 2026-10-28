package io.github.ernstkl.multitabletpdfviewer.net

import io.github.ernstkl.multitabletpdfviewer.core.PairCode

/**
 * HTTPS API offered by a secondary (all JSON), TLS 1.3 only, see [Tls]. There is no CA: each device
 * has its own key with a self-signed certificate, and both sides present theirs. The controller
 * accepts a secondary's certificate in one of three ways: pinned (a paired secondary: only the
 * fingerprint stored at pairing), any (GET /v1/info probes: the answer is only a hint, a paired link
 * checks the UUID again over a pinned connection) or first seen (one pairing: the first certificate,
 * then only that one). The secondary takes any client certificate and decides by its fingerprint
 * what the client may do.
 *
 *   GET  /v1/info          {uuid, name, apiVersion, open}               (any client; also how the controller
 *                                                                        finds secondaries by address scan
 *                                                                        on DEFAULT_PORT where mDNS fails)
 *   POST /v1/pair          {controller, name, commit} -> {requestId, nonce}
 *                                                                       (any client; 409 already_paired if
 *                                                                        paired with another controller)
 *   POST /v1/pair/{id}/nonce {nonce} -> {status}                        (the asking client only; 400 bad_nonce)
 *   GET  /v1/pair/{id}     {status: pending|allowed|denied|unknown}     (the asking client only)
 *
 * Pairing is a numeric comparison: both screens show a PAIR_CODE_DIGITS code derived from both
 * certificates as each side sees them in its TLS session and from two nonces (core/PairCode), so
 * with someone in the middle the codes differ. The controller sends a commitment to its nonce Nc
 * (PairCode.commit) first and gets the secondary's nonce Ns, then reveals Nc; the secondary checks it against the
 * commit (else 400 bad_nonce, and the request is gone) and only then shows the request with its
 * code. The asking client is the one with the client certificate of the POST /v1/pair; others get
 * unknown. On Allow the secondary stores the controller's fingerprint, and the controller the
 * secondary's. Nonces and commit are 32 bytes as lowercase hex.
 *
 * All other calls need a paired client certificate, else 401 not_paired:
 *
 *   GET  /v1/files         {files: [id, ...]}
 *   PUT  /v1/files/{id}    raw PDF body; 409 hash_mismatch if the SHA-256 differs from {id}
 *   POST /v1/display       {controller, version, fileId, notesKey, page, song, songs, pages, notes, notesRev,
 *                          savedRev} -> state;
 *                          401 not_paired if controller is not the paired controller of the client certificate;
 *                          409 stale_version (with current version), 404 unknown_file,
 *                          409 notes_unsaved if the shown page has edits newer than savedRev
 *   GET  /v1/state         {fileId, page, version, controller, tool, fingers, dark}: fileId and page as shown,
 *                          so blank ("", 0) from the secondary's start or return to the screen until the
 *                          next display command
 *   GET  /v1/notes         {fileId, notesKey, page, baseRev, rev, dirty, strokes}: notes of the page last
 *                          displayed, also while the screen is blank
 *   POST /v1/tool          {tool, fingers, dark} -> {tool, fingers, dark}: note tool (a NoteTool name),
 *                          finger switch and dark pages; 400 bad_tool
 *   GET  /v1/input?after=N {seq, events: ["next" | "prev" | "tool:<name>" | "fingers:on|off" | "dark:on|off",
 *                          ...]}; long poll, answers
 *                          as soon as the secondary has input after N, or after INPUT_WAIT_MS with none
 *   POST /v1/direct        {code} -> {code}: the code of the controller's direct network (16 digits),
 *                          "" if it never starts one; 400 bad_code otherwise. Sent once per
 *                          connection and when it changes. See Direct below.
 *
 * On screen only: both roles use the network only while MTPV is on their device's screen. In the
 * background a secondary's server is stopped and its service unannounced, and a controller drops
 * its links, discovery and direct network; back on screen they start again and catch up.
 *
 * Presence: a secondary paired with a controller is off the market. Only a secondary without one is
 * open: TXT attribute open=1 and info open=true; controllers list only open secondaries as nearby,
 * and pairing asks from another controller are refused until that controller is removed on the
 * secondary.
 *
 * JSON request bodies larger than MAX_JSON_BODY (or with a negative Content-Length) get 413 too_large.
 *
 * Pages are 1-based; page 0 (or past the end) shows a blank screen. song of songs (both 1-based) and
 * pages place the page in the set: its song among the songs shown and the song's page count, for the
 * label in the corner; all 0 with a blank page.
 *
 * Notes: the controller holds the authoritative copy and sends a page's strokes with the display
 * command. Notes belong to a song, not to the PDF: notesKey names the song's notes ("" with a blank
 * page), fileId only the PDF to render, so two songs with the same PDF have notes of their own. The
 * secondary hands the notesKey back with its notes, which files them under the song they were made
 * on. Before it sends the next command the controller fetches the shown page's notes
 * (GET /v1/notes) and saves them if dirty, then passes their rev as savedRev.
 *
 * Tool: the controller holds the note tool, finger switch and dark pages last picked on any screen
 * and sends all three with POST /v1/tool (resent when GET /v1/state reports others). A pick on a
 * secondary becomes a "tool:<name>", "fingers:on|off" or "dark:on|off" input event.
 *
 * Direct: where controller and secondary share no Wi-Fi, the controller starts a Wi-Fi Direct group
 * whose name and password come from its code (core/DirectCode), as soon as a secondary is out of
 * reach (core/DirectAuto). A secondary that knows the code joins the group when no controller has
 * reached it for a few seconds, or at once without a Wi-Fi network, so a secondary paired at home
 * finds the controller elsewhere without typing. A secondary never paired has the code typed in.
 *
 * During development all devices run the same app version; there are no fallbacks for older ones.
 */
object Protocol {
    const val SERVICE_TYPE = "_mtpv._tcp"
    const val API_VERSION = 2
    const val DEFAULT_PORT = 8765
    const val INPUT_WAIT_MS = 20_000L
    const val MAX_JSON_BODY = 16L * 1024 * 1024

    const val ERR_STALE = "stale_version"
    const val ERR_UNKNOWN_FILE = "unknown_file"
    const val ERR_HASH = "hash_mismatch"
    const val ERR_BAD_TOOL = "bad_tool"
    const val ERR_NOTES_UNSAVED = "notes_unsaved"
    const val ERR_NOT_PAIRED = "not_paired"
    const val ERR_TOO_LARGE = "too_large"
    const val ERR_BAD_CODE = "bad_code"
    const val ERR_ALREADY_PAIRED = "already_paired"
    const val ERR_BAD_NONCE = "bad_nonce"

    const val PAIR_CODE_DIGITS = PairCode.DIGITS
}

data class Endpoint(val host: String, val port: Int) {
    override fun toString() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}
