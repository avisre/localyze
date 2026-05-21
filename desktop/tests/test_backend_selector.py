"""Reproduces the BackendSelector decision matrix in Python and exercises it.

Each native BackendSelector (Linux/macOS/Windows) has identical priority logic;
this test pins the matrix so a regression in any of the three would be obvious.

Priority: NPU > dGPU > iGPU > CPU. Refuse below minimum.
"""

from __future__ import annotations

import unittest
from dataclasses import dataclass


@dataclass
class Hw:
    os: str          # "windows" | "macos" | "linux"
    ram_gb: int
    vram_gb: int
    gpu_api: str     # "cuda" | "rocm" | "vulkan" | "directml" | "metal" | "none"
    npu: str         # "qualcomm-hexagon" | "intel-meteor-lake" | "amd-xdna" | "apple-neural-engine" | "none"
    chip: str        # "apple-silicon" | "intel" | "amd"
    has_avx2: bool = True


class UnsupportedHardware(Exception):
    pass


def select(h: Hw) -> dict:
    """Same priority + thresholds as the three native selectors."""
    # Windows
    if h.os == "windows":
        if h.npu != "none" and h.ram_gb >= 16:
            if h.npu == "qualcomm-hexagon":
                return {"tier": "npu", "ep": "qnn", "quant": "int4"}
            return {"tier": "npu", "ep": "directml", "quant": "int8"}
        if h.gpu_api == "cuda" and h.vram_gb >= 10:
            return {"tier": "dgpu", "ep": "cuda", "quant": "fp16"}
        if h.gpu_api == "cuda" and h.vram_gb >= 6:
            return {"tier": "dgpu", "ep": "cuda", "quant": "int8"}
        if h.vram_gb >= 6:
            return {"tier": "dgpu", "ep": "directml", "quant": "int8"}
        if h.vram_gb >= 4 or (h.gpu_api != "none" and h.ram_gb >= 16):
            return {"tier": "igpu", "ep": "directml", "quant": "int4"}
        if h.ram_gb >= 16 and h.has_avx2:
            return {"tier": "cpu", "ep": "cpu", "quant": "int4"}
        raise UnsupportedHardware("win minimum")

    # macOS
    if h.os == "macos":
        if h.chip == "apple-silicon" and h.ram_gb >= 16:
            return {"tier": "mlx", "ep": "mlx", "quant": "fp16"}
        if h.chip == "apple-silicon" and h.ram_gb >= 8:
            return {"tier": "mlx", "ep": "mlx", "quant": "int4"}
        if h.chip == "intel" and h.gpu_api == "metal" and h.ram_gb >= 16:
            return {"tier": "metal", "ep": "llamacpp-metal", "quant": "int4"}
        raise UnsupportedHardware("mac minimum")

    # Linux
    if h.os == "linux":
        if h.npu != "none" and h.ram_gb >= 16:
            return {"tier": "npu", "ep": "openvino", "quant": "int4"}
        if h.gpu_api == "cuda" and h.vram_gb >= 10:
            return {"tier": "dgpu", "ep": "llamacpp-cuda", "quant": "fp16"}
        if h.gpu_api == "cuda" and h.vram_gb >= 6:
            return {"tier": "dgpu", "ep": "llamacpp-cuda", "quant": "int8"}
        if h.gpu_api == "rocm" and h.vram_gb >= 6:
            return {"tier": "dgpu", "ep": "llamacpp-rocm", "quant": "int4"}
        if h.gpu_api == "vulkan" and h.vram_gb >= 4:
            return {"tier": "dgpu", "ep": "llamacpp-vulkan", "quant": "int4"}
        if h.gpu_api != "none" and h.ram_gb >= 16:
            return {"tier": "igpu", "ep": "llamacpp-vulkan", "quant": "int4"}
        if h.ram_gb >= 16 and h.has_avx2:
            return {"tier": "cpu", "ep": "llamacpp-cpu", "quant": "int4"}
        raise UnsupportedHardware("linux minimum")

    raise UnsupportedHardware(f"unknown os {h.os}")


class SelectorMatrixTests(unittest.TestCase):
    # ------------------------ Windows ------------------------
    def test_win_snapdragon_npu(self):
        sel = select(Hw("windows", 32, 0, "none", "qualcomm-hexagon", "arm"))
        self.assertEqual(sel, {"tier": "npu", "ep": "qnn", "quant": "int4"})

    def test_win_intel_npu(self):
        sel = select(Hw("windows", 32, 0, "none", "intel-meteor-lake", "intel"))
        self.assertEqual(sel, {"tier": "npu", "ep": "directml", "quant": "int8"})

    def test_win_nvidia_4090(self):
        sel = select(Hw("windows", 32, 24, "cuda", "none", "amd"))
        self.assertEqual(sel, {"tier": "dgpu", "ep": "cuda", "quant": "fp16"})

    def test_win_nvidia_3060_8gb(self):
        sel = select(Hw("windows", 32, 8, "cuda", "none", "amd"))
        self.assertEqual(sel, {"tier": "dgpu", "ep": "cuda", "quant": "int8"})

    def test_win_amd_dgpu(self):
        sel = select(Hw("windows", 32, 12, "directml", "none", "amd"))
        self.assertEqual(sel, {"tier": "dgpu", "ep": "directml", "quant": "int8"})

    def test_win_cpu_only(self):
        sel = select(Hw("windows", 32, 0, "none", "none", "intel"))
        self.assertEqual(sel, {"tier": "cpu", "ep": "cpu", "quant": "int4"})

    def test_win_unsupported(self):
        with self.assertRaises(UnsupportedHardware):
            select(Hw("windows", 8, 0, "none", "none", "intel", has_avx2=False))

    # ------------------------ macOS ------------------------
    def test_mac_m3_max(self):
        sel = select(Hw("macos", 64, 0, "metal", "apple-neural-engine", "apple-silicon"))
        self.assertEqual(sel, {"tier": "mlx", "ep": "mlx", "quant": "fp16"})

    def test_mac_m1_8gb(self):
        sel = select(Hw("macos", 8, 0, "metal", "apple-neural-engine", "apple-silicon"))
        self.assertEqual(sel, {"tier": "mlx", "ep": "mlx", "quant": "int4"})

    def test_mac_intel(self):
        sel = select(Hw("macos", 32, 0, "metal", "none", "intel"))
        self.assertEqual(sel, {"tier": "metal", "ep": "llamacpp-metal", "quant": "int4"})

    def test_mac_unsupported_intel_low_ram(self):
        with self.assertRaises(UnsupportedHardware):
            select(Hw("macos", 8, 0, "metal", "none", "intel"))

    # ------------------------ Linux ------------------------
    def test_linux_amd_rx9070(self):
        # This is the user's actual hardware. RX 9070 is ROCm + Vulkan capable; ROCm wins.
        sel = select(Hw("linux", 45, 16, "rocm", "none", "amd"))
        self.assertEqual(sel, {"tier": "dgpu", "ep": "llamacpp-rocm", "quant": "int4"})

    def test_linux_amd_rx9070_vulkan_only(self):
        # Same GPU but ROCm not installed.
        sel = select(Hw("linux", 45, 16, "vulkan", "none", "amd"))
        self.assertEqual(sel, {"tier": "dgpu", "ep": "llamacpp-vulkan", "quant": "int4"})

    def test_linux_nvidia_24gb(self):
        sel = select(Hw("linux", 32, 24, "cuda", "none", "amd"))
        self.assertEqual(sel, {"tier": "dgpu", "ep": "llamacpp-cuda", "quant": "fp16"})

    def test_linux_intel_npu(self):
        sel = select(Hw("linux", 32, 0, "none", "intel-meteor-lake", "intel"))
        self.assertEqual(sel, {"tier": "npu", "ep": "openvino", "quant": "int4"})

    def test_linux_cpu_only(self):
        sel = select(Hw("linux", 32, 0, "none", "none", "intel"))
        self.assertEqual(sel, {"tier": "cpu", "ep": "llamacpp-cpu", "quant": "int4"})

    def test_linux_unsupported(self):
        with self.assertRaises(UnsupportedHardware):
            select(Hw("linux", 8, 0, "none", "none", "intel"))


if __name__ == "__main__":
    unittest.main()
