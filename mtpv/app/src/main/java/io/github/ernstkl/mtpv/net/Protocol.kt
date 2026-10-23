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
 *   GET  /v1/state         {fileId, page, version, controller, tool, fingers}
 *   GET  /v1/notes         {fileId, page, baseRev, rev, dirty, strokes}: notes of the shown page
 *   POST /v1/tool          {tool, fingers} -> {tool, fingers}: note tool (a NoteTool name) and finger
 *                          switch; 400 bad_tool
 *   GET  /v1/input?after=N {seq, events: ["next" | "prev" | "tool:<name>" | "fingers:on|off" | "front", ...]};
 *                          long poll, answers
 *                          as soon as the secondary has input after N, or after INPUT_WAIT_MS with none
 *   POST /v1/front         {} -> {shown}: MTPV came (back) to the front on another screen of the setup.
 *                          If MTPV is not on screen here, the secondary posts a "tap to show"
 *                          notification (Android lets no app jump to the front from the background);
 *                          shown tells whether it was on screen already.
 *
 * Pages are 1-based; page 0 (or past the end) shows a blank screen.
 *
 * Notes: the controller holds the authoritative copy and sends a page's strokes with the display
 * command. Before it sends the next command it fetches the shown page's notes (GET /v1/notes) and
 * saves them if dirty, then passes their rev as savedRev.
 *
 * Tool: the controller holds the note tool and finger switch last picked on any screen and sends
 * both with POST /v1/tool (resent when GET /v1/state reports others). A pick on a secondary becomes a
 * "tool:<name>" or "fingers:on|off" input event.
 *
 * Front: when MTPV comes (back) to the front on the controller, it sends POST /v1/front to every
 * secondary; on a secondary, that secondary reports a "front" input event and the controller passes
 * it on to itself and the other secondaries. Coming to the front by tapping the "tap to show"
 * notification is no trigger, so the screens do not wake each other in turn.
 *
 * During development all devices run the same app version; there are no fallbacks for older ones.
 */
object Protocol {
    const val SERVICE_TYPE = "_mtpv._tcp"
    const val API_VERSION = 1
    const val DEFAULT_PORT = 8765
    const val INPUT_WAIT_MS = 20_000L

    const val ERR_STALE = "stale_version"
    const val ERR_UNKNOWN_FILE = "unknown_file"
    const val ERR_HASH = "hash_mismatch"
    const val ERR_BAD_TOOL = "bad_tool"
    const val ERR_NOTES_UNSAVED = "notes_unsaved"
    const val ERR_NOT_PAIRED = "not_paired"
}

data class Endpoint(val host: String, val port: Int) {
    override fun toString() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}
