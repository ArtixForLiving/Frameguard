package com.optimizer.game.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.optimizer.game.ui.theme.AccentPurple
import com.optimizer.game.ui.theme.BoneWhite
import com.optimizer.game.ui.theme.HairlineBorder
import com.optimizer.game.ui.theme.SwitchTrackDark

/**
 * Mechanical switch with crisp rectangular track, hairline border, and zero glow/blur.
 */
@Composable
fun MechSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 24.dp else 2.dp,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "switchThumbOffset"
    )

    val trackColor by animateColorAsState(
        targetValue = if (checked) AccentPurple else SwitchTrackDark,
        animationSpec = tween(durationMillis = 150),
        label = "switchTrackColor"
    )

    val borderColor by animateColorAsState(
        targetValue = if (checked) AccentPurple else HairlineBorder,
        animationSpec = tween(durationMillis = 150),
        label = "switchBorderColor"
    )

    Box(
        modifier = modifier
            .alpha(if (enabled) 1.0f else 0.35f)
            .width(44.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(trackColor)
            .border(1.dp, borderColor, RoundedCornerShape(3.dp))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onCheckedChange(!checked)
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(BoneWhite)
        )
    }
}
