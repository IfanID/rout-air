package com.rout.air.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rout.air.live.ChatBubble
import com.rout.air.live.VERIFIED_COLOR
import com.rout.air.live.emoji
import com.rout.air.live.nameColor

/**
 * Tumpukan bubble chat melayang di bawah logo overlay Live.
 * Bubble baru masuk dari atas, yang lama turun lalu memudar keluar.
 */
@Composable
internal fun ChatBubbleStack(
    bubbles: List<ChatBubble>,
    onGone: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        bubbles.forEach { bubble ->
            key(bubble.id) { ChatBubbleRow(bubble, onGone) }
        }
    }
}

@Composable
private fun ChatBubbleRow(bubble: ChatBubble, onGone: (String) -> Unit) {
    val appear = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }

    // Bubble baru meluncur turun dari atas sambil memudar masuk.
    LaunchedEffect(bubble.id) { appear.animateTo(1f, tween(durationMillis = 200)) }
    LaunchedEffect(bubble.leaving) {
        if (bubble.leaving) {
            fade.animateTo(1f, tween(durationMillis = 150))
            // Setelah animasi selesai, service menghapus bubble dari daftar.
            onGone(bubble.id)
        }
    }

    val accent = Color(bubble.role.nameColor())
    Surface(
        color = Color(0xE60E1418),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = appear.value * (1f - fade.value)
                translationY = (1f - appear.value) * -12.dp.toPx()
                val scale = 1f - 0.08f * fade.value
                scaleX = scale
                scaleY = scale
            },
    ) {
        Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val badge = bubble.role.emoji()
                if (badge.isNotEmpty()) {
                    Text(
                        text = badge,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                Text(
                    text = bubble.author,
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (bubble.verified) {
                    Text(
                        text = "✔",
                        color = Color(VERIFIED_COLOR),
                        fontSize = 9.sp,
                        modifier = Modifier.padding(start = 3.dp),
                    )
                }
            }
            Text(
                text = bubble.text,
                color = Color(0xFFF2F2F2),
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
