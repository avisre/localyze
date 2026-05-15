package com.localyze.ui.navigation

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
import androidx.compose.material.icons.outlined.Code
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
import com.localyze.BuildConfig
import com.localyze.data.repository.ModelRepository
import com.localyze.ui.components.ErrorBoundary
import com.localyze.ui.components.rememberErrorBoundaryState
import com.localyze.ui.screens.AttachmentMemoryScreen
import com.localyze.ui.screens.BackupScreen
import com.localyze.ui.screens.CapabilitiesScreen
import com.localyze.ui.screens.ChatScreen
import com.localyze.ui.screens.CodeWorkspaceScreen
import com.localyze.ui.screens.ConversationsScreen
import com.localyze.ui.screens.DebugToolTesterScreen
import com.localyze.ui.screens.OnboardingScreen
import com.localyze.ui.screens.PerformanceScreen
import com.localyze.ui.screens.ProactiveScreen
import com.localyze.ui.screens.ReplyAssistantScreen
import com.localyze.ui.screens.SettingsScreen
import com.localyze.ui.screens.TasksScreen
import com.localyze.ui.screens.ToolCenterScreen
import com.localyze.ui.theme.Background
import com.localyze.ui.theme.Primary
import com.localyze.ui.theme.Surface
import com.localyze.ui.viewmodels.CapabilitiesViewModel

sealed class BottomNavItem(
    val route: String,
    val icon: ImageVector,
    val label: String
) {
    data object Chat : BottomNavItem("chat", Icons.Outlined.ChatBubbleOutline, "Chat")
    data object Code : BottomNavItem("code_workspace", Icons.Outlined.Code, "Code")
    data object Library : BottomNavItem("conversations", Icons.Outlined.Folder, "Library")
    data object Settings : BottomNavItem("settings", Icons.Outlined.Settings, "Settings")
}

val bottomNavItems = listOf(
    BottomNavItem.Chat,
    BottomNavItem.Code,
    BottomNavItem.Library,
    BottomNavItem.Settings
)

@Composable
fun MainNavigation(
    sharedText: String? = null,
    sharedImageUris: List<String> = emptyList(),
    codeTestPrompt: String? = null,
    onSharedContentConsumed: () -> Unit = {},
    onCodeTestPromptConsumed: () -> Unit = {},
    modelRepository: ModelRepository,
    gemmaInferenceEngine: com.localyze.ai.GemmaInferenceEngine
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val startsInCodeWorkspaceForDebug = remember {
        BuildConfig.DEBUG && !codeTestPrompt.isNullOrBlank()
    }

    // Determine start destination: onboarding if model not ready, else chat.
    // Debug code-workspace launches are allowed to bypass model setup for device UI tests.
    val isModelReady = remember {
        val downloaded = modelRepository.isModelDownloaded()
        val ready = downloaded
        com.localyze.utils.AppLog.d("MainNavigation", "isModelDownloaded=$downloaded, modelPath=${modelRepository.getModelFilePath()}, modelSize=${modelRepository.getModelFileSize()}")
        ready
    }
    val shouldInitializeRealModel = remember {
        modelRepository.isModelDownloaded() && !BuildConfig.USE_TEST_DOWNLOAD
    }

    val startDestination = when {
        startsInCodeWorkspaceForDebug -> "code_workspace"
        isModelReady -> "chat"
        else -> "onboarding"
    }
    com.localyze.utils.AppLog.d("MainNavigation", "startDestination=$startDestination")

    // CRITICAL FIX: If model is already downloaded but we're going to chat directly,
    // we must still initialize the model in memory. The OnboardingViewModel only
    // runs if the user sees onboarding. Without this, chat would have no engine.
    if (shouldInitializeRealModel) {
        LaunchedEffect(Unit) {
            try {
                val state = gemmaInferenceEngine.modelLoadState.value
                com.localyze.utils.AppLog.d("MainNavigation", "Model already downloaded, current loadState=$state")
                if (state !is com.localyze.ai.ModelLoadState.Loaded && state !is com.localyze.ai.ModelLoadState.Loading) {
                    com.localyze.utils.AppLog.d("MainNavigation", "Auto-initializing model...")
                    gemmaInferenceEngine.initialize()
                    com.localyze.utils.AppLog.d("MainNavigation", "Model auto-init complete, state=${gemmaInferenceEngine.modelLoadState.value}")
                }
            } catch (e: Exception) {
                android.util.Log.e("MainNavigation", "Model auto-init FAILED: ${e.message}", e)
            }
        }
    }

    val isKeyboardVisible = WindowInsets.ime.asPaddingValues().calculateBottomPadding() > 0.dp

    // Bottom bar visibility: hidden during onboarding and while the keyboard is open.
    val showBottomBarBase = currentDestination?.route?.startsWith("chat") == true ||
            currentDestination?.route in listOf("code_workspace", "conversations", "settings")
    val showBottomBar = showBottomBarBase && !isKeyboardVisible

    LaunchedEffect(codeTestPrompt) {
        if (!startsInCodeWorkspaceForDebug && !codeTestPrompt.isNullOrBlank() && currentDestination != null) {
            navController.navigate("code_workspace") {
                launchSingleTop = true
            }
        }
    }

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
            // â”€â”€ Onboarding â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            composable("onboarding") {
                OnboardingScreen(
                    onModelReady = {
                        navController.navigate("chat") {
                            popUpTo("onboarding") { inclusive = true }
                        }
                    }
                )
            }

            // â”€â”€ Chat â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

            composable("code_workspace") {
                ErrorBoundary(state = rememberErrorBoundaryState()) {
                    CodeWorkspaceScreen(
                        codeTestPrompt = codeTestPrompt,
                        onCodeTestPromptConsumed = onCodeTestPromptConsumed
                    )
                }
            }

            // â”€â”€ Capabilities â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
                        onOpenCodeWorkspace = {
                            navController.navigate("code_workspace") {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
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

            // â”€â”€ Settings â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

            // â”€â”€ Tasks â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            composable("tasks") {
                TasksScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            // â”€â”€ Conversations â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

            // â”€â”€ Debug Tool Tester â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            composable("debug_tools") {
                val context = LocalContext.current
                val toolRegistry = remember {
                    com.localyze.di.ToolProvider.getToolRegistry(context)
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
