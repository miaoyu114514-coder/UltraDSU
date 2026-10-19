package io.github.ultradsu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ultradsu.dsu.ArchivePreparer
import io.github.ultradsu.dsu.NativeSevenZip
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.*

/** Runs only file preparation in a unique app-cache directory. No su, gsid, slots or reboots. */
@RunWith(AndroidJUnit4::class)
class ArchiveDeviceTest {
    private class SampleComplete : RuntimeException()
    private fun image() = ByteArray(8192).apply { this[1080] = 0x53; this[1081] = 0xef.toByte() }
    private fun temporary(test: (File) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "archive-verification-${UUID.randomUUID()}")
        check(root.mkdir())
        try { test(root) } finally { root.deleteRecursively() }
    }
    @Test fun zipMultiPartitionPreparationAndInstallerPackageWorkOnAndroid() = temporary { root ->
        val source = File(root, "images.zip")
        ZipOutputStream(source.outputStream()).use { zip ->
            for (name in listOf("system.img", "product.img")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(image()); zip.closeEntry()
            }
        }
        val output = File(root, "prepared")
        val preparer = ArchivePreparer()
        val images = preparer.prepare(source, source.name, output)
        assertEquals(setOf("system", "product"), images.map { it.partition }.toSet())
        ZipFile(preparer.installerZip(images, output)).use { zip ->
            assertArrayEquals(image(), zip.getInputStream(zip.getEntry("product.img")).readBytes())
        }
    }
    @Test fun sevenZipDecoderAndSinglePartitionSelectionWorkOnAndroid() = temporary { root ->
        val source = File(root, "image.7z")
        SevenZOutputFile(source).use { seven ->
            seven.putArchiveEntry(SevenZArchiveEntry().apply { name = "Android.img"; size = image().size.toLong() })
            seven.write(image()); seven.closeArchiveEntry()
        }
        val output = File(root, "prepared")
        val prepared = ArchivePreparer(sevenZip = NativeSevenZip).prepare(source, source.name, output, "system_ext", false).single()
        assertEquals("system_ext", prepared.partition)
        GZIPInputStream(File(output, prepared.fileName).inputStream()).use { assertArrayEquals(image(), it.readBytes()) }
    }
    @Test fun nativeSevenZipKeepsPartitionBoundariesAndRejectsTraversal() = temporary { root ->
        for (unsafe in listOf(false, true)) {
            val source = File(root, "multi-$unsafe.7z")
            SevenZOutputFile(source).use { seven ->
                for (name in listOf("system.img", if (unsafe) "../product.img" else "product.img")) {
                    val bytes = image().apply { if (name.contains("product")) this[4096] = 42 }
                    seven.putArchiveEntry(SevenZArchiveEntry().apply { this.name = name; size = bytes.size.toLong() })
                    seven.write(bytes); seven.closeArchiveEntry()
                }
            }
            val output = File(root, "native-$unsafe")
            try {
                val prepared = ArchivePreparer(sevenZip = NativeSevenZip).prepare(source, source.name, output)
                assertFalse(unsafe)
                for (entry in prepared) GZIPInputStream(File(output, entry.fileName).inputStream()).use {
                    assertArrayEquals(image().apply { if (entry.partition == "product") this[4096] = 42 }, it.readBytes())
                }
            } catch (e: IllegalArgumentException) { if (!unsafe) throw e }
        }
    }
    @Test fun comparePreparationCompressionCostOnAndroid() = temporary { root ->
        val source = File(root, "system.img")
        val random = java.util.Random(42)
        val block = ByteArray(1024 * 1024)
        source.outputStream().buffered().use { out ->
            repeat(32) { index ->
                random.nextBytes(block)
                // Approximate an EROFS image containing compressed data and padding.
                java.util.Arrays.fill(block, block.size * 3 / 4, block.size, 0.toByte())
                if (index == 0) { block[1080] = 0x53; block[1081] = 0xef.toByte() }
                out.write(block)
            }
        }
        for ((label, level) in listOf("previous" to Deflater.DEFAULT_COMPRESSION, "stored" to Deflater.NO_COMPRESSION)) {
            val start = android.os.SystemClock.elapsedRealtime()
            val output = File(root, label)
            val prepared = ArchivePreparer(compressionLevel = level).prepare(source, source.name, output).single()
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            GZIPInputStream(File(output, prepared.fileName).inputStream(), 256 * 1024).use { decoded ->
                source.inputStream().use { expected ->
                    val actualHash = java.security.MessageDigest.getInstance("SHA-256")
                    val expectedHash = java.security.MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(256 * 1024)
                    while (true) { val n = decoded.read(buffer); if (n < 0) break; actualHash.update(buffer, 0, n) }
                    while (true) { val n = expected.read(buffer); if (n < 0) break; expectedHash.update(buffer, 0, n) }
                    assertArrayEquals(expectedHash.digest(), actualHash.digest())
                }
            }
            android.util.Log.i("UltraDSU-Benchmark", "$label: 32 MiB in $elapsed ms")
        }
    }
    @Test fun profileSelectedSevenZipReadOnly() = temporary { root ->
        val uri = InstrumentationRegistry.getArguments().getString("sourceUri") ?: return@temporary
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(root, "selected.7z")
        context.contentResolver.openInputStream(android.net.Uri.parse(uri))!!.use { input ->
            source.outputStream().buffered(1024 * 1024).use { output -> input.copyTo(output, 1024 * 1024) }
        }
            val hashes = mutableListOf<ByteArray>()
            for (native in listOf(false, true)) {
                val start = android.os.SystemClock.elapsedRealtime()
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                val consume: (java.io.InputStream) -> Unit = { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (bytes < 64 * 1024 * 1024) {
                        val n = input.read(buffer); if (n < 0) break
                        digest.update(buffer, 0, n); bytes += n
                    }
                }
                if (native) {
                    try { NativeSevenZip.read(source) { name, directory, input ->
                        if (!directory && name.endsWith(".img")) { consume(input); throw SampleComplete() }
                    } } catch (_: SampleComplete) { }
                } else org.apache.commons.compress.archivers.sevenz.SevenZFile.builder().setFile(source).get().use { seven ->
                    while (true) {
                        val entry = seven.nextEntry ?: break
                        if (!entry.isDirectory && entry.name.endsWith(".img")) {
                            consume(object : java.io.InputStream() {
                                override fun read() = seven.read()
                                override fun read(b: ByteArray, off: Int, len: Int) = seven.read(b, off, len)
                            }); break
                        }
                    }
                }
                assertEquals(64 * 1024 * 1024L, bytes)
                hashes += digest.digest()
                android.util.Log.i("UltraDSU-Benchmark", "selected-7z native=$native: ${bytes / 1048576} MiB in ${android.os.SystemClock.elapsedRealtime() - start} ms")
            }
            assertArrayEquals(hashes[0], hashes[1])
            val start = android.os.SystemClock.elapsedRealtime()
            val prepared = ArchivePreparer(sevenZip = NativeSevenZip).prepare(source, "selected.7z", File(root, "full-preparation"))
            assertTrue(prepared.any { it.partition == "system" && it.bytes >= 64 * 1024 * 1024L })
            android.util.Log.i("UltraDSU-Benchmark", "selected-7z full preparation: ${prepared.sumOf { it.bytes } / 1048576} MiB in ${android.os.SystemClock.elapsedRealtime() - start} ms")
    }
}
