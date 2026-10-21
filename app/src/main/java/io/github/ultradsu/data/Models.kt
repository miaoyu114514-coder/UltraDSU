package io.github.ultradsu.data

import java.util.UUID

const val GIB = 1024L * 1024 * 1024
enum class UiStyle { MIUIX, MD3 }
enum class SlotState(val label: String) {
    DRAFT("待准备"), PREPARED("镜像已准备"), SUBMITTED("已交给系统 · 待核实"),
    INSTALLED("已安装"), INCOMPLETE("安装未完成"), MISSING("系统中未发现")
}
data class PartitionImage(val partition: String, val fileName: String, val bytes: Long, val sourceName: String)
data class InstalledPartition(val name: String, val bytes: Long = 0, val mapped: Boolean = false)
data class PartitionInventory(val partitions: List<InstalledPartition> = emptyList(), val pending: List<String> = emptyList())

object PartitionRules {
    val readOnly = setOf("system", "system_ext", "product", "vendor", "odm", "vendor_dlkm", "odm_dlkm", "system_dlkm")
    fun fromFile(name: String): String? {
        var base = name.substringAfterLast('/').lowercase(java.util.Locale.ROOT)
        for (suffix in listOf(".gz", ".xz", ".bz2")) if (base.endsWith(suffix)) base = base.removeSuffix(suffix)
        base = base.removeSuffix(".img").removeSuffix("_a").removeSuffix("_b")
        return base.takeIf { it in readOnly }
    }
    fun assign(images: List<PartitionImage>, singleTarget: String = "system", requireSystem: Boolean = true): List<PartitionImage> {
        require(images.isNotEmpty()) { "压缩包中没有找到 .img 镜像" }
        require(images.size <= 32) { "镜像数量过多" }
        require(singleTarget in readOnly) { "不支持的目标分区：$singleTarget" }
        val mapped = if (images.size == 1) images.map { it.copy(partition = singleTarget) }
        else images.map { it.copy(partition = fromFile(it.sourceName) ?: error("无法识别分区：${it.sourceName}；多镜像包请使用 system.img、product.img 等分区名")) }
        require(mapped.map { it.partition }.distinct().size == mapped.size) { "压缩包中有重复目标分区（包括 _a / _b 同名变体），请整理后重新选择" }
        require(!requireSystem || mapped.any { it.partition == "system" }) { "新槽的多分区包必须包含 system.img" }
        return mapped.sortedBy { if (it.partition == "system") "0" else it.partition }
    }
}

data class DsuSlot(
    val id: String = UUID.randomUUID().toString(),
    val slotName: String,
    val displayName: String,
    val userdataGiB: Int = 8,
    val sourceUri: String = "",
    val sourceName: String = "",
    val state: SlotState = SlotState.DRAFT,
    val systemBytes: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val images: List<PartitionImage> = emptyList(),
    val pendingImages: List<PartitionImage> = emptyList(),
    val pendingSourceName: String = "",
    val preparedFilesCleaned: Boolean = false,
) {
    val userdataBytes: Long get() = userdataGiB * GIB
}

data class DeviceStatus(
    val sdk: Int,
    val installerAvailable: Boolean,
    val multiSlot: Boolean = sdk >= 30,
    val internalMultiSlot: Boolean = sdk >= 35,
    val verified: Boolean = false,
    val installedSlots: List<String> = emptyList(),
    val occupiedSlots: List<String> = emptyList(),
    val activeSlot: String = "",
    val running: Boolean = false,
    val enabled: Boolean = false,
    val installing: Boolean = false,
    val detail: String = "",
)

data class UserdataGrowthInfo(
    val supported: Boolean = false, val reason: String = "", val filesystem: String = "",
    val encrypted: Boolean = false, val actualBytes: Long = 0, val filesystemBytes: Long = 0,
    val exists: Boolean = false, val phase: String = "", val backupBytes: Long = 0,
    val oldBytes: Long = 0, val newBytes: Long = 0, val canRollback: Boolean = false, val canDiscard: Boolean = false,
    val checkFailed: Boolean = false,
    val canAssist: Boolean = false, val assistancePhase: String = "NONE", val assisted: Boolean = false,
)

data class GuestAssistanceInfo(val slot: String, val phase: String)

data class UserdataBackupEntry(
    val slot: String, val kind: String, val bytes: Long = 0, val modified: Long = 0,
    val canDelete: Boolean = false, val reason: String = "", val phase: String = "",
    val revision: String = "",
) {
    val id get() = "$slot:$kind"
    val current get() = kind == "current"
}

object SlotRules {
    // Used for filesystem identifiers and shell arguments. User-facing labels are independent.
    fun validName(value: String) = value.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,31}"))
    fun validate(name: String, userdata: String, sdk: Int, existing: List<DsuSlot>): String? = when {
        !validName(name) -> "槽标识须为 1–32 位英文字母、数字、下划线或短横线，首位为字母或数字"
        sdk < 30 && name != "dsu" -> "Android 10 仅支持默认槽 dsu"
        existing.any { it.slotName == name } -> "此槽标识已存在，请选择其他名称"
        userdata.toIntOrNull() !in 1..512 -> "userdata 大小须为 1–512 GiB 的整数"
        else -> null
    }
}

object Shell {
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    fun install(uri: String, slot: DsuSlot, sdk: Int): String = buildString {
        append("am start -W -n com.android.dynsystem/com.android.dynsystem.VerificationActivity")
        append(" -a android.os.image.action.START_INSTALL -d ").append(quote(uri))
        append(" --el KEY_SYSTEM_SIZE ").append(slot.systemBytes)
        append(" --el KEY_USERDATA_SIZE ").append(slot.userdataBytes)
        if (sdk >= 30) append(" --es KEY_DSU_SLOT ").append(quote(slot.slotName))
        append(" --ez KEY_ENABLE_WHEN_COMPLETED false")
    }
}
