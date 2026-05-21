#include "HardwareProbe.h"

#include <QFile>
#include <QProcess>
#include <QRegularExpression>
#include <QSysInfo>
#include <QTextStream>
#include <sys/sysinfo.h>
#include <filesystem>
#include <thread>

namespace localyze {

namespace {

std::string readFile(const QString& path) {
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly | QIODevice::Text)) return {};
    return QString::fromUtf8(f.readAll()).toStdString();
}

std::string readCpuName() {
    const auto cpuinfo = QString::fromStdString(readFile("/proc/cpuinfo"));
    QRegularExpression rx(R"(model name\s*:\s*(.+))");
    auto m = rx.match(cpuinfo);
    return m.hasMatch() ? m.captured(1).trimmed().toStdString() : "unknown";
}

bool hasCpuFlag(const std::string& flag) {
    const auto cpuinfo = QString::fromStdString(readFile("/proc/cpuinfo"));
    return cpuinfo.contains(QRegularExpression("\\b" + QString::fromStdString(flag) + "\\b"));
}

bool nvidiaPresent() {
    QProcess p;
    p.start("nvidia-smi", {"-L"});
    if (!p.waitForFinished(800)) return false;
    return p.exitCode() == 0 && !p.readAllStandardOutput().isEmpty();
}

bool amdPresent() {
    // Heuristic: walk /sys/class/drm/card*/device/vendor; 0x1002 = AMD.
    for (const auto& e : std::filesystem::directory_iterator("/sys/class/drm")) {
        auto path = e.path() / "device" / "vendor";
        if (!std::filesystem::exists(path)) continue;
        if (QString::fromStdString(readFile(QString::fromStdString(path.string()))).trimmed() == "0x1002")
            return true;
    }
    return false;
}

int largestVramGB() {
    // Walk all /sys/class/drm/cardN/device/mem_info_vram_total and return the largest in GB.
    int bestGB = 0;
    for (const auto& e : std::filesystem::directory_iterator("/sys/class/drm")) {
        auto path = e.path() / "device" / "mem_info_vram_total";
        if (!std::filesystem::exists(path)) continue;
        const auto txt = QString::fromStdString(readFile(QString::fromStdString(path.string()))).trimmed();
        bool ok = false;
        const auto bytes = txt.toLongLong(&ok);
        if (ok) bestGB = qMax(bestGB, static_cast<int>(bytes / 1'000'000'000));
    }
    return bestGB;
}

QString primaryGpuName() {
    // Read the PCI device name for the first AMD/NVIDIA card found.
    QProcess p;
    p.start("lspci", {"-mm", "-n", "-k"});
    if (!p.waitForFinished(800)) return {};
    const auto out = QString::fromUtf8(p.readAllStandardOutput());
    QRegularExpression rx(R"R(\bVGA\b.*"([^"]+)"\s+"([^"]+)")R");
    auto it = rx.globalMatch(out);
    while (it.hasNext()) {
        auto m = it.next();
        const auto desc = m.captured(2);
        if (desc.contains("Radeon", Qt::CaseInsensitive)) return desc;
        if (desc.contains("GeForce", Qt::CaseInsensitive)) return desc;
        if (desc.contains("Arc", Qt::CaseInsensitive))     return desc;
    }
    return {};
}

bool vulkanPresent() {
    QProcess p;
    p.start("vulkaninfo", {"--summary"});
    if (!p.waitForFinished(800)) return false;
    return p.exitCode() == 0;
}

bool intelNpuPresent() {
    // /dev/accel/accel0 is the accel-subsystem device node Intel NPU exposes on
    // modern kernels (6.7+). Crude but reliable enough for a launch-time probe.
    return std::filesystem::exists("/dev/accel/accel0");
}

}  // namespace

HardwareReport HardwareProbe::run() {
    HardwareReport r;
    r.osRelease  = QSysInfo::prettyProductName().toStdString();
    r.cpuName    = readCpuName();
    r.cpuCores   = static_cast<int>(std::thread::hardware_concurrency());
    r.hasAvx2    = hasCpuFlag("avx2");
    r.hasAvx512  = hasCpuFlag("avx512f");

    struct sysinfo si{};
    if (sysinfo(&si) == 0) r.ramGB = static_cast<int>(si.totalram / 1'000'000'000);

    if (nvidiaPresent())      { r.gpuApi = GpuApi::Cuda;   r.gpuName = "NVIDIA"; }
    else if (amdPresent())    { r.gpuApi = GpuApi::Rocm;   r.gpuName = "AMD"; }
    else if (vulkanPresent()) { r.gpuApi = GpuApi::Vulkan; r.gpuName = "Vulkan-compatible"; }

    r.vramGB = largestVramGB();
    if (const auto pretty = primaryGpuName(); !pretty.isEmpty()) {
        r.gpuName = pretty.toStdString();
    }

    if (intelNpuPresent()) {
        r.npu = QString::fromStdString(r.cpuName).contains("Core Ultra 2", Qt::CaseInsensitive)
              ? NpuKind::IntelLunarLake : NpuKind::IntelMeteorLake;
    }
    return r;
}

}  // namespace localyze
