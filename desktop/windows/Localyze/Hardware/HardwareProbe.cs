using System.Management;
using System.Runtime.InteropServices;

namespace Localyze.Hardware;

public enum Accelerator { Npu, DiscreteGpu, IntegratedGpu, Cpu }
public enum NpuVendor   { None, QualcommHexagon, IntelLunarLake, IntelMeteorLake, AmdXdna }
public enum GpuVendor   { None, Nvidia, Amd, Intel, Qualcomm, Other }

public readonly record struct HardwareReport(
    string OsVersion,
    string CpuName,
    int CpuCores,
    bool HasAvx2,
    bool HasAvx512,
    int RamGb,
    GpuVendor GpuVendor,
    string GpuName,
    int VramGb,
    NpuVendor Npu);

public static class HardwareProbe
{
    public static HardwareReport Run()
    {
        var (cpuName, cpuCores) = ReadCpu();
        var ramGb = (int)(GetTotalMemoryBytes() / 1_000_000_000);
        var (gpuVendor, gpuName, vramGb) = ReadPrimaryGpu();
        var npu = DetectNpu();

        return new HardwareReport(
            OsVersion: RuntimeInformation.OSDescription,
            CpuName: cpuName,
            CpuCores: cpuCores,
            HasAvx2: System.Runtime.Intrinsics.X86.Avx2.IsSupported,
            HasAvx512: System.Runtime.Intrinsics.X86.Avx512F.IsSupported,
            RamGb: ramGb,
            GpuVendor: gpuVendor,
            GpuName: gpuName,
            VramGb: vramGb,
            Npu: npu);
    }

    private static (string name, int cores) ReadCpu()
    {
        using var searcher = new ManagementObjectSearcher("SELECT Name, NumberOfCores FROM Win32_Processor");
        foreach (var obj in searcher.Get())
            return ((string)obj["Name"], (int)(uint)obj["NumberOfCores"]);
        return ("unknown", System.Environment.ProcessorCount);
    }

    private static (GpuVendor, string, int) ReadPrimaryGpu()
    {
        // Try the modern DXCore path first — it reports DedicatedVideoMemory as a 64-bit value,
        // so a 24 GB RTX 4090 isn't truncated to 4 GiB the way WMI's legacy UInt32 AdapterRAM is.
        var dxcore = TryReadGpuViaDxCore();
        if (dxcore is { } d) return d;

        // Win32_VideoController returns one row per adapter; pick the one with largest AdapterRAM.
        // AdapterRAM on Win32_VideoController is a CIM UInt32 and tops out at ~4 GiB; we widen the
        // local read to ulong so any provider that returns a 64-bit value still works, and we keep
        // the UInt32 fallback explicitly so older WMI providers continue to function.
        using var searcher = new ManagementObjectSearcher("SELECT Name, AdapterRAM FROM Win32_VideoController");
        GpuVendor best = GpuVendor.None;
        string bestName = "";
        ulong bestRam = 0;
        foreach (var obj in searcher.Get())
        {
            var name = (string)(obj["Name"] ?? "");
            ulong ram = obj["AdapterRAM"] switch
            {
                ulong ul => ul,
                long l   => l < 0 ? 0UL : (ulong)l,
                uint u   => (ulong)u,
                int  i   => i < 0 ? 0UL : (ulong)i,
                _        => 0UL
            };
            if (ram < bestRam) continue;
            bestRam = ram;
            bestName = name;
            best = VendorFromName(name);
        }
        return (best, bestName, (int)(bestRam / 1_000_000_000UL));
    }

    // Best-effort DXCore probe: queries the running DirectX 12 / DXGI stack for the
    // adapter's DedicatedVideoMemory (UINT64). Returns null if the runtime / OS doesn't
    // expose dxcore.dll or DXGI in a way we can call without a heavy P/Invoke surface;
    // the caller then falls back to WMI. Wrapped in try/catch so a missing dxgi.dll on
    // headless / minimal SKUs never breaks hardware detection.
    private static (GpuVendor, string, int)? TryReadGpuViaDxCore()
    {
        try
        {
            // DXGI exposes DedicatedVideoMemory as SIZE_T (UInt64 on x64), which is what we need.
            // We enumerate adapters and pick the one with the largest dedicated memory.
            ulong bestRam = 0;
            string bestName = "";
            GpuVendor best = GpuVendor.None;

            if (DxgiEnumerate(out var adapters))
            {
                foreach (var (name, dedicated) in adapters)
                {
                    if (dedicated < bestRam) continue;
                    bestRam = dedicated;
                    bestName = name;
                    best = VendorFromName(name);
                }
                if (bestRam > 0)
                    return (best, bestName, (int)(bestRam / 1_000_000_000UL));
            }
        }
        catch
        {
            // Fall through to WMI.
        }
        return null;
    }

    // Minimal DXGI enumeration via reflection-free P/Invoke. Implemented as a thin helper so
    // hardware probing stays self-contained; returns false when DXGI isn't loadable.
    private static bool DxgiEnumerate(out System.Collections.Generic.List<(string Name, ulong Dedicated)> adapters)
    {
        adapters = new System.Collections.Generic.List<(string, ulong)>();
        try
        {
            int hr = CreateDXGIFactory1(ref s_iidIDXGIFactory1, out var factoryPtr);
            if (hr < 0 || factoryPtr == System.IntPtr.Zero) return false;
            try
            {
                uint i = 0;
                while (true)
                {
                    // IDXGIFactory1::EnumAdapters1 sits at vtable slot 12.
                    var vtbl = Marshal.ReadIntPtr(factoryPtr);
                    var enumAdapters1Ptr = Marshal.ReadIntPtr(vtbl, 12 * System.IntPtr.Size);
                    var enumAdapters1 = (EnumAdapters1Delegate)Marshal.GetDelegateForFunctionPointer(
                        enumAdapters1Ptr, typeof(EnumAdapters1Delegate));
                    int eh = enumAdapters1(factoryPtr, i++, out var adapterPtr);
                    if (eh < 0 || adapterPtr == System.IntPtr.Zero) break;
                    try
                    {
                        // IDXGIAdapter1::GetDesc1 sits at vtable slot 10.
                        var avtbl = Marshal.ReadIntPtr(adapterPtr);
                        var getDesc1Ptr = Marshal.ReadIntPtr(avtbl, 10 * System.IntPtr.Size);
                        var getDesc1 = (GetDesc1Delegate)Marshal.GetDelegateForFunctionPointer(
                            getDesc1Ptr, typeof(GetDesc1Delegate));
                        var desc = new DXGI_ADAPTER_DESC1();
                        if (getDesc1(adapterPtr, ref desc) >= 0)
                        {
                            var name = new string(desc.Description).TrimEnd('\0');
                            // Skip the Microsoft Basic Render driver (software).
                            if ((desc.Flags & 2u) == 0)
                                adapters.Add((name, (ulong)desc.DedicatedVideoMemory.ToInt64()));
                        }
                    }
                    finally { Marshal.Release(adapterPtr); }
                }
            }
            finally { Marshal.Release(factoryPtr); }
            return adapters.Count > 0;
        }
        catch
        {
            return false;
        }
    }

    [DllImport("dxgi.dll")]
    private static extern int CreateDXGIFactory1(ref System.Guid riid, out System.IntPtr ppFactory);

    private static System.Guid s_iidIDXGIFactory1 = new("770aae78-f26f-4dba-a829-253c83d1b387");

    [UnmanagedFunctionPointer(System.Runtime.InteropServices.CallingConvention.StdCall)]
    private delegate int EnumAdapters1Delegate(System.IntPtr self, uint adapterIndex, out System.IntPtr adapter);

    [UnmanagedFunctionPointer(System.Runtime.InteropServices.CallingConvention.StdCall)]
    private delegate int GetDesc1Delegate(System.IntPtr self, ref DXGI_ADAPTER_DESC1 desc);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct DXGI_ADAPTER_DESC1
    {
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 128)]
        public char[] Description;
        public uint VendorId;
        public uint DeviceId;
        public uint SubSysId;
        public uint Revision;
        public System.IntPtr DedicatedVideoMemory;   // SIZE_T → 64-bit on x64
        public System.IntPtr DedicatedSystemMemory;
        public System.IntPtr SharedSystemMemory;
        public LUID AdapterLuid;
        public uint Flags;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct LUID { public uint LowPart; public int HighPart; }

    private static GpuVendor VendorFromName(string n) => n switch
    {
        var s when s.Contains("NVIDIA", System.StringComparison.OrdinalIgnoreCase) => GpuVendor.Nvidia,
        var s when s.Contains("Radeon", System.StringComparison.OrdinalIgnoreCase) || s.Contains("AMD", System.StringComparison.OrdinalIgnoreCase) => GpuVendor.Amd,
        var s when s.Contains("Intel", System.StringComparison.OrdinalIgnoreCase) => GpuVendor.Intel,
        var s when s.Contains("Adreno", System.StringComparison.OrdinalIgnoreCase) || s.Contains("Qualcomm", System.StringComparison.OrdinalIgnoreCase) => GpuVendor.Qualcomm,
        _ => GpuVendor.Other
    };

    private static NpuVendor DetectNpu()
    {
        // Heuristic by CPU name. The real probe would use DirectML's IDXCoreAdapterList
        // (DXCore) to enumerate AI accelerators, but that's a P/Invoke-heavy path
        // we don't need until first runtime build.
        using var searcher = new ManagementObjectSearcher("SELECT Name FROM Win32_Processor");
        foreach (var obj in searcher.Get())
        {
            var n = ((string)obj["Name"] ?? "").ToLowerInvariant();
            if (n.Contains("snapdragon") && n.Contains("x"))    return NpuVendor.QualcommHexagon;
            if (n.Contains("core ultra") && n.Contains("200"))  return NpuVendor.IntelLunarLake;
            if (n.Contains("core ultra"))                        return NpuVendor.IntelMeteorLake;
            if (n.Contains("ryzen") && n.Contains("ai"))         return NpuVendor.AmdXdna;
        }
        return NpuVendor.None;
    }

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetPhysicallyInstalledSystemMemory(out long totalKb);

    private static long GetTotalMemoryBytes()
    {
        return GetPhysicallyInstalledSystemMemory(out long kb) ? kb * 1024L : 0;
    }
}
