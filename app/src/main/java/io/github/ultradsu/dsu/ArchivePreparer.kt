package io.github.ultradsu.dsu

import io.github.ultradsu.data.*
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.*
import java.util.UUID
import java.util.zip.*

/** Pure JVM archive preparation. Entry names are never used as filesystem output paths. */
class ArchivePreparer(private val progress: (String) -> Unit = {}, private val checkpoint: () -> Unit = {},
    private val maxBytes: Long = 128 * GIB, private val compressionLevel: Int = Deflater.NO_COMPRESSION,
    private val sevenZip: SevenZipReader? = null) {
    fun interface SevenZipReader {
        fun read(source: File, consumer: (String, Boolean, InputStream) -> Unit)
    }
    private var total = 0L
    private var entries = 0
    private var lastReport = 0L
    private val results = mutableListOf<PartitionImage>()
    private fun safeName(name: String) {
        require(name.isNotEmpty() && name.length <= 1024 && !name.contains('\\') && !name.startsWith('/') &&
            !name.contains(':') && name.split('/').none { it == ".." } && name.none { it.code < 32 }) { "压缩包含不安全路径：$name" }
    }
    private fun imageName(name: String) = listOf(".img", ".img.gz", ".img.xz", ".img.bz2").any { name.lowercase().endsWith(it) }
    private fun entry(name: String) { checkpoint(); safeName(name); require(++entries <= 4096) { "压缩包条目过多" } }
    private fun budget(bytes: Long) {
        checkpoint(); total = Math.addExact(total, bytes)
        require(total <= maxBytes) { "解包数据超过 ${maxBytes / GIB} GiB 限制" }
    }
    private fun decoded(source: InputStream): BufferedInputStream {
        val input = source.buffered(1024 * 1024); input.mark(8)
        val magic = ByteArray(6); val n = input.read(magic); input.reset()
        val stream = when {
            n >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() -> GZIPInputStream(input, 1024 * 1024)
            n == 6 && magic.contentEquals(byteArrayOf(0xfd.toByte(), 0x37, 0x7a, 0x58, 0x5a, 0)) -> XZInputStream(input, 131072)
            n >= 3 && String(magic, 0, 3, Charsets.US_ASCII) == "BZh" -> BZip2CompressorInputStream(input, true)
            else -> input
        }
        return stream.buffered(1024 * 1024)
    }
    private fun consume(name: String, input: InputStream, output: File) {
        require(results.size < 32) { "镜像数量超过 32" }
        val file = File(output, "${UUID.randomUUID()}.img.gz")
        var size = 0L
        decoded(input).use { image ->
            image.mark(4096)
            val header = ByteArray(4096); var read = 0
            while (read < header.size) { val n = image.read(header, read, header.size - read); if (n < 0) break; read += n }
            image.reset()
            require(read == header.size) { "$name：镜像过小" }
            require(!header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x3a, 0xff.toByte(), 0x26, 0xed.toByte()))) { "$name 是 sparse 镜像，请先用 simg2img 转 raw" }
            val ext4 = header[1080] == 0x53.toByte() && header[1081] == 0xef.toByte()
            val erofs = header.copyOfRange(1024, 1028).contentEquals(byteArrayOf(0xe2.toByte(), 0xe1.toByte(), 0xf5.toByte(), 0xe0.toByte()))
            require(ext4 || erofs) { "$name：仅接受 raw ext4 / EROFS 分区镜像，不接受 boot、super 或整机固件" }
            // Gzip stored blocks retain system-installer compatibility without recompressing
            // an already compressed filesystem (EROFS). Root can stream the same files.
            object : GZIPOutputStream(file.outputStream().buffered(1024 * 1024), 256 * 1024) {
                init { def.setLevel(compressionLevel) }
            }.use { gzip ->
                val buffer = ByteArray(256 * 1024)
                val started = System.nanoTime()
                while (true) {
                    checkpoint(); val n = image.read(buffer); if (n < 0) break
                    budget(n.toLong()); size += n; gzip.write(buffer, 0, n)
                    if (System.currentTimeMillis() - lastReport > 1000) {
                        check(output.usableSpace > 256 * 1024 * 1024) { "存储空间不足" }
                        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
                        progress("解包 $name · ${size / (1024 * 1024)} MiB · ${"%.1f".format(java.util.Locale.ROOT, size / 1048576.0 / seconds)} MiB/s"); lastReport = System.currentTimeMillis()
                    }
                }
            }
        }
        require(size % 4096 == 0L) { "$name：镜像大小未按 4096 字节对齐" }
        results += PartitionImage("", file.name, size, name)
    }
    private fun borrowed(input: InputStream) = object : FilterInputStream(input) { override fun close() {} }
    private fun header(input: InputStream, limit: Int): ByteArray {
        val bytes = ByteArray(limit); var count = 0
        while (count < limit) { val n = input.read(bytes, count, limit - count); if (n < 0) break; count += n }
        return bytes.copyOf(count)
    }
    fun prepare(source: File, sourceName: String, output: File, singleTarget: String = "system", requireSystem: Boolean = true): List<PartitionImage> {
        require(output.mkdirs() || output.isDirectory) { "无法创建准备目录" }
        val signature = source.inputStream().use { header(it, 6) }
        when {
            signature.size >= 4 && signature[0] == 0x50.toByte() && signature[1] == 0x4b.toByte() -> {
                ZipFile.builder().setFile(source).get().use { zip ->
                    val items = zip.entries
                    while (items.hasMoreElements()) {
                        val e = items.nextElement(); entry(e.name)
                        require(!e.isUnixSymlink) { "压缩包不能包含符号链接" }
                        if (!e.isDirectory && imageName(e.name)) {
                            require(zip.canReadEntryData(e)) { "不支持或已加密的 ZIP 条目：${e.name}" }
                            zip.getInputStream(e).use { raw ->
                                val crc = CRC32(); val checked = CheckedInputStream(raw, crc)
                                consume(e.name, borrowed(checked), output)
                                if (e.crc >= 0) require(crc.value == e.crc) { "ZIP 校验失败：${e.name}" }
                            }
                        }
                    }
                }
            }
            signature.contentEquals(byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c)) -> {
                if (sevenZip != null) sevenZip.read(source) { name, directory, stream ->
                    entry(name)
                    if (!directory && imageName(name)) consume(name, borrowed(stream), output)
                    else if (!directory) {
                        val buffer = ByteArray(65536)
                        while (true) { checkpoint(); val n = stream.read(buffer); if (n < 0) break; budget(n.toLong()) }
                    }
                } else SevenZFile.builder().setFile(source).setMaxMemoryLimitKiB(131072).get().use { seven ->
                    while (true) {
                        val e = seven.nextEntry ?: break; val name = e.name ?: error("7z 条目没有文件名")
                        entry(name)
                        if (!e.isDirectory && imageName(name)) {
                            val stream = object : InputStream() {
                                override fun read() = seven.read()
                                override fun read(b: ByteArray, off: Int, len: Int) = seven.read(b, off, len)
                            }
                            consume(name, stream, output)
                        } else if (!e.isDirectory) {
                            val buffer = ByteArray(65536)
                            while (true) { val n = seven.read(buffer); if (n < 0) break; budget(n.toLong()) }
                        }
                    }
                }
            }
            else -> decoded(source.inputStream()).use { stream ->
                stream.mark(512); val header = header(stream, 512); stream.reset()
                val tar = sourceName.lowercase().let { it.endsWith(".tar") || it.endsWith(".tar.gz") || it.endsWith(".tgz") || it.endsWith(".tar.xz") || it.endsWith(".txz") || it.endsWith(".tar.bz2") || it.endsWith(".tbz2") } ||
                    (header.size >= 262 && String(header, 257, 5, Charsets.US_ASCII) == "ustar")
                if (tar) TarArchiveInputStream(stream).use { archive ->
                    while (true) {
                        val e = archive.nextEntry ?: break; entry(e.name)
                        require(!e.isSymbolicLink && !e.isLink) { "TAR 中不允许链接" }
                        if (e.isFile && imageName(e.name)) consume(e.name, borrowed(archive), output)
                        else {
                            val buffer = ByteArray(65536)
                            while (true) { val n = archive.read(buffer); if (n < 0) break; budget(n.toLong()) }
                        }
                    }
                } else consume(sourceName, borrowed(stream), output)
            }
        }
        return PartitionRules.assign(results, singleTarget, requireSystem)
    }
    fun installerZip(images: List<PartitionImage>, directory: File): File {
        val target = File(directory, "package.zip")
        ZipOutputStream(target.outputStream().buffered(1024 * 1024)).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            for (image in images) {
                checkpoint(); progress("打包系统安装器分区：${image.partition}")
                zip.putNextEntry(ZipEntry("${image.partition}.img"))
                GZIPInputStream(File(directory, image.fileName).inputStream()).use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) { checkpoint(); val n = input.read(buffer); if (n < 0) break
                        check(directory.usableSpace > 256 * 1024 * 1024) { "存储空间不足" }; zip.write(buffer, 0, n) }
                }
                zip.closeEntry()
            }
        }
        return target
    }
}
