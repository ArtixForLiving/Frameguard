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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optimizer.game.privilege.PrivilegeSource
import com.optimizer.game.ui.components.AppSelectorDrawer
import com.optimizer.game.ui.components.FpsSegmentedControl
import com.optimizer.game.ui.components.MechSwitch
import com.optimizer.game.ui.components.PrivilegeSetupCard
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
import com.optimizer.game.ui.theme.SignalAmber
import com.optimizer.game.ui.theme.SignalRed
import com.optimizer.game.ui.theme.ZincMuted
import com.optimizer.game.viewmodel.MainViewModel

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val isCompact = config.screenWidthDp < 360
    val isExpanded = config.screenWidthDp > 480
    val clampedFontScale = config.fontScale.coerceIn(0.85f, 1.15f)
    val currentDensity = LocalDensity.current

    val privilegeState by viewModel.privilegeState.collectAsState()
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
    val renderScale by viewModel.renderScale.collectAsState()
    val scaledMetrics by viewModel.scaledDisplayMetrics.collectAsState()
    val logs by viewModel.logs.collectAsState()

    val isConnected = privilegeState.isConnected

    CompositionLocalProvider(
        LocalDensity provides Density(
            density = currentDensity.density,
            fontScale = clampedFontScale
        )
    ) {
        Scaffold(
            containerColor = CanvasDark,
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CanvasDark)
                        .statusBarsPadding()
                ) {
                    // =====================================================================
                    // 1. APP HEADER (FRAMEGUARD + BUILD 107 + PRIVILEGE STATUS PILL)
                    // =====================================================================
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CanvasDark)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isCompact) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(1.dp)
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
                                    text = "BUILD 107",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 1.sp,
                                    color = ZincMuted
                                )
                            }
                        } else {
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
                                    text = "BUILD 107",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 1.sp,
                                    color = ZincMuted,
                                    modifier = Modifier.padding(bottom = 1.dp)
                                )
                            }
                        }

                        // Clickable Privilege Status Pill
                        val pillDotColor = when {
                            isConnected -> NominalGreen
                            privilegeState.statusMessage.contains("NO PERM") -> SignalRed
                            else -> SignalAmber
                        }
                        val pillLabel = when {
                            privilegeState.source == PrivilegeSource.NATIVE_ADB -> "UID 2000 • ADB"
                            privilegeState.source == PrivilegeSource.SHIZUKU -> "UID 2000 • SHIZUKU"
                            privilegeState.statusMessage.contains("NO PERM") -> "NO PERM"
                            else -> "UNBOUND"
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
                                    viewModel.onPillClicked()
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
                                color = BoneWhite,
                                maxLines = 1,
                                softWrap = false
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
                }
            },
            bottomBar = {
                // =====================================================================
                // 7. BOTTOM DOCKED PRIMARY CTA (SHARP MECHANICAL BUTTON)
                // =====================================================================
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CanvasDark)
                        .navigationBarsPadding()
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
        ) { innerPadding ->
            // =====================================================================
            // SCROLLABLE INSTRUMENT BODY
            // =====================================================================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
            ) {
                // Contextual PrivilegeSetupCard: Visible only when isConnected == false
                AnimatedVisibility(visible = !isConnected) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        PrivilegeSetupCard(
                            onPairAdb = { viewModel.onPairAdb(context) },
                            onConnectShizuku = { viewModel.onConnectShizuku() }
                        )
                    }
                }

                // 2. TELEMETRY GRID
            val effectiveScale = if (selectedApp.supportsRenderScale) renderScale else 100
            val displayValue = when {
                isBoostActive && effectiveScale < 100 -> "${pairedRefreshRate}•${effectiveScale}%"
                isBoostActive -> pairedRefreshRate
                else -> currentDisplayHz
            }
            val displaySub = when {
                isBoostActive && effectiveScale < 100 -> "${scaledMetrics.width}×${scaledMetrics.height}"
                isBoostActive -> "LOCKED"
                effectiveScale < 100 -> "ARMED ${effectiveScale}%"
                else -> "NATIVE"
            }

            TelemetryGrid(
                thermalText = thermalValue,
                thermalSubText = thermalSubText,
                displayText = displayValue,
                displaySubText = displaySub,
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
                    maxSupportedFps = selectedApp.maxSupportedFps,
                    enabled = isConnected
                )

                Spacer(modifier = Modifier.height(10.dp))

                // SMART PRESET ADAPTIVE BANNER
                val isAboveCap = targetFps > selectedApp.maxSupportedFps
                val isOptimal = targetFps == selectedApp.recommendedFps

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(ObsidianSurface)
                        .border(
                            width = 1.dp,
                            color = if (isAboveCap) AccentPurple.copy(alpha = 0.45f) else HairlineBorder,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(AccentPurple)
                            )
                            Text(
                                text = if (isAboveCap) "ENGINE CAP REACHED" else if (isOptimal) "SMART PRESET" else "MANUAL CADENCE",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentPurple,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = if (isAboveCap) "· MAX ${selectedApp.maxSupportedFps} FPS" else "· REC ${selectedApp.recommendedFps} FPS",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BoneWhite
                            )
                        }
                        if (isAboveCap || !isOptimal) {
                            Text(
                                text = "[ RESTORE ${selectedApp.recommendedFps} ]",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                color = AccentPurple,
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        viewModel.onSelectFps(selectedApp.recommendedFps)
                                    }
                            )
                        }
                    }
                    Text(
                        text = if (isAboveCap) {
                            "${selectedApp.name} engine renders at ${selectedApp.maxSupportedFps} FPS max. Selecting ${targetFps}Hz will not increase framerate and introduces micro-stutter."
                        } else {
                            selectedApp.recommendationReason
                        },
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        color = ZincMuted,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(start = 12.dp)
                    )
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianSurface)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SYSTEM DIRECTIVES",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp,
                        color = ZincMuted
                    )
                    Text(
                        text = if (selectedApp.supportsRenderScale) "3 MODULES" else "2 MODULES",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.5.sp,
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

                // Directive 3 (Conditional): Render Scale
                if (selectedApp.supportsRenderScale) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(HairlineBorder)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Render Scale",
                                    fontFamily = FontFamily.SansSerif,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BoneWhite
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "wm size & wm density",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.5.sp,
                                    color = ZincMuted
                                )
                            }

                            Text(
                                text = "${scaledMetrics.width}×${scaledMetrics.height} • ${scaledMetrics.dpi} DPI",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = ZincMuted
                            )
                        }

                        // Boxed Segmented Control [ 100% | 75% | 50% ]
                        Row(
                            modifier = Modifier
                                .alpha(if (isConnected) 1.0f else 0.35f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(ObsidianSurface)
                                .border(1.dp, HairlineBorder, RoundedCornerShape(4.dp))
                                .height(38.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(100, 75, 50).forEach { scale ->
                                val isSelected = scale == renderScale
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .background(if (isSelected) BoneWhite else ObsidianSurface)
                                        .clickable(
                                            enabled = isConnected,
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            viewModel.onSelectRenderScale(scale)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = "$scale%",
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.5.sp,
                                            color = if (isSelected) CanvasDark else ZincMuted
                                        )
                                        if (scale == 75) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(4.dp)
                                                    .clip(CircleShape)
                                                    .background(AccentPurple)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Smart Preset Adaptive Banner
                        val bannerTag = when (renderScale) {
                            100 -> "· REC ${selectedApp.recommendedScale}%"
                            75 -> "· 75% (${scaledMetrics.pixelLoadPercent}% PIXELS)"
                            else -> "· 50% (25% PIXELS)"
                        }
                        val bannerTitle = when (renderScale) {
                            100 -> "SCALE PRESET"
                            75 -> "SCALED"
                            else -> "LOW RES"
                        }
                        val bannerReason = when (renderScale) {
                            100 -> selectedApp.scaleReason.ifEmpty { "75% cuts GPU load by 44% for steadier 60 FPS." }
                            75 -> "Runs at ${scaledMetrics.width}×${scaledMetrics.height} · Resets on exit."
                            else -> "Half resolution (${scaledMetrics.width}×${scaledMetrics.height}) · Resets on exit."
                        }
                        val bannerAction = when (renderScale) {
                            100 -> "[ APPLY ${selectedApp.recommendedScale}% ]"
                            75 -> "[ RESET 100% ]"
                            else -> "[ RESTORE 75% ]"
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(ObsidianSurface)
                                .border(
                                    1.dp,
                                    if (renderScale < 100) AccentPurple.copy(alpha = 0.45f) else HairlineBorder,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .clip(CircleShape)
                                                .background(AccentPurple)
                                        )
                                        Text(
                                            text = bannerTitle,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.8.sp,
                                            color = AccentPurple
                                        )
                                        Text(
                                            text = bannerTag,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = BoneWhite
                                        )
                                    }

                                    Text(
                                        text = bannerAction,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AccentPurple,
                                        modifier = Modifier.clickable {
                                            val target = if (renderScale == 100) selectedApp.recommendedScale else if (renderScale == 75) 100 else 75
                                            viewModel.onSelectRenderScale(target)
                                        }
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = bannerReason,
                                    fontFamily = FontFamily.SansSerif,
                                    fontSize = 11.sp,
                                    color = ZincMuted,
                                    lineHeight = 15.sp,
                                    modifier = Modifier.padding(start = 11.dp)
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
                onClearLogs = { viewModel.onClearLogs() },
                isExpanded = isExpanded
            )
        }
    }
}
}
