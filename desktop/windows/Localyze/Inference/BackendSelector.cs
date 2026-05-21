using Localyze.Hardware;

namespace Localyze.Inference;

public enum ExecutionProvider { Qnn, DirectML, Cuda, Cpu }

/// <summary>Backend selection result. Always Gemma 4 E4B — only the format/quantization varies.</summary>
public readonly record struct Selection(
    Accelerator Tier,
    ExecutionProvider Ep,
    string TierId,
    string Reason,
    string Quantization);

public sealed class UnsupportedHardwareException : System.Exception
{
    public UnsupportedHardwareException(string reason) : base(reason) {}
}

public static class BackendSelector
{
    /// <summary>
    /// Picks the best Gemma 4 E4B tier for the detected hardware.
    /// Throws UnsupportedHardwareException if the device can't fit E4B at any tier —
    /// we refuse to install rather than swap in a smaller model. Matches mobile policy.
    /// </summary>
    public static Selection Pick(HardwareReport h, int contextTokens = 0)
    {
        // Priority: NPU > dGPU > iGPU > CPU. Quantization steps down with capability.

        if (h.Npu != NpuVendor.None && h.RamGb >= 16)
            return h.Npu switch
            {
                NpuVendor.QualcommHexagon => new(Accelerator.Npu, ExecutionProvider.Qnn,
                    "win-npu-snapdragon", "Snapdragon NPU + QNN EP", "int4"),
                _ => new(Accelerator.Npu, ExecutionProvider.DirectML,
                    "win-npu-directml", $"{h.Npu} via DirectML EP", "int8")
            };

        if (h.GpuVendor == GpuVendor.Nvidia && h.VramGb >= 10)
            return new(Accelerator.DiscreteGpu, ExecutionProvider.Cuda,
                "win-cuda-fp16", "NVIDIA dGPU + CUDA EP", "fp16");

        if (h.GpuVendor == GpuVendor.Nvidia && h.VramGb >= 6)
            return new(Accelerator.DiscreteGpu, ExecutionProvider.Cuda,
                "win-cuda-int8", "NVIDIA dGPU + CUDA EP", "int8");

        if (h.VramGb >= 6)
            return new(Accelerator.DiscreteGpu, ExecutionProvider.DirectML,
                "win-directml-dgpu", $"{h.GpuVendor} dGPU + DirectML EP", "int8");

        if (h.VramGb >= 4 || (h.GpuVendor != GpuVendor.None && h.RamGb >= 16))
            return new(Accelerator.IntegratedGpu, ExecutionProvider.DirectML,
                "win-directml-igpu", $"{h.GpuVendor} iGPU + DirectML EP", "int4");

        if (h.RamGb >= 16 && (h.HasAvx2 || h.HasAvx512))
            return new(Accelerator.Cpu, ExecutionProvider.Cpu,
                "win-cpu-int4", "CPU + ONNX Runtime int4 (slow but supported)", "int4");

        throw new UnsupportedHardwareException(
            $"Localyze needs ≥16 GB RAM and either an NPU, a GPU with ≥4 GB VRAM, or AVX2 CPU. " +
            $"Detected: {h.RamGb} GB RAM, {h.GpuVendor} GPU ({h.VramGb} GB VRAM), NPU={h.Npu}.");
    }
}
