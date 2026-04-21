# AGENT HANDOFF — LocalAssistant Android App

**Date:** 2026-04-20  
**Project path:** `C:\Users\avina\Downloads\ai7` (Windows)  
**Device:** OnePlus NE2211 (Snapdragon 8 Gen 1 / QCS8275), Android 16 (SDK 36), 12GB RAM  
**Build:** Debug, compiles successfully (`BUILD SUCCESSFUL`)  
**App package:** `com.localassistant`  
**ADB note:** Windows Git Bash requires `MSYS_NO_PATHCONV=1` prefix for all `adb` commands that use `/data/` paths  

---

## WHAT THIS APP IS

An on-device AI assistant running Google's **Gemma 4 E4B** model via **LiteRT-LM** (formerly MediaPipe LLM Inference). All inference happens locally on GPU. No cloud calls.

---

## CURRENT STATE: WHAT WORKS

### 1. Model loads on GPU ✅
- `gemma-4-E4B-it.litertlm` (3.65GB) loads in ~12 seconds on GPU backend
- Logs confirm: `Backend: GPU`, `EncoderBackend: GPU`, `AdapterBackend: CPU`
- Model uses ~3.8GB GPU memory (OnePlus flags this as a "memory leak" event but it's normal)

### 2. Chat inference produces responses ✅
- User can type a message, tap send, and get an AI response
- Confirmed via screenshot: model responded with text to "Hello" input
- Streaming tokens display as they generate
- Stop generation button available during streaming

### 3. App builds and installs ✅
- Kotlin 2.2.0, AGP 8.8.2, Hilt 2.57.2, KSP 2.2.0-2.0.2
- Debug APK at `app/build/outputs/apk/debug/app-debug.apk`
- Install via: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

### 4. Database persistence layer exists ✅
- Room database: `local_assistant_db` with `conversations` and `messages` tables
- `ConversationDao` has: insert, update, delete, deleteById, getAllConversations, searchConversations, updateTitle, updatePinnedStatus, updateCapabilityMode
- `MessageDao` has: insert, update, delete, deleteByConversationId, getMessagesForConversation, getRecentMessages, getMessageById, updateContent, searchMessagesSimple
- `ChatRepositoryImpl` implements full CRUD for both entities
- WAL file size (197KB) confirms data IS being written to the database

### 5. NPU model detection + error messaging ✅
- If an NPU-only model file is detected, app shows error and tells user to download generic model
- `findModelFile()` priority: generic E2B → E4B → NPU → legacy names

---

## CURRENT STATE: WHAT IS BROKEN / MISSING

### 🔴 CRITICAL BUG 1: No conversation list / drawer UI
**Problem:** There is NO way for the user to see, switch between, or delete past conversations. The app has:
- A "+" FAB button that creates a new conversation
- Individual chat messages that get saved to Room DB
- `ConversationDao.getAllConversations()` that returns a `Flow<List<Conversation>>`
- **BUT** there is no UI component that calls `getAllConversations()` or displays the list

**What exists:**
- `ChatScreen.kt` has `onOpenDrawer: () -> Unit = {}` parameter that is never wired up
- No drawer/sidebar composable exists anywhere in the codebase
- No hamburger menu button in the chat screen header

**What needs to be built:**
- A `ConversationDrawer` composable (side panel or bottom sheet) that:
  - Lists all conversations from `chatRepository.getAllConversations()`
  - Shows title, last updated time, message count
  - Tapping a conversation loads it in ChatScreen
  - Long-press or swipe shows delete confirmation
  - Edit/rename conversation title
  - Pin/unpin conversations
  - Search conversations
- A hamburger/menu button in ChatScreen top bar that opens the drawer
- Wire `onOpenDrawer` callback in `ChatScreen` to toggle the drawer

### 🔴 CRITICAL BUG 2: No conversation auto-load on app restart
**Problem:** When the app restarts, `ChatUiState.currentConversationId = -1L`. The `init` block checks `SavedStateHandle` for a conversation ID, but the `"chat"` navigation route doesn't pass any ID:
```kotlin
composable("chat") {
    ChatScreen(onNewConversation = { })
}
```
So every time the app restarts, the user sees an empty chat. All previous conversations exist in the DB but are invisible.

**Fix needed:**
- On app startup, load the most recent conversation from `chatRepository.getAllConversations()` and set `currentConversationId`
- OR add a conversation list UI so the user can pick one

### 🔴 CRITICAL BUG 3: Capabilities screen creates orphaned conversations
**Problem:** When user taps a capability card (e.g., "Code Help"), `CapabilitiesViewModel.selectCapability()` creates a new Conversation in the DB. Then navigation goes to `chat` route, but the ChatViewModel doesn't receive the new conversation ID:
```kotlin
LaunchedEffect(createdConversationId) {
    if (createdConversationId != null) {
        navController.navigate("chat") { ... }
        capabilitiesViewModel.consumeConversationId()
    }
}
```
The conversation ID is consumed but never passed to ChatScreen. ChatScreen creates YET ANOTHER new conversation with default "chat" capability mode, leaving the Capabilities-created one orphaned in the DB.

**Fix needed:**
- Change navigation route to `"chat/{conversationId}"` and pass the ID
- OR use shared ViewModel state between CapabilitiesViewModel and ChatViewModel
- OR use a `SavedStateHandle` with the conversation ID

### 🟡 MODERATE BUG 4: DB verification is blocked by ADB binary transfer issues
**Problem:** Cannot read Room DB from device because:
- `adb shell "run-as ... cat databases/local_assistant_db"` truncates on first null byte (4KB instead of full DB)
- No `sqlite3` binary on the device
- `base64` inside `run-as` only outputs 57 bytes
- WAL file must be checkpointed to read the main DB, but we can't run sqlite3 on device

**Fix options (pick one):**
1. Add a debug Activity/Fragment that queries the DB and dumps results to logcat
2. Add a ContentProvider that exposes DB data
3. Use `adb backup` to pull the whole app data
4. Add a `/data/local/tmp/` bridge with proper permissions
5. Add a diagnostic broadcast receiver that dumps DB contents to logcat

### 🟡 MODERATE BUG 5: No conversation title editing UI
**Problem:** `ConversationDao.updateTitle()` exists but is never called from the UI. Auto-generated titles come from the first AI response, but the user can't edit them.

**Fix needed:**
- Add title editing in the conversation drawer (long-press → rename, or inline edit)

### 🟡 MODERATE BUG 6: No conversation delete UI
**Problem:** `chatRepository.deleteConversation()` exists but is never triggered from the UI. Conversations accumulate forever.

**Fix needed:**
- Add delete button in conversation drawer (long-press → delete with confirmation)

---

## NPU BACKEND STATUS (BLOCKED — NOT FIXABLE BY US)

- **`Backend.NPU()` + `Engine.initialize()` → SIGABRT** in `liblitertlm_jni.so` at `nativeCreateEngine`
- Occurs on Snapdragon 8 Gen 1 (QCS8275)
- NPU-only model file (`gemma-4-E2B-it_qualcomm_qcs8275.litertlm`) also REQUIRES NPU backend: error `"Model requires one of [npu] but Main backend is GPU"`
- Upstream issues: https://github.com/google-ai-edge/LiteRT-LM/issues/774, https://github.com/google-ai-edge/LiteRT/issues/5159
- **Workaround:** App downloads and runs generic `gemma-4-E4B-it.litertlm` on GPU instead

---

## WHAT NEEDS TO BE DONE (PRIORITY ORDER)

### Task 1: Build Conversation Drawer UI (CRUD for conversations)
**Files to create/modify:**
- **NEW:** `app/src/main/java/com/localassistant/ui/components/ConversationDrawer.kt`
  - Side panel or ModalNavigationDrawer
  - Lists all conversations from `chatRepository.getAllConversations()` 
  - Each row: title, updatedAt relative time, message count, capability mode icon
  - Tap → load that conversation
  - Long-press → bottom sheet with: Rename, Pin/Unpin, Delete (with confirmation dialog)
  - Search bar at top to filter conversations
  - "New Chat" button at bottom
- **MODIFY:** `app/src/main/java/com/localassistant/ui/screens/ChatScreen.kt`
  - Add hamburger/menu icon button in top-left that opens the drawer
  - Wire `onOpenDrawer` callback
  - When a conversation is selected from drawer, call `viewModel.loadConversation(id)`
  - Show current conversation title in the header area
- **MODIFY:** `app/src/main/java/com/localassistant/ui/viewmodels/ChatViewModel.kt`
  - Add `allConversations: StateFlow<List<Conversation>>` collected from `chatRepository.getAllConversations()`
  - Add `deleteConversation(id: Long)` method
  - Add `renameConversation(id: Long, title: String)` method
  - Add `pinConversation(id: Long, pinned: Boolean)` method
  - Add `loadMostRecentConversation()` called from `init` when no SavedStateHandle ID
  - Fix `loadConversation()` to cancel previous Flow collection before starting new one

### Task 2: Fix conversation ID passing in navigation
**Files to modify:**
- **MODIFY:** `app/src/main/java/com/localassistant/ui/navigation/MainNavigation.kt`
  - Change chat route from `"chat"` to `"chat?conversationId={conversationId}"`
  - When navigating from Capabilities, pass the created conversation ID
  - In ChatScreen composable, extract conversationId from SavedStateHandle
  - On first launch with no conversation, auto-load most recent or show conversation list

### Task 3: Add DB diagnostic tool for verification
**Files to modify:**
- **MODIFY:** `app/src/main/java/com/localassistant/ui/viewmodels/ChatViewModel.kt`
  - Add `dumpConversationsToLog()` that queries all conversations and logs them
  - Add `dumpMessagesToLog(conversationId)` that queries all messages and logs them
- **MODIFY:** `app/src/main/java/com/localassistant/MainActivity.kt`
  - Add a BroadcastReceiver for `com.localassistant.DUMP_DB` that calls the dump methods
  - This allows `adb shell am broadcast -a com.localassistant.DUMP_DB` to verify DB contents

### Task 4: Test 20-question chat flow
After Tasks 1-3 are done:
1. Launch app, verify most recent conversation loads
2. Open conversation drawer, verify all conversations listed
3. Send 20 different questions one at a time, verify each response appears
4. Create a new conversation, verify it starts empty
5. Switch back to old conversation, verify all messages are there
6. Delete a conversation, verify it's removed
7. Rename a conversation title
8. Kill and restart app, verify conversations persist

### Task 5: Test additional features
- Voice input (mic button → record → send audio)
- Image input (attach button → pick image → describe)
- Thinking mode toggle
- Capability mode switching (through Capabilities screen)
- Stop generation mid-stream
- Regenerate response

---

## ARCHITECTURE OVERVIEW

```
┌─────────────────────────────────────────────────────────────────┐
│                         UI Layer                                 │
│  ChatScreen.kt  │  CapabilitiesScreen.kt  │  SettingsScreen.kt  │
│       ↓                  ↓                      ↓               │
│  ChatViewModel   │  CapabilitiesViewModel    │  SettingsViewModel│
└──────────────────────────────────────────────────────────────────┘
                              ↓
┌──────────────────────────────────────────────────────────────────┐
│                       Domain Layer                                │
│  SendMessageUseCase │ RecordAudioUseCase │ ManageMemoryUseCase   │
│  ExecuteToolUseCase │ ContextWindowManager │ SystemPromptBuilder  │
└──────────────────────────────────────────────────────────────────┘
                              ↓
┌──────────────────────────────────────────────────────────────────┐
│                        Data Layer                                  │
│  ChatRepository ← ChatRepositoryImpl                             │
│  ModelRepository ← ModelDownloadService                           │
│  MemoryRepository │ TaskRepository                                │
│       ↓                                                            │
│  Room DB: AppDatabase → ConversationDao, MessageDao, MemoryDao,    │
│           TaskDao                                                  │
│  Entities: Conversation, Message, Memory, Task                    │
└──────────────────────────────────────────────────────────────────┘
                              ↓
┌──────────────────────────────────────────────────────────────────┐
│                        AI Engine Layer                             │
│  GemmaInferenceEngine (real)  │  MockGemmaEngine (dev/fallback)   │
│  Uses: LiteRT-LM 0.10.0 (com.google.ai.edge:litertlm)            │
│  Backend: GPU (working) │ NPU (SIGABRT — upstream bug)            │
│  Model: gemma-4-E4B-it.litertlm (3.65GB) on GPU                  │
└──────────────────────────────────────────────────────────────────┘
```

### Key Architecture Decisions (matching AI Edge Gallery)
1. **Real engine:** LiteRT-LM `Engine` + `Conversation` objects are persistent. History tracked internally by Conversation. We do NOT manually build context windows for the real engine.
2. **Mock engine:** Still uses context window approach (no internal history tracking in mock).
3. **Conversation lifecycle:** Engine created once, Conversation created per chat session. `resetConversation()` called when mode/thinking changes.
4. **MessageCallback API:** Used for streaming token output from real engine.
5. **NPU blocked:** `Backend.NPU()` causes SIGABRT. App falls back to GPU. NPU model files also require NPU backend and can't run on GPU.

---

## KEY FILE MAP

| File | Purpose | Status |
|---|---|---|
| `app/src/main/java/com/localassistant/ai/GemmaInferenceEngine.kt` | Real inference engine (Gallery pattern) | ✅ Rewritten, working |
| `app/src/main/java/com/localassistant/ai/MockGemmaEngine.kt` | Mock engine for dev | ✅ Unchanged |
| `app/src/main/java/com/localassistant/domain/usecases/SendMessageUseCase.kt` | Message send + tool loop | ✅ Rewritten |
| `app/src/main/java/com/localassistant/ui/viewmodels/ChatViewModel.kt` | Chat state management | ⚠️ Needs conversation list support |
| `app/src/main/java/com/localassistant/ui/screens/ChatScreen.kt` | Chat UI composable | ⚠️ Needs drawer + menu button |
| `app/src/main/java/com/localassistant/ui/navigation/MainNavigation.kt` | Nav graph | ⚠️ Needs conversation ID passing |
| `app/src/main/java/com/localassistant/data/repository/ChatRepositoryImpl.kt` | Room CRUD | ✅ Full CRUD exists |
| `app/src/main/java/com/localassistant/data/local/ConversationDao.kt` | Room DAO | ✅ All queries exist |
| `app/src/main/java/com/localassistant/data/local/MessageDao.kt` | Room DAO | ✅ All queries exist |
| `app/src/main/java/com/localassistant/domain/models/Conversation.kt` | Room entity | ✅ Complete |
| `app/src/main/java/com/localassistant/domain/models/Message.kt` | Room entity | ✅ Complete |
| `app/src/main/java/com/localassistant/data/local/AppDatabase.kt` | Room DB | ✅ Working |
| `app/src/main/java/com/localassistant/data/repository/ModelRepository.kt` | Model download/find | ✅ Updated for generic model |
| `app/src/main/java/com/localassistant/ui/screens/CapabilitiesScreen.kt` | Capability picker grid | ✅ Working |
| `app/src/main/java/com/localassistant/ui/viewmodels/CapabilitiesViewModel.kt` | Creates conversation on capability select | ⚠️ ID not passed to ChatScreen |
| `app/src/main/java/com/localassistant/ui/components/` | UI components dir | Needs ConversationDrawer.kt |
| `app/build.gradle.kts` | Build config | ✅ Updated deps |
| `build.gradle.kts` | Root build config | ✅ Updated |

---

## DEVICE-SPECIFIC NOTES

- **ADB on Windows Git Bash:** Always prefix paths with `MSYS_NO_PATHCONV=1`, e.g.:  
  `MSYS_NO_PATHCONV=1 adb shell "run-as com.localassistant ls databases/"`  
  Without this, Git Bash converts `/data/` paths to Windows paths.
- **Binary file transfer via ADB:** `adb shell "run-as ... cat database_file"` truncates on first null byte. Use `base64` encoding or `adb backup` instead.
- **Input via ADB:** `input text` works for Compose TextFields after tapping to focus. `input keyevent KEYCODE_*` for individual characters works but mapping is tricky (KEYCODE_H sends 'h' lowercase).
- **Memory:** E4B model uses 3.8GB GPU RAM. OnePlus monitoring system flags this as `memory_leak_event` with `leak_type=4` (GPU limit). This is expected behavior, not an actual leak.
- **`run-as` on Android 16:** Works for the app's private directory. Cannot write to `/data/local/tmp/` from `run-as`.

---

## MODEL FILES ON DEVICE

```
/data/user/0/com.localassistant/files/models/
├── gemma-4-E4B-it.litertlm                     (3.65GB — generic, working on GPU)
├── gemma-4-E4B-it.litertlm.audio_adapter.xnnpack_cache  (15MB)
├── gemma-4-E4B-it.litertlm.audio_encoder.xnnpack_cache  (91MB)
├── gemma-4-E4B-it.litertlm.vision_adapter.xnnpack_cache  (8MB)
├── gemma-4-E4B-it.litertlm_*.bin                (152MB — weight cache)
└── gemma-4-E4B-it.litertlm_mldrift_program_cache.bin     (21MB)
```

**NPU model (NOT on device, on PC only):**
- `gemma-4-E2B-it_qualcomm_qcs8275.litertlm` (3.29GB) — requires NPU backend, cannot run on GPU

---

## DATABASE SCHEMA

```sql
-- conversations table
CREATE TABLE conversations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL DEFAULT 'New Chat',
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    isPinned INTEGER NOT NULL DEFAULT 0,
    capabilityMode TEXT NOT NULL DEFAULT 'chat',
    messageCount INTEGER NOT NULL DEFAULT 0
);

-- messages table  
CREATE TABLE messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    conversationId INTEGER NOT NULL,
    role TEXT NOT NULL,  -- USER, ASSISTANT, SYSTEM, TOOL
    content TEXT NOT NULL,
    thinkingContent TEXT,
    toolCallId TEXT,
    toolName TEXT,
    toolResult TEXT,
    imageUris TEXT NOT NULL DEFAULT '',  -- joined with ||| separator
    audioPath TEXT,
    timestamp INTEGER NOT NULL,
    isStreaming INTEGER NOT NULL DEFAULT 0
);

-- FTS4 virtual tables for search
CREATE VIRTUAL TABLE messages_fts USING fts4(content, thinking_content, tool_result, content=messages, tokenize=unicode61);
CREATE VIRTUAL TABLE conversations_fts USING fts4(title, content=conversations, tokenize=unicode61);
```

---

## BUILD & INSTALL COMMANDS

```bash
cd /c/Users/avina/Downloads/ai7

# Build
./gradlew assembleDebug

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb shell am start -n com.localassistant/.MainActivity

# View logs
adb logcat -d --pid=$(adb shell pidof com.localassistant) | grep -E "GemmaInference|ChatViewModel|MainNavigation"

# Force stop
adb shell am force-stop com.localassistant

# Check DB files
MSYS_NO_PATHCONV=1 adb shell "run-as com.localassistant ls -la databases/"

# Check model files
MSYS_NO_PATHCONV=1 adb shell "run-as com.localassistant ls -la files/models/"
```