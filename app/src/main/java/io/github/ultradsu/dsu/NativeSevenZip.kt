package io.github.ultradsu.dsu

import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer

/** Native liblzma decoding. The shared preparer still validates paths, bytes and images. */
object NativeSevenZip : ArchivePreparer.SevenZipReader {
    override fun read(source: File, consumer: (String, Boolean, Long, InputStream) -> Unit) {
        val archive = Archive.readNew()
        try {
            Archive.readSupportFilterNone(archive)
            Archive.readSupportFormat7zip(archive)
            Archive.readOpenFileName(archive, source.absolutePath.toByteArray(Charsets.UTF_8), 256 * 1024L)
            val buffer = ByteBuffer.allocateDirect(256 * 1024)
            while (true) {
                val entry = Archive.readNextHeader(archive)
                if (entry == 0L) break
                require(Archive.readHasEncryptedEntries(archive) <= 0) { "不支持加密的 7z 压缩包" }
                require(ArchiveEntry.symlink(entry) == null && ArchiveEntry.hardlink(entry) == null) { "7z 中不允许链接" }
                val type = ArchiveEntry.filetype(entry)
                require(type == ArchiveEntry.AE_IFREG || type == ArchiveEntry.AE_IFDIR) { "7z 中包含特殊文件" }
                val name = ArchiveEntry.pathnameUtf8(entry) ?: error("7z 条目没有有效文件名")
                val stream = object : InputStream() {
                    override fun read(): Int {
                        val byte = ByteArray(1)
                        return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
                    }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                        if (length == 0) return 0
                        buffer.clear(); buffer.limit(minOf(length, buffer.capacity()))
                        Archive.readData(archive, buffer)
                        val count = buffer.position()
                        if (count == 0) return -1
                        buffer.flip(); buffer.get(bytes, offset, count)
                        return count
                    }
                }
                consumer(name, type == ArchiveEntry.AE_IFDIR,
                    if (ArchiveEntry.sizeIsSet(entry)) ArchiveEntry.size(entry) else 0, stream)
            }
        } finally { Archive.readFree(archive) }
    }
}
