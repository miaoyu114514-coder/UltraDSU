// Copyright 2026 UltraDSU contributors
// SPDX-License-Identifier: Apache-2.0
// Optical parameters follow BakaSU and the Apache-2.0 compose-miuix-ui examples.
// See assets/licenses/liquid-glass-NOTICE.txt.
package io.github.ultradsu.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable internal fun LiquidGlassNavigationBar(
    labels: List<String>, icons: List<ImageVector>, selected: Int, backdrop: GraphicsLayer, backdropOrigin: Offset,
    modifier: Modifier = Modifier, onSelect: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dark = isSystemInDarkTheme()
    val primary = accent
    val foreground = inkColor
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val latestSelect by rememberUpdatedState(onSelect)
    val latestSelected by rememberUpdatedState(selected)
    val tabsBackdrop = rememberGraphicsLayer()
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    var barOrigin by remember { mutableStateOf(Offset.Zero) }
    var pressed by remember { mutableStateOf(false) }
    var dragTarget by remember { mutableStateOf<Float?>(null) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val position = remember { Animatable(selected.toFloat()) }
    val target = dragTarget ?: selected.toFloat()
    LaunchedEffect(pressed, target) {
        if (pressed) position.snapTo(target)
        else position.animateTo(target, spring(1f, 1000f, .001f))
    }
    val pressure by animateFloatAsState(if (pressed) 1f else 0f, spring(1f, 1000f), label = "glass pressure")
    val scaleX by animateFloatAsState(if (pressed) 78f / 56f else 1f, spring(.6f, 250f), label = "lens width")
    val scaleY by animateFloatAsState(if (pressed) 78f / 56f else 1f, spring(.7f, 250f), label = "lens height")
    val inset = with(density) { 4.dp.toPx() }
    val cellWidth = ((barSize.width - 2 * inset) / labels.size).coerceAtLeast(0f)
    val rubberTarget = if (pressed && barSize.width > 0)
        with(density) { 4.dp.toPx() } * (dragDistance / barSize.width).coerceIn(-1f, 1f) else 0f
    val rubber by animateFloatAsState(rubberTarget, spring(1f, 300f), label = "bar rubber band")
    // Read the pointer coordinate directly during a gesture. Spring interpolation is
    // reserved for release/accessibility activation, so the lens never trails the finger.
    val displayedPosition = if (pressed) dragTarget ?: selected.toFloat() else position.value
    val physicalIndex = if (isLtr) displayedPosition else labels.lastIndex - displayedPosition
    val lensX = inset + cellWidth * physicalIndex
    val tint = surface.copy(alpha = if (Build.VERSION.SDK_INT >= 31) .4f else .96f)

    fun activate(index: Int) { if (index != latestSelected) latestSelect(index) }

    // 76dp per tab + 4dp padding, matching BakaSU's compact centered capsule.
    Box(modifier.width((labels.size * 76 + 8).dp).height(64.dp)
        .onSizeChanged { barSize = it }
        .onGloballyPositioned { barOrigin = it.positionInRoot() }
        .graphicsLayer { translationX = rubber }
        .pointerInput(labels.size, isLtr) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val cell = ((size.width - 2 * inset) / labels.size).coerceAtLeast(1f)
                fun indexAt(positionX: Float): Float {
                    val logicalX = if (isLtr) positionX else size.width - positionX
                    return ((logicalX - inset) / cell - .5f).coerceIn(0f, labels.lastIndex.toFloat())
                }
                val start = indexAt(down.position.x)
                var finalTarget = start
                var released = false
                pressed = true
                dragTarget = start
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                        dragDistance = change.position.x - down.position.x
                        finalTarget = indexAt(change.position.x)
                        dragTarget = finalTarget
                        change.consume()
                        if (!change.pressed) { released = true; break }
                    }
                    if (released) activate(finalTarget.roundToInt().coerceIn(0, labels.lastIndex))
                } finally {
                    pressed = false
                    dragTarget = null
                    dragDistance = 0f
                }
            }
        }) {
        Box(Modifier.matchParentSize().graphicsLayer {
            val s = 1f + (16.dp.toPx() / size.width.coerceAtLeast(1f)) * pressure
            this.scaleX = s; this.scaleY = s
        }.shadow(8.dp, CircleShape, ambientColor = Color.Black.copy(alpha = .12f),
            spotColor = Color.Black.copy(alpha = .12f)).clip(CircleShape)) {
            GlassBackdrop(backdrop, barOrigin - backdropOrigin, tint, false, pressure, Modifier.matchParentSize())
            GlassRim(if (dark) .20f else .35f, Modifier.matchParentSize())
        }
        GlassTabs(labels, icons, selected, foreground, 1f, Modifier.matchParentSize().selectableGroup(), ::activate)

        // Capture a separate accent copy. The lens samples this copy; it never draws
        // on screen and contributes no duplicate accessibility nodes.
        Box(Modifier.matchParentSize().clearAndSetSemantics {}.drawWithContent {
            tabsBackdrop.record { this@drawWithContent.drawContent() }
        }) {
            Box(Modifier.matchParentSize().clip(CircleShape)) {
                GlassBackdrop(backdrop, barOrigin - backdropOrigin, tint, false, pressure, Modifier.matchParentSize())
            }
            GlassTabs(labels, icons, selected, primary, 1f + .2f * pressure, Modifier.matchParentSize(), null)
        }
        if (cellWidth > 0) {
            Box(Modifier.offset(x = with(density) { lensX.toDp() }, y = 4.dp)
                .width(with(density) { cellWidth.toDp() }).height(56.dp)
                .graphicsLayer {
                    val velocity = position.velocity / 10f
                    this.scaleX = scaleX / (1f - (velocity * .75f).coerceIn(-.2f, .2f))
                    this.scaleY = scaleY * (1f - (velocity * .25f).coerceIn(-.2f, .2f))
                }.clip(CircleShape).clearAndSetSemantics {}) {
                GlassBackdrop(tabsBackdrop, Offset(lensX, inset), Color.Transparent, true, pressure, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(
                    (if (dark) Color.White else Color.Black).copy(alpha = .1f * (1f - pressure))))
                GlassRim(.6f * pressure, Modifier.matchParentSize())
            }
        }
    }
}

@Composable private fun GlassTabs(
    labels: List<String>, icons: List<ImageVector>, selected: Int, color: Color, scale: Float,
    modifier: Modifier, activate: ((Int) -> Unit)?,
) {
    Row(modifier.padding(4.dp)) {
        labels.forEachIndexed { index, label ->
            val interaction = if (activate == null) Modifier else Modifier
                .semantics(mergeDescendants = true) {
                    role = Role.Tab; this.selected = selected == index
                    onClick { activate(index); true }
                }.onKeyEvent {
                    if (it.key in listOf(Key.Enter, Key.NumPadEnter, Key.Spacebar)) {
                        if (it.type == KeyEventType.KeyUp) activate(index)
                        true
                    } else false
                }.focusable()
            Column(Modifier.weight(1f).fillMaxHeight().then(interaction)
                .graphicsLayer { scaleX = scale; scaleY = scale },
                verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icons[index], null, Modifier.size(24.dp), tint = color)
                Text(label, color = color, fontSize = 11.sp, lineHeight = 14.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** Extra sampled space prevents the refraction from reading cropped/transparent edge pixels. */
@Composable private fun GlassBackdrop(
    source: GraphicsLayer, origin: Offset, tint: Color, indicator: Boolean, pressure: Float, modifier: Modifier,
) {
    val sample = rememberGraphicsLayer()
    val lens = if (Build.VERSION.SDK_INT >= 33) remember(indicator) { GlassLensEffect(indicator) } else null
    Canvas(modifier) {
        val padding = 40.dp.toPx()
        sample.record(size = IntSize((size.width + 2 * padding).roundToInt(), (size.height + 2 * padding).roundToInt())) {
            translate(padding - origin.x, padding - origin.y) { drawLayer(source) }
        }
        sample.renderEffect = when {
            Build.VERSION.SDK_INT >= 33 && lens != null -> lens.create(size, padding, density, pressure)
            Build.VERSION.SDK_INT >= 31 && !indicator -> glassBlurEffect(density)
            else -> null
        }
        translate(-padding, -padding) { drawLayer(sample) }
        drawRect(tint)
    }
}

@Composable private fun GlassRim(alpha: Float, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = .65.dp.toPx()
        drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = alpha), Color.Transparent,
            Color.Transparent, Color.White.copy(alpha = alpha * .6f)), start = Offset.Zero,
            end = Offset(size.width, size.height)), topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2), style = Stroke(stroke))
    }
}
