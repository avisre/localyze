package com.localassistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localassistant.ui.components.CapabilityCard
import com.localassistant.ui.components.RobotMascot
import com.localassistant.ui.theme.Background
import com.localassistant.ui.theme.Nunito
import com.localassistant.ui.theme.TextSecondary
import com.localassistant.ui.viewmodels.CAPABILITIES
import com.localassistant.ui.viewmodels.CapabilityItem
import kotlinx.coroutines.delay

/**
 * Capabilities grid screen — Tab 2 of the bottom navigation.
 *
 * Displays a 2×3 grid of [CapabilityCard]s representing the six capability
 * modes (Chat, See & Understand, Write & Draft, Brainstorm, Code Help,
 * Data & Charts). Tapping a card triggers haptic feedback and invokes
 * [onCapabilitySelected] with the corresponding mode string.
 *
 * Cards animate in with a staggered spring animation (50 ms delay per card).
 */
@Composable
fun CapabilitiesScreen(
    onCapabilitySelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current

    // Track which cards have become visible for staggered animation
    var visibleCardCount by remember { mutableStateOf(0) }

    // Launch the staggered reveal — increment visible count every 50 ms
    LaunchedEffect(Unit) {
        visibleCardCount = 0
        for (i in CAPABILITIES.indices) {
            delay(50L)
            visibleCardCount = i + 1
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "What can I help with?",
                    fontFamily = Nunito,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    color = Color(0xFF2D2417),
                    lineHeight = 36.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Choose a mode to get specialized assistance",
                    fontFamily = Nunito,
                    fontWeight = FontWeight.Normal,
                    fontSize = 16.sp,
                    color = TextSecondary,
                    lineHeight = 22.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            RobotMascot(
                isThinking = false,
                modifier = Modifier.size(80.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── 2×3 Grid ─────────────────────────────────────────────────────────
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            userScrollEnabled = true
        ) {
            itemsIndexed(
                items = CAPABILITIES,
                key = { index, item -> item.mode }
            ) { index, capability ->
                AnimatedVisibility(
                    visible = index < visibleCardCount,
                    enter = fadeIn(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) + slideInVertically(
                        initialOffsetY = { it / 3 },
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
                ) {
                    CapabilityCard(
                        title = capability.title,
                        description = capability.description,
                        iconTint = Color(capability.iconTint),
                        onClick = {
                            hapticFeedback.performHapticFeedback(
                                HapticFeedbackType.LongPress
                            )
                            onCapabilitySelected(capability.mode)
                        },
                        modifier = Modifier.height(160.dp)
                    )
                }
            }
        }

        // ── Bottom privacy notice ─────────────────────────────────────────────
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🔒",
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = "All modes work fully on-device. Your data stays private.",
                fontFamily = Nunito,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                color = TextSecondary,
                lineHeight = 20.sp
            )
        }
    }
}