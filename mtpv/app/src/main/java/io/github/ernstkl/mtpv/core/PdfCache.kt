package io.github.ernstkl.mtpv.core

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class HashMismatchException(expected: String, actual: String) :
    IOException("hash mismatch: expected $expected, got $actual")

/**
 * PDFs stored by content: the ID is the SHA-256 of the file. Lives in filesDir, which Android
 * does not clear under storage pressure (unlike cacheDir).
 */
class PdfCache(context: Context) {
    private val dir = File(context.filesDir, "pdfs").apply { mkdirs() }
    private val incoming = File(dir, ".incoming").apply { mkdirs() }

    fun file(id: String): File {
        require(isValidId(id)) { "invalid file id" }
        return File(dir, "$id.pdf")
    }

    fun has(id: String): Boolean = isValidId(id) && file(id).isFile

    fun ids(): List<String> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".pdf") }.orEmpty().map { it.name.removeSuffix(".pdf") }

    fun touch(id: String) {
        if (has(id)) file(id).setLastModified(System.currentTimeMillis())
    }

    /**
     * Streams [input] into the cache and returns its ID. Reads exactly [length] bytes when it is
     * known (needed on a keep-alive HTTP connection), otherwise until EOF. If [expectedId] is given
     * and the content does not match, nothing is stored.
     */
    fun store(input: InputStream, expectedId: String? = null, length: Long = -1): String {
        val tmp = File.createTempFile("incoming", ".part", incoming)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var remaining = if (length >= 0) length else Long.MAX_VALUE
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) {
                        if (length >= 0) throw IOException("upload ended early")
                        break
                    }
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedId != null && expectedId != id) throw HashMismatchException(expectedId, id)
            val target = file(id)
            if (target.exists()) {
                touch(id)
            } else if (!tmp.renameTo(target)) {
                throw IOException("could not store $id")
            }
            return id
        } finally {
            tmp.delete()
        }
    }

    fun evict(keep: Set<String>) {
        val entries = ids().map { id -> file(id).let { Eviction.Entry(id, it.length(), it.lastModified()) } }
        Eviction.victims(entries, keep, MAX_FILES, MAX_BYTES).forEach { file(it).delete() }
    }

    companion object {
        private val ID = Regex("[0-9a-f]{64}")
        private const val MAX_FILES = 100
        private const val MAX_BYTES = 2L * 1024 * 1024 * 1024

        fun isValidId(id: String) = ID.matches(id)
    }
}
