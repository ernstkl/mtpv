package io.github.ernstkl.multitabletpdfviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private val set = "a".repeat(32)
    private val song = "b".repeat(32)
    private val record = "c".repeat(64)

    private fun filesDir(): File = tmp.newFolder().apply {
        File(this, "notes/$set/$song").mkdirs()
        File(this, "notes/$set/$song/1.json").writeText("""{"rev":1}""")
        File(this, "notes/$set/$song/3.json").writeText("""{"rev":2}""")
        File(this, "notes/$set/$song/3.json.tmp").writeText("half")
        File(this, "sets").mkdirs()
        File(this, "sets/$record.json").writeText("""{"set":1}""")
        File(this, "pdfs").mkdirs()
        File(this, "pdfs/x.pdf").writeText("pdf")
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, bytes) ->
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun roundTripReplacesNotesAndSets() {
        val out = ByteArrayOutputStream()
        val written = Backup.write(out, filesDir(), "{m}", "{s}")
        assertEquals(Backup.Summary(songs = 1, sets = 1), written)

        val target = tmp.newFolder().apply {
            File(this, "notes/old").mkdirs()
            File(this, "notes/old/1.json").writeText("old")
            File(this, "pdfs").mkdirs()
        }
        val staging = File(target, "import")
        val u = Backup.unpack(ByteArrayInputStream(out.toByteArray()), staging)
        assertEquals("{m}", u.manifest)
        assertEquals("{s}", u.settings)
        assertEquals(written, u.summary)
        Backup.replace(target, staging)

        assertFalse(File(target, "notes/old").exists())
        assertEquals("""{"rev":2}""", File(target, "notes/$set/$song/3.json").readText())
        assertFalse(File(target, "notes/$set/$song/3.json.tmp").exists())
        assertEquals("""{"set":1}""", File(target, "sets/$record.json").readText())
        assertTrue(File(target, "pdfs").isDirectory)
        assertFalse(staging.exists())
    }

    @Test
    fun noEntryLeavesItsFolder() {
        listOf(
            "../evil.json", "notes/../../evil.json", "/abs.json", "sets/../x.json",
            "notes/$set/$song/../../../x.json", "pdfs/x.pdf", "shared_prefs/mtpv.xml", "notes/$set/1.json",
        ).forEach { name ->
            assertFalse(name, Backup.isAllowed(name))
            val staging = tmp.newFolder()
            val bad = zip(Backup.MANIFEST to "{}".toByteArray(), name to "x".toByteArray())
            runCatching { Backup.unpack(ByteArrayInputStream(bad), staging) }
                .onSuccess { throw AssertionError("accepted $name") }
                .onFailure { assertTrue(it is Backup.BadBackupException) }
        }
    }

    @Test
    fun needsAManifest() {
        val r = runCatching { Backup.unpack(ByteArrayInputStream(zip("sets/$record.json" to "{}".toByteArray())), tmp.newFolder()) }
        assertTrue(r.exceptionOrNull() is Backup.BadBackupException)
        val notZip = runCatching { Backup.unpack(ByteArrayInputStream("hello".toByteArray()), tmp.newFolder()) }
        assertTrue(notZip.exceptionOrNull() is Backup.BadBackupException)
    }

    @Test
    fun oversizedEntryIsRefused() {
        val big = ByteArray((Backup.MAX_ENTRY + 1).toInt())
        val r = runCatching { Backup.unpack(ByteArrayInputStream(zip(Backup.MANIFEST to big)), tmp.newFolder()) }
        assertTrue(r.exceptionOrNull() is Backup.BadBackupException)
    }

    @Test
    fun settingsAreOptional() {
        val u = Backup.unpack(ByteArrayInputStream(zip(Backup.MANIFEST to "{}".toByteArray())), tmp.newFolder())
        assertNull(u.settings)
        assertEquals(Backup.Summary(0, 0), u.summary)
    }
}
