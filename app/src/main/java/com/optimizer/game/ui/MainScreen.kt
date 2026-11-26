package com.optimizer.game.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optimizer.game.privilege.CapabilityProbe
import com.optimizer.game.privilege.PrivilegeSource
import com.optimizer.game.service.ClosedLoopStatus
import com.optimizer.game.ui.components.AppSelectorDrawer
import com.optimizer.game.ui.components.CapabilityProbeScreen
import com.optimizer.game.ui.components.ConnectionGateScreen
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
import com.optimizer.game.ui.theme.SignalAmber
import com.optimizer.game.ui.theme.SignalRed
import com.optimizer.game.ui.theme.ZincMuted
import com.optimizer.game.viewmodel.MainViewModel
import com.optimizer.game.viewmodel.UiStage
import kotlin.math.roundToInt

private data class DownscaleOption(
    val ratio: String,
    val label: String
)

private val DOWNSCALE_OPTIONS = listOf(
    DownscaleOption("1.0", "1.0x"),
    DownscaleOption("0.85", "0.85x"),
    DownscaleOption("0.75", "0.75x"),
    DownscaleOption("0.6", "0.6x")
)

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val isCompact = config.screenWidthDp < 360
    val isExpanded = config.screenWidthDp > 480
    val clampedFontScale = config.fontScale.coerceIn(0.85f, 1.15f)
    val currentDensity = LocalDensity.current

    val uiStage by viewModel.uiStage.collectAsState()
    val privilegeState by viewModel.privilegeState.collectAsState()
    val daemonStoppedReason by viewModel.daemonStoppedReason.collectAsState()
    val hasPendingWmSnapshot by viewModel.hasPendingWmSnapshot.collectAsState()
    val deviceProfile by viewModel.deviceProfile.collectAsState()
    val probeSteps by viewModel.probeSteps.collectAsState()
    val probeLogs by viewModel.probeLogs.collectAsState()
    val probeElapsedMs by viewModel.probeElapsedMs.collectAsState()
    val isProbing by viewModel.isProbing.collectAsState()
    val probeRequiresDecision by viewModel.probeRequiresDecision.collectAsState()
    val isPairingInProgress by viewModel.isPairingInProgress.collectAsState()

    val isBoostActive by viewModel.isBoostActive.collectAsState()
    val closedLoopStatus by viewModel.closedLoopStatus.collectAsState()
    val isClamped30Fps by viewModel.isClamped30Fps.collectAsState()
    val effectiveRefreshRateHz by viewModel.effectiveRefreshRateHz.collectAsState()
    val selectedApp by viewModel.selectedApp.collectAsState()
    val availableApps by viewModel.availableApps.collectAsState()
    val isAppDrawerOpen by viewModel.isAppDrawerOpen.collectAsState()
    val targetFps by viewModel.targetFps.collectAsState()
    val downscaleRatio by viewModel.downscaleRatio.collectAsState()
    val ramTrimEnabled by viewModel.ramTrimEnabled.collectAsState()
    val dndEnabled by viewModel.dndEnabled.collectAsState()
    val isAdaptiveThermalEnabled by viewModel.isAdaptiveThermalEnabled.collectAsState()
    val thermalValue by viewModel.thermalValue.collectAsState()
    val thermalSubText by viewModel.thermalSubText.collectAsState()
    val pairedRefreshRate by viewModel.pairedRefreshRate.collectAsState()
    val currentDisplayHz by viewModel.currentDisplayHz.collectAsState()
    val renderScale by viewModel.renderScale.collectAsState()
    val scaledMetrics by viewModel.scaledDisplayMetrics.collectAsState()
    val logs by viewModel.logs.collectAsState()

    val isConnected = privilegeState.isConnected
    val isDegraded = closedLoopStatus == ClosedLoopStatus.DEGRADED
    var isHiddenModulesOpen by remember { mutableStateOf(false) }

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
                    // Persistent App Header (FRAMEGUARD + BUILD 108 + Privilege Pill)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CanvasDark)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isCompact) {
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(
                                    text = "FRAMEGUARD",
                                    fontFamily = FontFamily.SansSerif,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp,
                                    color = BoneWhite
                                )
                                Text(
                                    text = "BUILD 108",
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
                                    text = "BUILD 108",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 1.sp,
                                    color = ZincMuted,
                                    modifier = Modifier.padding(bottom = 1.dp)
                                )
                            }
                        }

                        val pillDotColor = when {
                            isDegraded -> SignalRed
                            !isConnected && daemonStoppedReason -> SignalRed
                            !isConnected && privilegeState.statusMessage.contains("NO PERM") -> SignalRed
                            !isConnected -> SignalAmber
                            else -> NominalGreen
                        }
                        val pillLabel = when {
                            isDegraded -> "DEGRADED"
                            !isConnected && daemonStoppedReason -> "DAEMON STOPPED"
                            !isConnected && privilegeState.statusMessage.contains("NO PERM") -> "NO PERM"
                            !isConnected -> "UNBOUND"
                            privilegeState.source == PrivilegeSource.SHIZUKU -> "UID 2000 • SHIZUKU"
                            else -> "UID 2000 • DAEMON"
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
                                letterSpacing = 0.8.sp,
                                color = BoneWhite,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    HorizontalHairline()
                }
            },
            bottomBar = {
                when (uiStage) {
                    UiStage.CONNECTION_GATE -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CanvasDark)
                                .navigationBarsPadding()
                        ) {
                            HorizontalHairline()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "127.0.0.1 EPHEMERAL · MUTUAL AUTH · ALLOWBACKUP=FALSE",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.7.sp,
                                    color = ZincMuted
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                GestureBar()
                            }
                        }
                    }

                    UiStage.CAPABILITY_PROBE -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CanvasDark)
                                .navigationBarsPadding()
                        ) {
                            HorizontalHairline()
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                GestureBar()
                            }
                        }
                    }

                    UiStage.MAIN_CONSOLE -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CanvasDark)
                                .navigationBarsPadding()
                        ) {
                            HorizontalHairline()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val ctaEnabled = isConnected || isDegraded
                                val ctaBackground = when {
                                    isDegraded -> ObsidianSurface
                                    isBoostActive -> SignalRed
                                    else -> AccentPurple
                                }
                                val ctaBorderColor = if (isDegraded) SignalAmber else ctaBackground
                                val ctaTextColor = if (isDegraded) SignalAmber else BoneWhite
                                val ctaText = when {
                                    isDegraded -> "RECONNECT & RESTORE SNAPSHOT"
                                    isBoostActive -> "DISENGAGE & RESTORE DEFAULTS"
                                    else -> "ENGAGE PROFILE & LAUNCH"
                                }

                                Box(
                                    modifier = Modifier
                                        .alpha(if (ctaEnabled) 1.0f else 0.35f)
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(ctaBackground)
                                        .border(1.dp, ctaBorderColor, RoundedCornerShape(4.dp))
                                        .clickable(
                                            enabled = ctaEnabled,
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
                                        color = ctaTextColor
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                GestureBar()
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            when (uiStage) {
                UiStage.CONNECTION_GATE -> {
                    ConnectionGateScreen(
                        daemonStoppedReason = daemonStoppedReason,
                        hasPendingWmSnapshot = hasPendingWmSnapshot,
                        isPairingInProgress = isPairingInProgress,
                        onConnectWirelessAdb = { viewModel.onConnectWirelessAdb(context) },
                        onOpenWirelessAdbSettings = { viewModel.onPairAdb(context) },
                        onPairWithCode = { port, code -> viewModel.onPairAdbWithCode(context, port, code) },
                        onConnectShizuku = { viewModel.onConnectShizuku() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }

                UiStage.CAPABILITY_PROBE -> {
                    CapabilityProbeScreen(
                        steps = probeSteps,
                        probeLogs = probeLogs,
                        elapsedMs = probeElapsedMs,
                        isProbing = isProbing,
                        requiresDecision = probeRequiresDecision,
                        hiddenModuleCount = deviceProfile.hiddenModules.size,
                        onReProbe = { viewModel.onReProbe() },
                        onContinueFiltered = { viewModel.onContinueFiltered() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }

                UiStage.MAIN_CONSOLE -> {
                    val filteredFpsOptions = remember(deviceProfile.maxRefreshRateHz) {
                        CapabilityProbe.filterSupportedFpsOptions(deviceProfile.maxRefreshRateHz)
                    }
                    val clampedRecFps = remember(selectedApp.recommendedFps, filteredFpsOptions) {
                        if (selectedApp.recommendedFps in filteredFpsOptions) {
                            selectedApp.recommendedFps
                        } else {
                            filteredFpsOptions.lastOrNull { it <= selectedApp.recommendedFps }
                                ?: filteredFpsOptions.firstOrNull()
                                ?: 60
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // 1. Telemetry Grid
                        val effectiveScale = if (deviceProfile.supportsWmWrite) renderScale else 100
                        val activeHzLabel = effectiveRefreshRateHz?.let { "${it.roundToInt()}Hz" } ?: pairedRefreshRate
                        val displayValue = when {
                            isBoostActive && effectiveScale < 100 -> "$activeHzLabel•${effectiveScale}%"
                            isBoostActive -> activeHzLabel
                            else -> currentDisplayHz
                        }
                        val displaySub = when {
                            isBoostActive && effectiveScale < 100 -> "${scaledMetrics.width}×${scaledMetrics.height}"
                            isBoostActive && closedLoopStatus == ClosedLoopStatus.OEM_BYPASS -> "OEM OVERRIDE"
                            isBoostActive -> "VSYNC LOCKED"
                            effectiveScale < 100 -> "ARMED ${effectiveScale}%"
                            deviceProfile.detectedOemDaemons.isNotEmpty() -> {
                                "${deviceProfile.maxRefreshRateHz}Hz · OEM"
                            }
                            else -> "NATIVE"
                        }

                        val (profileText, profileSub, profileColor) = when {
                            isDegraded -> Triple("DEGRADED", "LINK LOST", SignalRed)
                            !isBoostActive -> Triple("IDLE", "STANDBY", BoneWhite)
                            closedLoopStatus == ClosedLoopStatus.THERMAL_30 || isClamped30Fps ->
                                Triple("THERMAL 30", "HAL SEVERE", SignalAmber)
                            closedLoopStatus == ClosedLoopStatus.OEM_BYPASS -> Triple(
                                "OEM BYPASS",
                                deviceProfile.detectedOemDaemons.firstOrNull()
                                    ?.substringAfterLast('.')
                                    ?.uppercase()
                                    ?: "OVERRIDDEN",
                                SignalAmber
                            )
                            else -> Triple("SYNC LOCKED", "VERIFIED", NominalGreen)
                        }

                        val thermalColor = if (thermalSubText == "HAL SEVERE") SignalAmber else BoneWhite

                        TelemetryGrid(
                            thermalText = thermalValue,
                            thermalSubText = thermalSubText,
                            displayText = displayValue,
                            displaySubText = displaySub,
                            profileText = profileText,
                            profileSubText = profileSub,
                            thermalValueColor = thermalColor,
                            profileValueColor = profileColor
                        )

                        // 2. Target Application Selector
                        AppSelectorDrawer(
                            selectedApp = selectedApp,
                            availableApps = availableApps,
                            isDrawerOpen = isAppDrawerOpen,
                            onToggleDrawer = { viewModel.onToggleAppDrawer() },
                            onSelectApp = { app -> viewModel.onSelectApp(app) },
                            enabled = !isBoostActive
                        )

                        // 3. Adaptive Thermal Limiter
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CanvasDark)
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp)
                                ) {
                                    Text(
                                        text = "Adaptive Thermal Limiter",
                                        fontFamily = FontFamily.SansSerif,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = BoneWhite,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "30 FPS @ severe · HAL 10s poll",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.5.sp,
                                        color = ZincMuted,
                                        maxLines = 1,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }

                                Row(
                                    modifier = Modifier
                                        .width(148.dp)
                                        .height(32.dp)
                                        .background(ObsidianSurface, RoundedCornerShape(4.dp))
                                        .border(BorderStroke(1.dp, HairlineBorder), RoundedCornerShape(4.dp))
                                        .clip(RoundedCornerShape(4.dp)),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val isOff = !isAdaptiveThermalEnabled
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .background(if (isOff) Color(0xFF222222) else ObsidianSurface)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                if (isAdaptiveThermalEnabled) {
                                                    viewModel.onToggleAdaptiveThermal(false)
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "OFF",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isOff) BoneWhite else ZincMuted
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .fillMaxHeight()
                                            .background(HairlineBorder)
                                    )

                                    val isAdaptive = isAdaptiveThermalEnabled
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .background(if (isAdaptive) BoneWhite else ObsidianSurface)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                if (!isAdaptiveThermalEnabled) {
                                                    viewModel.onToggleAdaptiveThermal(true)
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "ADAPTIVE",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isAdaptive) CanvasDark else ZincMuted
                                        )
                                    }
                                }
                            }

                            AnimatedVisibility(
                                visible = isAdaptiveThermalEnabled,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 10.dp)
                                        .background(ObsidianSurface, RoundedCornerShape(4.dp))
                                        .border(1.dp, HairlineBorder, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 12.dp, vertical = 9.dp)
                                ) {
                                    Text(
                                        text = "Polls ThermalHAL SKIN/SOC at 10s intervals. Engages 30 FPS at severe headroom and restores target FPS when cool.",
                                        fontFamily = FontFamily.SansSerif,
                                        fontSize = 11.sp,
                                        color = ZincMuted,
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }

                        HorizontalHairline()

                        // 4. Frame Pacing Cap (Dynamically Filtered by Device Hz)
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
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "FRAME PACING CAP (FPS)",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        letterSpacing = 1.sp,
                                        color = ZincMuted
                                    )
                                    if (isClamped30Fps) {
                                        AmberTag(text = "CLAMPED 30")
                                    }
                                }
                                Text(
                                    text = if (deviceProfile.supportsGameManager) "cmd game mode 2" else "peak/min_refresh_rate",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = ZincMuted
                                )
                            }

                            FpsSegmentedControl(
                                selectedFps = targetFps,
                                onFpsSelected = { fps -> viewModel.onSelectFps(fps) },
                                recommendedFps = clampedRecFps,
                                maxSupportedFps = selectedApp.maxSupportedFps,
                                options = filteredFpsOptions,
                                enabled = isConnected && !isDegraded
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            val isAboveCap = targetFps > selectedApp.maxSupportedFps
                            val isOptimal = targetFps == clampedRecFps

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
                                            text = if (isAboveCap) "· MAX ${selectedApp.maxSupportedFps} FPS" else "· REC $clampedRecFps FPS",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = BoneWhite
                                        )
                                    }
                                    if (isAboveCap || !isOptimal) {
                                        Text(
                                            text = "[ RESTORE $clampedRecFps ]",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.5.sp,
                                            color = AccentPurple,
                                            modifier = Modifier.clickable(
                                                enabled = isConnected && !isDegraded,
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                viewModel.onSelectFps(clampedRecFps)
                                            }
                                        )
                                    }
                                }
                                Text(
                                    text = if (isAboveCap) {
                                        "${selectedApp.name} engine renders at ${selectedApp.maxSupportedFps} FPS max. Selecting ${targetFps}Hz will not increase framerate and introduces micro-stutter."
                                    } else if (deviceProfile.maxRefreshRateHz <= 60) {
                                        "Panel capped at ${deviceProfile.maxRefreshRateHz}Hz · $clampedRecFps FPS lock matches native scanout with minimal heat"
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

                        HorizontalHairline()

                        // 5. System Directives (Filtered by DeviceCapabilityProfile & per-app support)
                        val showGameDownscale = deviceProfile.supportsGameManager && selectedApp.supportsRenderScale
                        val visibleDirectiveCount = 1 +
                            (if (showGameDownscale) 1 else 0) +
                            (if (deviceProfile.supportsDnd) 1 else 0)

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CanvasDark)
                        ) {
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
                                    text = "$visibleDirectiveCount MODULE${if (visibleDirectiveCount == 1) "" else "S"}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 8.5.sp,
                                    color = ZincMuted
                                )
                            }

                            // Directive 1: Per-App Game Downscale (Hidden if !supportsGameManager or !selectedApp.supportsRenderScale)
                            if (showGameDownscale) {
                                HorizontalHairline()
                                val downscaleInteractive = isConnected && !isBoostActive
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 10.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = "Per-App Game Downscale",
                                                    fontFamily = FontFamily.SansSerif,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = BoneWhite
                                                )
                                                if (isBoostActive) {
                                                    AmberTag(text = "APPLIES ON LAUNCH")
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "cmd game set --downscale",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.5.sp,
                                                color = ZincMuted
                                            )
                                        }

                                        Text(
                                            text = if (downscaleRatio == "1.0") "1.0x NATIVE" else "${downscaleRatio}x BUFFER",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = ZincMuted
                                        )
                                    }

                                    Row(
                                        modifier = Modifier
                                            .alpha(if (downscaleInteractive) 1.0f else 0.35f)
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(ObsidianSurface)
                                            .border(1.dp, HairlineBorder, RoundedCornerShape(4.dp))
                                            .height(38.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        DOWNSCALE_OPTIONS.forEachIndexed { index, opt ->
                                            val isSelected = downscaleRatio == opt.ratio
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxHeight()
                                                    .background(if (isSelected) BoneWhite else ObsidianSurface)
                                                    .clickable(
                                                        enabled = downscaleInteractive,
                                                        interactionSource = remember { MutableInteractionSource() },
                                                        indication = null
                                                    ) {
                                                        viewModel.onSelectDownscale(opt.ratio)
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = opt.label,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 12.sp,
                                                    color = if (isSelected) CanvasDark else ZincMuted
                                                )
                                            }
                                            if (index < DOWNSCALE_OPTIONS.lastIndex) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(1.dp)
                                                        .fillMaxHeight()
                                                        .background(HairlineBorder)
                                                )
                                            }
                                        }
                                    }

                                    Text(
                                        text = if (isBoostActive) {
                                            "APPLIES ON LAUNCH · Disengage active session to modify game buffer scale"
                                        } else {
                                            "Scales game buffer only · System UI stays native"
                                        },
                                        fontFamily = FontFamily.SansSerif,
                                        fontSize = 11.sp,
                                        color = ZincMuted,
                                        lineHeight = 15.sp
                                    )
                                }
                            }

                            HorizontalHairline()

                            // Directive 2: Safe RAM Trim
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
                                        text = "Safe RAM Trim",
                                        fontFamily = FontFamily.SansSerif,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = BoneWhite
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "am kill-all (cached only on launch)",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.5.sp,
                                        color = ZincMuted
                                    )
                                }

                                MechSwitch(
                                    checked = ramTrimEnabled,
                                    onCheckedChange = { viewModel.onToggleRamTrim(it) },
                                    enabled = isConnected && !isBoostActive
                                )
                            }

                            // Directive 3: Auto Gaming DND (Hidden if !supportsDnd)
                            if (deviceProfile.supportsDnd) {
                                HorizontalHairline()
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
                                            text = "Auto Gaming DND",
                                            fontFamily = FontFamily.SansSerif,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = BoneWhite
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "cmd notification set_dnd priority",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.5.sp,
                                            color = ZincMuted
                                        )
                                    }

                                    MechSwitch(
                                        checked = dndEnabled,
                                        onCheckedChange = { viewModel.onToggleDnd(it) },
                                        enabled = isConnected && !isBoostActive
                                    )
                                }
                            }
                        }

                        // 6. EXPERIMENTAL Section (Hidden if !supportsWmWrite)
                        if (deviceProfile.supportsWmWrite) {
                            HorizontalHairline()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(ObsidianSurface)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "EXPERIMENTAL",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 1.sp,
                                    color = ZincMuted
                                )
                                AmberTag(text = "WM OVERRIDE")
                            }

                            HorizontalHairline()

                            val wmInteractive = isConnected && !isBoostActive
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(CanvasDark)
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "WindowManager Render Scale",
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

                                Row(
                                    modifier = Modifier
                                        .alpha(if (wmInteractive) 1.0f else 0.35f)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(ObsidianSurface)
                                        .border(1.dp, HairlineBorder, RoundedCornerShape(4.dp))
                                        .height(38.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val scales = listOf(100, 75, 50)
                                    scales.forEachIndexed { index, scale ->
                                        val isSelected = scale == renderScale
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .background(if (isSelected) BoneWhite else ObsidianSurface)
                                                .clickable(
                                                    enabled = wmInteractive,
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null
                                                ) {
                                                    viewModel.onSelectRenderScale(scale)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "$scale%",
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 12.5.sp,
                                                color = if (isSelected) CanvasDark else ZincMuted
                                            )
                                        }
                                        if (index < scales.lastIndex) {
                                            Box(
                                                modifier = Modifier
                                                    .width(1.dp)
                                                    .fillMaxHeight()
                                                    .background(HairlineBorder)
                                            )
                                        }
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(ObsidianSurface)
                                        .border(1.dp, SignalAmber.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = "Global UI scale · Persists across reboot if killed",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.5.sp,
                                        color = SignalAmber,
                                        lineHeight = 14.sp
                                    )
                                }
                            }
                        }

                        // 7. Device Profile & Hidden Modules Strip
                        HorizontalHairline()
                        val hiddenModules = deviceProfile.hiddenModules
                        val reVerifyEnabled = isConnected && !isBoostActive

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ObsidianSurface)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${hiddenModules.size} MODULE${if (hiddenModules.size == 1) "" else "S"} HIDDEN · DEVICE PROFILE",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.6.sp,
                                color = if (hiddenModules.isEmpty()) ZincMuted else SignalAmber,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (hiddenModules.isNotEmpty()) {
                                    Text(
                                        text = if (isHiddenModulesOpen) "[ HIDE ]" else "[ VIEW ]",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.6.sp,
                                        color = BoneWhite,
                                        modifier = Modifier.clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            isHiddenModulesOpen = !isHiddenModulesOpen
                                        }
                                    )
                                }

                                Text(
                                    text = "[ RE-VERIFY ]",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.6.sp,
                                    color = AccentPurple,
                                    modifier = Modifier
                                        .alpha(if (reVerifyEnabled) 1.0f else 0.35f)
                                        .clickable(
                                            enabled = reVerifyEnabled,
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            viewModel.onReVerifyCapabilities()
                                        }
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = isHiddenModulesOpen && hiddenModules.isNotEmpty(),
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(CanvasDark)
                            ) {
                                hiddenModules.forEach { mod ->
                                    HorizontalHairline()
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 9.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = mod.name,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.6.sp,
                                            color = BoneWhite
                                        )
                                        Text(
                                            text = mod.reason,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.5.sp,
                                            color = ZincMuted
                                        )
                                    }
                                }
                            }
                        }

                        // 8. Live Shell Output Strip
                        ShellTerminalView(
                            logs = logs,
                            onClearLogs = { viewModel.onClearLogs() },
                            isExpanded = isExpanded
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HorizontalHairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(HairlineBorder)
    )
}

@Composable
private fun GestureBar() {
    Box(
        modifier = Modifier
            .width(96.dp)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(GesturePill)
    )
}

@Composable
private fun AmberTag(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(2.dp))
            .background(SignalAmber.copy(alpha = 0.12f))
            .border(1.dp, SignalAmber.copy(alpha = 0.35f), RoundedCornerShape(2.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            color = SignalAmber
        )
    }
}
