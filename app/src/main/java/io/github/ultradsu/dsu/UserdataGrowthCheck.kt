package io.github.ultradsu.dsu

import io.github.ultradsu.data.UserdataGrowthInfo
import org.json.JSONObject

/** A failed refresh must invalidate old permission to grow or remove a recovery backup. */
internal object UserdataGrowthCheck {
    fun failed(previous: UserdataGrowthInfo?, reason: String, sdk: Int): UserdataGrowthInfo =
        (previous ?: UserdataGrowthInfo()).copy(supported = false, reason = explain(reason.ifBlank { "Unknown failure" }, sdk),
            canRollback = false, canDiscard = false, checkFailed = true, canAssist = false)

    fun read(result: JSONObject, sdk: Int): UserdataGrowthInfo {
        val growth = result.getJSONObject("growth")
        val fs = result.optJSONObject("filesystem") ?: JSONObject()
        return UserdataGrowthInfo(result.optBoolean("supported"), result.optString("assistanceError").ifBlank { explain(result.optString("reason"), sdk) },
            fs.optString("filesystem"), fs.optBoolean("encrypted"), fs.optLong("blockBytes"),
            fs.optLong("filesystemBytes"), growth.optBoolean("exists"), growth.optString("phase"),
            growth.optLong("backupBytes"), growth.optLong("oldBytes"), growth.optLong("newBytes"),
            growth.optBoolean("canRollback"), growth.optBoolean("canDiscard"),
            canAssist = result.optBoolean("canAssist"), assistancePhase = result.optJSONObject("assistance")?.optString("phase", "NONE") ?: "NONE",
            assisted = fs.optBoolean("assisted"))
    }

    private fun explain(reason: String, sdk: Int): String = when {
        reason.isBlank() -> ""
        reason.contains("内部多槽标准布局") -> "当前槽使用旧版或非标准布局，暂不支持保留数据扩容。" +
            if (sdk < 36) "当前系统 API $sdk；此功能还要求 Android 16+（API 36）及 arm64。" else ""
        reason.contains("Android 16+") -> "此功能暂支持 Android 16+（API 36）的 arm64 设备，当前系统 API $sdk，仍需通过本机预检。"
        reason.contains("Encrypted ext4") -> "元数据加密的 ext4 暂未适配保留数据扩容。"
        reason.contains("Unsupported metadata") || reason.contains("Legacy metadata") -> "当前槽的元数据加密算法、参数格式或硬件封装密钥暂未适配。"
        reason.contains("KEYSTORE_INVALID_ARGUMENT(-38)") -> "系统 Keystore 拒绝现有槽位密钥（INVALID_ARGUMENT）。可能存在版本绑定冲突，尤其是客体系统版本或安全补丁高于主系统时。当前无法在主系统保留数据扩容，检查不会重建密钥或清空数据。"
        reason.contains("Cannot unseal") -> "无法解封装现有槽位密钥，此环境暂不支持。即使槽能正常启动，主系统也可能因版本或加密接口差异无法使用同一密钥。"
        reason.contains("FBE key records") -> "无法验证本槽的文件加密密钥记录或算法，此环境暂不支持；已有备份仍可回退。"
        reason.contains("CANNOT LINK") -> "系统维护接口不兼容，此环境暂不支持。"
        reason.contains("另一个 UltraDSU") || reason.contains("其他 DSU 安装会话") -> "其他 DSU 操作正在进行，请等待完成后重新检查。"
        reason.contains("请先返回主系统") || reason.contains("请先在设置中开启并授权 Root") -> "请先返回主系统，并在设置中开启和授权 Root，再重新检查。"
        reason.contains("设备没有多槽维护接口") -> "设备没有多槽维护接口，暂不支持保留数据扩容。"
        reason.contains("请先完成现有分区修复") -> "请先完成本槽的分区修复，再重新检查。"
        reason.contains("userdata 当前仍映射") -> "userdata 正被占用，请结束相关操作后重新检查。"
        else -> "检查失败，未能读取最新兼容性与备份状态。请重试；若仍失败，请在设置中开启调试模式，重现后导出日志。"
    }
}
