#pragma once
#include "../hardware/HardwareProbe.h"
#include <optional>
#include <stdexcept>
#include <string>

namespace localyze {

enum class BackendKind {
    OpenVinoNpu,
    LlamaCppCuda,
    LlamaCppRocm,
    LlamaCppVulkan,
    LlamaCppCpu,
};

// Always Gemma 4 E4B; only the quantization changes.
struct Selection {
    BackendKind kind;
    std::string tierId;
    std::string label;
    std::string reason;
    std::string quantization;   // "fp16" | "int8" | "int4"
};

struct UnsupportedHardware : std::runtime_error {
    using std::runtime_error::runtime_error;
};

struct BackendSelector {
    // Returns the best Gemma 4 E4B tier for the device.
    // Throws UnsupportedHardware if it can't fit at any tier.
    static Selection pick(const HardwareReport& h);
};

}  // namespace localyze
