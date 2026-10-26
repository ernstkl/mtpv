package io.github.ernstkl.mtpv.net

/**
 * HTTP API offered by a secondary (all JSON):
 *
 *   GET  /v1/info          {uuid, name, apiVersion, open}                 (open; also how the controller
 *                                                                          finds secondaries by address scan
 *                                                                          on DEFAULT_PORT where mDNS fails)
 *   POST /v1/pair          {controller, name} -> {requestId, code}        (open; 409 already_paired if
 *                                                                          paired with another controller)
 *   GET  /v1/pair/{id}     {status: pending|allowed|denied|unknown, token} (open; token once, on allowed)
 *
 * All other calls need "Authorization: Bearer <token>" from pairing, else 401 not_paired:
 *
 *   GET  /v1/files         {files: [id, ...]}
 *   PUT  /v1/files/{id}    raw PDF body; 409 hash_mismatch if the SHA-256 differs from {id}
 *   POST /v1/display       {controller, version, fileId, notesKey, page, song, songs, pages, notes, notesRev,
 *                          savedRev} -> state;
 *                          409 stale_version (with current version), 404 unknown_file,
 *                          409 notes_unsaved if the shown page has edits newer than savedRev
 *   GET  /v1/state         {fileId, page, version, controller, tool, fingers, dark}
 *   GET  /v1/notes         {fileId, notesKey, page, baseRev, rev, dirty, strokes}: notes of the shown page
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
 * whose name and password come from its code (core/DirectCode). A secondary that knows the code joins
 * the group when no controller has reached it for a while, so a secondary paired at home finds the
 * controller elsewhere without typing. A secondary never paired has the code typed in.
 *
 * During development all devices run the same app version; there are no fallbacks for older ones.
 */
object Protocol {
    const val SERVICE_TYPE = "_mtpv._tcp"
    const val API_VERSION = 1
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
}

data class Endpoint(val host: String, val port: Int) {
    override fun toString() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}
