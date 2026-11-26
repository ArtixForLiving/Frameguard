package com.optimizer.game.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optimizer.game.optimizer.GameOptimizerEngine
import com.optimizer.game.ui.theme.BoneWhite
import com.optimizer.game.ui.theme.HairlineBorder
import com.optimizer.game.ui.theme.NominalGreen
import com.optimizer.game.ui.theme.SignalRed
import com.optimizer.game.ui.theme.TerminalBackground
import com.optimizer.game.ui.theme.ZincMuted

/**
 * Compact 4-line monospace terminal strip displaying live Shizuku shell stdout/stderr.
 */
@Composable
fun ShellTerminalView(
    logs: List<GameOptimizerEngine.ShellLogEntry>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier,
    isExpanded: Boolean = false,
    title: String = "CLOSED-LOOP SHELL // STDOUT"
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TerminalBackground)
    ) {
        // Border top
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(HairlineBorder)
        )

        // Terminal Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
                color = ZincMuted,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = "CLR",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = ZincMuted,
                modifier = Modifier.clickable { onClearLogs() }
            )
        }

        // Border divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(HairlineBorder)
        )

        // Log list viewport (approx 4 lines = 84dp, or flexible min 120dp when expanded)
        val listModifier = if (isExpanded) {
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 240.dp)
                .padding(horizontal = 16.dp, vertical = 6.dp)
        } else {
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .padding(horizontal = 16.dp, vertical = 6.dp)
        }

        LazyColumn(
            state = listState,
            modifier = listModifier
        ) {
            items(logs) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val (prefix, prefixColor, textColor) = when (entry.type) {
                        GameOptimizerEngine.LogType.COMMAND -> Triple("$", BoneWhite, Color(0xFFD4D4D0))
                        GameOptimizerEngine.LogType.STDOUT -> Triple(">", NominalGreen, Color(0xFFB5B5B0))
                        GameOptimizerEngine.LogType.ERROR -> Triple("!", SignalRed, SignalRed)
                        GameOptimizerEngine.LogType.INFO -> Triple("#", ZincMuted, ZincMuted)
                    }

                    Text(
                        text = prefix,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = prefixColor
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = entry.message,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
