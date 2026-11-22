package com.optimizer.game.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optimizer.game.ui.theme.AccentPurple
import com.optimizer.game.ui.theme.BoneWhite
import com.optimizer.game.ui.theme.CanvasDark
import com.optimizer.game.ui.theme.HairlineBorder
import com.optimizer.game.ui.theme.NominalGreen
import com.optimizer.game.ui.theme.ObsidianSurface
import com.optimizer.game.ui.theme.ZincMuted

data class GameAppItem(
    val id: String,
    val name: String,
    val packageName: String,
    val recommendedFps: Int = 60,
    val maxSupportedFps: Int = 60,
    val recommendationReason: String = "Engine native cap 60 FPS · 60Hz lock provides 1:1 VSYNC & lowest thermals",
    val isInstalled: Boolean = true
) {
    companion object {
        fun createWithPreset(
            id: String,
            name: String,
            packageName: String,
            isInstalled: Boolean = true
        ): GameAppItem {
            val (recFps, maxFps, reason) = when {
                packageName.contains("warframemobile", ignoreCase = true) -> Triple(
                    60,
                    60,
                    "Engine native cap 60 FPS · 60Hz lock provides 1:1 VSYNC & lowest thermals"
                )
                packageName.contains("genshin", ignoreCase = true) || packageName.contains("yuanshen", ignoreCase = true) -> Triple(
                    60,
                    60,
                    "Engine hard-capped at 60 FPS · 60Hz lock eliminates thermal throttling"
                )
                packageName.contains("wutheringwaves", ignoreCase = true) || packageName.contains("kurogame", ignoreCase = true) -> Triple(
                    60,
                    60,
                    "Android engine capped at 60 FPS · 60Hz lock ensures sustained GPU stability"
                )
                packageName.contains("igfit", ignoreCase = true) -> Triple(
                    60,
                    60,
                    "Lightweight client capped at 60 FPS · 60Hz lock provides flat 16.6ms frametime"
                )
                packageName.contains("mobile.legends", ignoreCase = true) || packageName.contains("mobilelegends", ignoreCase = true) -> Triple(
                    90,
                    120,
                    "Supports up to 120 FPS · 90Hz lock recommended for low touch latency & thermals"
                )
                packageName.contains("pubg", ignoreCase = true) || packageName.contains("tencent.ig", ignoreCase = true) -> Triple(
                    90,
                    120,
                    "Supports up to 120 FPS · 90Hz lock recommended for competitive latency & thermals"
                )
                packageName.contains("freefire", ignoreCase = true) -> Triple(
                    90,
                    120,
                    "Supports High-FPS (90/120) · 90Hz lock delivers fluid response & steady frametimes"
                )
                else -> Triple(
                    60,
                    60,
                    "Engine native cap 60 FPS · 60Hz lock provides 1:1 VSYNC & lowest thermals"
                )
            }
            return GameAppItem(
                id = id,
                name = name,
                packageName = packageName,
                recommendedFps = recFps,
                maxSupportedFps = maxFps,
                recommendationReason = reason,
                isInstalled = isInstalled
            )
        }
    }
}

/**
 * Utilitarian target game selector with inline mechanical accordion drawer.
 */
@Composable
fun AppSelectorDrawer(
    selectedApp: GameAppItem,
    availableApps: List<GameAppItem>,
    isDrawerOpen: Boolean,
    onToggleDrawer: () -> Unit,
    onSelectApp: (GameAppItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CanvasDark)
    ) {
        // Main target row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Text(
                    text = "TARGET APPLICATION",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                    color = ZincMuted
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = selectedApp.name,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BoneWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = selectedApp.packageName,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = ZincMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // CHANGE / CLOSE Button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(ObsidianSurface)
                    .border(1.dp, HairlineBorder, RoundedCornerShape(4.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onToggleDrawer()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isDrawerOpen) "CLOSE" else "CHANGE",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    color = BoneWhite
                )
            }
        }

        // Accordion Drawer
        AnimatedVisibility(
            visible = isDrawerOpen,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ObsidianSurface)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(HairlineBorder)
                )

                availableApps.forEachIndexed { index, app ->
                    val isActive = app.packageName == selectedApp.packageName

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isActive) CanvasDark else ObsidianSurface)
                            .clickable {
                                onSelectApp(app)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = app.name,
                                    fontFamily = FontFamily.SansSerif,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isActive) BoneWhite else ZincMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(CanvasDark)
                                        .border(1.dp, HairlineBorder, RoundedCornerShape(2.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "${app.recommendedFps} FPS",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isActive) AccentPurple else ZincMuted
                                    )
                                }
                                if (app.isInstalled) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(NominalGreen.copy(alpha = 0.12f))
                                            .border(1.dp, NominalGreen.copy(alpha = 0.35f), RoundedCornerShape(2.dp))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = "INSTALLED",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = NominalGreen
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = app.packageName,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = ZincMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = if (isActive) "[ ACTIVE ]" else "[ SELECT ]",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp,
                            color = if (isActive) BoneWhite else ZincMuted
                        )
                    }

                    if (index < availableApps.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(HairlineBorder)
                        )
                    }
                }
            }
        }

        // Section bottom border
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(HairlineBorder)
        )
    }
}
