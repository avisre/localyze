# CLAUDE.md — Localyze Desktop (root)

Top-level guidance for any future Claude agent working in `desktop/`. This file
covers the **policies that apply to all three platforms**. For per-platform
specifics, follow the cross-references below.

- Windows: [windows/README.md](windows/README.md)
- macOS: [macos/README.md](macos/README.md)
- Linux: [linux/README.md](linux/README.md) and (deeper) any `linux/**/CLAUDE.md` once written
- Release process: [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md)
- Current state: [STATUS.md](STATUS.md)
- Design rationale: [ARCHITECTURE.md](ARCHITECTURE.md)

---

## Hard rules (do not violate)

These come from the user's standing instructions. Breaking any of them is a
correctness regression, not a stylistic preference.

### 1. Gemma 4 E4B everywhere

All three desktop platforms run **the same model the Android app uses** — Google
`gemma-3n-E4B-it`. Weights are identical; only the on-disk container changes per
platform:

| Platform | Container | Loader |
|---|---|---|
| Windows | ONNX (GenAI extension) | ONNX Runtime |
| macOS | MLX | `mlx-swift` (`MLXLMCommon.generate`) |
| Linux | GGUF (`Q4_K_M`, Q8, fp16) and OpenVINO IR (Intel NPU) | llama.cpp / OpenVINO |

**Do not** swap in a smaller model. Do not propose Phi-3, TinyLlama, Mistral 7B,
or anything else "to fit weaker hardware". The model is fixed.

### 2. Native per platform — no cross-platform frameworks

- Windows = C# / .NET 9 / WinUI 3 / XAML.
- macOS = Swift 5.10+ / SwiftUI / MLX.
- Linux = C++20 / Qt 6 / QML / llama.cpp.

**Never** propose Electron, Tauri, Flutter, MAUI, React Native, Compose
Multiplatform, .NET MAUI, Qt for Python, Avalonia, or any other shell that pretends
to be cross-platform. Each native toolkit gives us the best UX on its OS and the
shortest path to the OS's accelerator APIs. This is non-negotiable.

### 3. Refuse on unsupported hardware

Mobile philosophy: if the device can't fit Gemma 4 E4B, the installer **refuses**.
It does not silently downgrade.

Minimum:
- 16 GB system RAM
- 4 GB GPU VRAM **or** 12 GB shared iGPU memory **or** a supported NPU
- ~5 GB free disk

The selector picks the best matching tier and reports it to the user. If no tier
matches, the UI shows what's missing and exits. See
[shared/manifest.example.json](shared/manifest.example.json) for the tier list.

### 4. Web search is binary

A single toggle in Settings, **default OFF**. No "auto" mode, no per-query
prompts. When OFF, the `web.search` tool is unavailable to the ReAct agent.
When ON, the agent can call it freely. All other tools (memory, files, calc,
run, system.info) are always on.

### 5. Quality gate is ≥ 95% on the 300-Q gauntlet

A platform does not ship until the 300-Q live gauntlet scores ≥ 95% on real
hardware, with every category at ≥ 90%. See § "300-Q quality bar" below.

---

## Layout

```
desktop/
├── CLAUDE.md                  # this file
├── README.md                  # public-facing overview
├── ARCHITECTURE.md            # design + hardware-detection flow
├── STATUS.md                  # what's built, what's tested, what's pending
├── RELEASE_CHECKLIST.md       # v1.0 ship gate (single source of truth)
├── shared/
│   ├── manifest.schema.json   # JSON Schema for the hardware-tier manifest
│   ├── manifest.example.json  # 10 example tiers
│   ├── viz-schema.md          # <viz> block format (used by all 3 platforms)
│   └── research-agent.md      # ReAct loop spec (mirrored in each platform's code)
├── windows/                   # C# + WinUI 3 + ONNX Runtime GenAI
├── macos/                     # Swift + SwiftUI + MLX
├── linux/                     # C++ + Qt 6 + QML + llama.cpp
└── tests/                     # 41 cross-platform Python unit tests + gauntlet drivers
```

---

## Bugs already squashed (do not re-introduce)

The 300-Q gauntlet (v1 → v5) exposed these. Future Claude agents should
recognise the symptom and avoid the rewind.

| # | Symptom | Root cause | Fix |
|---|---|---|---|
| 1 | QtCharts widget can't be instantiated | The Qt 6 build used `QGuiApplication` instead of `QApplication`; QtCharts (and any Qt Widgets bridge) requires the full `QApplication`. | Use `QApplication` in `linux/src/main.cpp`. Pulling in `QtWidgets` is fine for the chart bridge — the rest of the UI is still QML. |
| 2 | Driver kills itself mid-test | `pgrep -f "build/Localyze"` matched the test bash session's own eval string, sending SIGTERM to the driver. | Replace with `ps -eo pid,comm \| awk '$2 == "Localyze"'`. **Never** use `pgrep -f` against a substring that could appear in your own argv. |
| 3 | OOM / context overflow on Q4 builds | Default `n_batch=512` was too small for the 300-Q gauntlet's long prompts; chunked encoding tripped the context allocator. | Set `n_batch=4096` (and `n_ubatch=512`) on `llama_context_params` in `LlamaCppBackend.cpp`. |
| 4 | Mode toggle (chat ↔ research) doesn't persist between launches | `ModeStore::currentMode()` cached the `QSettings` value at construction; subsequent `QSettings::setValue` calls from another instance didn't refresh. | Call `settings_.sync()` on every read in `ModeStore`. |
| 5 | Research mode happily writes unsafe / injection-style responses | `kResearchPrompt` was missing the safety clauses that `kChatPrompt` had. | Mirror `HARD REFUSALS`, `INJECTION RESISTANCE`, `CRISIS SUPPORT`, and `CODE OUTPUT IN FENCED BLOCK` into `kResearchPrompt`. **All system prompts must carry these four clauses.** |

Other landmines documented in [STATUS.md](STATUS.md):
- Raw string literals with `)"` substrings break tokenisation in `VizParser.cpp` / `ReActAgent.cpp`; use delimited `R"R(...)R"`.
- `qt_add_executable(X)` + `qt_add_qml_module(URI X)` clash on the filesystem; use distinct names (`Localyze` exe / `LocalyzeUI` module URI).
- `/sys/class/drm/card*/device/mem_info_vram_total` is the canonical VRAM source on Linux; without it the selector falls back to iGPU tier on dGPU hardware.

---

## 300-Q quality bar — ≥ 95% gate

Before any release tag, the 300-Q live gauntlet must score ≥ 95% on real
hardware, with **every** category at ≥ 90%.

- v1 baseline: 228/286 = 79.7%
- v4 (mode-switch fix + research-prompt safety): 262/286 = 91.6%
- **v5 (current): 277/286 = 96.9%** — see [STATUS.md § v5 quality milestone](STATUS.md#v5-quality-milestone-2026-05-18)

Categories tracked: `chat`, `clarify`, `writing`, `comparison`, `research`,
`safety`, `edge`, `code`, `math`, `multilingual`, `redteam`.

### Where to find the test infrastructure

| Asset | Path |
|---|---|
| 300-Q live test driver (latest) | `/tmp/qrepo/k300_driver_v2.py` and `k300_driver.py` |
| 300-Q graded results (v5) | `/tmp/qrepo/k300v5_graded.jsonl` |
| 300-Q final reports per pass | `/tmp/qrepo/k300v{4,5}_final_report.md` |
| 50-Q deep-research bank | [tests/bank250.json](tests/bank250.json) (research subset) |
| 1000-Q deeper sweep infrastructure | [tests/run_combined_1000.py](tests/run_combined_1000.py) + [tests/combined_1000_v3.json](tests/combined_1000_v3.json) |
| 41-test cross-platform unit suite | [tests/test_manifest_schema.py](tests/test_manifest_schema.py), [tests/test_viz_parser.py](tests/test_viz_parser.py), [tests/test_backend_selector.py](tests/test_backend_selector.py), [tests/test_react_protocol.py](tests/test_react_protocol.py) |
| Live-runner helpers | [tests/run_bank100.py](tests/run_bank100.py), [tests/run_bank250.py](tests/run_bank250.py), [tests/run_android_full.py](tests/run_android_full.py) |

Run the unit suite from `desktop/tests/` with:

```bash
python3 -m unittest -v
```

Run the 300-Q gauntlet against a built `Localyze` binary by pointing the driver
at it; see `/tmp/qrepo/k300_driver_v2.py` for the canonical invocation.

---

## Working style on this codebase

- Per the user's standing instructions, operate **fully autonomously** on Localyze
  desktop. Don't ask clarifying questions during fix-and-test loops; pick the
  best fix, execute end-to-end, report only when done.
- Prefer the simplest viable code. Declarative UI, batteries-included APIs, no
  premature abstractions.
- Visualisation must beat Claude's iframe-bound artifacts: native widgets,
  editable tables, real code execution, live streaming. No iframes, no JS
  sandbox.
- When proposing fixes that touch multiple subsystems, list 2–4 numbered options
  rather than prose — the user will pick.
- Phone-first weighting does **not** apply here; desktop is its own product
  with its own priorities. Phone is the parent project, not this one.
