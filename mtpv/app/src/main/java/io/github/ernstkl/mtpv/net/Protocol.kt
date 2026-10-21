package io.github.ernstkl.mtpv.net

/**
 * HTTP API offered by a secondary (all JSON):
 *
 *   GET  /v1/info          {uuid, name, apiVersion}                       (open)
 *   POST /v1/pair          {controller, name} -> {requestId, code}        (open)
 *   GET  /v1/pair/{id}     {status: pending|allowed|denied|unknown, token} (open; token once, on allowed)
 *
 * All other calls need "Authorization: Bearer <token>" from pairing, else 401 not_paired:
 *
 *   GET  /v1/files         {files: [id, ...]}
 *   PUT  /v1/files/{id}    raw PDF body; 409 hash_mismatch if the SHA-256 differs from {id}
 *   POST /v1/display       {controller, version, fileId, page, notes, notesRev, savedRev} -> state;
 *                          409 stale_version (with current version), 404 unknown_file,
 *                          409 notes_unsaved if the shown page has edits newer than savedRev
 *   GET  /v1/state         {fileId, page, version, controller}
 *   GET  /v1/notes         {fileId, page, baseRev, rev, dirty, strokes}: notes of the shown page
 *   GET  /v1/input?after=N {seq, events: ["next" | "prev", ...]}; long poll, answers as soon as
 *                          the secondary has page turns after N, or after INPUT_WAIT_MS with none
 *
 * Pages are 1-based; page 0 (or past the end) shows a blank screen.
 *
 * Notes: the controller holds the authoritative copy and sends a page's strokes with the display
 * command. Before it sends the next command it fetches the shown page's notes (GET /v1/notes) and
 * saves them if dirty, then passes their rev as savedRev.
 */
object Protocol {
    const val SERVICE_TYPE = "_mtpv._tcp"
    const val API_VERSION = 1
    const val DEFAULT_PORT = 8765
    const val INPUT_WAIT_MS = 20_000L

    const val ERR_STALE = "stale_version"
    const val ERR_UNKNOWN_FILE = "unknown_file"
    const val ERR_HASH = "hash_mismatch"
    const val ERR_NOTES_UNSAVED = "notes_unsaved"
    const val ERR_NOT_PAIRED = "not_paired"
}

data class Endpoint(val host: String, val port: Int) {
    override fun toString() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}
