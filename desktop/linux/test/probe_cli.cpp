// Standalone CLI that exercises HardwareProbe + BackendSelector on real hardware.
// Built separately from the main app to verify the core logic works without the
// QML/GUI stack.

#include "../src/hardware/HardwareProbe.h"
#include "../src/inference/BackendSelector.h"

#include <QCoreApplication>
#include <iostream>

const char* gpuApiName(localyze::GpuApi a) {
    using A = localyze::GpuApi;
    switch (a) {
        case A::None:   return "none";
        case A::Cuda:   return "cuda";
        case A::Rocm:   return "rocm";
        case A::Vulkan: return "vulkan";
    }
    return "?";
}

const char* npuName(localyze::NpuKind n) {
    using N = localyze::NpuKind;
    switch (n) {
        case N::None:             return "none";
        case N::IntelMeteorLake:  return "intel-meteor-lake";
        case N::IntelLunarLake:   return "intel-lunar-lake";
    }
    return "?";
}

const char* backendKindName(localyze::BackendKind k) {
    using K = localyze::BackendKind;
    switch (k) {
        case K::OpenVinoNpu:      return "openvino-npu";
        case K::LlamaCppCuda:     return "llamacpp-cuda";
        case K::LlamaCppRocm:     return "llamacpp-rocm";
        case K::LlamaCppVulkan:   return "llamacpp-vulkan";
        case K::LlamaCppCpu:      return "llamacpp-cpu";
    }
    return "?";
}

int main(int argc, char* argv[]) {
    QCoreApplication app(argc, argv);

    std::cout << "=== Localyze hardware probe ===\n";
    const auto hw = localyze::HardwareProbe::run();
    std::cout << "OS:        " << hw.osRelease << "\n";
    std::cout << "CPU:       " << hw.cpuName << "  (" << hw.cpuCores << " cores)\n";
    std::cout << "AVX2:      " << (hw.hasAvx2 ? "yes" : "no") << "\n";
    std::cout << "AVX512:    " << (hw.hasAvx512 ? "yes" : "no") << "\n";
    std::cout << "RAM:       " << hw.ramGB << " GB\n";
    std::cout << "GPU:       " << hw.gpuName << "  (" << hw.vramGB << " GB VRAM, api=" << gpuApiName(hw.gpuApi) << ")\n";
    std::cout << "NPU:       " << npuName(hw.npu) << "\n";

    std::cout << "\n=== Backend selection (Gemma 4 E4B) ===\n";
    try {
        const auto sel = localyze::BackendSelector::pick(hw);
        std::cout << "Tier ID:        " << sel.tierId << "\n";
        std::cout << "Backend kind:   " << backendKindName(sel.kind) << "\n";
        std::cout << "Label:          " << sel.label << "\n";
        std::cout << "Quantization:   " << sel.quantization << "\n";
        std::cout << "Reason:         " << sel.reason << "\n";
        std::cout << "\nVERDICT: This device CAN run Gemma 4 E4B at " << sel.quantization
                  << " via " << backendKindName(sel.kind) << ".\n";
        return 0;
    } catch (const localyze::UnsupportedHardware& e) {
        std::cout << "Tier:           UNSUPPORTED\n";
        std::cout << "Reason:         " << e.what() << "\n";
        std::cout << "\nVERDICT: This device CANNOT run Gemma 4 E4B.\n";
        return 1;
    }
}
