package com.ludomadagascar.supreme.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ludomadagascar.supreme.model.Player

@Composable
fun PlayerBadge(
    player: Player,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "player_active_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isActive) 1.05f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Surface(
        modifier = modifier
            .scale(pulseScale)
            .border(
                width = if (isActive) 2.5.dp else 1.dp,
                color = if (isActive) player.color.primaryColor else Color(0x33FFFFFF),
                shape = RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) Color(0xFF263238) else Color(0xFF1E272C),
        shadowElevation = if (isActive) 8.dp else 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Color indicator dot
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(player.color.lightColor, player.color.primaryColor)
                        )
                    )
            )

            Column {
                Text(
                    text = player.name,
                    color = if (isActive) Color.White else Color(0xFFB0BEC5),
                    fontSize = 13.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
                )
                Text(
                    text = if (player.hasWon) "Finished #${player.rank} 🏆" else "Terminé: ${player.finishedTokensCount}/4",
                    color = if (player.hasWon) Color(0xFFFFD54F) else Color(0xFF90A4AE),
                    fontSize = 11.sp
                )
            }
        }
    }
}
