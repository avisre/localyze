# Audio Fix — Matching AI Edge Gallery Pattern

## Root Cause

Our app was passing **raw PCM bytes** directly to `Content.AudioBytes()`, but LiteRT-LM expects **WAV-wrapped audio data** (with a 44-byte RIFF/WAVE header). The AI Edge Gallery explicitly does this conversion via `ChatMessageAudioClip.genByteArrayForWav()` before passing audio to the model.

## Changes Made

### 1. `GemmaInferenceEngine.kt` — WAV Header Wrapping (CRITICAL FIX)

**Before:**
```kotlin
Content.AudioBytes(audioBytes)  // raw PCM — model can't parse this
```

**After:**
```kotlin
val wavBytes = wrapPcmInWavHeader(audioBytes, SAMPLE_RATE)
Content.AudioBytes(wavBytes)  // WAV-wrapped — model can parse this
```

Added `wrapPcmInWavHeader()` function that creates a proper 44-byte WAV header:
- RIFF/WAVE container format
- PCM audio format (format code 1)
- Mono (1 channel)
- 16-bit samples
- 16kHz sample rate

This matches the Gallery's `ChatMessageAudioClip.genByteArrayForWav()` exactly.

### 2. `GemmaInferenceEngine.kt` — Added `SAMPLE_RATE` constant

Added `const val SAMPLE_RATE = 16000` to the companion object, matching the Gallery's `SAMPLE_RATE` constant. Both the recording and the WAV header must use the same sample rate.

### 3. `SendMessageUseCase.kt` — Removed Explicit Conversation Reset

**Before:** Called `gemmaInferenceEngine.resetConversation(supportAudio = true)` before every audio message, which would clear the KV cache and break multi-turn conversations.

**After:** Removed the explicit reset. The `ensureConversation()` method inside `GemmaInferenceEngine` automatically detects when `supportAudio` changes from `false` to `true` and creates a new conversation with the right settings. This matches the Gallery pattern where conversation resets only happen when configuration actually changes.

Same fix applied to `sendMessageWithImage()`.

### 4. `SendMessageUseCase.kt` — Updated Audio Prompt Text

Changed the audio prompt from `"Voice message"` to `"Transcribe and respond to this audio"` to give the model a clearer instruction.

## How the Gallery's Audio Pipeline Works (for reference)

1. **Recording**: `AudioRecorderPanel` uses `AudioRecord` to capture raw PCM at 16kHz mono 16-bit
2. **Data Model**: `ChatMessageAudioClip` stores `audioData: ByteArray` (raw PCM) + `sampleRate: Int`
3. **WAV Conversion**: `ChatMessageAudioClip.genByteArrayForWav()` wraps the raw PCM in a 44-byte WAV header before sending to the model
4. **Model Inference**: `LlmChatModelHelper.runInference()` assembles `Contents.of(Content.AudioBytes(wavBytes), Content.Text(prompt))` — **audio before text**
5. **Playback**: `AudioPlaybackPanel` uses `AudioTrack` with `MODE_STATIC` to play back the raw PCM data
6. **File Import**: `convertWavToMonoWithMaxSeconds()` handles importing external WAV files — converts to mono, resamples to 16kHz, trims to 30 sec

## Architecture Diagram

```
User taps mic button
         │
         ▼
AudioRecorderButton → AudioInputProcessor.startRecording()
         │                    └── AudioRecord at 16kHz mono 16-bit PCM
         │                    └── Stores raw PCM bytes in ByteArrayOutputStream
         │
User taps stop
         │
         ▼
AudioInputProcessor.stopRecording() → raw PCM ByteArray
         │
         ▼
ChatViewModel.sendAudioMessage(bytes)
         │
         ▼
SendMessageUseCase.sendMessageWithAudio(audioBytes)
         │
         ▼
GemmaInferenceEngine.generateResponseWithAudio(audioBytes)
         │
         ├── wrapPcmInWavHeader(audioBytes, 16000)  ← NEW: WAV wrapping
         │       └── 44-byte header + raw PCM = WAV format
         │
         ├── ensureConversation(capabilityMode, enableThinking, supportAudio=true)
         │       └── Creates new Conversation if supportAudio changed
         │
         └── conv.sendMessageAsync(
                Contents.of(
                    Content.AudioBytes(wavBytes),  ← WAV-wrapped audio
                    Content.Text(prompt)            ← Text AFTER audio (Gallery pattern)
                ),
                MessageCallback,
                extraContext
            )
```