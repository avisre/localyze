# Localyze â€” Codebase Agent Guide

## 1. Project Overview

**Localyze** is an Android application that provides a fully **on-device, privacy-first AI assistant** powered by Google's **Gemma 4 E4B** model. All inference runs locally on the device â€” no data ever leaves the user's phone. The app supports text, image (vision), and audio modalities, offers 6 capability modes, and provides an agentic tool-calling system with 9 built-in tools that execute against device capabilities (calendar, contacts, clipboard, alarms, tasks, memory, web search, file reading, system info).

| Attribute | Value |
|---|---|
| **Package** | `com.localyze` |
| **Min SDK** | 28 (Android 9) |
| **Target SDK** | 35 (Android 15) |
| **Language** | Kotlin 1.9.22 |
| **UI Framework** | Jetpack Compose + Material 3 |
| **DI** | Hilt (Dagger) |
| **Database** | Room (SQLite) with FTS4 |
| **AI Runtime** | MediaPipe GenAI LlmInference (LiteRT-LM) |
| **Model** | Gemma 4 E4B IT (~3.65 GB, .litertlm format) |
| **Model URL** | https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm |
| **Build System** | Gradle 8.10.2 + KSP |
| **Architecture** | MVVM + Clean Architecture (domain/data/ui layers) |
| **Emulator Target** | 16 GB RAM, 32 GB storage, API 34 |

---

## Model Availability Notice

### âœ… RESOLVED: Public LiteRT-LM Artifact

The Gemma 4 E4B LiteRT-LM model (`gemma-4-E4B-it.litertlm`) is **publicly available**.

- **Model card:** https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm
- **Direct download:** https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm
- **File size:** ~3.65 GB
- **Format:** LiteRT-LM (MediaPipe GenAI compatible)

### Mock Mode Reclassification

This project includes a `MockGemmaEngine` that simulates model responses. **Mock mode is NOT "because the real model doesn't exist."** The real model IS available.

Mock mode is retained for:
1. **Development velocity** â€” no 3.65 GB download for every clean build
2. **CI/CD automation** â€” automated tests without model dependency
3. **Unsupported-device fallback** â€” devices with <8GB RAM can't load the real model
4. **UI/UX testing** â€” verify streaming, tool-calling UI without inference cost

To use the real model: Set `USE_MOCK_ENGINE=false` and `USE_TEST_DOWNLOAD=false` in `build.gradle.kts` debug builds.

### Integration Status

- âœ… Download infrastructure implemented (resume, progress, storage checks)
- âœ… Real model URL configured in `ModelRepository.kt`
- âš ï¸ End-to-end integration needs device validation
- âš ï¸ SHA-256 hash verification pending official publication
- âš ï¸ Device compatibility matrix not yet established

See `BLOCKERS.md` for detailed migration path and technical TODOs.

---

## 2. Project Structure

```
app/src/main/java/com/localyze/
â”œâ”€â”€ LocalyzeApp.kt              # Hilt Application class, ensures models/ dir
â”œâ”€â”€ MainActivity.kt                   # Single-Activity entry, handles share intents
â”‚
â”œâ”€â”€ ai/                               # AI / Inference Layer
â”‚   â”œâ”€â”€ GemmaInferenceEngine.kt       # Core inference engine (MediaPipe LlmInference)
â”‚   â”œâ”€â”€ ContextWindowManager.kt       # Manages 128K-token context window, trimming
â”‚   â”œâ”€â”€ SystemPromptBuilder.kt        # Builds per-mode system prompts + tool schemas
â”‚   â”œâ”€â”€ AudioInputProcessor.kt        # Raw PCM recording at 16kHz mono 16-bit
â”‚   â”œâ”€â”€ InferenceToken.kt             # Sealed class: TextToken, ThinkingToken, ToolCallToken, EndOfStream, Error
â”‚   â”œâ”€â”€ ModelLoadState.kt             # Sealed class: NotLoaded, Loading, Loaded, Error
â”‚   â”œâ”€â”€ ModelExceptions.kt            # ModelNotFoundException, ModelLoadException, etc.
â”‚   â””â”€â”€ AudioRecordingState.kt        # Sealed class: Idle, Recording, Ready, Error
â”‚
â”œâ”€â”€ data/                             # Data Layer
â”‚   â”œâ”€â”€ local/                         # Room database & DAOs
â”‚   â”‚   â”œâ”€â”€ AppDatabase.kt            # Room DB with FTS4 virtual tables
â”‚   â”‚   â”œâ”€â”€ MessageDao.kt             # CRUD + FTS search for messages
â”‚   â”‚   â”œâ”€â”€ ConversationDao.kt         # CRUD + search for conversations
â”‚   â”‚   â”œâ”€â”€ MemoryDao.kt              # CRUD + keyword search for memories
â”‚   â”‚   â””â”€â”€ TaskDao.kt                # CRUD for tasks
â”‚   â””â”€â”€ repository/                    # Repository pattern implementations
â”‚       â”œâ”€â”€ ChatRepository.kt          # Interface for chat persistence
â”‚       â”œâ”€â”€ ChatRepositoryImpl.kt      # Full implementation with export
â”‚       â”œâ”€â”€ MemoryRepository.kt        # Interface for memory access
â”‚       â”œâ”€â”€ MemoryRepositoryImpl.kt    # Full implementation with save/delete/search
â”‚       â”œâ”€â”€ TaskRepository.kt          # Interface + implementation for tasks
â”‚       â”œâ”€â”€ ModelRepository.kt         # Model download, verification, storage checks
â”‚       â””â”€â”€ DownloadProgress.kt        # Sealed class: Downloading, Verifying, Complete, Error
â”‚
â”œâ”€â”€ di/                                # Dependency Injection Modules
â”‚   â”œâ”€â”€ AppModule.kt                   # OkHttpClient, models dir, cache dir
â”‚   â”œâ”€â”€ DatabaseModule.kt              # Room DB, DAOs, repositories
â”‚   â”œâ”€â”€ AIModule.kt                    # Placeholder for future AI-specific bindings
â”‚   â””â”€â”€ ToolModule.kt                  # Placeholder for future tool bindings
â”‚
â”œâ”€â”€ domain/                            # Domain Layer (Clean Architecture)
â”‚   â”œâ”€â”€ models/                        # Entity / value objects
â”‚   â”‚   â”œâ”€â”€ Message.kt                 # Room entity + MessageRole enum + TypeConverters
â”‚   â”‚   â”œâ”€â”€ Conversation.kt            # Room entity with capabilityMode
â”‚   â”‚   â”œâ”€â”€ Memory.kt                  # Room entity with keywords list
â”‚   â”‚   â”œâ”€â”€ Task.kt                    # Room entity with dueDate, completion
â”‚   â”‚   â”œâ”€â”€ ToolCall.kt                # Serializable with JsonObject arguments
â”‚   â”‚   â””â”€â”€ ToolResult.kt              # callId, name, result, isError
â”‚   â””â”€â”€ usecases/                      # Business logic orchestrators
â”‚       â”œâ”€â”€ SendMessageUseCase.kt       # Full agentic generation loop (â‰¤3 tool iterations)
â”‚       â”œâ”€â”€ ExecuteToolUseCase.kt       # Thin wrapper over ToolDispatcher
â”‚       â”œâ”€â”€ ManageMemoryUseCase.kt      # Memory CRUD + keyword extraction
â”‚       â”œâ”€â”€ RecordAudioUseCase.kt       # Audio recording orchestration
â”‚       â””â”€â”€ ChatResponseEvent.kt       # Sealed class for streaming events
â”‚
â”œâ”€â”€ tools/                             # Agentic Tool System
â”‚   â”œâ”€â”€ Tool.kt                        # Interface: name, description, execute(), getParameterSchema()
â”‚   â”œâ”€â”€ ToolRegistry.kt               # Central registry, auto-wires all 9 tools
â”‚   â”œâ”€â”€ ToolDispatcher.kt             # Dispatches ToolCalls, handles errors
â”‚   â”œâ”€â”€ CalendarTool.kt               # Read/write Android Calendar
â”‚   â”œâ”€â”€ ContactsTool.kt               # Search contacts by name
â”‚   â”œâ”€â”€ AlarmTool.kt                  # Set exact/inexact alarms
â”‚   â”œâ”€â”€ ClipboardTool.kt             # Read/write system clipboard
â”‚   â”œâ”€â”€ SystemInfoTool.kt             # Battery, WiFi, storage, device info
â”‚   â”œâ”€â”€ WebSearchTool.kt              # DuckDuckGo Instant Answer API
â”‚   â”œâ”€â”€ FileReaderTool.kt             # Read text files from URI
â”‚   â”œâ”€â”€ MemoryTool.kt                # Save/search long-term memories
â”‚   â””â”€â”€ TaskTool.kt                   # Create/list/complete to-do tasks
â”‚
â””â”€â”€ ui/                                # Presentation Layer
    â”œâ”€â”€ navigation/
    â”‚   â””â”€â”€ MainNavigation.kt          # NavHost with 4 routes: onboarding, chat, capabilities, settings
    â”œâ”€â”€ screens/
    â”‚   â”œâ”€â”€ ChatScreen.kt              # Main chat UI with messages, input bar, mic, image attach
    â”‚   â”œâ”€â”€ OnboardingScreen.kt        # Multi-step model download flow
    â”‚   â”œâ”€â”€ CapabilitiesScreen.kt      # 2Ã—3 grid of capability modes
    â”‚   â””â”€â”€ SettingsScreen.kt          # Toggles, model info, memories, permissions, about
    â”œâ”€â”€ components/
    â”‚   â”œâ”€â”€ RobotMascot.kt             # Canvas-drawn animated robot with blink, bob, breath
    â”‚   â”œâ”€â”€ MessageBubble.kt           # User (terracotta pill) and Assistant (cream card) bubbles
    â”‚   â”œâ”€â”€ ThinkingBubble.kt          # Collapsible thinking trace with sparkle icon
    â”‚   â”œâ”€â”€ ToolIndicator.kt           # Animated pill: "Calling X..." â†’ "Used: X âœ“"
    â”‚   â”œâ”€â”€ CapabilityCard.kt          # Grid card with Canvas-drawn icon per capability
    â”‚   â”œâ”€â”€ AudioRecorderButton.kt     # Microphone button with recording state
    â”‚   â”œâ”€â”€ AudioWaveform.kt           # Amplitude bar visualizer (40 bars)
    â”‚   â”œâ”€â”€ SettingsRow.kt             # SettingsToggleRow and SettingsChevronRow
    â”‚   â””â”€â”€ RelativeTimestamp.kt       # "just now"/"5m ago"/"yesterday" formatting
    â”œâ”€â”€ viewmodels/
    â”‚   â”œâ”€â”€ ChatViewModel.kt           # Chat state, message sending, audio, tool calls
    â”‚   â”œâ”€â”€ ChatUiState.kt            # Data class for chat screen state
    â”‚   â”œâ”€â”€ OnboardingViewModel.kt     # Model download and initialization lifecycle
    â”‚   â”œâ”€â”€ OnboardingUiState.kt       # Sealed class for onboarding steps
    â”‚   â”œâ”€â”€ CapabilitiesViewModel.kt   # Creates conversations from capability selection
    â”‚   â”œâ”€â”€ CapabilitiesUiState.kt     # Selected mode and CAPABILITIES list
    â”‚   â”œâ”€â”€ SettingsViewModel.kt       # Toggles, memories, model management
    â”‚   â””â”€â”€ SettingsUiState.kt         # Settings state data class
    â””â”€â”€ theme/
        â”œâ”€â”€ Color.kt                   # Light/dark color palette (warm cream/terracotta)
        â”œâ”€â”€ Theme.kt                   # MaterialTheme wrapper
        â”œâ”€â”€ Shape.kt                   # RoundedCorner shape definitions
        â””â”€â”€ Type.kt                    # Nunito typography scale
```

---

## 3. Architecture Diagram

```
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚                         UI LAYER (Compose)                          â”‚
â”‚  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  â”‚
â”‚  â”‚ Onboardingâ”‚  â”‚   ChatScreen  â”‚  â”‚ Capabilitiesâ”‚  â”‚  Settings  â”‚  â”‚
â”‚  â”‚  Screen   â”‚  â”‚               â”‚  â”‚   Screen   â”‚  â”‚   Screen   â”‚  â”‚
â”‚  â””â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”˜  â””â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”˜  â””â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”˜  â””â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”˜  â”‚
â”‚        â”‚               â”‚                  â”‚               â”‚          â”‚
â”‚  â”Œâ”€â”€â”€â”€â”€â–¼â”€â”€â”€â”€â”€â”  â”Œâ”€â”€â”€â”€â”€â”€â–¼â”€â”€â”€â”€â”€â”€â”€â”€â”  â”Œâ”€â”€â”€â”€â”€â–¼â”€â”€â”€â”€â”€â”€â”  â”Œâ”€â”€â”€â”€â”€â–¼â”€â”€â”€â”€â”€â”€â”  â”‚
â”‚  â”‚ Onboarding â”‚  â”‚ ChatViewModel â”‚  â”‚ Capabilitiesâ”‚  â”‚  Settings  â”‚  â”‚
â”‚  â”‚  ViewModel â”‚  â”‚               â”‚  â”‚  ViewModel â”‚  â”‚  ViewModel â”‚  â”‚
â”‚  â””â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”˜  â””â”€â”€â”¬â”€â”€â”€â”€â”¬â”€â”€â”€â”¬â”€â”€â”˜  â””â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”˜  â””â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”˜  â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”¼â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¼â”€â”€â”€â”€â”¼â”€â”€â”€â”¼â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¼â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¼â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
         â”‚            â”‚    â”‚   â”‚             â”‚               â”‚
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â–¼â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â–¼â”€â”€â”€â”€â–¼â”€â”€â”€â–¼â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â–¼â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â–¼â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚                      DOMAIN LAYER (Use Cases)                        â”‚
â”‚  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â” â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â” â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  â”‚
â”‚  â”‚ SendMessageUseCaseâ”‚ â”‚ RecordAudio   â”‚ â”‚ ManageMemoryUseCase   â”‚  â”‚
â”‚  â”‚ (agentic loop)   â”‚ â”‚ UseCase       â”‚ â”‚                        â”‚  â”‚
â”‚  â””â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”˜ â””â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜ â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜  â”‚
â”‚       â”‚         â”‚             â”‚                       â”‚              â”‚
â”‚  â”Œâ”€â”€â”€â”€â–¼â”€â”€â” â”Œâ”€â”€â”€â”€â–¼â”€â”€â”€â”€â”€â”€â”€â” â”Œâ”€â”€â–¼â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”       â”‚              â”‚
â”‚  â”‚ Executeâ”‚ â”‚ Context    â”‚ â”‚ AudioInput     â”‚       â”‚              â”‚
â”‚  â”‚ ToolUC â”‚ â”‚ WindowMgr  â”‚ â”‚ Processor      â”‚       â”‚              â”‚
â”‚  â””â”€â”€â”€â”€â”¬â”€â”€â”˜ â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜ â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜       â”‚              â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”¼â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¼â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
        â”‚                                             â”‚
â”Œâ”€â”€â”€â”€â”€â”€â”€â–¼â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â–¼â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚                        DATA LAYER                                    â”‚
â”‚  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â” â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â” â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”   â”‚
â”‚  â”‚ ChatRepository   â”‚ â”‚ ModelRepo    â”‚ â”‚ MemoryRepository     â”‚   â”‚
â”‚  â”‚ (Impl)           â”‚ â”‚ (download)    â”‚ â”‚ (Impl)               â”‚   â”‚
â”‚  â””â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜ â””â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”˜ â””â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜   â”‚
â”‚       â”‚                       â”‚                â”‚                     â”‚
â”‚  â”Œâ”€â”€â”€â”€â–¼â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â–¼â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â–¼â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”    â”‚
â”‚  â”‚           Room Database (AppDatabase)                       â”‚    â”‚
â”‚  â”‚   MessageDao | ConversationDao | MemoryDao | TaskDao       â”‚    â”‚
â”‚  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜    â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜

â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚                        AI LAYER                                     â”‚
â”‚  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”              â”‚
â”‚  â”‚ GemmaInferenceEngine â”‚  â”‚  SystemPromptBuilder   â”‚              â”‚
â”‚  â”‚ (MediaPipe LiteRT-LM)â”‚  â”‚  (per-mode prompts)   â”‚              â”‚
â”‚  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜              â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜

â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚                        TOOL LAYER                                   â”‚
â”‚  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â” â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â” â”Œâ”€â”€â”€â”€â”€â”€â”€â” â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â” â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  â”‚
â”‚  â”‚ Calendar   â”‚ â”‚ Contacts â”‚ â”‚ Alarm â”‚ â”‚ Clipboardâ”‚ â”‚  System  â”‚  â”‚
â”‚  â”‚ Tool       â”‚ â”‚ Tool     â”‚ â”‚ Tool  â”‚ â”‚ Tool     â”‚ â”‚ InfoTool  â”‚  â”‚
â”‚  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜ â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜ â””â”€â”€â”€â”€â”€â”€â”€â”˜ â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜ â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜  â”‚
â”‚  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â” â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â” â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â” â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â” â”‚
â”‚  â”‚ WebSearch  â”‚ â”‚ FileRead â”‚ â”‚ Memory â”‚   â”‚ Task                 â”‚ â”‚
â”‚  â”‚ Tool       â”‚ â”‚ Tool     â”‚ â”‚ Tool   â”‚   â”‚ Tool                 â”‚ â”‚
â”‚  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜ â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜ â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜ â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜ â”‚
â”‚                                                                     â”‚
â”‚  ToolRegistry â”€â”€â–º ToolDispatcher â”€â”€â–º Tool.execute(args)             â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
```

---

## 4. Key Components In Detail

### 4.1 AI Inference Engine (`GemmaInferenceEngine`)

The centerpiece of the app. Uses **MediaPipe's LlmInference** API with the `.litertlm` model format.

- **Model file**: `gemma-4-E4B-it.litertlm` (~3.4 GB), stored at `/data/data/com.localyze/files/models/`
- **Download URL**: HuggingFace `litert-community/gemma-4-E4B-it-litert-lm`
- **Inference backends**: CPU (XNNPack, ~17 tok/s) or GPU (ML Drift, ~22 tok/s)
- **Context window**: Up to 128K tokens (managed by `ContextWindowManager`)
- **Streaming**: Real-time token streaming via `generateResponseAsync()` with `Flow<InferenceToken>`
- **Multimodal**: Text (primary), Image (via `generateResponseWithImage`), Audio (via `generateResponseWithAudio`)
- **Session management**: Single-turn sessions rebuilt per conversation turn; `resetSession()` clears context
- **Tool call parsing**: Regex-based extraction of `{"name": "...", "arguments": {...}}` JSON from model output
- **Thinking mode**: Recognizes Gemma 4's unicode special tokens `\uF7B8`/`\uF7B9` for thinking blocks
- **Fallback mode**: When model isn't loaded, returns simulated per-mode responses for UI testing

**Key methods:**
| Method | Purpose |
|---|---|
| `initialize()` | Load model into memory, create first session |
| `generateResponse(messages, capabilityMode, enableThinking)` | Text-ony streaming inference |
| `generateResponseWithImage(messages, imageBitmap, prompt, ...)` | Vision inference |
| `generateResponseWithAudio(messages, audioBytes, prompt, ...)` | Audio inference (partial fallback) |
| `resetSession()` | Clear context window for new conversation |
| `release()` | Unload model, free memory |

### 4.2 Context Window Manager (`ContextWindowManager`)

Ensures conversations fit within Gemma 4 E4B's 128K token context:

- **Token estimation**: Dual heuristic â€” character-based (2 chars/tok for code, 4 chars/tok for English) + word-based (1.3 words/tok), averaged
- **Truncation strategy**: Starts from most recent messages, works backwards; drops oldest when budget exceeded
- **Reserved space**: 4,000 tokens for system prompt, 2,048 tokens for response
- **Memory injection**: User memories are prepended as SYSTEM messages at context start

### 4.3 System Prompt Builder (`SystemPromptBuilder`)

Generates the system prompt injected before each conversation:

1. **Mode-specific base prompt** â€” one of 6 tailored prompts (chat, see, write, brainstorm, code, data)
2. **Thinking instruction** â€” appended when `enableThinking = true`
3. **Tool schema section** â€” lists all registered tools with JSON parameter schemas
4. **Date/time context** â€” current date and time for temporal grounding

### 4.4 Audio Input Processor (`AudioInputProcessor`)

Records raw PCM audio for the model's native audio input:

- **Format**: 16kHz, mono, 16-bit PCM (as required by Gemma 4 E4B)
- **API**: Android `AudioRecord` (NOT MediaRecorder â€” model needs raw PCM bytes)
- **Max duration**: 30 seconds
- **Amplitude visualization**: RMS normalization to 0f-1f range, emitted at ~20Hz
- **State machine**: Idle â†’ Recording â†’ Ready (with audioData + durationMs)
- **Temp file caching**: Saved to `cacheDir/audio_rec_*.pcm`

### 4.5 Agentic Tool-Calling Loop (`SendMessageUseCase`)

The core "agent" â€” orchestrates the full message-sending flow:

```
1. Save user message â†’ DB
2. Build context window (system prompt + memories + messages)
3. Call GemmaInferenceEngine.generateResponse()
4. Collect streaming tokens â†’ emit ChatResponseEvents
5. If ToolCallToken received:
   a. Emit ToolCallStarted
   b. Execute tool via ToolDispatcher
   c. Save TOOL result message â†’ DB
   d. Emit ToolCallCompleted
   e. Re-invoke generateResponse with tool results injected
   f. Loop up to MAX_TOOL_ITERATIONS (3)
6. Save final ASSISTANT message â†’ DB
7. Auto-generate conversation title from first AI response
```

**Three input modes:**
- `sendMessage()` â€” text only
- `sendMessageWithImage()` â€” text + Bitmap
- `sendMessageWithAudio()` â€” raw PCM bytes

Additional capabilities:
- `regenerateLastResponse()` â€” deletes last assistant message and re-runs
- `stopGeneration()` â€” cancels the active coroutine job

### 4.6 Tool System

All tools implement the `Tool` interface:
```kotlin
interface Tool {
    val name: String
    val description: String
    suspend fun execute(args: JsonObject): String
    fun getParameterSchema(): JsonObject
}
```

**Tool Registry** auto-wires all 9 tools via Hilt constructor injection:

| Tool | Name | Capabilities |
|---|---|---|
| `CalendarTool` | `calendar` | Read events (date range), write events (title, time, location) |
| `ContactsTool` | `contacts_search` | Search contacts by name, returns phones + emails |
| `AlarmTool` | `alarm_set` | Set one-shot or repeating alarms, exact/inexact fallback |
| `ClipboardTool` | `clipboard` | Read current clipboard content, write text to clipboard |
| `SystemInfoTool` | `system_info` | Battery level/charging, WiFi status/SSID, storage used/available |
| `WebSearchTool` | `web_search` | DuckDuckGo Instant Answer API (abstracts, results, definitions) |
| `FileReaderTool` | `file_read` | Read text files (.txt, .md, .csv, .json, .kt, etc.) |
| `MemoryTool` | `memory` | Save facts with keywords, search saved memories |
| `TaskTool` | `task` | Create tasks, list (pending/completed/all), mark complete |

**ToolDispatcher** handles execution with error wrapping (SecurityException, generic Exception).

### 4.7 Model Repository (`ModelRepository`)

Manages model download lifecycle:

- **Download flow**: Create `.tmp` file â†’ stream HTTP response in 8KB chunks â†’ emit `DownloadProgress` every 200ms â†’ SHA-256 verify (if hash set) â†’ rename `.tmp` â†’ `.litertlm`
- **Storage check**: Requires model size (~3.4 GB) + 500 MB buffer free
- **RAM check**: Requires â‰¥8 GB total device RAM
- **Demo mode**: `DEMO_MODE = false` in production; when true, simulates download progress
- **Recovery**: On failure/cancellation, deletes `.tmp` file; on retry, starts clean

### 4.8 Room Database (`AppDatabase`)

- **Version**: 1
- **Entities**: `Message`, `Conversation`, `Memory`, `Task`
- **FTS4 tables**: `messages_fts` (content + thinking_content + tool_result), `conversations_fts` (title)
- **TypeConverters**: `MessageRoleConverter` (enum â†” String), `StringListConverter` (List<String> â†” `|||`-delimited)
- **Singleton pattern**: `AppDatabase.getInstance(context)` with `@Volatile` + `synchronized`

### 4.9 Navigation

4 routes managed by `MainNavigation`:

1. **`onboarding`** â€” Shown when model isn't downloaded. Multi-step: Welcome â†’ Checking â†’ ReadyToDownload â†’ Downloading â†’ Verifying â†’ ReadyToChat (or Error/InsufficientRam/InsufficientStorage)
2. **`chat`** â€” Main screen with message list, input bar, and robot mascot
3. **`capabilities`** â€” 2Ã—3 grid of capability modes, creates conversation and navigates to chat
4. **`settings`** â€” Toggle preferences, model info, memory management, permissions, about

**Start destination**: `onboarding` if model not downloaded, `chat` if already available

**Bottom navigation**: 3 tabs (Chat, Capabilities, Settings); hidden during onboarding

### 4.10 Share Intents

`MainActivity` handles incoming `ACTION_SEND` and `ACTION_SEND_MULTIPLE` intents:
- **Text**: Extracts `EXTRA_TEXT` â†’ sets `sharedText` state
- **Image(s)**: Extracts URI(s) â†’ sets `sharedImageUris` state

These are passed to `MainNavigation` for the chat screen to consume.

---

## 5. UI Design System

### 5.1 Color Palette (Light)

| Token | Color | Hex |
|---|---|---|
| Background | Warm cream | `#FDF6EE` |
| Surface | Light cream | `#FAF0E4` |
| SurfaceVariant | Tan | `#F5E6D4` |
| Primary | Terracotta orange | `#E8865A` |
| Secondary | Dusty blue | `#6C9EBF` |
| OnPrimary | White | `#FFFFFF` |
| OnBackground | Dark brown | `#2D2417` |
| TextSecondary | Muted brown | `#8A7060` |
| Error | Red | `#D84040` |

### 5.2 Key UI Components

- **RobotMascot**: Canvas-drawn animated robot with body, antenna, ears, eyes (with blinking), and smile. Two animation states: thinking (4dp bob at 1.5s) and idle (1dp breathing at 3s). Eyes blink every ~3.5s.
- **MessageBubble**: User messages = terracotta pill (right-aligned), Assistant messages = cream card with warm border (left-aligned). Streaming mode shows blinking cursor.
- **ThinkingBubble**: Collapsible section with sparkle icon and chevron. Shows AI reasoning traces in italic when expanded.
- **ToolIndicator**: Animated pill showing tool call progress. Blue tint, spinning indicator during execution, checkmark when complete.
- **CapabilityCard**: 2Ã—3 grid card with Canvas-drawn icon (speech bubble, eye, document, lightbulb, code brackets, chart) and spring animation on press.
- **AudioRecorderButton**: Microphone button that morphs from idle â†’ recording (red pulsing circle) â†’ ready (play icon)
- **AudioWaveform**: 40-bar amplitude visualizer with terracotta gradient

### 5.3 Capability Modes

| Mode | System | Icon | Color |
|---|---|---|
| `chat` | General assistant | Speech bubble | `#A8C8E8` (blue) |
| `see` | Visual analysis | Eye | `#F0B090` (orange) |
| `write` | Writing assistant | Document | `#A8D4B0` (green) |
| `brainstorm` | Creative ideation | Lightbulb | `#F0D890` (yellow) |
| `code` | Programming help | Code brackets `</>` | `#C8B8E8` (purple) |
| `data` | Data analysis | Bar chart | `#A8D4D0` (teal) |

---

## 6. Data Flow: Sending a Message

```
User types "What's on my calendar today?"
    â”‚
    â–¼
ChatViewModel.sendMessage(text)
    â”‚
    â–¼
SendMessageUseCase.sendMessage(conversationId, text, capabilityMode="chat", enableThinking=true)
    â”‚
    â”œâ”€â”€ 1. Save USER message â†’ Room DB
    â”‚
    â”œâ”€â”€ 2. Fetch memories â†’ MemoryRepository.getAllMemories()
    â”‚
    â”œâ”€â”€ 3. Build context window â†’ ContextWindowManager.buildContextWindow()
    â”‚       - System prompt (chat mode + tools + date/time)
    â”‚       - Recent messages (trimmed to fit 128K tokens)
    â”‚       - Memories injected as SYSTEM message
    â”‚
    â”œâ”€â”€ 4. Format as Gemma prompt â†’ <start_of_turn>system ... <end_of_turn> ...
    â”‚
    â”œâ”€â”€ 5. Stream inference â†’ GemmaInferenceEngine.generateResponse()
    â”‚       â”‚
    â”‚       â”œâ”€â”€ MediaPipe loaded? â†’ Real LlmInferenceSession
    â”‚       â”‚       - session.addQueryChunk(prompt)
    â”‚       â”‚       - session.generateResponseAsync { partial, done â†’ }
    â”‚       â”‚       - Parse thinking blocks (âŸ¨F7B8âŸ©...âŸ¨F7B9âŸ©)
    â”‚       â”‚       - Parse tool calls ({"name":"calendar","arguments":{...}})
    â”‚       â”‚
    â”‚       â””â”€â”€ Model not loaded? â†’ Fallback simulated response
    â”‚
    â”œâ”€â”€ 6. Token stream â†’ InferenceToken.TextToken / ThinkingToken / ToolCallToken
    â”‚
    â”œâ”€â”€ 7. Emit ChatResponseEvents:
    â”‚       - StreamingToken â†’ UI appends to streaming text
    â”‚       - ThinkingToken â†’ UI shows thinking bubble
    â”‚       - ToolCallToken â†’ Agentic loop:
    â”‚           â”œâ”€â”€ Emit ToolCallStarted("calendar")
    â”‚           â”œâ”€â”€ Execute CalendarTool.execute({action:"read", start_date:"today"})
    â”‚           â”œâ”€â”€ Save TOOL message â†’ DB
    â”‚           â”œâ”€â”€ Emit ToolCallCompleted("calendar", result)
    â”‚           â””â”€â”€ Re-invoke generateResponse with tool result (iteration 2)
    â”‚
    â”œâ”€â”€ 8. Save ASSISTANT message â†’ Room DB
    â”‚
    â””â”€â”€ 9. Auto-generate title â†’ First 40 chars of response â†’ update conversation
```

---

## 7. Permissions

The app requests the following runtime permissions:

| Permission | Purpose | Used By |
|---|---|---|
| `CAMERA` | Image capture for vision mode | Future camera integration |
| `READ_CONTACTS` | Contact search tool | `ContactsTool` |
| `READ_CALENDAR` | Calendar event reading | `CalendarTool` |
| `WRITE_CALENDAR` | Calendar event creation | `CalendarTool` |
| `RECORD_AUDIO` | Voice input | `AudioInputProcessor` |
| `READ_MEDIA_IMAGES` | Image sharing/attachment | Share intent handler |
| `READ_EXTERNAL_STORAGE` | Legacy storage access (API â‰¤32) | File access |
| `POST_NOTIFICATIONS` | Alarm notifications (API 33+) | `AlarmTool` |
| `INTERNET` | Model download + web search | `ModelRepository`, `WebSearchTool` |
| `SCHEDULE_EXACT_ALARM` | Exact alarm scheduling (API 31+) | `AlarmTool` |

---

## 8. Build & Run Instructions

### Prerequisites
- **JDK**: 17 (Microsoft OpenJDK 17.0.18+)
- **Android SDK**: API 28â€“35, with Build Tools 35.x
- **Gradle**: 8.10.2 (wrapper included)
- **Kotlin**: 1.9.22 with KSP 1.9.22-1.0.17

### Build
```bash
# Debug APK
gradlew.bat assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk (~168 MB due to ML libs)

# Release APK (no minification currently)
gradlew.bat assembleRelease
```

### Install & Run on Emulator
```bash
# Create AVD with 16GB RAM, 32GB storage
avdmanager create avd -n Gemma_16GB -k "system-images;android-34;google_apis;x86_64" \
  -d "pixel_6" --sdcard 32G

# Configure RAM in config.ini:
# hw.ramSize=16384
# disk.dataPartition.size=32GB

# Start emulator
emulator -avd Gemma_16GB -memory 16384

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb shell am start -n com.localyze/.MainActivity
```

### Current Emulator Setup (Staged)
- **AVD**: Already running on `emulator-5554`
- **RAM**: ~16 GB (16,384,404 kB MemTotal)
- **Storage**: ~31 GB data partition
- **Android**: API 34 (Android 14)
- **Device**: `sdk_gphone64_x86_64`
- **App**: Installed and running successfully

---

## 9. Key Dependencies

| Dependency | Version | Purpose |
|---|---|---|
| Compose BOM | 2024.02.00 | UI toolkit |
| Material 3 | 1.2.1 | Design system |
| Hilt | 2.51 | Dependency injection |
| Room | 2.6.1 | Local database (SQLite + FTS4) |
| MediaPipe GenAI | 0.10.29 | On-device LLM inference |
| TensorFlow Lite | 0.4.4 | ML task text (supplementary) |
| OkHttp | 4.12.0 | HTTP client (model download, web search) |
| Navigation Compose | 2.7.7 | Screen navigation |
| CameraX | 1.3.1 | Camera integration |
| Work Manager | 2.9.0 | Background tasks |
| Kotlin Serialization | 1.6.3 | JSON parsing (tool args, DuckDuckGo API) |
| DataStore | 1.0.0 | Key-value preferences |

---

## 10. Testing

### Instrumented Tests (`DataLayerIntegrationTest`)
Located at `app/src/androidTest/java/com/localyze/DataLayerIntegrationTest.kt`

Covers:
- Model repository: dir existence, download detection, storage/RAM checks, URL/size validation, deletion
- Chat repository: conversation creation (all 6 modes), message CRUD, conversation isolation
- Memory repository: save, search, delete
- Download progress sealed class
- Model load state sealed class
- Message/MessageRole data models

### How to Run Tests
```bash
# Instrumented tests (requires emulator or device)
gradlew.bat connectedDebugAndroidTest
```

---

## 11. State Flow Summary

### Onboarding States
```
Welcome â†’ CheckingModel â†’ (InsufficientRam | InsufficientStorage | ReadyToDownload)
ReadyToDownload â†’ Downloading(progress) â†’ Verifying(percent) â†’ ReadyToChat
                                    â†˜ Error(message, isRetryable)
```

### Chat States
```
ChatUiState {
    currentConversationId: Long
    messages: List<Message>
    isStreaming: Boolean
    isThinking: Boolean
    streamingText: String          # In-progress response text
    thinkingText: String           # In-progress thinking text
    activeToolCalls: List<ActiveToolCall>  # [{toolName, isExecuting, result}]
    capabilityMode: String        # "chat" | "see" | "write" | "brainstorm" | "code" | "data"
    enableThinking: Boolean
    showMascot: Boolean           # True when no messages
    error: String?
}
```

### Settings State
```
SettingsUiState {
    darkMode, thinkingMode, streamTokens, voiceAutoPlay, allowWebSearch: Boolean
    memories: List<Memory>
    memoryCount: Int
    modelInfo: ModelInfo { isLoaded, isDownloaded, modelSizeMb, ... }
    storageInfo: StorageInfo { availableStorageGb, ... }
    isMemorySectionExpanded: Boolean
    memorySearchQuery: String
    searchResults: List<Memory>
    showDeleteModelDialog, showClearMemoriesDialog: Boolean
}
```

---

## 12. Important Implementation Notes

### Model Format
The `.litertlm` format is **not** Safetensors or GGUF â€” it's Google's optimized on-device format for MediaPipe GenAI inference with XNNPack (CPU) and ML Drift (GPU) acceleration. It bundles text decoder weights, embedding parameters, vision encoder, and audio encoder in a single file. Vision and audio encoders are loaded **on-demand** when multimodal input is provided.

### Session Management
MediaPipe `LlmInferenceSession` objects are **single-turn** â€” they don't accumulate context across multiple `addQueryChunk` calls as a chat. The engine rebuilds the full prompt (all history + system prompt) each turn and creates a fresh session. This is handled by `createNewSession()` after each `generateResponse()` completion.

### Agentic Loop Limit
The tool-calling loop is capped at **3 iterations** (`MAX_TOOL_ITERATIONS = 3`) to prevent infinite loops. Each iteration may produce multiple tool calls, all of which are executed sequentially. If the model still produces tool calls after 3 iterations, the accumulated text is saved as-is.

### Memory Persistence
Memories are stored in Room as `Memory` entities with `content` (the fact), `keywords` (list stored as `|||`-delimited string), and `lastAccessedAt`. The `MemoryTool` can save new memories and search existing ones. The `ContextWindowManager` injects relevant memories into the system prompt for personalized responses.

### Audio Pipeline
Audio goes through: `AudioInputProcessor` (raw PCM at 16kHz) â†’ `RecordAudioUseCase` (orchestration) â†’ `ChatViewModel` (state management) â†’ `SendMessageUseCase.sendMessageWithAudio()` â†’ `GemmaInferenceEngine.generateResponseWithAudio()`. The LiteRT-LM audio API is not yet fully exposed in the MediaPipe session API, so audio inference currently falls back to text-based handling.

### Share Intent Feature
The app registers for `ACTION_SEND` (text and image) and `ACTION_SEND_MULTIPLE` (images) intents. Shared content is captured in `MainActivity` as `sharedText` and `sharedImageUris` compose state, passed down through `MainNavigation`. This enables "Share to Localyze" functionality from other apps.

### Prompt Formatting
Messages are formatted using Gemma 4's chat template:
```
<start_of_turn>system
{system_prompt}
<end_of_turn>
<start_of_turn>user
{user_message}
<end_of_turn>
<start_of_turn>model
{assistant_response}  â† generation continues here
<end_of_turn>
```
Tool results are injected as `<start_of_turn>user\n[Tool Result: {toolName}] {result}\n<end_of_turn>`.

### Feature Flags
- `ModelRepository.DEMO_MODE = false` â€” When `true`, simulates model presence so UI can be tested without the 3.4 GB download
- `ModelRepository.SHA256_HASH = ""` â€” When empty, SHA-256 verification is skipped after download

---

## 13. File Count & Lines of Code

| Layer | Files | Approx. LOC |
|---|---|---|
| AI (`ai/`) | 7 | ~1,200 |
| Data (`data/`) | 11 | ~1,400 |
| Domain (`domain/`) | 10 | ~900 |
| Tools (`tools/`) | 11 | ~1,800 |
| UI Screens | 4 | ~2,000 |
| UI Components | 9 | ~1,100 |
| UI ViewModels | 8 | ~800 |
| UI Theme | 4 | ~200 |
| DI (`di/`) | 4 | ~80 |
| Entry Points | 2 | ~80 |
| **Total** | **70** | **~9,560** |

---

## 14. Common Development Tasks

### Adding a New Tool
1. Create `app/src/main/java/com/localyze/tools/NewTool.kt` implementing the `Tool` interface
2. Add `@Inject constructor(...)` with required dependencies
3. Add the tool as a constructor parameter to `ToolRegistry`
4. Register it in `ToolRegistry.init { register(newTool) }`
5. The tool will automatically appear in the system prompt and be callable by the model

### Adding a New Capability Mode
1. Add the mode string and prompt in `SystemPromptBuilder`
2. Add a `CapabilityItem` in `CapabilitiesUiState.kt` â†’ `CAPABILITIES` list
3. Add a Canvas icon drawer in `CapabilityCard.drawCapabilityIcon()`
4. Add fallback responses in `GemmaInferenceEngine.generateResponse()`

### Changing the Model
1. Update `ModelRepository.MODEL_FILENAME`, `MODEL_URL`, `MODEL_SIZE_BYTES`
2. Optionally set `SHA256_HASH` for integrity verification
3. Update `ContextWindowManager.MAX_CONTEXT_TOKENS` if context length differs
4. Update `SystemPromptBuilder` if the model uses different special tokens

### Adding a New Screen
1. Create the screen composable in `ui/screens/`
2. Create a `@HiltViewModel` ViewModel in `ui/viewmodels/`
3. Add a `composable("route")` entry in `MainNavigation.kt`
4. Add a `BottomNavItem` if it should appear in the tab bar

---

## 15. Known Limitations & TODOs

- **Audio inference**: The MediaPipe LlmInferenceSession API doesn't fully expose audio input yet; `generateResponseWithAudio` falls back to text-based handling
- **AlarmReceiver**: The `AlarmTool` references a `com.localyze.receivers.AlarmReceiver` class that hasn't been created yet â€” alarms are scheduled but won't trigger a notification
- **ProGuard/R8**: `isMinifyEnabled = false` for both debug and release builds â€” no code shrinking
- **Dark mode toggle**: The `SettingsViewModel` saves dark mode preference but `LocalyzeTheme` currently reads `isSystemInDarkTheme()` â€” manual toggle connection needed
- **Nunito font**: `Type.kt` sets `Nunito = FontFamily.Default` â€” custom TTF files haven't been added to `res/font/`
- **Image sharing to chat**: `sharedText`/`sharedImageUris` are captured in `MainActivity` and passed to `MainNavigation`, but `ChatScreen` doesn't yet consume them to pre-populate messages
- **SHA-256 hash**: `ModelRepository.SHA256_HASH` is empty, so model integrity isn't verified after download in production
- **Conversation search**: `ConversationDao` and `MessageDao` have search queries, but no search UI is implemented in `ChatScreen`
- **Export**: `ChatRepositoryImpl.exportConversation()` generates markdown, but no export button exists in the UI