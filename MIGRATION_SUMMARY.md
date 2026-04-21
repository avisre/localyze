# AI Edge Gallery Migration — Complete

## What was done

This migration rewrites the core inference engine (`GemmaInferenceEngine.kt`) and message handling (`SendMessageUseCase.kt`) to match the architecture used by Google's official [AI Edge Gallery app](https://github.com/google-ai-edge/gallery), which successfully runs Gemma 4 E4B on mobile devices.

## Critical bugs fixed

### 1. 🐛 Conversation recreated per message (BROKEN multi-turn)

**Before:** Every call to `generateResponse()` created a new `Conversation` object with `buildInitialMessages()`. This threw away the KV cache and token history, making multi-turn conversations impossible.

**After:** One `Conversation` per session, reused across messages. Only recreated when configuration changes (mode switch, thinking toggle). Matches Gallery's `LlmModelInstance` pattern where `engine` and `conversation` are long-lived singletons.

### 2. 🐛 Wrong LiteRT-LM API used

**Before:** Used `sendMessageAsync(LiteRtMessage)` returning `Flow<Message>` — no such overload that works correctly for streaming in production.

**After:** Uses `sendMessageAsync(Contents, MessageCallback, extraContext)` — the callback-based API that the Gallery actually uses in production.

### 3. 🐛 Manual history injection (duplicated context)

**Before:** Manually built `initialMessages` from DB history and passed them to Conversation. This caused duplication since Conversation already tracks history.

**After:** Only sends the latest user message. The Conversation object tracks history internally through repeated `sendMessageAsync()` calls. Only the mock engine still uses context window building.

### 4. 🐛 Missing backend configuration

**Before:** Only set `backend` in EngineConfig. No `visionBackend` or `audioBackend`.

**After:** Matches Gallery: `visionBackend = Backend.GPU()`, `audioBackend = Backend.CPU()`, and conditional `cacheDir`.

### 5. 🐛 No cancellation support

**Before:** No way to cancel mid-generation. `stopGeneration()` was a no-op for the real engine.

**After:** Uses `conversation.cancelProcess()` (Gallery pattern).

### 6. 🔧 Unnecessary QNN dependencies

**Before:** Had `qnn-runtime:2.34.0` and `qnn-litert-delegate:2.34.0` — the Gallery doesn't include these; LiteRT-LM bundles what's needed.

**After:** Removed both. LiteRT-LM handles QNN internally.

### 7. 🔧 Kotlin version incompatibility

**Before:** Kotlin 1.9.22, which couldn't read LiteRT-LM 0.10.0's Kotlin 2.3.0 metadata.

**After:** Kotlin 2.2.0 matching the Gallery. Also upgraded AGP, KSP, Hilt, Compose, and all support libraries to match.

## Files changed

| File | Change |
|------|--------|
| `GemmaInferenceEngine.kt` | Complete rewrite — Gallery pattern with Engine+Conversation lifecycle, MessageCallback API, cancelProcess() |
| `SendMessageUseCase.kt` | Major rewrite — real engine sends only latest message, mock engine still uses context window |
| `ChatViewModel.kt` | Added resetEngineConversation() calls on mode/thinking changes |
| `build.gradle.kts` (root) | Kotlin 2.2.0, AGP 8.8.2, KSP 2.2.0-2.0.2, Hilt 2.57.2 |
| `build.gradle.kts` (app) | Updated dependencies, added compose plugin, removed QNN deps, updated all library versions |
| `BLOCKERS.md` | Updated to reflect all fixes |
| `gradle.properties` | Increased JVM heap to 8GB for Kotlin 2.2 build |

## Architecture comparison (before → after)

```
BEFORE (broken):                          AFTER (Gallery pattern):
┌─────────────────────────┐              ┌─────────────────────────┐
│ generateResponse() call   │              │ sendMessage() call      │
│         │                │              │         │               │
│  Build initialMessages   │              │  (no history building)  │
│  from DB history         │              │         │               │
│         │                │              │  Get last user message  │
│  Create NEW Conversation │  ← BROKEN   │  from DB               │
│  with initialMessages    │              │         │               │
│         │                │              │  EnsureConversation()   │
│  sendMessageAsync(       │              │  (reuses existing or    │
│    LiteRtMessage)        │  ← WRONG API │   creates new if needed)│
│  → Flow<Message>         │              │         │               │
│         │                │              │  sendMessageAsync(       │
│  Collect flow            │              │    Contents.of(text),    │
│         │                │              │    MessageCallback,      │
│  Close conversation      │  ← WASTEFUL  │    extraContext)         │
│         │                │              │         │               │
│  (Conversation destroyed)│              │  (Conversation persists) │
└─────────────────────────┘              └─────────────────────────┘
```

## Key patterns from AI Edge Gallery that are now implemented

1. **LlmModelInstance pattern** — Engine + Conversation are stored together and persist across messages
2. **MessageCallback API** — `sendMessageAsync(Contents, MessageCallback, extraContext)` for streaming
3. **Backend selection** — NPU → GPU → CPU fallback with configurable vision/audio backends
4. **SamplerConfig null on NPU** — NPU handles sampling internally (already correct, kept)
5. **conversation.cancelProcess()** — Clean cancellation support
6. **resetConversation()** — Only when config changes, not every message
7. **channel["thought"]** — Thinking mode via LiteRT-LM Channel API
8. **ToolProvider** — Native `tool()` function for function calling
9. **No QNN deps** — LiteRT-LM bundles everything
10. **ExperimentalFlags** — Framework for constrained decoding (set to false for now)