# Progress Report

## ✅ Working
1. **Model Loading (GPU)** — `gemma-4-E4B-it.litertlm` loads on GPU in ~12 seconds
2. **Backend fallback** — GPU → CPU works with proper error catching
3. **Chat inference** — Model responds to user messages (confirmed via screenshot)
4. **App stability** — No crashes during inference, surviving OnePlus memory management

## 🚫 Blocked: NPU Backend
- **Issue**: `Backend.NPU()` + `Engine.initialize()` causes SIGABRT in `liblitertlm_jni.so`
- **NPU model constraint**: `gemma-4-E2B-it_qualcomm_qcs8275.litertlm` REQUIRES NPU backend — error: "Model requires one of [npu] but Main backend is GPU"
- **Upstream issue**: https://github.com/google-ai-edge/LiteRT-LM/issues/774 — Kotlin NPU API not fully working yet
- **Workaround**: Using generic E4B model on GPU (3.8GB GPU RAM, 12GB device)

## 📋 Still To Test
- [ ] Voice input (microphone recording → transcription)
- [ ] Agentic abilities (tool calling)
- [ ] Model download via onboarding
- [ ] Thinking mode toggle
- [ ] Multi-turn conversation
- [ ] Stop generation button
- [ ] Capabilities modes (code, write, see, etc.)

## Architecture Status
- **ModelRepository.kt** ✅ — Downloads NPU (for Qualcomm), E2B/E4B generic models
- **GemmaInferenceEngine.kt** ✅ — Gallery pattern (Engine+Conversation, MessageCallback, GPU/CPU fallback, NPU blocked)
- **ChatViewModel.kt** ✅ — State management, sendMessage
- **SendMessageUseCase.kt** ✅ — Delegates to engine
- **Model download** — Configured but not tested end-to-end