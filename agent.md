# LocalAssistant — Codebase Agent Guide

## 1. Project Overview

**LocalAssistant** is an Android application that provides a fully **on-device, privacy-first AI assistant** powered by Google's **Gemma 4 E4B** model. All inference runs locally on the device — no data ever leaves the user's phone. The app supports text, image (vision), and audio modalities, offers 6 capability modes, and provides an agentic tool-calling system with 9 built-in tools that execute against device capabilities (calendar, contacts, clipboard, alarms, tasks, memory, web search, file reading, system info).

| Attribute | Value |
|---|---|
| **Package** | `com.localassistant` |
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

### ✅ RESOLVED: Public LiteRT-LM Artifact

The Gemma 4 E4B LiteRT-LM model (`gemma-4-E4B-it.litertlm`) is **publicly available**.

- **Model card:** https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm
- **Direct download:** https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm
- **File size:** ~3.65 GB
- **Format:** LiteRT-LM (MediaPipe GenAI compatible)

### Mock Mode Reclassification

This project includes a `MockGemmaEngine` that simulates model responses. **Mock mode is NOT "because the real model doesn't exist."** The real model IS available.

Mock mode is retained for:
1. **Development velocity** — no 3.65 GB download for every clean build
2. **CI/CD automation** — automated tests without model dependency
3. **Unsupported-device fallback** — devices with <8GB RAM can't load the real model
4. **UI/UX testing** — verify streaming, tool-calling UI without inference cost

To use the real model: Set `USE_MOCK_ENGINE=false` and `USE_TEST_DOWNLOAD=false` in `build.gradle.kts` debug builds.

### Integration Status

- ✅ Download infrastructure implemented (resume, progress, storage checks)
- ✅ Real model URL configured in `ModelRepository.kt`
- ⚠️ End-to-end integration needs device validation
- ⚠️ SHA-256 hash verification pending official publication
- ⚠️ Device compatibility matrix not yet established

See `BLOCKERS.md` for detailed migration path and technical TODOs.

---

## 2. Project Structure

```
app/src/main/java/com/localassistant/
├── LocalAssistantApp.kt              # Hilt Application class, ensures models/ dir
├── MainActivity.kt                   # Single-Activity entry, handles share intents
│
├── ai/                               # AI / Inference Layer
│   ├── GemmaInferenceEngine.kt       # Core inference engine (MediaPipe LlmInference)
│   ├── ContextWindowManager.kt       # Manages 128K-token context window, trimming
│   ├── SystemPromptBuilder.kt        # Builds per-mode system prompts + tool schemas
│   ├── AudioInputProcessor.kt        # Raw PCM recording at 16kHz mono 16-bit
│   ├── InferenceToken.kt             # Sealed class: TextToken, ThinkingToken, ToolCallToken, EndOfStream, Error
│   ├── ModelLoadState.kt             # Sealed class: NotLoaded, Loading, Loaded, Error
│   ├── ModelExceptions.kt            # ModelNotFoundException, ModelLoadException, etc.
│   └── AudioRecordingState.kt        # Sealed class: Idle, Recording, Ready, Error
│
├── data/                             # Data Layer
│   ├── local/                         # Room database & DAOs
│   │   ├── AppDatabase.kt            # Room DB with FTS4 virtual tables
│   │   ├── MessageDao.kt             # CRUD + FTS search for messages
│   │   ├── ConversationDao.kt         # CRUD + search for conversations
│   │   ├── MemoryDao.kt              # CRUD + keyword search for memories
│   │   └── TaskDao.kt                # CRUD for tasks
│   └── repository/                    # Repository pattern implementations
│       ├── ChatRepository.kt          # Interface for chat persistence
│       ├── ChatRepositoryImpl.kt      # Full implementation with export
│       ├── MemoryRepository.kt        # Interface for memory access
│       ├── MemoryRepositoryImpl.kt    # Full implementation with save/delete/search
│       ├── TaskRepository.kt          # Interface + implementation for tasks
│       ├── ModelRepository.kt         # Model download, verification, storage checks
│       └── DownloadProgress.kt        # Sealed class: Downloading, Verifying, Complete, Error
│
├── di/                                # Dependency Injection Modules
│   ├── AppModule.kt                   # OkHttpClient, models dir, cache dir
│   ├── DatabaseModule.kt              # Room DB, DAOs, repositories
│   ├── AIModule.kt                    # Placeholder for future AI-specific bindings
│   └── ToolModule.kt                  # Placeholder for future tool bindings
│
├── domain/                            # Domain Layer (Clean Architecture)
│   ├── models/                        # Entity / value objects
│   │   ├── Message.kt                 # Room entity + MessageRole enum + TypeConverters
│   │   ├── Conversation.kt            # Room entity with capabilityMode
│   │   ├── Memory.kt                  # Room entity with keywords list
│   │   ├── Task.kt                    # Room entity with dueDate, completion
│   │   ├── ToolCall.kt                # Serializable with JsonObject arguments
│   │   └── ToolResult.kt              # callId, name, result, isError
│   └── usecases/                      # Business logic orchestrators
│       ├── SendMessageUseCase.kt       # Full agentic generation loop (≤3 tool iterations)
│       ├── ExecuteToolUseCase.kt       # Thin wrapper over ToolDispatcher
│       ├── ManageMemoryUseCase.kt      # Memory CRUD + keyword extraction
│       ├── RecordAudioUseCase.kt       # Audio recording orchestration
│       └── ChatResponseEvent.kt       # Sealed class for streaming events
│
├── tools/                             # Agentic Tool System
│   ├── Tool.kt                        # Interface: name, description, execute(), getParameterSchema()
│   ├── ToolRegistry.kt               # Central registry, auto-wires all 9 tools
│   ├── ToolDispatcher.kt             # Dispatches ToolCalls, handles errors
│   ├── CalendarTool.kt               # Read/write Android Calendar
│   ├── ContactsTool.kt               # Search contacts by name
│   ├── AlarmTool.kt                  # Set exact/inexact alarms
│   ├── ClipboardTool.kt             # Read/write system clipboard
│   ├── SystemInfoTool.kt             # Battery, WiFi, storage, device info
│   ├── WebSearchTool.kt              # DuckDuckGo Instant Answer API
│   ├── FileReaderTool.kt             # Read text files from URI
│   ├── MemoryTool.kt                # Save/search long-term memories
│   └── TaskTool.kt                   # Create/list/complete to-do tasks
│
└── ui/                                # Presentation Layer
    ├── navigation/
    │   └── MainNavigation.kt          # NavHost with 4 routes: onboarding, chat, capabilities, settings
    ├── screens/
    │   ├── ChatScreen.kt              # Main chat UI with messages, input bar, mic, image attach
    │   ├── OnboardingScreen.kt        # Multi-step model download flow
    │   ├── CapabilitiesScreen.kt      # 2×3 grid of capability modes
    │   └── SettingsScreen.kt          # Toggles, model info, memories, permissions, about
    ├── components/
    │   ├── RobotMascot.kt             # Canvas-drawn animated robot with blink, bob, breath
    │   ├── MessageBubble.kt           # User (terracotta pill) and Assistant (cream card) bubbles
    │   ├── ThinkingBubble.kt          # Collapsible thinking trace with sparkle icon
    │   ├── ToolIndicator.kt           # Animated pill: "Calling X..." → "Used: X ✓"
    │   ├── CapabilityCard.kt          # Grid card with Canvas-drawn icon per capability
    │   ├── AudioRecorderButton.kt     # Microphone button with recording state
    │   ├── AudioWaveform.kt           # Amplitude bar visualizer (40 bars)
    │   ├── SettingsRow.kt             # SettingsToggleRow and SettingsChevronRow
    │   └── RelativeTimestamp.kt       # "just now"/"5m ago"/"yesterday" formatting
    ├── viewmodels/
    │   ├── ChatViewModel.kt           # Chat state, message sending, audio, tool calls
    │   ├── ChatUiState.kt            # Data class for chat screen state
    │   ├── OnboardingViewModel.kt     # Model download and initialization lifecycle
    │   ├── OnboardingUiState.kt       # Sealed class for onboarding steps
    │   ├── CapabilitiesViewModel.kt   # Creates conversations from capability selection
    │   ├── CapabilitiesUiState.kt     # Selected mode and CAPABILITIES list
    │   ├── SettingsViewModel.kt       # Toggles, memories, model management
    │   └── SettingsUiState.kt         # Settings state data class
    └── theme/
        ├── Color.kt                   # Light/dark color palette (warm cream/terracotta)
        ├── Theme.kt                   # MaterialTheme wrapper
        ├── Shape.kt                   # RoundedCorner shape definitions
        └── Type.kt                    # Nunito typography scale
```

---

## 3. Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                         UI LAYER (Compose)                          │
│  ┌──────────┐  ┌───────────────┐  ┌────────────┐  ┌────────────┐  │
│  │ Onboarding│  │   ChatScreen  │  │ Capabilities│  │  Settings  │  │
│  │  Screen   │  │               │  │   Screen   │  │   Screen   │  │
│  └─────┬─────┘  └──────┬────────┘  └─────┬──────┘  └─────┬──────┘  │
│        │               │                  │               │          │
│  ┌─────▼─────┐  ┌──────▼────────┐  ┌─────▼──────┐  ┌─────▼──────┐  │
│  │ Onboarding │  │ ChatViewModel │  │ Capabilities│  │  Settings  │  │
│  │  ViewModel │  │               │  │  ViewModel │  │  ViewModel │  │
│  └─────┬──────┘  └──┬────┬───┬──┘  └─────┬──────┘  └─────┬──────┘  │
└────────┼────────────┼────┼───┼─────────────┼───────────────┼─────────┘
         │            │    │   │             │               │
┌────────▼────────────▼────▼───▼─────────────▼───────────────▼─────────┐
│                      DOMAIN LAYER (Use Cases)                        │
│  ┌──────────────────┐ ┌────────────────┐ ┌────────────────────────┐  │
│  │ SendMessageUseCase│ │ RecordAudio   │ │ ManageMemoryUseCase   │  │
│  │ (agentic loop)   │ │ UseCase       │ │                        │  │
│  └────┬─────────┬───┘ └──────┬─────────┘ └───────────┬────────────┘  │
│       │         │             │                       │              │
│  ┌────▼──┐ ┌────▼───────┐ ┌──▼──────────────┐       │              │
│  │ Execute│ │ Context    │ │ AudioInput     │       │              │
│  │ ToolUC │ │ WindowMgr  │ │ Processor      │       │              │
│  └────┬──┘ └────────────┘ └────────────────┘       │              │
└───────┼─────────────────────────────────────────────┼──────────────┘
        │                                             │
┌───────▼─────────────────────────────────────────────▼──────────────┐
│                        DATA LAYER                                    │
│  ┌──────────────────┐ ┌───────────────┐ ┌───────────────────────┐   │
│  │ ChatRepository   │ │ ModelRepo    │ │ MemoryRepository     │   │
│  │ (Impl)           │ │ (download)    │ │ (Impl)               │   │
│  └────┬─────────────┘ └──────┬──────┘ └──────┬────────────────┘   │
│       │                       │                │                     │
│  ┌────▼───────────────────────▼────────────────▼──────────────┐    │
│  │           Room Database (AppDatabase)                       │    │
│  │   MessageDao | ConversationDao | MemoryDao | TaskDao       │    │
│  └────────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                        AI LAYER                                     │
│  ┌──────────────────────┐  ┌────────────────────────┐              │
│  │ GemmaInferenceEngine │  │  SystemPromptBuilder   │              │
│  │ (MediaPipe LiteRT-LM)│  │  (per-mode prompts)   │              │
│  └──────────────────────┘  └────────────────────────┘              │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                        TOOL LAYER                                   │
│  ┌────────────┐ ┌──────────┐ ┌───────┐ ┌──────────┐ ┌──────────┐  │
│  │ Calendar   │ │ Contacts │ │ Alarm │ │ Clipboard│ │  System  │  │
│  │ Tool       │ │ Tool     │ │ Tool  │ │ Tool     │ │ InfoTool  │  │
│  └────────────┘ └──────────┘ └───────┘ └──────────┘ └──────────┘  │
│  ┌────────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────────────┐ │
│  │ WebSearch  │ │ FileRead │ │ Memory │   │ Task                 │ │
│  │ Tool       │ │ Tool     │ │ Tool   │   │ Tool                 │ │
│  └────────────┘ └──────────┘ └──────────┘ └──────────────────────┘ │
│                                                                     │
│  ToolRegistry ──► ToolDispatcher ──► Tool.execute(args)             │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 4. Key Components In Detail

### 4.1 AI Inference Engine (`GemmaInferenceEngine`)

The centerpiece of the app. Uses **MediaPipe's LlmInference** API with the `.litertlm` model format.

- **Model file**: `gemma-4-E4B-it.litertlm` (~3.4 GB), stored at `/data/data/com.localassistant/files/models/`
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

- **Token estimation**: Dual heuristic — character-based (2 chars/tok for code, 4 chars/tok for English) + word-based (1.3 words/tok), averaged
- **Truncation strategy**: Starts from most recent messages, works backwards; drops oldest when budget exceeded
- **Reserved space**: 4,000 tokens for system prompt, 2,048 tokens for response
- **Memory injection**: User memories are prepended as SYSTEM messages at context start

### 4.3 System Prompt Builder (`SystemPromptBuilder`)

Generates the system prompt injected before each conversation:

1. **Mode-specific base prompt** — one of 6 tailored prompts (chat, see, write, brainstorm, code, data)
2. **Thinking instruction** — appended when `enableThinking = true`
3. **Tool schema section** — lists all registered tools with JSON parameter schemas
4. **Date/time context** — current date and time for temporal grounding

### 4.4 Audio Input Processor (`AudioInputProcessor`)

Records raw PCM audio for the model's native audio input:

- **Format**: 16kHz, mono, 16-bit PCM (as required by Gemma 4 E4B)
- **API**: Android `AudioRecord` (NOT MediaRecorder — model needs raw PCM bytes)
- **Max duration**: 30 seconds
- **Amplitude visualization**: RMS normalization to 0f-1f range, emitted at ~20Hz
- **State machine**: Idle → Recording → Ready (with audioData + durationMs)
- **Temp file caching**: Saved to `cacheDir/audio_rec_*.pcm`

### 4.5 Agentic Tool-Calling Loop (`SendMessageUseCase`)

The core "agent" — orchestrates the full message-sending flow:

```
1. Save user message → DB
2. Build context window (system prompt + memories + messages)
3. Call GemmaInferenceEngine.generateResponse()
4. Collect streaming tokens → emit ChatResponseEvents
5. If ToolCallToken received:
   a. Emit ToolCallStarted
   b. Execute tool via ToolDispatcher
   c. Save TOOL result message → DB
   d. Emit ToolCallCompleted
   e. Re-invoke generateResponse with tool results injected
   f. Loop up to MAX_TOOL_ITERATIONS (3)
6. Save final ASSISTANT message → DB
7. Auto-generate conversation title from first AI response
```

**Three input modes:**
- `sendMessage()` — text only
- `sendMessageWithImage()` — text + Bitmap
- `sendMessageWithAudio()` — raw PCM bytes

Additional capabilities:
- `regenerateLastResponse()` — deletes last assistant message and re-runs
- `stopGeneration()` — cancels the active coroutine job

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

- **Download flow**: Create `.tmp` file → stream HTTP response in 8KB chunks → emit `DownloadProgress` every 200ms → SHA-256 verify (if hash set) → rename `.tmp` → `.litertlm`
- **Storage check**: Requires model size (~3.4 GB) + 500 MB buffer free
- **RAM check**: Requires ≥8 GB total device RAM
- **Demo mode**: `DEMO_MODE = false` in production; when true, simulates download progress
- **Recovery**: On failure/cancellation, deletes `.tmp` file; on retry, starts clean

### 4.8 Room Database (`AppDatabase`)

- **Version**: 1
- **Entities**: `Message`, `Conversation`, `Memory`, `Task`
- **FTS4 tables**: `messages_fts` (content + thinking_content + tool_result), `conversations_fts` (title)
- **TypeConverters**: `MessageRoleConverter` (enum ↔ String), `StringListConverter` (List<String> ↔ `|||`-delimited)
- **Singleton pattern**: `AppDatabase.getInstance(context)` with `@Volatile` + `synchronized`

### 4.9 Navigation

4 routes managed by `MainNavigation`:

1. **`onboarding`** — Shown when model isn't downloaded. Multi-step: Welcome → Checking → ReadyToDownload → Downloading → Verifying → ReadyToChat (or Error/InsufficientRam/InsufficientStorage)
2. **`chat`** — Main screen with message list, input bar, and robot mascot
3. **`capabilities`** — 2×3 grid of capability modes, creates conversation and navigates to chat
4. **`settings`** — Toggle preferences, model info, memory management, permissions, about

**Start destination**: `onboarding` if model not downloaded, `chat` if already available

**Bottom navigation**: 3 tabs (Chat, Capabilities, Settings); hidden during onboarding

### 4.10 Share Intents

`MainActivity` handles incoming `ACTION_SEND` and `ACTION_SEND_MULTIPLE` intents:
- **Text**: Extracts `EXTRA_TEXT` → sets `sharedText` state
- **Image(s)**: Extracts URI(s) → sets `sharedImageUris` state

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
- **CapabilityCard**: 2×3 grid card with Canvas-drawn icon (speech bubble, eye, document, lightbulb, code brackets, chart) and spring animation on press.
- **AudioRecorderButton**: Microphone button that morphs from idle → recording (red pulsing circle) → ready (play icon)
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
    │
    ▼
ChatViewModel.sendMessage(text)
    │
    ▼
SendMessageUseCase.sendMessage(conversationId, text, capabilityMode="chat", enableThinking=true)
    │
    ├── 1. Save USER message → Room DB
    │
    ├── 2. Fetch memories → MemoryRepository.getAllMemories()
    │
    ├── 3. Build context window → ContextWindowManager.buildContextWindow()
    │       - System prompt (chat mode + tools + date/time)
    │       - Recent messages (trimmed to fit 128K tokens)
    │       - Memories injected as SYSTEM message
    │
    ├── 4. Format as Gemma prompt → <start_of_turn>system ... <end_of_turn> ...
    │
    ├── 5. Stream inference → GemmaInferenceEngine.generateResponse()
    │       │
    │       ├── MediaPipe loaded? → Real LlmInferenceSession
    │       │       - session.addQueryChunk(prompt)
    │       │       - session.generateResponseAsync { partial, done → }
    │       │       - Parse thinking blocks (⟨F7B8⟩...⟨F7B9⟩)
    │       │       - Parse tool calls ({"name":"calendar","arguments":{...}})
    │       │
    │       └── Model not loaded? → Fallback simulated response
    │
    ├── 6. Token stream → InferenceToken.TextToken / ThinkingToken / ToolCallToken
    │
    ├── 7. Emit ChatResponseEvents:
    │       - StreamingToken → UI appends to streaming text
    │       - ThinkingToken → UI shows thinking bubble
    │       - ToolCallToken → Agentic loop:
    │           ├── Emit ToolCallStarted("calendar")
    │           ├── Execute CalendarTool.execute({action:"read", start_date:"today"})
    │           ├── Save TOOL message → DB
    │           ├── Emit ToolCallCompleted("calendar", result)
    │           └── Re-invoke generateResponse with tool result (iteration 2)
    │
    ├── 8. Save ASSISTANT message → Room DB
    │
    └── 9. Auto-generate title → First 40 chars of response → update conversation
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
| `READ_EXTERNAL_STORAGE` | Legacy storage access (API ≤32) | File access |
| `POST_NOTIFICATIONS` | Alarm notifications (API 33+) | `AlarmTool` |
| `INTERNET` | Model download + web search | `ModelRepository`, `WebSearchTool` |
| `SCHEDULE_EXACT_ALARM` | Exact alarm scheduling (API 31+) | `AlarmTool` |

---

## 8. Build & Run Instructions

### Prerequisites
- **JDK**: 17 (Microsoft OpenJDK 17.0.18+)
- **Android SDK**: API 28–35, with Build Tools 35.x
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
adb shell am start -n com.localassistant/.MainActivity
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
Located at `app/src/androidTest/java/com/localassistant/DataLayerIntegrationTest.kt`

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
Welcome → CheckingModel → (InsufficientRam | InsufficientStorage | ReadyToDownload)
ReadyToDownload → Downloading(progress) → Verifying(percent) → ReadyToChat
                                    ↘ Error(message, isRetryable)
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
The `.litertlm` format is **not** Safetensors or GGUF — it's Google's optimized on-device format for MediaPipe GenAI inference with XNNPack (CPU) and ML Drift (GPU) acceleration. It bundles text decoder weights, embedding parameters, vision encoder, and audio encoder in a single file. Vision and audio encoders are loaded **on-demand** when multimodal input is provided.

### Session Management
MediaPipe `LlmInferenceSession` objects are **single-turn** — they don't accumulate context across multiple `addQueryChunk` calls as a chat. The engine rebuilds the full prompt (all history + system prompt) each turn and creates a fresh session. This is handled by `createNewSession()` after each `generateResponse()` completion.

### Agentic Loop Limit
The tool-calling loop is capped at **3 iterations** (`MAX_TOOL_ITERATIONS = 3`) to prevent infinite loops. Each iteration may produce multiple tool calls, all of which are executed sequentially. If the model still produces tool calls after 3 iterations, the accumulated text is saved as-is.

### Memory Persistence
Memories are stored in Room as `Memory` entities with `content` (the fact), `keywords` (list stored as `|||`-delimited string), and `lastAccessedAt`. The `MemoryTool` can save new memories and search existing ones. The `ContextWindowManager` injects relevant memories into the system prompt for personalized responses.

### Audio Pipeline
Audio goes through: `AudioInputProcessor` (raw PCM at 16kHz) → `RecordAudioUseCase` (orchestration) → `ChatViewModel` (state management) → `SendMessageUseCase.sendMessageWithAudio()` → `GemmaInferenceEngine.generateResponseWithAudio()`. The LiteRT-LM audio API is not yet fully exposed in the MediaPipe session API, so audio inference currently falls back to text-based handling.

### Share Intent Feature
The app registers for `ACTION_SEND` (text and image) and `ACTION_SEND_MULTIPLE` (images) intents. Shared content is captured in `MainActivity` as `sharedText` and `sharedImageUris` compose state, passed down through `MainNavigation`. This enables "Share to Local Assistant" functionality from other apps.

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
{assistant_response}  ← generation continues here
<end_of_turn>
```
Tool results are injected as `<start_of_turn>user\n[Tool Result: {toolName}] {result}\n<end_of_turn>`.

### Feature Flags
- `ModelRepository.DEMO_MODE = false` — When `true`, simulates model presence so UI can be tested without the 3.4 GB download
- `ModelRepository.SHA256_HASH = ""` — When empty, SHA-256 verification is skipped after download

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
1. Create `app/src/main/java/com/localassistant/tools/NewTool.kt` implementing the `Tool` interface
2. Add `@Inject constructor(...)` with required dependencies
3. Add the tool as a constructor parameter to `ToolRegistry`
4. Register it in `ToolRegistry.init { register(newTool) }`
5. The tool will automatically appear in the system prompt and be callable by the model

### Adding a New Capability Mode
1. Add the mode string and prompt in `SystemPromptBuilder`
2. Add a `CapabilityItem` in `CapabilitiesUiState.kt` → `CAPABILITIES` list
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
- **AlarmReceiver**: The `AlarmTool` references a `com.localassistant.receivers.AlarmReceiver` class that hasn't been created yet — alarms are scheduled but won't trigger a notification
- **ProGuard/R8**: `isMinifyEnabled = false` for both debug and release builds — no code shrinking
- **Dark mode toggle**: The `SettingsViewModel` saves dark mode preference but `LocalAssistantTheme` currently reads `isSystemInDarkTheme()` — manual toggle connection needed
- **Nunito font**: `Type.kt` sets `Nunito = FontFamily.Default` — custom TTF files haven't been added to `res/font/`
- **Image sharing to chat**: `sharedText`/`sharedImageUris` are captured in `MainActivity` and passed to `MainNavigation`, but `ChatScreen` doesn't yet consume them to pre-populate messages
- **SHA-256 hash**: `ModelRepository.SHA256_HASH` is empty, so model integrity isn't verified after download in production
- **Conversation search**: `ConversationDao` and `MessageDao` have search queries, but no search UI is implemented in `ChatScreen`
- **Export**: `ChatRepositoryImpl.exportConversation()` generates markdown, but no export button exists in the UI