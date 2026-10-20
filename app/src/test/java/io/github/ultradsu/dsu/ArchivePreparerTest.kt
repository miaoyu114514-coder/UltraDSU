package io.github.ultradsu.dsu

import io.github.ultradsu.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.tukaani.xz.XZOutputStream
import org.tukaani.xz.LZMA2Options
import java.io.File
import java.util.zip.*

class ArchivePreparerTest {
    @get:Rule val temp = TemporaryFolder()
    private fun raw(erofs: Boolean = false) = ByteArray(8192).also {
        if (erofs) byteArrayOf(0xe2.toByte(), 0xe1.toByte(), 0xf5.toByte(), 0xe0.toByte()).copyInto(it, 1024)
        else { it[1080] = 0x53; it[1081] = 0xef.toByte() }
    }
    private fun zip(vararg images: Pair<String, ByteArray>): File = temp.newFile("${java.util.UUID.randomUUID()}.zip").also { file ->
        ZipOutputStream(file.outputStream()).use { zip -> images.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }
    private fun prepare(file: File, target: String = "system", requireSystem: Boolean = true): List<PartitionImage> =
        ArchivePreparer().prepare(file, file.name, temp.newFolder(), target, requireSystem)
    @Test fun `single arbitrary named image in zip becomes system`() {
        val result = prepare(zip("nested/PixelOS.img" to raw()))
        assertEquals("system", result.single().partition); assertEquals(8192L, result.single().bytes)
    }
    @Test fun `archive progress follows each raw entry and the complete installer package`() {
        val file = zip("system.img" to raw(), "product.img" to raw(true))
        val events = mutableListOf<OperationProgress>()
        val preparer = ArchivePreparer(onProgress = events::add)
        val output = temp.newFolder()
        val images = preparer.prepare(file, file.name, output)
        for (name in listOf("system.img", "product.img")) {
            val completed = events.first { it.message == "已解包 $name" }
            assertEquals(8192L, completed.totalBytes)
            assertEquals(8192L, completed.bytes)
        }
        preparer.installerZip(images, output)
        assertEquals(16384L, events.last().bytes)
        assertEquals(16384L, events.last().totalBytes)
    }
    @Test fun `compressed entries never use compressed sizes as expanded image totals`() {
        val compressed = java.io.ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw()) } }.toByteArray()
        for (name in listOf("system.img.gz", "system.img")) {
            val file = zip(name to compressed)
            val events = mutableListOf<OperationProgress>()
            ArchivePreparer(onProgress = events::add).prepare(file, file.name, temp.newFolder())
            assertEquals(8192L, events.last().bytes)
            assertNull(events.last().fraction)
        }
    }
    @Test fun `prepared gzip uses stored blocks and preserves exact filesystem bytes`() {
        val source = temp.newFile("system.img").apply { writeBytes(raw(true)) }
        val output = temp.newFolder()
        val prepared = ArchivePreparer().prepare(source, source.name, output).single()
        val gzip = File(output, prepared.fileName)
        assertTrue(gzip.length() >= source.length())
        GZIPInputStream(gzip.inputStream()).use { assertArrayEquals(raw(true), it.readBytes()) }
    }
    @Test fun `multiple zip images keep partition mapping and nested paths`() {
        val result = prepare(zip("images/system.img" to raw(), "images/product.img" to raw(true), "system_ext.img" to raw()))
        assertEquals(setOf("system", "product", "system_ext"), result.map { it.partition }.toSet())
    }
    @Test fun `single replacement uses explicitly chosen partition`() {
        assertEquals("product", prepare(zip("gsi.img" to raw()), "product", false).single().partition)
    }
    @Test fun `unknown and duplicate mappings are rejected`() {
        for (file in listOf(zip("system.img" to raw(), "random.img" to raw()), zip("system_a.img" to raw(), "system_b.img" to raw()))) {
            try { prepare(file); fail() } catch (_: IllegalStateException) { } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun `traversal paths never become output files`() {
        try { prepare(zip("../system.img" to raw())); fail() } catch (_: IllegalArgumentException) { }
        assertFalse(File(temp.root.parentFile, "system.img").exists())
    }
    @Test fun `empty archive does not count as prepared`() {
        try { prepare(zip("readme.txt" to "nothing".toByteArray())); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun `sparse and non-filesystem images are rejected`() {
        val sparse = raw().also { byteArrayOf(0x3a, 0xff.toByte(), 0x26, 0xed.toByte()).copyInto(it) }
        for (bytes in listOf(sparse, ByteArray(8192))) {
            try { prepare(zip("system.img" to bytes)); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun `zip containing compressed image streams is supported`() {
        val compressed = java.io.ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw()) } }.toByteArray()
        assertEquals(8192L, prepare(zip("system.img.gz" to compressed)).single().bytes)
    }
    @Test fun `seven zip extracts multiple images without mixing their data`() {
        val file = temp.newFile("test.7z")
        SevenZOutputFile(file).use { seven ->
            for ((name, data) in listOf("system.img" to raw(), "product.img" to raw(true))) {
                seven.putArchiveEntry(SevenZArchiveEntry().apply { this.name = name; size = data.size.toLong() })
                seven.write(data); seven.closeArchiveEntry()
            }
        }
        assertEquals(setOf("system", "product"), prepare(file).map { it.partition }.toSet())
    }
    @Test fun `tar gzip multiple images are supported`() {
        val file = temp.newFile("images.tar.gz")
        TarArchiveOutputStream(GZIPOutputStream(file.outputStream())).use { tar ->
            for (name in listOf("system.img", "system_ext.img")) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = raw().size.toLong() }); tar.write(raw()); tar.closeArchiveEntry()
            }
        }
        assertEquals(2, prepare(file).size)
    }
    @Test fun `xz and bzip2 single images are supported`() {
        val xz = temp.newFile("system.img.xz"); XZOutputStream(xz.outputStream(), LZMA2Options()).use { it.write(raw()) }
        val bz = temp.newFile("system.img.bz2"); BZip2CompressorOutputStream(bz.outputStream()).use { it.write(raw()) }
        assertEquals(8192L, prepare(xz).single().bytes); assertEquals(8192L, prepare(bz).single().bytes)
    }
    @Test fun `expanded byte budget is enforced`() {
        val file = zip("system.img" to raw())
        try { ArchivePreparer(maxBytes = 4096).prepare(file, file.name, temp.newFolder()); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun `multi partition installer zip contains raw images and correct names`() {
        val file = zip("system.img" to raw(), "product.img" to raw(true)); val output = temp.newFolder()
        val preparer = ArchivePreparer(); val images = preparer.prepare(file, file.name, output)
        val packageFile = preparer.installerZip(images, output)
        ZipFile(packageFile).use { zip ->
            assertEquals(2, zip.size()); assertArrayEquals(raw(), zip.getInputStream(zip.getEntry("system.img")).readBytes())
            assertArrayEquals(raw(true), zip.getInputStream(zip.getEntry("product.img")).readBytes())
        }
    }
    @Test fun `new slot requires system but replacement package may contain subset`() {
        val file = zip("product.img" to raw(), "system_ext.img" to raw())
        try { prepare(file); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(2, prepare(file, requireSystem = false).size)
    }
}
