package io.github.ultradsu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.ultradsu.dsu.PreparationState
import io.github.ultradsu.dsu.TaskOutcome
import io.github.ultradsu.dsu.progressBytes
import kotlin.math.roundToInt

/** One app-local layer shared by the main tabs and slot editor; never a system overlay. */
@Composable
internal fun OperationBubble(
    preparation: PreparationState, operationLabel: String?, onCancel: () -> Unit, onDismiss: () -> Unit,
) {
    val service = preparation.busy || (operationLabel == null && preparation.finishedAt > 0)
    if (!service && operationLabel == null) return
    val key = if (service) preparation.taskId else "operation:$operationLabel"
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    var dragX by rememberSaveable { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    var dragY by rememberSaveable { mutableFloatStateOf(with(density) { 64.dp.toPx() }) }
    var bubbleSize by remember { mutableStateOf(IntSize.Zero) }
    val busy = if (service) preparation.busy else true
    val outcome = if (service) preparation.outcome else null
    val fraction = if (service) preparation.fraction else null
    val title = if (service) preparation.title.ifBlank { "镜像任务" } else operationLabel.orEmpty()
    val status = when (outcome) {
        TaskOutcome.SUCCESS -> "已完成"
        TaskOutcome.FAILED -> "失败"
        TaskOutcome.CANCELLED -> "已取消"
        null -> fraction?.let { "${(it * 100).toInt()}%" } ?: "处理中"
    }
    val color = when (outcome) {
        TaskOutcome.SUCCESS -> Color(0xFF25977B)
        TaskOutcome.FAILED -> Color(0xFFC13D42)
        else -> accent
    }
    val icon = when (outcome) {
        TaskOutcome.SUCCESS -> Icons.Outlined.CheckCircle
        TaskOutcome.FAILED -> Icons.Outlined.ErrorOutline
        TaskOutcome.CANCELLED -> Icons.Outlined.Cancel
        null -> Icons.Outlined.Sync
    }
    BackHandler(enabled = expanded) { expanded = false }

    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(12.dp)) {
        val maxX = (with(density) { maxWidth.toPx() } - bubbleSize.width).coerceAtLeast(0f)
        val maxY = (with(density) { maxHeight.toPx() } - bubbleSize.height).coerceAtLeast(0f)
        val dragLimits by rememberUpdatedState(Offset(maxX, maxY))
        Box(Modifier.align(Alignment.TopEnd)
            .offset { IntOffset(dragX.coerceIn(-maxX, 0f).roundToInt(), dragY.coerceIn(0f, maxY).roundToInt()) }
            .widthIn(max = maxWidth).width(if (expanded) 360.dp else 236.dp)
            .onSizeChanged { bubbleSize = it }
            .pointerInput(Unit) {
                detectDragGestures { change, amount ->
                    change.consume()
                    dragX = (dragX + amount.x).coerceIn(-dragLimits.x, 0f)
                    dragY = (dragY + amount.y).coerceIn(0f, dragLimits.y)
                }
            }.animateContentSize()) {
            if (!expanded) {
                Surface(onClick = { expanded = true }, shape = RoundedCornerShape(24.dp), color = pageColor,
                    shadowElevation = 8.dp, border = BorderStroke(1.dp, color.copy(alpha = .35f)),
                    modifier = Modifier.semantics {
                        contentDescription = "展开任务进度"
                        stateDescription = "$title，$status"
                    }) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
                            UText(title, 13, true, modifier = Modifier.weight(1f), maxLines = 1)
                            UText(status, 12, true, color)
                        }
                        TaskProgressBar(fraction, busy, color)
                    }
                }
            } else UCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
                    UText(title, 17, true, modifier = Modifier.weight(1f), maxLines = 1)
                    IconButton(onClick = { expanded = false }) {
                        Icon(Icons.Outlined.ExpandLess, "收起任务进度", tint = inkColor)
                    }
                    if (!busy) IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, "关闭任务结果", tint = inkColor)
                    }
                }
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (service && preparation.slotName.isNotEmpty()) UText(preparation.slotName, 13, color = mutedColor)
                    UText(if (service) preparation.message else "正在执行操作，请稍候…", 14)
                    TaskProgressBar(fraction, busy, color)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        UText(if (busy && fraction != null) "当前阶段进度" else if (busy) "此阶段暂无可量化进度" else "任务结果",
                            12, color = mutedColor)
                        UText(status, 13, true, color)
                    }
                    if (service && preparation.bytes > 0) {
                        UText(if (preparation.totalBytes > 0 && fraction != null)
                            "${progressBytes(preparation.bytes)} / ${progressBytes(preparation.totalBytes)}"
                        else "已处理 ${progressBytes(preparation.bytes)}", 13, color = mutedColor)
                    }
                    if (service && busy && !preparation.installation) UButton("取消准备", onClick = onCancel)
                }
            }
        }
    }
}

@Composable
private fun TaskProgressBar(fraction: Float?, busy: Boolean, color: Color) {
    val modifier = Modifier.fillMaxWidth().height(5.dp)
    if (fraction == null && busy) LinearProgressIndicator(modifier = modifier, color = color,
        trackColor = color.copy(alpha = .12f))
    else LinearProgressIndicator(progress = { fraction ?: 0f }, modifier = modifier, color = color,
        trackColor = color.copy(alpha = .12f))
}
