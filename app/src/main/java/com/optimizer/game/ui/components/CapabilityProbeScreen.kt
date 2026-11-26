package com.optimizer.game.ui.components

import android.os.Build
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optimizer.game.optimizer.GameOptimizerEngine
import com.optimizer.game.privilege.ProbeStatus
import com.optimizer.game.privilege.ProbeStepResult
import com.optimizer.game.ui.theme.AccentPurple
import com.optimizer.game.ui.theme.BoneWhite
import com.optimizer.game.ui.theme.CanvasDark
import com.optimizer.game.ui.theme.HairlineBorder
import com.optimizer.game.ui.theme.NominalGreen
import com.optimizer.game.ui.theme.ObsidianSurface
import com.optimizer.game.ui.theme.ProgressTrack
import com.optimizer.game.ui.theme.SignalAmber
import com.optimizer.game.ui.theme.TerminalBackground
import com.optimizer.game.ui.theme.ZincMuted

/**
 * Stage 2 Live Capability Probe Screen displaying parallel dry-run OS verification.
 */
@Composable
fun CapabilityProbeScreen(
    steps: List<ProbeStepResult>,
    probeLogs: List<GameOptimizerEngine.ShellLogEntry>,
    elapsedMs: Long,
    isProbing: Boolean,
    requiresDecision: Boolean,
    hiddenModuleCount: Int,
    onReProbe: () -> Unit,
    onContinueFiltered: () -> Unit,
    modifier: Modifier = Modifier
) {
    val completedCount = steps.count { it.status != ProbeStatus.PENDING && it.status != ProbeStatus.PROBING }
    val progressFraction = if (steps.isEmpty()) 0f else (completedCount.toFloat() / steps.size.toFloat()).coerceIn(0f, 1f)
    val deviceMeta = "${Build.MODEL.uppercase()} · API ${Build.VERSION.SDK_INT}"
    val stdoutState = rememberLazyListState()

    LaunchedEffect(probeLogs.size) {
        if (probeLogs.isNotEmpty()) {
            stdoutState.animateScrollToItem(probeLogs.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CanvasDark)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            // Diagnostic Header + 2dp Progress Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ObsidianSurface)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LIVE CAPABILITY PROBE",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = BoneWhite
                    )
                    Text(
                        text = "${elapsedMs}ms",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentPurple
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = deviceMeta,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        letterSpacing = 0.6.sp,
                        color = ZincMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "BUILD FINGERPRINT CACHE",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        letterSpacing = 0.6.sp,
                        color = ZincMuted
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(ProgressTrack)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressFraction)
                            .height(2.dp)
                            .background(AccentPurple)
                    )
                }
            }

            ProbeDivider()

            // 5 Probe Rows
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CanvasDark)
            ) {
                steps.forEachIndexed { index, step ->
                    val rowBg = if (step.status == ProbeStatus.PROBING) Color(0xFF0D0D0F) else CanvasDark
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(rowBg)
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = step.name,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.7.sp,
                                color = BoneWhite,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = step.detail,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.5.sp,
                                color = ZincMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        ProbeStatusBadge(badge = step.badge, status = step.status)
                    }

                    if (index < steps.lastIndex) {
                        ProbeDivider()
                    }
                }
            }

            ProbeDivider()

            // Live Dry-Run Stdout Strip
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TerminalBackground)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PROBE SHELL // DRY-RUN STDOUT",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp,
                        color = ZincMuted
                    )
                    Text(
                        text = if (isProbing) "RUNNING" else "CACHED",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        color = ZincMuted
                    )
                }

                ProbeDivider()

                LazyColumn(
                    state = stdoutState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(112.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    items(probeLogs) { entry ->
                        val (prefix, prefixColor, textColor) = when (entry.type) {
                            GameOptimizerEngine.LogType.COMMAND -> Triple("$", BoneWhite, Color(0xFFD4D4D0))
                            GameOptimizerEngine.LogType.STDOUT -> Triple(">", NominalGreen, Color(0xFFB5B5B0))
                            GameOptimizerEngine.LogType.ERROR -> Triple("!", SignalAmber, SignalAmber)
                            GameOptimizerEngine.LogType.INFO -> Triple("#", ZincMuted, ZincMuted)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = prefix,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = prefixColor
                            )
                            Spacer(modifier = Modifier.padding(horizontal = 3.dp))
                            Text(
                                text = entry.message,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Outcome Action Box when probe pauses on OEM Security Block or finishes
        if (!isProbing && requiresDecision) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CanvasDark)
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(ObsidianSurface)
                        .border(1.dp, SignalAmber.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "OEM SECURITY BLOCK",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = SignalAmber
                    )
                    Text(
                        text = "Enable \"USB Debugging (Security Settings)\" to unlock WindowManager scaling & refresh rate. $hiddenModuleCount module(s) filtered.",
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 11.sp,
                        color = ZincMuted,
                        lineHeight = 15.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(CanvasDark)
                                .border(1.dp, HairlineBorder, RoundedCornerShape(4.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    onReProbe()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "[ RE-PROBE ]",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BoneWhite
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1.35f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(AccentPurple)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    onContinueFiltered()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "[ CONTINUE (FILTERED) ]",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = BoneWhite
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProbeStatusBadge(
    badge: String,
    status: ProbeStatus
) {
    val (textColor, borderColor, bgColor) = when (status) {
        ProbeStatus.PROBING -> Triple(AccentPurple, AccentPurple.copy(alpha = 0.5f), AccentPurple.copy(alpha = 0.12f))
        ProbeStatus.PASS -> Triple(NominalGreen, NominalGreen.copy(alpha = 0.45f), NominalGreen.copy(alpha = 0.12f))
        ProbeStatus.WARN -> Triple(SignalAmber, SignalAmber.copy(alpha = 0.45f), SignalAmber.copy(alpha = 0.12f))
        ProbeStatus.SKIP -> Triple(ZincMuted, HairlineBorder, Color(0xFF141414))
        ProbeStatus.PENDING -> Triple(ZincMuted, HairlineBorder, ObsidianSurface)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(3.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = badge,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp,
            color = textColor,
            maxLines = 1
        )
    }
}

@Composable
private fun ProbeDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(HairlineBorder)
    )
}
