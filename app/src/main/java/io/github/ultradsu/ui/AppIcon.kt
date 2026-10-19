package io.github.ultradsu.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ultradsu.R

/** Uses the launcher's same foreground, background and resource-qualified Monet colors. */
@Composable internal fun AppIcon(modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(colorResource(R.color.ic_launcher_paper))
        .semantics { contentDescription = "UltraDSU 应用图标" },
        contentAlignment = Alignment.Center) {
        // Adaptive icons include a safe area; enlarge the foreground to match the launcher crop.
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(54.dp))
    }
}
