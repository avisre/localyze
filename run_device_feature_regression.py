#!/usr/bin/env python3
"""Device-level Localyze regression pass for chat, charts, Code workspace, and settings."""

from __future__ import annotations

import json
import os
import re
import sqlite3
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import quote

PKG = "com.localyze"
ACTIVITY = "com.localyze/.MainActivity"
DB_FILES = ("local_assistant_db", "local_assistant_db-wal", "local_assistant_db-shm")


@dataclass
class Check:
    name: str
    passed: bool
    detail: str = ""


class DeviceRegression:
    def __init__(self, device: str, out_dir: Path) -> None:
        self.device = device
        self.out_dir = out_dir
        self.out_dir.mkdir(parents=True, exist_ok=True)
        self.checks: list[Check] = []

    def run(self, args: list[str], timeout: int = 30, text: bool = True) -> subprocess.CompletedProcess:
        return subprocess.run(["adb", "-s", self.device, *args], capture_output=True, text=text, timeout=timeout)

    def shell(self, *args: str, timeout: int = 30, text: bool = True) -> subprocess.CompletedProcess:
        return self.run(["shell", *args], timeout=timeout, text=text)

    def record(self, name: str, passed: bool, detail: str = "") -> None:
        status = "PASS" if passed else "FAIL"
        print(f"[{status}] {name} {('- ' + detail) if detail else ''}")
        self.checks.append(Check(name, passed, detail))

    def force_stop(self) -> None:
        self.shell("am", "force-stop", PKG, timeout=10)

    def start_app(self, **extras: Any) -> None:
        cmd = ["am", "start", "-W", "-n", ACTIVITY]
        for key, value in extras.items():
            if isinstance(value, bool):
                cmd.extend(["--ez", key, "true" if value else "false"])
            else:
                cmd.extend(["--es", key, str(value)])
        result = self.shell(*cmd, timeout=20)
        if result.returncode != 0:
            raise RuntimeError(result.stderr or result.stdout)

    def screenshot(self, name: str) -> Path:
        path = self.out_dir / f"{name}.png"
        result = self.run(["exec-out", "screencap", "-p"], timeout=10, text=False)
        if result.returncode == 0:
            path.write_bytes(result.stdout)
        return path

    def dump_ui(self) -> str:
        self.shell("uiautomator", "dump", "/sdcard/localyze_ui.xml", timeout=10)
        result = self.shell("cat", "/sdcard/localyze_ui.xml", timeout=10)
        xml = result.stdout or ""
        (self.out_dir / "last_ui.xml").write_text(xml, encoding="utf-8")
        return xml

    def nodes(self, xml: str) -> list[dict[str, str]]:
        try:
            root = ET.fromstring(xml)
        except ET.ParseError:
            return []
        return [node.attrib for node in root.iter("node")]

    def find_node(self, label: str, contains: bool = False) -> dict[str, str] | None:
        needle = label.lower()
        for node in self.nodes(self.dump_ui()):
            haystacks = [node.get("text", ""), node.get("content-desc", ""), node.get("hint", "")]
            for hay in haystacks:
                hay_l = hay.lower()
                if (contains and needle in hay_l) or (not contains and needle == hay_l):
                    return node
        return None

    @staticmethod
    def center(bounds: str) -> tuple[int, int]:
        nums = [int(n) for n in re.findall(r"\d+", bounds)]
        return ((nums[0] + nums[2]) // 2, (nums[1] + nums[3]) // 2)

    def tap_node(self, node: dict[str, str]) -> None:
        x, y = self.center(node["bounds"])
        self.shell("input", "tap", str(x), str(y), timeout=10)
        time.sleep(1.0)

    def tap_label(self, label: str, contains: bool = False) -> bool:
        node = self.find_node(label, contains=contains)
        if not node:
            return False
        self.tap_node(node)
        return True

    def tap_bottom(self, label: str) -> None:
        if self.tap_label(label):
            return
        coords = {
            "Chat": (180, 2940),
            "Code": (540, 2940),
            "Library": (900, 2940),
            "Settings": (1260, 2940),
        }
        x, y = coords[label]
        self.shell("input", "tap", str(x), str(y), timeout=10)
        time.sleep(1.5)

    def snapshot_db(self) -> tuple[sqlite3.Connection, tempfile.TemporaryDirectory[str]]:
        tmpdir = tempfile.TemporaryDirectory()
        tmp_path = Path(tmpdir.name)
        for db_file in DB_FILES:
            result = self.run(
                ["exec-out", "run-as", PKG, "cat", f"databases/{db_file}"],
                timeout=10,
                text=False,
            )
            if result.returncode == 0 and result.stdout:
                tmp_path.joinpath(db_file).write_bytes(result.stdout)
        db_path = tmp_path / "local_assistant_db"
        if not db_path.exists():
            tmpdir.cleanup()
            raise RuntimeError("Could not read Room database through run-as.")
        conn = sqlite3.connect(db_path)
        conn.row_factory = sqlite3.Row
        return conn, tmpdir

    def latest_exchange(self, prompt: str, after_ms: int) -> dict[str, Any] | None:
        conn, tmpdir = self.snapshot_db()
        try:
            user = conn.execute(
                """
                SELECT id, conversationId, content, timestamp
                FROM messages
                WHERE role = 'USER' AND content = ? AND timestamp >= ?
                ORDER BY id DESC LIMIT 1
                """,
                (prompt, after_ms - 5000),
            ).fetchone()
            if user is None:
                return None
            assistant = conn.execute(
                """
                SELECT id, content, timestamp
                FROM messages
                WHERE role = 'ASSISTANT'
                  AND conversationId = ?
                  AND timestamp >= ?
                  AND length(trim(content)) > 0
                ORDER BY timestamp ASC, id ASC LIMIT 1
                """,
                (user["conversationId"], user["timestamp"]),
            ).fetchone()
            return {
                "conversation_id": user["conversationId"],
                "answer": assistant["content"] if assistant else "",
                "assistant_id": assistant["id"] if assistant else None,
            }
        finally:
            conn.close()
            tmpdir.cleanup()

    def wait_for_answer(self, prompt: str, timeout_s: int = 60) -> str:
        start_ms = int(time.time() * 1000)
        start = time.time()
        while time.time() - start < timeout_s:
            exchange = self.latest_exchange(prompt, start_ms)
            if exchange and exchange["answer"].strip():
                return exchange["answer"].strip()
            time.sleep(1.5)
        exchange = self.latest_exchange(prompt, start_ms)
        return exchange["answer"].strip() if exchange else ""

    def selected_model_pref(self) -> str:
        result = self.run(["exec-out", "run-as", PKG, "cat", "shared_prefs/model_download_prefs.xml"], timeout=10)
        match = re.search(r'<string name="selected_model">([^<]+)</string>', result.stdout or "")
        return match.group(1) if match else ""

    def write_report(self) -> None:
        payload = {
            "device": self.device,
            "output_dir": str(self.out_dir),
            "passed": sum(c.passed for c in self.checks),
            "total": len(self.checks),
            "checks": [c.__dict__ for c in self.checks],
        }
        (self.out_dir / "report.json").write_text(json.dumps(payload, indent=2), encoding="utf-8")
        lines = [
            f"# Localyze Device Regression",
            f"",
            f"Device: `{self.device}`",
            f"Result: **{payload['passed']}/{payload['total']} passed**",
            f"",
        ]
        for check in self.checks:
            mark = "PASS" if check.passed else "FAIL"
            lines.append(f"- {mark}: {check.name}" + (f" - {check.detail}" if check.detail else ""))
        (self.out_dir / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    def run_suite(self) -> None:
        self.force_stop()
        self.start_app()
        time.sleep(7)
        launch_xml = self.dump_ui()
        self.screenshot("01_chat_launch")
        self.record("Chat launches with branded header", "Localyze.ai" in launch_xml)
        self.record("Chat composer controls visible", "Attach image" in launch_xml and "Send message" in launch_xml)

        apple_prompt = "Show the last 3 years of Apple revenue as a bar chart."
        self.start_app(chat_msg=quote(apple_prompt, safe=""))
        apple_answer = self.wait_for_answer(apple_prompt, timeout_s=35)
        time.sleep(2)
        chart_xml = self.dump_ui()
        self.screenshot("02_chat_apple_revenue_chart")
        self.record("Apple revenue answer persisted", all(x in apple_answer for x in ("2023", "2024", "2025", "416.2")))
        self.record("Apple revenue answer uses Markdown table", "| 2025 | 416.2 |" in apple_answer)
        self.record("Inline bar chart renders in chat UI", "Inline bar chart" in chart_xml)

        self.tap_bottom("Code")
        code_xml = self.dump_ui()
        self.screenshot("03_code_workspace_empty")
        self.record(
            "Bottom nav switches Chat to Code workspace",
            ("Ready - describe what to build" in code_xml or "Paste code" in code_xml) and
                "Editor" in code_xml and
                "Preview" in code_xml,
        )

        code_prompt = "Build a complete ecommerce landing page for KickX sneakers with hero, 6 product cards, cart sidebar and newsletter signup"
        self.start_app(triggerTest=True, testPrompt=quote(code_prompt, safe=""))
        code_answer = self.wait_for_answer(code_prompt, timeout_s=50)
        time.sleep(4)
        preview_xml = self.dump_ui()
        self.screenshot("04_code_workspace_kickx_preview")
        product_cards = len(re.findall(r'class="product-card"', code_answer))
        self.record("Code workspace generates self-contained HTML", "<!doctype html" in code_answer.lower() and "</html>" in code_answer.lower())
        self.record("Generated website matches requested brand", "KickX" in code_answer)
        self.record("Generated website includes six product cards", product_cards == 6, f"found {product_cards}")
        self.record("Generated website includes cart sidebar", "cart-sidebar" in code_answer and "checkout" in code_answer.lower())
        self.record("Code workspace stays on visible Preview", "Preview" in preview_xml)

        editor_tapped = self.tap_label("Editor")
        time.sleep(1)
        editor_xml = self.dump_ui()
        preview_tapped = self.tap_label("Preview")
        time.sleep(1)
        self.screenshot("05_code_workspace_editor_preview_toggle")
        self.record("Editor/Preview segmented control is tappable", editor_tapped and preview_tapped and "Editor" in editor_xml)

        self.tap_bottom("Chat")
        time.sleep(2)
        chat_return_xml = self.dump_ui()
        self.screenshot("06_return_to_chat")
        self.record("Bottom nav switches Code workspace back to Chat", "Localyze.ai" in chat_return_xml)
        self.record("Chat retains visual answer after returning", "Inline bar chart" in chat_return_xml or "Apple Revenue" in chat_return_xml)

        self.tap_bottom("Library")
        library_xml = self.dump_ui()
        self.screenshot("07_library")
        self.record("Library tab opens", "Library" in library_xml or "Conversations" in library_xml)

        self.tap_bottom("Settings")
        settings_xml = self.dump_ui()
        self.screenshot("08_settings")
        self.record(
            "Settings tab opens",
            "Settings" in settings_xml and ("AI &amp; MODEL" in settings_xml or "Switch model" in settings_xml),
        )
        self.record("Settings shows active model", "Gemma 4 E4B" in settings_xml)

        opened_dialog = self.tap_label("Gemma 4 E4B", contains=True)
        time.sleep(1.5)
        dialog_xml = self.dump_ui()
        self.screenshot("09_model_dialog")
        self.record("Model information dialog opens", opened_dialog and "Model Information" in dialog_xml and "Switch Model" in dialog_xml)
        self.record("Model dialog lists both models", "Gemma 4 E4B" in dialog_xml and "Gemma 4 E2B" in dialog_xml)

        switched_to_e2b = self.tap_label("Gemma 4 E2B", contains=True)
        time.sleep(1.5)
        pref_e2b = self.selected_model_pref()
        self.screenshot("10_model_switched_e2b")
        self.record("Switching to Gemma 4 E2B updates preference", switched_to_e2b and pref_e2b == "gemma-4-E2B", pref_e2b)

        switched_back = self.tap_label("Gemma 4 E4B", contains=True)
        time.sleep(1.5)
        pref_e4b = self.selected_model_pref()
        self.screenshot("11_model_switched_e4b")
        self.record("Switching back to downloaded Gemma 4 E4B updates preference", switched_back and pref_e4b == "gemma-4-E4B", pref_e4b)

        self.tap_label("Close")
        self.write_report()


def detect_device() -> str:
    explicit = os.environ.get("ANDROID_SERIAL")
    if explicit:
        return explicit
    result = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=10)
    devices = [line.split()[0] for line in result.stdout.splitlines() if "\tdevice" in line]
    if not devices:
        raise SystemExit("No adb device attached.")
    return devices[0]


def main() -> None:
    stamp = time.strftime("%Y%m%d_%H%M%S")
    out_dir = Path("/tmp") / f"localyze_device_regression_{stamp}"
    suite = DeviceRegression(detect_device(), out_dir)
    try:
        suite.run_suite()
    finally:
        suite.write_report()
    passed = sum(c.passed for c in suite.checks)
    total = len(suite.checks)
    print(f"\nReport: {suite.out_dir / 'summary.md'}")
    print(f"Artifacts: {suite.out_dir}")
    print(f"Final: {passed}/{total} checks passed")
    raise SystemExit(0 if passed == total else 1)


if __name__ == "__main__":
    main()
