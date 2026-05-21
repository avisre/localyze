# Localyze for Linux

C++20 + Qt 6 + QML + llama.cpp. Packaged as `AppImage` (universal) plus `.deb` / `.rpm` / Flatpak. Runs **Gemma 4 E4B** (same weights as the mobile app — GGUF for llama.cpp, OpenVINO IR for the Intel NPU path).

## Why this stack

- **Qt 6 + QML** — closest thing Linux has to a "native" UI on every distro. QML is declarative (terse), and Qt brings batteries: HTTP, JSON, Charts, MapView, WebEngine, networking, theming. Fewer external deps than GTK.
- **C++20** — same language `llama.cpp` is written in, so the inference glue is trivial (link, call functions, done).
- **llama.cpp** — best Linux LLM runtime. Build it once per backend (CUDA / HIP / Vulkan / CPU). For Intel NPU on Linux we add an alternative OpenVINO path.

## Toolchain

- Qt 6.7+ (with `Qt6QuickControls2`, `Qt6Charts`, `Qt6Network`, `Qt6Quick`)
- CMake 3.21+
- A C++20 compiler (gcc 12+ or clang 16+)
- For GPU/NPU: vendor SDKs (CUDA / ROCm / Vulkan SDK / OpenVINO 2024+)

## Model

Gemma 4 E4B (Google `google/gemma-3n-E4B-it`):

- **GGUF for llama.cpp:** community quants are already published — `bartowski/gemma-3n-E4B-it-GGUF` (Q4_K_M ≈ 2.2 GB, Q8 ≈ 4.0 GB, fp16 ≈ 7.5 GB)
- **OpenVINO IR for the Intel NPU path:**
  ```bash
  optimum-cli export openvino --model google/gemma-3n-E4B-it --weight-format int4 ./gemma-4-e4b-it-ov
  ```

The manifest tier maps each hardware bucket to one of these files.

## Build

```bash
# from desktop/linux/
mkdir build && cd build
cmake -DCMAKE_BUILD_TYPE=Release ..
cmake --build . -j
./Localyze
```

## Backend builds of llama.cpp

The installer pulls just one of these per device after the hardware probe:

| Build | When |
|---|---|
| `llama.cpp CUDA`        | NVIDIA dGPU detected |
| `llama.cpp HIP / ROCm`  | AMD dGPU + ROCm available |
| `llama.cpp Vulkan`      | Any modern GPU (NVIDIA/AMD/Intel) — universal fallback |
| `llama.cpp CPU`         | No GPU — small Q4 model only |
| OpenVINO Genai runtime  | Intel NPU (Meteor/Lunar Lake) on Linux |

## Project layout

```
linux/
├── CMakeLists.txt
├── src/
│   ├── main.cpp
│   ├── hardware/HardwareProbe.{h,cpp}
│   ├── inference/{BackendSelector,LlamaCppBackend}.{h,cpp}
│   ├── download/ModelDownloader.{h,cpp}
│   ├── research/ReActAgent.{h,cpp}
│   └── artifacts/VizParser.{h,cpp}
└── qml/
    ├── Main.qml
    └── ChatView.qml
```

## Status

Scaffold only. `cmake` configures clean once Qt 6 is installed. Inference adapter is a stub with the right API shape so QML can bind against it.
