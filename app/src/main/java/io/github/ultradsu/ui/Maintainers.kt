package io.github.ultradsu.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.ultradsu.R

@Composable
fun MaintainersCard() {
    UCard {
        UText("项目维护者", 18, true)
        MaintainerRow("Fish_Neko", R.drawable.maintainer_fish_neko)
        HorizontalDivider(color = mutedColor.copy(alpha = .15f))
        MaintainerRow("GPT-6.1 Sol", R.drawable.maintainer_gpt_sol)
    }
}

@Composable
private fun MaintainerRow(name: String, @DrawableRes avatar: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Image(painterResource(avatar), contentDescription = "$name 的头像",
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(pageColor),
            contentScale = ContentScale.Fit)
        UText(name, 17, true, modifier = Modifier.weight(1f))
    }
}
