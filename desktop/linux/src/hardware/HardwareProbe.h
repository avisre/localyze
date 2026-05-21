#pragma once
#include <string>
#include <vector>

namespace localyze {

enum class GpuApi   { None, Cuda, Rocm, Vulkan };
enum class NpuKind  { None, IntelMeteorLake, IntelLunarLake };

struct HardwareReport {
    std::string osRelease;
    std::string cpuName;
    int cpuCores = 0;
    bool hasAvx2 = false;
    bool hasAvx512 = false;
    int ramGB = 0;
    std::string gpuName;
    GpuApi gpuApi = GpuApi::None;
    int vramGB = 0;
    NpuKind npu = NpuKind::None;
};

struct HardwareProbe {
    static HardwareReport run();
};

}  // namespace localyze
