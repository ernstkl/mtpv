package io.github.ernstkl.mtpv.net

/**
 * HTTP API offered by a secondary (all JSON):
 *
 *   GET  /v1/info          {uuid, name, apiVersion}
 *   GET  /v1/files         {files: [id, ...]}
 *   PUT  /v1/files/{id}    raw PDF body; 409 hash_mismatch if the SHA-256 differs from {id}
 *   POST /v1/display       {controller, version, fileId, page} -> state;
 *                          409 stale_version (with current version), 404 unknown_file
 *   GET  /v1/state         {fileId, page, version, controller}
 *   GET  /v1/input?after=N {seq, events: ["next" | "prev", ...]}; long poll, answers as soon as
 *                          the secondary has page turns after N, or after INPUT_WAIT_MS with none
 *
 * Pages are 1-based; page 0 (or past the end) shows a blank screen.
 */
object Protocol {
    const val SERVICE_TYPE = "_mtpv._tcp"
    const val API_VERSION = 1
    const val DEFAULT_PORT = 8765
    const val INPUT_WAIT_MS = 20_000L

    const val ERR_STALE = "stale_version"
    const val ERR_UNKNOWN_FILE = "unknown_file"
    const val ERR_HASH = "hash_mismatch"
}

data class Endpoint(val host: String, val port: Int) {
    override fun toString() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}
