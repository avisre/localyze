package com.localassistant.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.localassistant.data.repository.ModelRepository
import com.localassistant.ui.components.ErrorBoundary
import com.localassistant.ui.components.rememberErrorBoundaryState
import com.localassistant.ui.screens.AttachmentMemoryScreen
import com.localassistant.ui.screens.BackupScreen
import com.localassistant.ui.screens.CapabilitiesScreen
import com.localassistant.ui.screens.ChatScreen
import com.localassistant.ui.screens.ConversationsScreen
import com.localassistant.ui.screens.DebugToolTesterScreen
import com.localassistant.ui.screens.OnboardingScreen
import com.localassistant.ui.screens.PerformanceScreen
import com.localassistant.ui.screens.ProactiveScreen
import com.localassistant.ui.screens.ReplyAssistantScreen
import com.localassistant.ui.screens.SettingsScreen
import com.localassistant.ui.screens.TasksScreen
import com.localassistant.ui.screens.ToolCenterScreen
import com.localassistant.ui.theme.Background
import com.localassistant.ui.theme.Primary
import com.localassistant.ui.theme.Surface
import com.localassistant.ui.viewmodels.CapabilitiesViewModel

sealed class BottomNavItem(
    val route: String,
    val icon: ImageVector,
    val label: String
) {
    data object Chat : BottomNavItem("chat", Icons.Outlined.ChatBubbleOutline, "Chat")
    data object Library : BottomNavItem("conversations", Icons.Outlined.Folder, "Library")
    data object Settings : BottomNavItem("settings", Icons.Outlined.Settings, "Settings")
}

val bottomNavItems = listOf(
    BottomNavItem.Chat,
    BottomNavItem.Library,
    BottomNavItem.Settings
)

@Composable
fun MainNavigation(
    sharedText: String? = null,
    sharedImageUris: List<String> = emptyList(),
    onSharedContentConsumed: () -> Unit = {},
    modelRepository: ModelRepository,
    gemmaInferenceEngine: com.localassistant.ai.GemmaInferenceEngine
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Determine start destination: onboarding if model not ready, else chat
    val isModelReady = remember {
        val downloaded = modelRepository.isModelDownloaded()
        android.util.Log.d("MainNavigation", "isModelDownloaded=$downloaded, modelPath=${modelRepository.getModelFilePath()}, modelSize=${modelRepository.getModelFileSize()}")
        downloaded
    }

    val startDestination = if (isModelReady) "chat" else "onboarding"
    android.util.Log.d("MainNavigation", "startDestination=$startDestination")

    // CRITICAL FIX: If model is already downloaded but we're going to chat directly,
    // we must still initialize the model in memory. The OnboardingViewModel only
    // runs if the user sees onboarding. Without this, chat would have no engine.
    if (isModelReady) {
        LaunchedEffect(Unit) {
            try {
                val state = gemmaInferenceEngine.modelLoadState.value
                android.util.Log.d("MainNavigation", "Model already downloaded, current loadState=$state")
                if (state !is com.localassistant.ai.ModelLoadState.Loaded && state !is com.localassistant.ai.ModelLoadState.Loading) {
                    android.util.Log.d("MainNavigation", "Auto-initializing model...")
                    gemmaInferenceEngine.initialize()
                    android.util.Log.d("MainNavigation", "Model auto-init complete, state=${gemmaInferenceEngine.modelLoadState.value}")
                }
            } catch (e: Exception) {
                android.util.Log.e("MainNavigation", "Model auto-init FAILED: ${e.message}", e)
            }
        }
    }

    val isKeyboardVisible = WindowInsets.ime.asPaddingValues().calculateBottomPadding() > 0.dp

    // Bottom bar visibility: hidden during onboarding and while the keyboard is open.
    val showBottomBarBase = currentDestination?.route?.startsWith("chat") == true ||
            currentDestination?.route in listOf("conversations", "settings")
    val showBottomBar = showBottomBarBase && !isKeyboardVisible

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = Surface,
                    tonalElevation = 0.dp
                ) {
                    bottomNavItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == item.route || it.route?.startsWith("${item.route}?") == true
                        } == true

                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label,
                                    modifier = Modifier.size(23.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            },
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Primary,
                                selectedTextColor = Primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = Primary.copy(alpha = 0.12f)
                            )
                        )
                    }
                }
            }
        },
        containerColor = Background
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ── Onboarding ──────────────────────────────────────────────────
            composable("onboarding") {
                OnboardingScreen(
                    onModelReady = {
                        navController.navigate("chat") {
                            popUpTo("onboarding") { inclusive = true }
                        }
                    }
                )
            }

            // ── Chat ─────────────────────────────────────────────────────────
            composable(
                route = "chat?conversationId={conversationId}",
                arguments = listOf(
                    navArgument("conversationId") {
                        type = NavType.LongType
                        defaultValue = -1L
                    }
                )
            ) {
                val errorBoundaryState = rememberErrorBoundaryState()
                ErrorBoundary(state = errorBoundaryState) {
                    ChatScreen(
                        onNewConversation = {
                            // ChatViewModel handles creating a new conversation internally
                        },
                        onOpenDrawer = {
                            navController.navigate("conversations")
                        },
                        onOpenAttachments = {
                            navController.navigate("attachments")
                        },
                        sharedText = sharedText,
                        sharedImageUris = sharedImageUris,
                        onSharedContentConsumed = onSharedContentConsumed
                    )
                }
            }

            // ── Capabilities ─────────────────────────────────────────────────
            composable("capabilities") {
                val capabilitiesViewModel: CapabilitiesViewModel = hiltViewModel()
                val createdConversationId by capabilitiesViewModel.createdConversationId.collectAsState()
                val errorBoundaryState = rememberErrorBoundaryState()

                // Navigate to chat when a capability creates a conversation
                LaunchedEffect(createdConversationId) {
                    if (createdConversationId != null) {
                        navController.navigate("chat?conversationId=$createdConversationId") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = false
                            }
                            launchSingleTop = false
                            restoreState = false
                        }
                        capabilitiesViewModel.consumeConversationId()
                    }
                }

                ErrorBoundary(state = errorBoundaryState) {
                    CapabilitiesScreen(
                        onCapabilitySelected = { mode ->
                            capabilitiesViewModel.selectCapability(mode)
                        },
                        onOpenTasks = {
                            navController.navigate("tasks")
                        },
                        onOpenAttachments = {
                            navController.navigate("attachments")
                        },
                        onOpenReplies = {
                            navController.navigate("reply_assistant")
                        },
                        onOpenToolCenter = {
                            navController.navigate("tool_center")
                        },
                        onOpenSettings = {
                            navController.navigate("settings") {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }

            // ── Settings ────────────────────────────────────────────────────
            composable("settings") {
                val errorBoundaryState = rememberErrorBoundaryState()
                ErrorBoundary(state = errorBoundaryState) {
                    SettingsScreen(
                        onNavigateToDebugTools = {
                            navController.navigate("debug_tools")
                        },
                        onNavigateToTasks = {
                            navController.navigate("tasks")
                        },
                        onNavigateToConversations = {
                            navController.navigate("conversations")
                        },
                        onNavigateToToolCenter = {
                            navController.navigate("tool_center")
                        },
                        onNavigateToAttachments = {
                            navController.navigate("attachments")
                        },
                        onNavigateToReplies = {
                            navController.navigate("reply_assistant")
                        },
                        onNavigateToPerformance = {
                            navController.navigate("performance")
                        },
                        onNavigateToBackups = {
                            navController.navigate("backups")
                        },
                        onNavigateToProactive = {
                            navController.navigate("proactive")
                        }
                    )
                }
            }

            // ── Tasks ────────────────────────────────────────────────────────
            composable("tasks") {
                TasksScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            // ── Conversations ─────────────────────────────────────────────────
            composable("conversations") {
                ConversationsScreen(
                    onBack = { navController.popBackStack() },
                    showBack = false,
                    onNavigateToChat = { conversationId ->
                        navController.navigate("chat?conversationId=$conversationId") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = false
                            }
                            launchSingleTop = false
                            restoreState = false
                        }
                    },
                    onCreateNewChat = {
                        navController.navigate("chat?conversationId=0") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = false
                            }
                            launchSingleTop = false
                            restoreState = false
                        }
                    }
                )
            }

            // ── Debug Tool Tester ─────────────────────────────────────────────
            composable("debug_tools") {
                val context = LocalContext.current
                val toolRegistry = remember {
                    com.localassistant.di.ToolProvider.getToolRegistry(context)
                }
                DebugToolTesterScreen(
                    toolRegistry = toolRegistry,
                    onBack = { navController.popBackStack() }
                )
            }

            composable("tool_center") {
                ToolCenterScreen(onBack = { navController.popBackStack() })
            }

            composable("attachments") {
                AttachmentMemoryScreen(onBack = { navController.popBackStack() })
            }

            composable("reply_assistant") {
                ReplyAssistantScreen(onBack = { navController.popBackStack() })
            }

            composable("performance") {
                PerformanceScreen(onBack = { navController.popBackStack() })
            }

            composable("backups") {
                BackupScreen(onBack = { navController.popBackStack() })
            }

            composable("proactive") {
                ProactiveScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
