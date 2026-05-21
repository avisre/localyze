#include "BackendSelector.h"

#include <string>

namespace localyze {

Selection BackendSelector::pick(const HardwareReport& h) {
    // Priority: NPU > dGPU > iGPU > CPU. Quantization steps with capability.
    if (h.npu != NpuKind::None && h.ramGB >= 16) {
        return {BackendKind::OpenVinoNpu, "linux-openvino-intel-npu",
                "OpenVINO NPU", "Intel NPU + OpenVINO runtime", "int4"};
    }
    if (h.gpuApi == GpuApi::Cuda && h.vramGB >= 10) {
        return {BackendKind::LlamaCppCuda, "linux-cuda-fp16",
                "llama.cpp CUDA fp16", "NVIDIA dGPU ≥10 GB VRAM", "fp16"};
    }
    if (h.gpuApi == GpuApi::Cuda && h.vramGB >= 6) {
        return {BackendKind::LlamaCppCuda, "linux-cuda-q8",
                "llama.cpp CUDA Q8", "NVIDIA dGPU 6–10 GB VRAM", "int8"};
    }
    if (h.gpuApi == GpuApi::Rocm && h.vramGB >= 6) {
        return {BackendKind::LlamaCppRocm, "linux-rocm",
                "llama.cpp ROCm Q4", "AMD dGPU + ROCm/HIP", "int4"};
    }
    if (h.gpuApi == GpuApi::Vulkan && h.vramGB >= 4) {
        return {BackendKind::LlamaCppVulkan, "linux-vulkan-q4",
                "llama.cpp Vulkan Q4", "Vulkan-compatible GPU", "int4"};
    }
    if (h.gpuApi != GpuApi::None && h.ramGB >= 16) {
        return {BackendKind::LlamaCppVulkan, "linux-vulkan-igpu-q4",
                "llama.cpp Vulkan Q4 (iGPU)", "Integrated GPU, shared system memory", "int4"};
    }
    if (h.ramGB >= 16 && (h.hasAvx2 || h.hasAvx512)) {
        return {BackendKind::LlamaCppCpu, "linux-cpu-q4",
                "llama.cpp CPU Q4", "CPU fallback (slow but supported)", "int4"};
    }
    throw UnsupportedHardware(
        std::string("Localyze for Linux needs ≥16 GB RAM and either an NPU, ") +
        "a GPU with ≥4 GB VRAM, or AVX2 CPU. Detected " +
        std::to_string(h.ramGB) + " GB RAM, " + h.gpuName +
        " GPU (" + std::to_string(h.vramGB) + " GB VRAM).");
}

}  // namespace localyze
