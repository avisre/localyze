package com.localassistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.localassistant.ui.components.ReferenceCapabilityCard
import com.localassistant.ui.components.ReferenceHeader
import com.localassistant.ui.components.ReferenceSectionTitle
import com.localassistant.ui.components.StatusChip
import com.localassistant.ui.theme.Background
import com.localassistant.ui.theme.TextSecondary

private data class CapabilityAction(
    val title: String,
    val subtitle: String,
    val target: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
fun CapabilitiesScreen(
    onCapabilitySelected: (String) -> Unit,
    onOpenTasks: () -> Unit = {},
    onOpenAttachments: () -> Unit = {},
    onOpenReplies: () -> Unit = {},
    onOpenToolCenter: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current

    fun choose(target: String) {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        when (target) {
            "tasks" -> onOpenTasks()
            "attachments" -> onOpenAttachments()
            "replies" -> onOpenReplies()
            "tool_center" -> onOpenToolCenter()
            "settings" -> onOpenSettings()
            else -> onCapabilitySelected(target)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
    ) {
        ReferenceHeader(
            title = "Capabilities",
            subtitle = "Tools to help you get things done locally.",
            actions = {
                IconButton(onClick = { choose("chat") }) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = "Search capabilities",
                        tint = TextSecondary
                    )
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 18.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusChip(text = "Agentic tools", selected = true)
                StatusChip(text = "Permission gated")
                StatusChip(text = "Local first")
            }

            CapabilitySection(
                title = "Communicate",
                actions = listOf(
                    CapabilityAction("Write a reply", "Texts and emails", "replies", Icons.Outlined.ChatBubbleOutline),
                    CapabilityAction("Draft email", "Subject, tone, polish", "replies", Icons.Outlined.Email)
                ),
                onSelect = ::choose
            )

            CapabilitySection(
                title = "Organize",
                actions = listOf(
                    CapabilityAction("Create reminder", "Natural language tasks", "tasks", Icons.Outlined.NotificationsNone),
                    CapabilityAction("Manage tasks", "Plan and prioritize", "tasks", Icons.Outlined.CheckCircle)
                ),
                onSelect = ::choose
            )

            CapabilitySection(
                title = "Documents",
                actions = listOf(
                    CapabilityAction("Summarize file", "Read and condense", "attachments", Icons.Outlined.Description),
                    CapabilityAction("Save to memory", "Keep useful context", "attachments", Icons.Outlined.BookmarkBorder)
                ),
                onSelect = ::choose
            )

            CapabilitySection(
                title = "Explore",
                actions = listOf(
                    CapabilityAction("Search web", "Ask before leaving device", "chat", Icons.Outlined.Public),
                    CapabilityAction("Ask about image", "Describe and inspect", "see", Icons.Outlined.Image)
                ),
                onSelect = ::choose
            )

            CapabilitySection(
                title = "Code",
                actions = listOf(
                    CapabilityAction("Write code", "Generate and explain", "code", Icons.Outlined.Code),
                    CapabilityAction("Run / debug code", "Trace errors and fixes", "code", Icons.Outlined.Terminal)
                ),
                onSelect = ::choose
            )

            CapabilitySection(
                title = "Safety",
                actions = listOf(
                    CapabilityAction("Tool approvals", "Review risky actions", "tool_center", Icons.Outlined.Security),
                    CapabilityAction("Permissions", "Control app access", "settings", Icons.Outlined.Lock)
                ),
                onSelect = ::choose
            )

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun CapabilitySection(
    title: String,
    actions: List<CapabilityAction>,
    onSelect: (String) -> Unit
) {
    ReferenceSectionTitle(
        title = title,
        modifier = Modifier.padding(top = 12.dp)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (action in actions.take(2)) {
            ReferenceCapabilityCard(
                icon = action.icon,
                title = action.title,
                subtitle = action.subtitle,
                onClick = { onSelect(action.target) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
