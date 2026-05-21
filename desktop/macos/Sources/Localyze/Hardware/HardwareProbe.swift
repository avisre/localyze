import Foundation
#if canImport(IOKit)
import IOKit
#endif

enum Chip { case appleSilicon, intel }

struct HardwareReport {
    let osVersion: String
    let chip: Chip
    let cpuName: String
    let cpuCores: Int
    let ramGB: Int
    let hasNeuralEngine: Bool   // true on every Apple Silicon Mac
    let metalSupported: Bool
}

enum HardwareProbe {
    static func run() -> HardwareReport {
        let info = ProcessInfo.processInfo
        let ramGB = Int(info.physicalMemory / 1_000_000_000)
        let cores = info.activeProcessorCount

        let chip: Chip
        #if arch(arm64)
        chip = .appleSilicon
        #else
        chip = .intel
        #endif

        return HardwareReport(
            osVersion: info.operatingSystemVersionString,
            chip: chip,
            cpuName: sysctlString("machdep.cpu.brand_string") ?? "Apple",
            cpuCores: cores,
            ramGB: ramGB,
            hasNeuralEngine: chip == .appleSilicon,
            metalSupported: true  // Every Mac since 2012 supports Metal
        )
    }

    private static func sysctlString(_ name: String) -> String? {
        var size = 0
        sysctlbyname(name, nil, &size, nil, 0)
        guard size > 0 else { return nil }
        var buf = [CChar](repeating: 0, count: size)
        sysctlbyname(name, &buf, &size, nil, 0)
        return String(cString: buf)
    }
}
