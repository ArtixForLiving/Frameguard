package com.optimizer.game.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optimizer.game.ui.theme.BoneWhite
import com.optimizer.game.ui.theme.HairlineBorder
import com.optimizer.game.ui.theme.ObsidianSurface
import com.optimizer.game.ui.theme.ZincMuted

/**
 * 3-column telemetry grid showing real-time Thermal, Display refresh rate, and Active Profile.
 */
@Composable
fun TelemetryGrid(
    thermalText: String,
    thermalSubText: String,
    displayText: String,
    displaySubText: String,
    profileText: String,
    profileSubText: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(ObsidianSurface)
        ) {
            // Col 1: THERMAL
            TelemetryCell(
                title = "THERMAL",
                value = thermalText,
                subtitle = thermalSubText,
                modifier = Modifier.weight(1f)
            )

            VerticalHairlineDivider()

            // Col 2: DISPLAY
            TelemetryCell(
                title = "DISPLAY",
                value = displayText,
                subtitle = displaySubText,
                modifier = Modifier.weight(1f)
            )

            VerticalHairlineDivider()

            // Col 3: PROFILE
            TelemetryCell(
                title = "PROFILE",
                value = profileText,
                subtitle = profileSubText,
                modifier = Modifier.weight(1f)
            )
        }

        // Bottom border
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(HairlineBorder)
        )
    }
}

@Composable
private fun TelemetryCell(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(14.dp)
    ) {
        Text(
            text = title,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            color = ZincMuted
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = BoneWhite
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            color = ZincMuted
        )
    }
}

@Composable
private fun VerticalHairlineDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(HairlineBorder)
    )
}
