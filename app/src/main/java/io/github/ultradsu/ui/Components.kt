package io.github.ultradsu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.ultradsu.data.UiStyle
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miDark
import top.yukonga.miuix.kmp.theme.lightColorScheme as miLight
import top.yukonga.miuix.kmp.basic.Text as MiText
import top.yukonga.miuix.kmp.basic.TextButton as MiButton
import top.yukonga.miuix.kmp.basic.Card as MiCard
import top.yukonga.miuix.kmp.basic.Switch as MiSwitch
import top.yukonga.miuix.kmp.basic.TextField as MiField
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiButtonDefaults
import top.yukonga.miuix.kmp.extra.SuperDialog

val LocalUi = staticCompositionLocalOf { UiStyle.MIUIX }
private val mi: Boolean @Composable get() = LocalUi.current == UiStyle.MIUIX
val pageColor: Color @Composable get() = if (mi) MiuixTheme.colorScheme.background else MaterialTheme.colorScheme.background
val inkColor: Color @Composable get() = if (mi) MiuixTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground
val mutedColor: Color @Composable get() = inkColor.copy(alpha = .58f)
val accent: Color @Composable get() = if (mi) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary

@Composable fun UltraTheme(style: UiStyle, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalUi provides style) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFFB5C5FF))
            else lightColorScheme(primary = Color(0xFF315FC4), background = Color(0xFFF8F9FF))) {
            MiuixTheme(colors = if (dark) miDark() else miLight()) { content() }
        }
    }
}

@Composable fun UText(value: String, size: Int = 15, bold: Boolean = false,
    color: Color = inkColor, modifier: Modifier = Modifier) {
    val weight = if (bold) FontWeight.SemiBold else FontWeight.Normal
    if (mi) MiText(value, modifier = modifier, color = color, fontSize = size.sp, fontWeight = weight)
    else Text(value, modifier = modifier, color = color, fontSize = size.sp, fontWeight = weight)
}
@Composable fun UButton(text: String, enabled: Boolean = true, primary: Boolean = false,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (mi) MiButton(text, onClick, modifier, enabled, colors = if (primary)
        MiButtonDefaults.textButtonColorsPrimary() else MiButtonDefaults.textButtonColors())
    else if (primary) Button(onClick, modifier, enabled) { Text(text) }
    else OutlinedButton(onClick, modifier, enabled) { Text(text) }
}
@Composable fun UCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    if (mi) MiCard(modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    } else Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable fun UField(value: String, label: String, enabled: Boolean = true, numeric: Boolean = false,
    onChange: (String) -> Unit) {
    val options = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text)
    if (mi) MiField(value, onChange, Modifier.fillMaxWidth(), label = label, enabled = enabled,
        singleLine = true, keyboardOptions = options)
    else OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text(label) }, singleLine = true, keyboardOptions = options, shape = RoundedCornerShape(16.dp))
}
@Composable fun USwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    if (mi) MiSwitch(checked, onChange, enabled = enabled)
    else Switch(checked, onChange, enabled = enabled)
}
@Composable fun UDialog(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (mi) {
        val visible = remember { mutableStateOf(true) }
        // Callers remove UDialog from composition on cancel/save. Clear its host
        // entry too, otherwise an old overlay can keep intercepting later taps.
        DisposableEffect(visible) { onDispose { visible.value = false } }
        SuperDialog(visible, title = title, onDismissRequest = { visible.value = false; dismiss() }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    } else Dialog(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().imePadding().background(MaterialTheme.colorScheme.surface,
            RoundedCornerShape(28.dp)).padding(24.dp).heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            UText(title, 22, true)
            content()
        }
    }
}
@Composable fun Tag(text: String, color: Color = accent) {
    UText(text, 12, true, color, Modifier.background(color.copy(alpha = .11f), RoundedCornerShape(8.dp))
        .padding(horizontal = 9.dp, vertical = 5.dp))
}
