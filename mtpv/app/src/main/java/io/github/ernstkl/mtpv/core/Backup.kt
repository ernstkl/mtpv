package io.github.ernstkl.mtpv.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * All app data worth keeping, as one zip file: the notes (filesDir/notes), the set records
 * (filesDir/sets: order, ticks, notes IDs), the settings, and a manifest with the format version.
 * Not the PDFs, which come from the set folders again, nor pairings (the device keys) or folder
 * grants, which do not survive a reinstall anyway. The caller writes and reads the JSON of [MANIFEST] and [SETTINGS].
 */
object Backup {
    const val FORMAT = 1
    const val MANIFEST = "manifest.json"
    const val SETTINGS = "settings.json"
    val DIRS = listOf("notes", "sets")

    /** A page of a song's notes, or a set record; nothing else is accepted, so no entry can leave its folder. */
    private val NOTES_PAGE = Regex("notes/[0-9a-f]{32}/[0-9a-f]{32}/[0-9]{1,6}\\.json")
    private val SET_RECORD = Regex("sets/[0-9a-f]{64}\\.json")

    const val MAX_ENTRY = 16L * 1024 * 1024
    const val MAX_TOTAL = 512L * 1024 * 1024

    class BadBackupException(message: String) : IOException(message)

    /** What a backup holds: [songs] with notes, [sets] records. */
    data class Summary(val songs: Int, val sets: Int)

    class Unpacked(val manifest: String, val settings: String?, val summary: Summary)

    fun isAllowed(name: String) = name == MANIFEST || name == SETTINGS || NOTES_PAGE.matches(name) || SET_RECORD.matches(name)

    /** Writes the data below [filesDir] to [out]. */
    fun write(out: OutputStream, filesDir: File, manifest: String, settings: String): Summary {
        val zip = ZipOutputStream(out)
        fun put(name: String, bytes: ByteArray) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
        put(MANIFEST, manifest.toByteArray())
        put(SETTINGS, settings.toByteArray())
        val names = DIRS.flatMap { d ->
            File(filesDir, d).walkTopDown().filter { it.isFile }.map { it.relativeTo(filesDir).invariantSeparatorsPath }.toList()
        }.filter(::isAllowed).sorted() // leaves out half-written .tmp files
        names.forEach { name ->
            // A page written meanwhile is replaced by renaming, so this reads either version whole.
            val bytes = runCatching { File(filesDir, name).readBytes() }.getOrNull() ?: return@forEach
            put(name, bytes)
        }
        zip.finish()
        return summaryOf(names)
    }

    /**
     * Unpacks a backup into [staging] (emptied first), checking every entry; nothing outside
     * [staging] is touched. Throws [BadBackupException] for anything that is not a backup of ours.
     */
    fun unpack(input: InputStream, staging: File): Unpacked {
        staging.deleteRecursively()
        staging.mkdirs()
        var manifest: String? = null
        var settings: String? = null
        val names = mutableListOf<String>()
        var total = 0L
        val zip = ZipInputStream(input)
        while (true) {
            val e = zip.nextEntry ?: break
            if (e.isDirectory) continue
            if (!isAllowed(e.name)) throw BadBackupException("unexpected entry ${e.name.take(80)}")
            val bytes = readCapped(zip)
            total += bytes.size
            if (total > MAX_TOTAL) throw BadBackupException("too large")
            when (e.name) {
                MANIFEST -> manifest = String(bytes)
                SETTINGS -> settings = String(bytes)
                else -> {
                    val f = File(staging, e.name)
                    f.parentFile?.mkdirs()
                    f.writeBytes(bytes)
                    names += e.name
                }
            }
        }
        return Unpacked(manifest ?: throw BadBackupException("no $MANIFEST"), settings, summaryOf(names))
    }

    /** Replaces the data below [filesDir] by what [unpack] left in [staging]. */
    fun replace(filesDir: File, staging: File) {
        DIRS.forEach { d ->
            val target = File(filesDir, d)
            target.deleteRecursively()
            val from = File(staging, d)
            if (from.isDirectory) {
                if (!from.renameTo(target)) throw IOException("could not move $d into place")
            } else {
                target.mkdirs()
            }
        }
        staging.deleteRecursively()
    }

    private fun readCapped(input: InputStream): ByteArray {
        val bytes = input.readNBytesCompat(MAX_ENTRY + 1)
        if (bytes.size > MAX_ENTRY) throw BadBackupException("entry too large")
        return bytes
    }

    /** InputStream.readNBytes needs Android 13. */
    private fun InputStream.readNBytesCompat(max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size.toLong(), max - out.size()).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun summaryOf(names: List<String>) = Summary(
        songs = names.filter { it.startsWith("notes/") }.map { it.substringBeforeLast('/') }.distinct().size,
        sets = names.count { it.startsWith("sets/") },
    )
}
