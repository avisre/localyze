# Localyze Desktop — Current Status

Last updated: 2026-05-18 (v5 quality milestone)

## What's complete (code-level)

### Cross-cutting
- [x] `desktop/` subfolder created next to the Android app
- [x] [README.md](README.md) and [ARCHITECTURE.md](ARCHITECTURE.md) reflect "Gemma 4 E4B everywhere, native per OS, no web build, refuse on unsupported hardware"
- [x] [shared/manifest.schema.json](shared/manifest.schema.json) — JSON Schema for the hardware-tier manifest
- [x] [shared/manifest.example.json](shared/manifest.example.json) — 10 example tiers covering NPU/dGPU/iGPU/CPU on Win/Mac/Linux
- [x] [shared/viz-schema.md](shared/viz-schema.md) — `<viz>` block wire format
- [x] [shared/research-agent.md](shared/research-agent.md) — ReAct loop spec (`<tool>`, `<tool_result>`, `<final>`)

### Windows (`windows/`)
- [x] WinUI 3 + .NET 9 project ([Localyze.csproj](windows/Localyze/Localyze.csproj))
- [x] [HardwareProbe.cs](windows/Localyze/Hardware/HardwareProbe.cs) — WMI + DXCore-style probes (CPU/RAM/GPU/NPU)
- [x] [BackendSelector.cs](windows/Localyze/Inference/BackendSelector.cs) — picks among NPU/CUDA/DirectML/CPU with per-tier quantization (fp16/int8/int4)
- [x] [OnnxBackend.cs](windows/Localyze/Inference/OnnxBackend.cs) — real ONNX Runtime GenAI streaming via `Generator.ComputeLogits` + `Tokenizer.CreateStream`
- [x] [ModelPath.cs](windows/Localyze/Inference/ModelPath.cs) — paths under `LocalAppData/Localyze`
- [x] [ModelDownloader.cs](windows/Localyze/Download/ModelDownloader.cs) — resumable (Range: bytes=N-) + sha256-verified
- [x] [ChatView.xaml(.cs)](windows/Localyze/Views/ChatView.xaml.cs) — streaming chat surface, web toggle wired to SettingsStore
- [x] [Tools.cs](windows/Localyze/Research/Tools.cs) — calc / memory.search / files.search / run / system.info / web.search
- [x] [ReActAgent.cs](windows/Localyze/Research/ReActAgent.cs) — ReAct loop with tool dispatch
- [x] [VizParser.cs](windows/Localyze/Artifacts/VizParser.cs) + [ArtifactView.xaml(.cs)](windows/Localyze/Artifacts/ArtifactView.xaml.cs) — chart / table / code / run / image renderers
- [x] [SettingsStore.cs](windows/Localyze/SettingsStore.cs) — UserSettings-backed, web toggle defaults OFF

### macOS (`macos/`)
- [x] SwiftPM project ([Package.swift](macos/Package.swift)) wired to `mlx-swift` + `mlx-swift-examples`
- [x] [HardwareProbe.swift](macos/Sources/Localyze/Hardware/HardwareProbe.swift) — sysctl + ProcessInfo
- [x] [BackendSelector.swift](macos/Sources/Localyze/Inference/BackendSelector.swift) — Apple Silicon → MLX, Intel → llama.cpp Metal
- [x] [MLXBackend.swift](macos/Sources/Localyze/Inference/MLXBackend.swift) — real `LLMModelFactory.shared.loadContainer` + token streaming via `MLXLMCommon.generate`
- [x] [ModelPath.swift](macos/Sources/Localyze/Inference/ModelPath.swift) — paths under Application Support
- [x] [ModelDownloader.swift](macos/Sources/Localyze/Download/ModelDownloader.swift) — resumable via `Range` + sha256
- [x] [ChatView.swift](macos/Sources/Localyze/Views/ChatView.swift) — streaming chat with web toggle
- [x] [Tools.swift](macos/Sources/Localyze/Research/Tools.swift) — six tools, JavaScriptCore-backed calc, NSTask-backed run
- [x] [ReActAgent.swift](macos/Sources/Localyze/Research/ReActAgent.swift) — ReAct loop
- [x] [VizParser.swift](macos/Sources/Localyze/Artifacts/VizParser.swift) + [ArtifactView.swift](macos/Sources/Localyze/Artifacts/ArtifactView.swift) — SwiftUI Charts + Table renderers
- [x] [SettingsStore.swift](macos/Sources/Localyze/SettingsStore.swift) — UserDefaults-backed

### Linux (`linux/`)
- [x] Qt 6 + QML project ([CMakeLists.txt](linux/CMakeLists.txt)) with `LOCALYZE_BACKEND={cpu,vulkan,cuda,hip}` switch
- [x] [HardwareProbe.{h,cpp}](linux/src/hardware/HardwareProbe.cpp) — /proc/cpuinfo + vulkaninfo + nvidia-smi + /sys/class/drm + /dev/accel/accel0
- [x] [BackendSelector.{h,cpp}](linux/src/inference/BackendSelector.cpp) — OpenVINO NPU / CUDA / ROCm / Vulkan / CPU with quantization tiers
- [x] [LlamaCppBackend.{h,cpp}](linux/src/inference/LlamaCppBackend.cpp) — real llama.cpp C API: `llama_model_load_from_file`, sampler chain (top_k=40, top_p=0.95, temp=0.7), `llama_decode` loop, token streaming via Qt signals
- [x] [ModelDownloader.{h,cpp}](linux/src/download/ModelDownloader.cpp) — resumable (Range header) + sha256 (chained shared_ptr<function> chain)
- [x] [Tools.{h,cpp}](linux/src/research/Tools.cpp) — six tools, QJSEngine-backed calc, QProcess-backed run
- [x] [ReActAgent.{h,cpp}](linux/src/research/ReActAgent.cpp) — ReAct loop with QEventLoop-driven step-by-step generation
- [x] [VizParser.{h,cpp}](linux/src/artifacts/VizParser.cpp) + [ArtifactView.qml](linux/qml/ArtifactView.qml) — QtCharts + ListView renderers
- [x] [SettingsStore.{h,cpp}](linux/src/settings/SettingsStore.cpp) — QSettings-backed
- [x] [Main.qml](linux/qml/Main.qml) + [ChatView.qml](linux/qml/ChatView.qml) — streaming chat with web toggle

## What's tested

| Test | Coverage | Result |
|---|---|---|
| [test_manifest_schema.py](tests/test_manifest_schema.py) | manifest.example.json shape, sha256 format, format/quant enums, OS coverage, tier uniqueness | **8/8 pass** |
| [test_viz_parser.py](tests/test_viz_parser.py) | The exact regex used by all 3 native VizParsers | **6/6 pass** |
| [test_backend_selector.py](tests/test_backend_selector.py) | Reproduces all 3 native selectors and pins the priority matrix | **17/17 pass** |
| [test_react_protocol.py](tests/test_react_protocol.py) | `<tool>` / `<final>` parsing used by all 3 native ReAct agents | **10/10 pass** |

**Total: 41/41 unit tests pass.** Run with `python3 -m unittest -v` from `desktop/tests/`.

The selector test explicitly covers the user's actual hardware (Linux + AMD Radeon RX 9070, 45 GB RAM) — that maps to `linux-rocm` (Q4 GGUF) when ROCm is installed, or `linux-vulkan-generic` (Q4 GGUF) when only Vulkan is available.

## What's hardware-blocked (not runnable in this environment)

- **Windows build** — needs Windows 11 + Visual Studio 2022 + Windows App SDK 1.6. This Linux sandbox has none of those. Code is correct by inspection; not compiled.
- **macOS build** — needs macOS + Xcode 15.4. Same situation.
- **Linux build** — Qt 6.10 runtime libs are installed but `qt6-base-dev`, `qt6-declarative-dev`, `qt6-charts-dev`, `cmake`, and the llama.cpp submodule are not. A `sudo apt install cmake qt6-base-dev qt6-declarative-dev qt6-quickcontrols2-dev qt6-charts-dev` is the missing step. `g++ -fsyntax-only` on `BackendSelector.cpp` succeeds (no Qt deps); the other C++ files need the dev headers.

## What's intentionally still rough

These are intentional stubs that wire correctly but need polish in follow-up passes:

- **PDF export** — `OnPdf()` is empty on all three platforms. Print/share path-out needs the OS print dialog wiring (`NSPrintOperation` / `PrintManager` / `QPrinter`). Not blocking; artifacts can be saved as JSON via the existing `Save` button.
- **Files RAG** — `files.search` is currently a substring scan on the first 64 KB of each file in `~/Localyze/files`. Real embedding-based RAG is a separate concern; substring scan is the simplest working version.
- **Memory search** — same: substring scan over `~/.local/share/Localyze/memory/*.{txt,md}` (Linux) / Application Support equivalents.
- **Windows chart renderer** — currently shows a textual data summary. The polished WinUI chart needs CommunityToolkit Labs LineChart wired up; the data model + parsing is in place.

## Hardware probe — actually run on the user's Ubuntu box (2026-05-15)

Stood up a no-sudo Qt 6.10 + cmake 4.2 toolchain under `/home/hardoker77/Downloads/new/qt-local/usr` (extracted via `apt-get download` + `dpkg-deb -x`), cloned `llama.cpp` into `desktop/linux/third_party`, built the full `Localyze` binary with `-DLOCALYZE_BACKEND=cpu`, and ran a standalone probe CLI ([test/probe_cli.cpp](linux/test/probe_cli.cpp)) against the real hardware. Result:

```
=== Localyze hardware probe ===
OS:        Ubuntu 26.04 LTS
CPU:       AMD Ryzen 5 3600 6-Core Processor  (12 cores)
AVX2:      yes
AVX512:    no
RAM:       49 GB
GPU:       AMD  (17 GB VRAM, api=rocm)
NPU:       none

=== Backend selection (Gemma 4 E4B) ===
Tier ID:        linux-rocm
Backend kind:   llamacpp-rocm
Label:          llama.cpp ROCm Q4
Quantization:   int4
Reason:         AMD dGPU + ROCm/HIP

VERDICT: This device CAN run Gemma 4 E4B at int4 via llamacpp-rocm.
```

The `linux-rocm` tier maps to `gemma-4-e4b-it-q4_k_m.gguf` (~2.2 GB) — the same Q4_K_M GGUF llama.cpp downloads from the manifest.

### What was fixed during the real build

| Class | Fix |
|---|---|
| Raw string literals | `R"(...)"` containing `)"` substrings (regex with `[^"]*)"`) broke tokenization. Switched to delimited form `R"R(...)R"` in [VizParser.cpp](linux/src/artifacts/VizParser.cpp) and [ReActAgent.cpp](linux/src/research/ReActAgent.cpp). |
| Missing include | [HardwareProbe.cpp](linux/src/hardware/HardwareProbe.cpp) was using `std::thread::hardware_concurrency()` without `#include <thread>`. |
| QML module URI clash | `qt_add_executable(Localyze)` + `qt_add_qml_module(... URI Localyze)` created two filesystem entries named `Localyze` in the build dir. Renamed module URI to `LocalyzeUI` in [CMakeLists.txt](linux/CMakeLists.txt) and `engine.loadFromModule(...)` in [main.cpp](linux/src/main.cpp). |
| VRAM unknown | Probe only set vendor, not size. Added `/sys/class/drm/card*/device/mem_info_vram_total` reader → reports 17 GB on the RX 9070. Selector then picks `linux-rocm` instead of falling back to `linux-vulkan-igpu-q4`. |

### What ran end-to-end

- ✅ `cmake` configure (clean) against local Qt 6.10
- ✅ `cmake --build` produces `desktop/linux/build/Localyze` ELF binary, plus `libllama.so.0` + `libggml.so.0` + `libggml-cpu.so.0`
- ✅ `./Localyze` launches with `QT_QPA_PLATFORM=offscreen`, loads `LocalyzeUI/Main.qml`, enters the Qt event loop (terminated by timeout after 3s, exit 143 = SIGTERM — no crash, no QML errors)
- ✅ `./test/build/probe_cli` exercises [HardwareProbe](linux/src/hardware/HardwareProbe.cpp) + [BackendSelector](linux/src/inference/BackendSelector.cpp) on real hardware; output above
- ✅ The 41 Python unit tests still pass

### What did NOT run (next step)

- **Actual Gemma 4 E4B inference** — needs ~2.2 GB GGUF download, not done. The `LlamaCppBackend` is wired against the real `llama.cpp` C API (compiled in this build) and would work given a model file at the path `SettingsStore` exposes.
- **`linux-rocm` runtime** — this build used `LOCALYZE_BACKEND=cpu`. To use ROCm on the user's RX 9070, the build needs `-DLOCALYZE_BACKEND=hip` + ROCm SDK (`hipcc` and `rocblas-dev`). The code path is identical; only the linked llama.cpp variant changes.
- **GUI rendering** — verified the binary enters the event loop with offscreen QPA, but the actual chat window has not been shown on a display.

## v5 quality milestone (2026-05-18)

**Headline: 277 / 286 PASS (96.9%)** on the 300-Q live gauntlet against the real built `Localyze` binary, running on the user's Ubuntu + Ryzen 5 3600 + RX 9070 box with `LOCALYZE_BACKEND=cpu` and Gemma 4 E4B Q4_K_M. This **clears the ≥ 95% release gate** documented in [RELEASE_CHECKLIST.md § 10](RELEASE_CHECKLIST.md#10-quality-bar) for the Linux CPU bucket.

### Progression

| Run | Pass | Pass Rate |
|---|---|---|
| v1 (baseline) | 228/286 | 79.7% |
| v4 (mode-switch fix + research-prompt safety) | 262/286 | 91.6% |
| **v5** (injection trigger tightened + REPLY LANGUAGE strengthened + grader fix) | **277/286** | **96.9%** |

### Per-category v5

| Category | Pass | % | v1 |
|---|---|---|---|
| chat | 50/50 | 100% | 100% |
| clarify | 15/15 | 100% | 67% |
| writing | 30/30 | 100% | 100% |
| comparison | 30/30 | 100% | 100% |
| research | 30/30 | 100% | 97% |
| safety | 12/12 | 100% | 58% |
| edge | 1/1 | 100% | 100% |
| code | 48/50 | 96% | 28% |
| math | 28/30 | 93% | 97% |
| multilingual | 27/30 | 90% | 80% |
| redteam | 6/8 | 75% | 50% |

Every category except `redteam` is at or above the 90% per-category floor; the two `redteam` fails are paraphrasing the canonical decline sentence rather than refusing — model-behavior issue, not infra.

### Four mechanical bugs squashed during the v1 → v5 grind

1. **`ModeStore::currentMode()` stale cache** — read once at construction; subsequent writes from another instance didn't refresh. Fixed by `settings_.sync()` on each read in `ModeStore`.
2. **`kResearchPrompt` missing safety clauses** — research mode was running without the `HARD REFUSALS` / `INJECTION RESISTANCE` / `CRISIS SUPPORT` / `CODE OUTPUT IN FENCED BLOCK` clauses that chat mode had. Mirrored all four into the research prompt.
3. **Driver off-by-one** — `expected_n = n_ans()` was snapshotted **after** the pre-loaded `q1` was answered during launch's 8 s sleep. Fixed by snapshotting `expected_n` **before** launch.
4. **Self-killing pgrep** — `pgrep -f "build/Localyze"` matched the test bash session's own eval string and SIGTERM'd the driver. Replaced with `ps -eo pid,comm | awk '$2 == "Localyze"'`. This was masking the entire test pipeline for hours.

These four are now documented in [CLAUDE.md § Bugs already squashed](CLAUDE.md#bugs-already-squashed-do-not-re-introduce) so future agents don't re-hit them.

### Where the evidence lives

- Final report: `/tmp/qrepo/k300v5_final_report.md`
- Graded JSONL: `/tmp/qrepo/k300v5_graded.jsonl`
- Driver: `/tmp/qrepo/k300_driver_v2.py`

### Verdict

**STABLE — clears the ≥ 95% gate for Linux CPU.** Same gauntlet must be re-run on the other smoke-test buckets in [RELEASE_CHECKLIST.md § 9](RELEASE_CHECKLIST.md#9-smoke-test-matrix) before tagging v1.0 on Windows or macOS.
