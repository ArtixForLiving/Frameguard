package com.optimizer.game.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optimizer.game.shizuku.ShizukuState
import com.optimizer.game.ui.components.AppSelectorDrawer
import com.optimizer.game.ui.components.FpsSegmentedControl
import com.optimizer.game.ui.components.MechSwitch
import com.optimizer.game.ui.components.ShellTerminalView
import com.optimizer.game.ui.components.TelemetryGrid
import com.optimizer.game.ui.theme.AccentPurple
import com.optimizer.game.ui.theme.BoneWhite
import com.optimizer.game.ui.theme.CanvasDark
import com.optimizer.game.ui.theme.GesturePill
import com.optimizer.game.ui.theme.HairlineBorder
import com.optimizer.game.ui.theme.NominalGreen
import com.optimizer.game.ui.theme.ObsidianSurface
import com.optimizer.game.ui.theme.ProgressTrack
import com.optimizer.game.ui.theme.SignalRed
import com.optimizer.game.ui.theme.ZincMuted
import com.optimizer.game.viewmodel.MainViewModel

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current

    val shizukuState by viewModel.shizukuState.collectAsState()
    val isBoostActive by viewModel.isBoostActive.collectAsState()
    val selectedApp by viewModel.selectedApp.collectAsState()
    val availableApps by viewModel.availableApps.collectAsState()
    val isAppDrawerOpen by viewModel.isAppDrawerOpen.collectAsState()
    val targetFps by viewModel.targetFps.collectAsState()
    val isDozeEnabled by viewModel.isDozeEnabled.collectAsState()
    val isCompiling by viewModel.isCompiling.collectAsState()
    val compileProgress by viewModel.compileProgress.collectAsState()
    val compileDone by viewModel.compileDone.collectAsState()
    val thermalValue by viewModel.thermalValue.collectAsState()
    val thermalSubText by viewModel.thermalSubText.collectAsState()
    val pairedRefreshRate by viewModel.pairedRefreshRate.collectAsState()
    val currentDisplayHz by viewModel.currentDisplayHz.collectAsState()
    val logs by viewModel.logs.collectAsState()

    val isConnected = shizukuState == ShizukuState.CONNECTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasDark)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // =====================================================================
        // 1. APP HEADER (FRAMEGUARD + BUILD 104 + SHIZUKU STATUS PILL)
        // =====================================================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CanvasDark)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "FRAMEGUARD",
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = BoneWhite
                )
                Text(
                    text = "BUILD 104",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                    color = ZincMuted,
                    modifier = Modifier.padding(bottom = 1.dp)
                )
            }

            // Clickable Shizuku Status Pill
            val pillDotColor = if (isConnected) NominalGreen else SignalRed
            val pillLabel = when (shizukuState) {
                ShizukuState.CONNECTED -> "UID 2000"
                ShizukuState.NO_PERMISSION -> "NO PERM"
                ShizukuState.UNAVAILABLE -> "UNBOUND"
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(ObsidianSurface)
                    .border(1.dp, HairlineBorder, RoundedCornerShape(4.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        viewModel.onShizukuPillClicked()
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(pillDotColor)
                )
                Text(
                    text = pillLabel,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    color = BoneWhite
                )
            }
        }

        // Hairline Divider below Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(HairlineBorder)
        )

        // =====================================================================
        // SCROLLABLE INSTRUMENT BODY
        // =====================================================================
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            // 2. TELEMETRY GRID
            TelemetryGrid(
                thermalText = thermalValue,
                thermalSubText = thermalSubText,
                displayText = if (isBoostActive) pairedRefreshRate else currentDisplayHz,
                displaySubText = if (isBoostActive) "LOCKED" else "NATIVE",
                profileText = if (isBoostActive) "CAP $targetFps" else "IDLE",
                profileSubText = if (isBoostActive) "ACTIVE" else "STANDBY"
            )

            // 3. TARGET APPLICATION ROW + INLINE ACCORDION DRAWER
            AppSelectorDrawer(
                selectedApp = selectedApp,
                availableApps = availableApps,
                isDrawerOpen = isAppDrawerOpen,
                onToggleDrawer = { viewModel.onToggleAppDrawer() },
                onSelectApp = { app -> viewModel.onSelectApp(app) }
            )

            // 4. FRAME PACING CONTROL
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CanvasDark)
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "FRAME PACING CAP (FPS)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp,
                        color = ZincMuted
                    )
                    Text(
                        text = "cmd game set",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = ZincMuted
                    )
                }

                FpsSegmentedControl(
                    selectedFps = targetFps,
                    onFpsSelected = { fps -> viewModel.onSelectFps(fps) },
                    recommendedFps = selectedApp.recommendedFps,
                    enabled = isConnected
                )

                Spacer(modifier = Modifier.height(10.dp))

                // SMART PRESET ADAPTIVE BANNER
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(ObsidianSurface)
                        .border(1.dp, HairlineBorder, RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(AccentPurple)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SMART PRESET",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentPurple,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "· REC ${selectedApp.recommendedFps} FPS",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BoneWhite
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = selectedApp.recommendationReason,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = ZincMuted,
                            lineHeight = 14.sp
                        )
                    }
                }
            }

            // Hairline Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(HairlineBorder)
            )

            // 5. SYSTEM DIRECTIVES LIST
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CanvasDark)
            ) {
                // Section Title Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianSurface)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "SYSTEM DIRECTIVES",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp,
                        color = ZincMuted
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(HairlineBorder)
                )

                // Directive 1: Deep Doze Enforcer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = "Deep Doze Enforcer",
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BoneWhite
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "dumpsys deviceidle force-idle",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            color = ZincMuted
                        )
                    }

                    MechSwitch(
                        checked = isDozeEnabled,
                        onCheckedChange = { viewModel.onToggleDoze(it) },
                        enabled = isConnected
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(HairlineBorder)
                )

                // Directive 2: ART Shader Pre-Compile
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "ART Shader Pre-Compile",
                                    fontFamily = FontFamily.SansSerif,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BoneWhite
                                )

                                if (compileDone) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(NominalGreen.copy(alpha = 0.2f))
                                            .border(1.dp, NominalGreen.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                                            .padding(horizontal = 6.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = "OK",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            letterSpacing = 1.sp,
                                            color = NominalGreen
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "cmd package compile -m speed -f",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                color = ZincMuted
                            )
                        }

                        // [ RUN ] Button
                        Box(
                            modifier = Modifier
                                .alpha(if (isConnected && !isCompiling) 1.0f else 0.35f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(ObsidianSurface)
                                .border(1.dp, HairlineBorder, RoundedCornerShape(4.dp))
                                .clickable(
                                    enabled = isConnected && !isCompiling,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    viewModel.onRunArtCompile()
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isCompiling) "[ ... ]" else "[ RUN ]",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp,
                                color = BoneWhite
                            )
                        }
                    }

                    // 2px Flat Progress Bar
                    AnimatedVisibility(
                        visible = isCompiling,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(ProgressTrack)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(compileProgress)
                                        .height(2.dp)
                                        .background(AccentPurple)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 6. LIVE SHELL OUTPUT STRIP
            ShellTerminalView(
                logs = logs,
                onClearLogs = { viewModel.onClearLogs() }
            )
        }

        // =====================================================================
        // 7. BOTTOM DOCKED PRIMARY CTA (SHARP MECHANICAL BUTTON)
        // =====================================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CanvasDark)
                .border(1.dp, HairlineBorder)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val ctaBackground = if (isBoostActive) SignalRed else AccentPurple
            val ctaText = if (isBoostActive) "DISENGAGE & RESTORE DEFAULTS" else "ENGAGE PROFILE & LAUNCH"

            Box(
                modifier = Modifier
                    .alpha(if (isConnected) 1.0f else 0.35f)
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(ctaBackground)
                    .clickable(
                        enabled = isConnected,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        viewModel.onToggleBoost(context)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = ctaText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    color = BoneWhite
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Minimal Android Gesture Pill
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(GesturePill)
            )
        }
    }
}
