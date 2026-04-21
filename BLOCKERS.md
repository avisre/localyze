# Blockers & Known Issues

## 1. NPU Backend SIGABRT (BLOCKING — not our bug)
**Status**: Blocked on LiteRT-LM upstream fix  
**Issue**: `Backend.NPU()` + `Engine.initialize()` causes `SIGABRT` (signal 6) in `liblitertlm_jni.so` at `nativeCreateEngine` on Snapdragon 8 Gen 1  
**Root cause**: LiteRT-LM Kotlin NPU support is still in development (Issue #774). The NPU-compiled model (`_qualcomm_qcs8275`) also *requires* NPU backend — it reports `"Model requires one of [npu] but Main backend is GPU"` when GPU is specified.  
**Impact**: NPU-optimized models (3x faster, 50% less RAM) CANNOT be used until this is fixed  
**Workaround**: Using generic `gemma-4-E4B-it.litertlm` on GPU backend — works perfectly, but slower and more RAM  
**Upstream issues**:  
- https://github.com/google-ai-edge/LiteRT-LM/issues/774  
- https://github.com/google-ai-edge/LiteRT/issues/5159  
- https://github.com/google-ai-edge/LiteRT/issues/5077  

## 2. Model Download Needs Testing (MEDIUM)
**Status**: Partially tested  
**Issue**: The onboarding download flow has not been fully tested end-to-end  
- Download URL points to correct HuggingFace commit  
- Progress reporting works (tested with test file)  
- Actual ~3.4GB download needs testing on real device  

## 3. Chat Feature Not Yet Verified (MEDIUM)
**Status**: Model loads on GPU, but chat UI not tested yet  
**Issue**: Need to verify:  
- Text generation works  
- Multi-turn conversation history tracked by Conversation  
- Streaming tokens appear correctly  
- Thinking mode toggle  
- Capabilities modes (chat, code, etc.)  
- Stop generation button  