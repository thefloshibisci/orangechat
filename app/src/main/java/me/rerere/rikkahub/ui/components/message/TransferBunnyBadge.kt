package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.interaction.ChatInteraction

@Composable
internal fun TransferBunnyBadge(card: ChatInteraction, size: Dp = 50.dp) {
    val expression = when (card.status) { "accepted" -> "heart"; "returned" -> "returned"; else -> "smile" }
    val icon = when (card.theme) {
        "sakura" -> when (expression) {
            "heart" -> R.drawable.transfer_bunny_heart_sakura
            "returned" -> R.drawable.transfer_bunny_returned_sakura
            else -> R.drawable.transfer_bunny_smile_sakura
        }
        "lavender" -> when (expression) {
            "heart" -> R.drawable.transfer_bunny_heart_lavender
            "returned" -> R.drawable.transfer_bunny_returned_lavender
            else -> R.drawable.transfer_bunny_smile_lavender
        }
        else -> when (expression) {
            "heart" -> R.drawable.transfer_bunny_heart_ice
            "returned" -> R.drawable.transfer_bunny_returned_ice
            else -> R.drawable.transfer_bunny_smile_ice
        }
    }
    val description = when (expression) { "heart" -> "比心兔 · 已收款"; "returned" -> "挤眼兔 · 已退回"; else -> "微笑兔 · 转账" }
    Box(Modifier.size(size).background(Color.White.copy(alpha = .65f), RoundedCornerShape(16.dp))
        .border(1.5.dp, Color.White.copy(alpha = .95f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
        // Each vector preserves the white face and theme-tinted features; no flat tint.
        Image(painterResource(icon), description, Modifier.size(size - 8.dp))
    }
}
