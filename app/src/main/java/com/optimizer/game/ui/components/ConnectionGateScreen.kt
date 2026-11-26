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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optimizer.game.ui.theme.AccentPurple
import com.optimizer.game.ui.theme.BoneWhite
import com.optimizer.game.ui.theme.CanvasDark
import com.optimizer.game.ui.theme.HairlineBorder
import com.optimizer.game.ui.theme.ObsidianSurface
import com.optimizer.game.ui.theme.SignalAmber
import com.optimizer.game.ui.theme.SignalRed
import com.optimizer.game.ui.theme.ZincMuted

private data class OemRequirementNote(
    val brand: String,
    val requirement: String
)

private val OEM_REQUIREMENT_NOTES = listOf(
    OemRequirementNote("XIAOMI / HYPEROS", "Enable USB Debugging (Security Settings)"),
    OemRequirementNote("OPPO / REALME / ONEPLUS", "Enable Disable Permission Monitoring"),
    OemRequirementNote("VIVO / IQOO", "Allow background autostart for Daemon"),
    OemRequirementNote("PIXEL / MOTO / AOSP", "Standard Wireless Debugging only")
)

/**
 * Stage 1 Connection Gate displayed when UID 2000 shell privileges are unbound or degraded.
 */
@Composable
fun ConnectionGateScreen(
    daemonStoppedReason: Boolean,
    hasPendingWmSnapshot: Boolean,
    isPairingInProgress: Boolean,
    onConnectWirelessAdb: () -> Unit,
    onOpenWirelessAdbSettings: () -> Unit,
    onPairWithCode: (port: String, code: String) -> Unit,
    onConnectShizuku: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pairDrawerOpen by remember { mutableStateOf(false) }
    var oemAccordionOpen by remember { mutableStateOf(false) }
    var pairCode by remember { mutableStateOf("") }
    var pairPort by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CanvasDark)
            .verticalScroll(rememberScrollState())
    ) {
        if (daemonStoppedReason || hasPendingWmSnapshot) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (daemonStoppedReason) {
                    GateAlertStrip(
                        title = "DAEMON STOPPED · RECONNECT ADB",
                        description = "Local UID 2000 process terminated by LMK or reboot. Re-establish bridge to resume closed-loop control.",
                        accentColor = SignalRed
                    )
                }
                if (hasPendingWmSnapshot) {
                    GateAlertStrip(
                        title = "WM SNAPSHOT PRESERVED · WILL RESTORE ON RECONNECT",
                        description = "Previous display geometry baseline is locked safely in storage and will verify-restore immediately upon reconnect.",
                        accentColor = SignalAmber
                    )
                }
            }
        }

        // Gate Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "PRIVILEGE GATE // UID 2000 REQUIRED",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = AccentPurple
            )
            Text(
                text = "Connect System Shell",
                fontFamily = FontFamily.SansSerif,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = BoneWhite
            )
            Text(
                text = "FrameGuard verifies real OS capabilities after connecting. No root required.",
                fontFamily = FontFamily.SansSerif,
                fontSize = 12.sp,
                color = ZincMuted,
                lineHeight = 17.sp
            )
        }

        HairlineDivider()

        // Connection Methods
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Card 1: WIRELESS ADB
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(ObsidianSurface)
                    .border(1.dp, HairlineBorder, RoundedCornerShape(4.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "WIRELESS ADB",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = BoneWhite
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(AccentPurple.copy(alpha = 0.14f))
                            .border(1.dp, AccentPurple.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "LOOPBACK HANDOVER",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp,
                            color = AccentPurple
                        )
                    }
                }

                Text(
                    text = "Spawns local UID 2000 daemon. Stays active offline even if Wi-Fi drops or switches.",
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 11.5.sp,
                    color = ZincMuted,
                    lineHeight = 16.sp
                )

                // Inline Compact Pairing Drawer
                AnimatedVisibility(
                    visible = pairDrawerOpen,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(CanvasDark)
                            .border(1.dp, HairlineBorder, RoundedCornerShape(4.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PairingInputField(
                                label = "6-DIGIT CODE",
                                value = pairCode,
                                placeholder = "482910",
                                maxLength = 6,
                                onValueChange = { pairCode = it.filter(Char::isDigit).take(6) },
                                modifier = Modifier.weight(1f)
                            )
                            PairingInputField(
                                label = "PAIRING PORT (OPT)",
                                value = pairPort,
                                placeholder = "AUTO mDNS",
                                maxLength = 5,
                                onValueChange = { pairPort = it.filter(Char::isDigit).take(5) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(ObsidianSurface)
                                    .border(1.dp, HairlineBorder, RoundedCornerShape(3.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        onOpenWirelessAdbSettings()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "[ DEV OPTIONS ]",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ZincMuted
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(34.dp)
                                    .alpha(if (isPairingInProgress) 0.5f else 1f)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(AccentPurple.copy(alpha = 0.18f))
                                    .border(1.dp, AccentPurple, RoundedCornerShape(3.dp))
                                    .clickable(
                                        enabled = !isPairingInProgress,
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        onPairWithCode(pairPort, pairCode)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isPairingInProgress) "[ PAIRING... ]" else "[ CONFIRM PAIR ]",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                    color = BoneWhite
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(CanvasDark)
                            .border(1.dp, HairlineBorder, RoundedCornerShape(4.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                pairDrawerOpen = !pairDrawerOpen
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (pairDrawerOpen) "[ CANCEL PAIR ]" else "[ ENTER CODE / MANUAL ]",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp,
                            color = BoneWhite
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(AccentPurple)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                onConnectWirelessAdb()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "[ CONNECT WIRELESS ADB ]",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = BoneWhite
                        )
                    }
                }
            }

            // Card 2: SHIZUKU SERVICE
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(ObsidianSurface)
                    .border(1.dp, HairlineBorder, RoundedCornerShape(4.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SHIZUKU SERVICE",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = BoneWhite
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(CanvasDark)
                            .border(1.dp, HairlineBorder, RoundedCornerShape(3.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "API v13+",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.6.sp,
                            color = ZincMuted
                        )
                    }
                }

                Text(
                    text = "Binds directly to IShellService UserService with active binder guard.",
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 11.5.sp,
                    color = ZincMuted,
                    lineHeight = 16.sp
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(CanvasDark)
                        .border(1.dp, HairlineBorder, RoundedCornerShape(4.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onConnectShizuku()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "[ CONNECT SHIZUKU ]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp,
                        color = BoneWhite
                    )
                }
            }
        }

        HairlineDivider()

        // Expandable OEM Developer Requirements Accordion
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianSurface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    oemAccordionOpen = !oemAccordionOpen
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "OEM DEVELOPER REQUIREMENTS",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = ZincMuted
            )
            Text(
                text = if (oemAccordionOpen) "[ CLOSE ]" else "[ VIEW ]",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = BoneWhite
            )
        }

        AnimatedVisibility(
            visible = oemAccordionOpen,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CanvasDark)
            ) {
                OEM_REQUIREMENT_NOTES.forEach { note ->
                    HairlineDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = note.brand,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.7.sp,
                            color = BoneWhite
                        )
                        Text(
                            text = note.requirement,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 11.sp,
                            color = ZincMuted,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }

        HairlineDivider()
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun GateAlertStrip(
    title: String,
    description: String,
    accentColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .background(ObsidianSurface)
            .border(1.dp, HairlineBorder, RoundedCornerShape(3.dp))
    ) {
        Box(
            modifier = Modifier
                .padding(end = 10.dp)
                .background(accentColor)
                .height(64.dp)
                .padding(horizontal = 1.5.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 10.dp, bottom = 10.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = accentColor
            )
            Text(
                text = description,
                fontFamily = FontFamily.SansSerif,
                fontSize = 11.sp,
                color = ZincMuted,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun PairingInputField(
    label: String,
    value: String,
    placeholder: String,
    maxLength: Int,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.5.sp,
            letterSpacing = 0.8.sp,
            color = ZincMuted
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(3.dp))
                .background(ObsidianSurface)
                .border(1.dp, HairlineBorder, RoundedCornerShape(3.dp))
                .padding(horizontal = 8.dp, vertical = 7.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = ZincMuted.copy(alpha = 0.45f)
                )
            }
            BasicTextField(
                value = value,
                onValueChange = { onValueChange(it.take(maxLength)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                cursorBrush = SolidColor(AccentPurple),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BoneWhite
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun HairlineDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(HairlineBorder)
    )
}
