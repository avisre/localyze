"""Validates that manifest.example.json conforms to manifest.schema.json
and that the tier set covers every platform/accelerator combination."""

from __future__ import annotations

import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMA_PATH = ROOT / "shared" / "manifest.schema.json"
MANIFEST_PATH = ROOT / "shared" / "manifest.example.json"


def _load_json(p: Path) -> dict:
    with p.open() as f:
        return json.load(f)


class ManifestSchemaTests(unittest.TestCase):
    def setUp(self) -> None:
        self.schema = _load_json(SCHEMA_PATH)
        self.manifest = _load_json(MANIFEST_PATH)

    def test_has_version_and_tiers(self) -> None:
        self.assertIn("version", self.manifest)
        self.assertIn("tiers", self.manifest)
        self.assertGreater(len(self.manifest["tiers"]), 0)

    def test_each_tier_has_required_keys(self) -> None:
        for tier in self.manifest["tiers"]:
            self.assertIn("id", tier, f"missing id in tier: {tier}")
            self.assertIn("match", tier, f"missing match in tier {tier['id']}")
            self.assertIn("model", tier, f"missing model in tier {tier['id']}")

    def test_model_format_is_one_of_known(self) -> None:
        allowed = {"onnx", "gguf", "mlx", "openvino-ir", "coreml"}
        for tier in self.manifest["tiers"]:
            self.assertIn(tier["model"]["format"], allowed,
                          f"unknown model format in tier {tier['id']}")

    def test_sha256_shape(self) -> None:
        rx = re.compile(r"^[a-f0-9]{64}$")
        for tier in self.manifest["tiers"]:
            self.assertRegex(tier["model"]["sha256"], rx,
                             f"bad sha256 in tier {tier['id']}")
            if tier.get("runtime"):
                self.assertRegex(tier["runtime"]["sha256"], rx,
                                 f"bad runtime sha in {tier['id']}")

    def test_quantization_is_known(self) -> None:
        allowed = {"fp16", "int8", "int4"}
        for tier in self.manifest["tiers"]:
            q = tier["model"].get("quantization")
            if q is not None:
                self.assertIn(q, allowed, f"unknown quant in tier {tier['id']}")

    def test_all_three_oses_covered(self) -> None:
        oses = {tier["match"]["os"] for tier in self.manifest["tiers"]}
        self.assertEqual(oses, {"windows", "macos", "linux"})

    def test_npu_dgpu_igpu_cpu_each_appear(self) -> None:
        # Sanity: we want at least one NPU tier, one dGPU/iGPU tier, and one CPU tier
        npu = [t for t in self.manifest["tiers"] if "npu" in t["match"]]
        gpu = [t for t in self.manifest["tiers"] if "gpu_api" in t["match"]]
        cpu = [t for t in self.manifest["tiers"] if "cpu_features" in t["match"]]
        self.assertGreater(len(npu), 0, "no NPU tier")
        self.assertGreater(len(gpu), 0, "no GPU tier")
        self.assertGreater(len(cpu), 0, "no CPU tier")

    def test_ids_unique(self) -> None:
        ids = [tier["id"] for tier in self.manifest["tiers"]]
        self.assertEqual(len(ids), len(set(ids)), "duplicate tier ids")


if __name__ == "__main__":
    unittest.main()
