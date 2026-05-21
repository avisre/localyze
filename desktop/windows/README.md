# Localyze for Windows

C# + WinUI 3 + ONNX Runtime. Native Windows 11 app, packaged as MSIX. Runs **Gemma 4 E4B** (same weights as the mobile app, exported to ONNX).

## Why this stack

- **WinUI 3 + XAML** — Microsoft's current native UI. Declarative XAML keeps UI code terse.
- **C# / .NET 9** — first-party language for WinUI; clean async/await for inference streaming.
- **ONNX Runtime** — one library, every Windows accelerator:
  - NPU (Snapdragon X / Intel Lunar/Meteor Lake NPU / AMD XDNA) → DirectML execution provider
  - NVIDIA GPU → CUDA execution provider
  - Any DX12 GPU → DirectML execution provider
  - CPU fallback → default CPU EP

## Toolchain

- Windows 11 22H2+ (Windows App SDK 1.6 needs it)
- Visual Studio 2022 17.10+ with workloads: ".NET desktop development", "Windows application development", "Universal Windows Platform development"
- .NET 9 SDK
- Windows App SDK 1.6+
- ONNX Runtime 1.20+ (pulled via NuGet)

## Model

Gemma 4 E4B (Google `google/gemma-3n-E4B-it`) exported to ONNX. Two ways to produce the artifact:

```bash
# Option A: Microsoft's ONNX GenAI model builder (KV-cache friendly, what onnxruntime-genai expects)
python -m onnxruntime_genai.models.builder \
    -m google/gemma-3n-E4B-it -o ./gemma-4-e4b-it-onnx -p int4 -e dml

# Option B: HuggingFace Optimum (more generic)
optimum-cli export onnx --model google/gemma-3n-E4B-it --task text-generation gemma-4-e4b-it-onnx
```

The CDN serves several quantizations (fp16 / int8 / int4) and the manifest tier picks one based on the hardware probe.

## Build

```powershell
# from desktop\windows\
dotnet restore
dotnet build -c Release
# Or open Localyze.sln in Visual Studio and F5.
```

## Project layout

```
Localyze/
├── Localyze.csproj         # NuGet refs, WinUI 3 SDK, MSIX packaging
├── App.xaml(.cs)           # app entry
├── MainWindow.xaml(.cs)    # main window shell
├── Hardware/
│   └── HardwareProbe.cs    # NPU/GPU/CPU/RAM detection
├── Inference/
│   ├── BackendSelector.cs  # picks NPU > dGPU > iGPU > CPU
│   └── OnnxBackend.cs      # ONNX Runtime wrapper (DML/CUDA/CPU)
├── Download/
│   └── ModelDownloader.cs  # manifest fetch + resumable downloads
├── Views/
│   └── ChatView.xaml(.cs)  # chat surface
├── Research/
│   └── ReActAgent.cs       # deep research loop
└── Artifacts/
    └── VizParser.cs        # <viz> block parsing + native renderers
```

## Status

Scaffold only. Compiles to "Hello Localyze" once you wire the NuGet packages. Inference adapter is a stub with the right shape.
