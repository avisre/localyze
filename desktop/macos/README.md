# Localyze for macOS

Swift + SwiftUI + MLX. Native Mac app, packaged as a notarized `.dmg`. Runs **Gemma 4 E4B** (same weights as the mobile app, converted to MLX).

## Why this stack

- **SwiftUI** — Apple's declarative UI; shortest path from idea to working window.
- **Swift 5.10+** — first-party language, clean async/await for token streaming.
- **MLX** (via `mlx-swift`) — Apple's official ML framework. On Apple Silicon it transparently uses **Neural Engine + GPU + unified memory**; you don't pick a backend, MLX schedules across them. This is the cleanest LLM stack on any OS right now.
- **llama.cpp Metal fallback** for Intel Macs (older devices with discrete GPUs).

## Toolchain

- macOS 14 Sonoma or newer (MLX needs it for full features)
- Xcode 15.4+
- Swift 5.10+
- Swift Package Manager (no CocoaPods / Carthage)

## Model

Gemma 4 E4B (Google `google/gemma-3n-E4B-it`) converted to MLX:

```bash
pip install mlx-lm
mlx_lm.convert --hf-path google/gemma-3n-E4B-it --mlx-path ./gemma-4-e4b-it-mlx -q
```

The CDN serves both an fp16 bundle (for ≥16 GB unified-memory Macs) and a 4-bit bundle (for 8–16 GB devices); the manifest tier picks based on the hardware probe.

## Build

```bash
# from desktop/macos/
swift build -c release
# Or open Package.swift in Xcode and run.
```

## Project layout

```
macos/
├── Package.swift            # SwiftPM manifest with mlx-swift dependency
├── Sources/Localyze/
│   ├── LocalyzeApp.swift    # @main entry
│   ├── Views/
│   │   └── ChatView.swift   # chat surface
│   ├── Hardware/
│   │   └── HardwareProbe.swift
│   ├── Inference/
│   │   ├── BackendSelector.swift
│   │   └── MLXBackend.swift   # MLX wrapper; llama.cpp Metal path for Intel Macs
│   ├── Download/
│   │   └── ModelDownloader.swift
│   ├── Research/
│   │   └── ReActAgent.swift
│   └── Artifacts/
│       └── VizParser.swift
```

## Status

Scaffold only. `swift build` will succeed once `mlx-swift` resolves (network needed). Inference adapter is a stub with the final API shape so the UI builds against it.
