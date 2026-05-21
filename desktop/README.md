# Localyze Desktop

Native desktop apps for **Windows, macOS, and Linux**. The Android app (in the parent folder) stays as it is — this is a parallel native implementation per OS, not a port.

## Design rules

1. **Native per platform.** No Electron, Tauri, Flutter, MAUI. Each OS uses its native UI toolkit and language.
2. **Simplest viable code.** Declarative UI, batteries-included frameworks, no premature abstractions.
3. **Hardware-adaptive.** At install time and every launch, detect available accelerators (NPU > dGPU > iGPU > CPU) and pick the best for the job.
4. **Lean install.** Installer is ~30 MB. After hardware probe, only the matching inference runtime + matching model artifact are downloaded. No multi-backend bundles.
5. **No web build.** The web target was considered and dropped. Desktop only.

## Platforms

All three run **Gemma 4 E4B — the same model the Android app uses.** Weights are identical; only the on-disk container changes per platform so each native runtime can load it efficiently.

| OS | Language | UI | Inference | Model container |
|---|---|---|---|---|
| [Windows](windows/) | C# / .NET 9 | WinUI 3 (XAML) | ONNX Runtime (DirectML / CUDA / CPU) | Gemma 4 E4B as ONNX |
| [macOS](macos/) | Swift 5.10+ | SwiftUI | MLX (Apple Silicon) / llama.cpp Metal (Intel) | Gemma 4 E4B as MLX / GGUF |
| [Linux](linux/) | C++20 | Qt 6 + QML | llama.cpp (CUDA / HIP / Vulkan / CPU) or OpenVINO (Intel NPU) | Gemma 4 E4B as GGUF / OpenVINO IR |

If the device can't fit Gemma 4 E4B (≥16 GB RAM, ≥4 GB VRAM **or** supported NPU **or** ≥12 GB shared iGPU memory), the installer refuses — matching the mobile philosophy. We don't quietly swap in a smaller model.

## Features above the mobile app

- **Deep research mode** — ReAct loop with local tools always available; web search is a single ON/OFF toggle (default off).
- **Artifacts richer than Claude** — native charts, editable tables, maps, real code execution, live streaming, native PDF/CSV/XLSX export. Rendered with the OS's own widget toolkit, not iframes.

## Layout

```
desktop/
├── README.md              # this file
├── ARCHITECTURE.md        # deep design + the hardware-detection / download flow
├── shared/
│   ├── manifest.schema.json   # device-tier → runtime+model mapping
│   ├── viz-schema.md          # the <viz> block format (used by all 3 platforms)
│   └── research-agent.md      # ReAct loop spec (mirrored in each platform's code)
├── windows/               # C# + WinUI 3
├── macos/                 # Swift + SwiftUI
└── linux/                 # C++ + Qt 6 + QML
```

## Build status

Scaffold only — no platform builds yet. Each platform README has the toolchain it needs.
