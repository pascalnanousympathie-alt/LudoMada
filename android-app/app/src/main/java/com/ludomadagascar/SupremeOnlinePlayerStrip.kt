package com.ludomadagascar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ludomadagascar.supreme.ui.PlayerBadge

/**
 * Ludo Supreme player status strip bound to the live LudoMada game state.
 * The existing GLES3/GLB board and physical dice stay in place.
 */
@Composable
internal fun SupremeOnlinePlayerStrip(
    state: SupremeOnlineUiState?,
    modifier: Modifier = Modifier
) {
    if (state == null || state.players.isEmpty()) return
    MaterialTheme {
        Row(
            modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            state.players.forEach { player ->
                PlayerBadge(
                    player = player,
                    isActive = player.id == state.activePlayerIndex,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
