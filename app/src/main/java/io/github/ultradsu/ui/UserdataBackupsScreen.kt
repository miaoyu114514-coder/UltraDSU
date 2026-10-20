package io.github.ultradsu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.ultradsu.AppState
import io.github.ultradsu.AppViewModel
import io.github.ultradsu.data.UserdataBackupEntry
import io.github.ultradsu.dsu.progressBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable internal fun UserdataBackupsScreen(
    model: AppViewModel, state: AppState, locked: Boolean, focusSlot: String?,
    onBack: () -> Unit, backEnabled: Boolean, readReady: Boolean,
    confirm: (String, String, () -> Unit) -> Unit,
) {
    var requested by remember { mutableStateOf(false) }
    LaunchedEffect(readReady, locked, state.root) {
        if (readReady && !locked && state.root && !requested) {
            requested = true; model.loadUserdataBackups()
        }
    }
    BackHandler(enabled = backEnabled, onBack = onBack)
    Column(Modifier.fillMaxSize().background(pageColor).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = backEnabled) { Icon(Icons.Outlined.ArrowBack, "返回", tint = inkColor) }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                UText("userdata 备份管理", 21, true)
                UText(focusSlot ?: "全部系统槽", 13, color = mutedColor)
            }
        }
        UserdataBackupsContent(
            entries = state.userdataBackups.filter { focusSlot == null || it.slot == focusSlot },
            loaded = state.userdataBackupsLoaded, enabled = state.root && !locked && readReady,
            error = state.userdataBackupsError, message = state.userdataBackupsMessage,
            onRefresh = model::loadUserdataBackups,
            onDelete = { item ->
                confirm("删除 ${item.slot} 的 userdata 备份",
                    "将永久删除这份独立备份，预计释放 ${progressBytes(item.bytes)}。删除后不能再用它回退，请先确认系统和数据正常。\n槽当前使用的 userdata 与系统镜像保留。") {
                    model.deleteUserdataBackup(item)
                }
            },
        )
    }
}

/** Shared by miuix and MD3; destructive actions require a fresh successful inventory. */
@Composable internal fun UserdataBackupsContent(
    entries: List<UserdataBackupEntry>, loaded: Boolean, enabled: Boolean,
    error: String?, message: String?, onRefresh: () -> Unit, onDelete: (UserdataBackupEntry) -> Unit,
) {
    LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 16.dp,
            bottom = 28.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "explanation") {
            UCard {
                UText("在用分区始终保留", 18, true)
                UText("每个槽当前绑定的 userdata 均不可删除，未启动的槽同样受保护。只清理独立备份；扩容失败后用于恢复的备份会继续保留。", 13, color = mutedColor)
                UButton("刷新备份列表", enabled, modifier = Modifier.fillMaxWidth(), onClick = onRefresh)
            }
        }
        if (error != null) item(key = "error") {
            UCard { UText("未能更新备份状态", 16, true); UText(error, 13, color = Color(0xFFC13D42)) }
        }
        if (message != null) item(key = "result") { UCard { UText(message, 14, color = accent) } }
        if (loaded && entries.isEmpty() && error == null) item(key = "empty") {
            UCard { UText("没有可管理的 userdata 或备份。", 14, color = mutedColor) }
        }
        if (!loaded && error == null) item(key = "unread") {
            UText("正在读取；也可以点击上方按钮重试。", 13, color = mutedColor)
        }
        items(entries, key = { it.id }) { entry ->
            UCard {
                UText(entry.slot, 17, true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    UText(if (entry.current) "当前 userdata" else "扩容备份", 15, true, modifier = Modifier.weight(1f))
                    Tag(if (entry.current) "在用" else if (entry.canDelete) "未使用" else "受保护",
                        if (entry.current || !entry.canDelete) mutedColor else accent)
                }
                UText(if (entry.bytes > 0) progressBytes(entry.bytes) else "容量未知", 14)
                if (entry.modified > 0) UText("更新时间：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(entry.modified))}", 12, color = mutedColor)
                UText(entry.reason, 13, color = mutedColor)
                UButton(if (entry.current) "当前分区不可删除" else if (!entry.canDelete) "恢复备份受保护" else "删除备份",
                    enabled && loaded && error == null && !entry.current && entry.canDelete,
                    modifier = Modifier.fillMaxWidth()) { onDelete(entry) }
            }
        }
    }
}
