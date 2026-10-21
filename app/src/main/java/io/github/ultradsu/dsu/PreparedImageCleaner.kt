package io.github.ultradsu.dsu

import io.github.ultradsu.data.DsuSlot
import io.github.ultradsu.data.SlotState
import java.io.File
import java.nio.file.Files

/** Only app-private prepared copies. Never opens a source URI, DSU image or userdata backup. */
class PreparedImageCleaner(private val imagesRoot: File) {
    fun clean(slot: DsuSlot, installationConfirmed: Boolean): Long {
        if (!installationConfirmed || slot.state != SlotState.INSTALLED || slot.pendingImages.isNotEmpty()) return 0
        require(slot.id.matches(Regex("[a-fA-F0-9-]{36}"))) { "无效的准备镜像标识" }
        check(!Files.isSymbolicLink(imagesRoot.toPath())) { "拒绝清理符号链接镜像目录" }
        val root = imagesRoot.canonicalFile
        val parent = File(root, slot.id)
        check(parent.canonicalFile == parent.absoluteFile) { "拒绝清理符号链接槽目录" }
        val groups = slot.images.mapNotNull { image ->
            if (image.fileName.isEmpty()) null else {
                val parts = image.fileName.split('/')
                require(parts.size == 2 && parts[0].matches(Regex("(prepared|update)_[a-fA-F0-9-]{36}")) &&
                    parts[1].matches(Regex("[a-fA-F0-9-]{36}\\.img\\.gz"))) { "无效的准备镜像路径" }
                parts[0]
            }
        }.distinct().map { File(parent, it) }
        val candidates = groups + File(root, "${slot.id}.img.gz")
        val deletion = mutableListOf<File>()
        fun inspect(file: File) {
            check(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile == file.absoluteFile) { "拒绝清理符号链接准备文件" }
            if (!file.exists()) return
            if (file.isDirectory) {
                val children = file.listFiles() ?: error("无法读取准备文件目录")
                children.forEach(::inspect)
            } else check(file.isFile) { "准备目录包含非普通文件" }
            deletion += file
        }
        // Validate the entire plan before deleting anything; never follow links.
        candidates.forEach(::inspect)
        var released = 0L
        deletion.forEach { file ->
            val bytes = if (file.isFile) file.length() else 0
            check(file.delete()) { "安装已完成，但准备文件清理失败，可在刷新时重试" }
            released += bytes
        }
        return released
    }
}
