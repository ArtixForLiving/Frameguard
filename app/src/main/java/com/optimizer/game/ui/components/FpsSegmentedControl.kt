package com.optimizer.game.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optimizer.game.ui.theme.BoneWhite
import com.optimizer.game.ui.theme.CanvasDark
import com.optimizer.game.ui.theme.HairlineBorder
import com.optimizer.game.ui.theme.ObsidianSurface
import com.optimizer.game.ui.theme.ZincMuted

/**
 * Mechanical segmented bar for selecting target frame pacing cap (30, 40, 45, 60 FPS).
 */
@Composable
fun FpsSegmentedControl(
    selectedFps: Int,
    onFpsSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    options: List<Int> = listOf(60, 90, 120)
) {
    Row(
        modifier = modifier
            .alpha(if (enabled) 1.0f else 0.35f)
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(ObsidianSurface)
            .border(1.dp, HairlineBorder, RoundedCornerShape(4.dp))
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { index, fps ->
            val isSelected = fps == selectedFps

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (isSelected) BoneWhite else ObsidianSurface)
                    .clickable(
                        enabled = enabled,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onFpsSelected(fps)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = fps.toString(),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = if (isSelected) CanvasDark else ZincMuted,
                    textAlign = TextAlign.Center
                )
            }

            if (index < options.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(HairlineBorder)
                )
            }
        }
    }
}
