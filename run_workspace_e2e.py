#!/usr/bin/env python3
"""
End-to-end test for the Code Workspace screen.

For each prompt in PROMPTS, we:
  1. Force-stop the app
  2. Fire the debug intent triggerTest=true testPrompt=<prompt>
     which makes MainActivity navigate to the Code Workspace and submit
     the prompt to Gemma
  3. Tail logcat for token deltas (GemmaInference: Received text content)
     and the workspace completion marker (CodeWorkspace: Streaming completed)
  4. Reconstruct the response, save it, and check basic properties
"""
from __future__ import annotations
import json, re, subprocess, sys, time
from pathlib import Path

DEVICE = "a5523839"
PKG = "com.localyze"
COMPONENT = "com.localyze/.MainActivity"
TIMEOUT_S = 240
PROMPTS = [
    ("ws_landing",  "build a simple landing page for a coffee shop"),
    ("ws_explain",  "explain what a Python list comprehension is"),
    ("ws_debug",    "find the bug: def avg(xs): return sum(xs)/len(xs)"),
]


def adb(*args: str, capture: bool = True) -> str:
    cmd = ["adb", "-s", DEVICE, *args]
    return subprocess.check_output(cmd, text=True) if capture else subprocess.call(cmd)


def fire_intent(prompt: str) -> None:
    subprocess.call(["adb", "-s", DEVICE, "shell", "am", "force-stop", PKG])
    time.sleep(1.0)
    subprocess.call(["adb", "-s", DEVICE, "logcat", "-c"])
    # decodeDebugMessage replaces %s with space + URL-decodes; we URL-encode
    # everything that's not [A-Za-z0-9] so shell + the activity see clean text.
    from urllib.parse import quote
    encoded = quote(prompt).replace("%20", "%s")
    subprocess.call([
        "adb", "-s", DEVICE, "shell", "am", "start",
        "-n", COMPONENT,
        "--ez", "triggerTest", "true",
        "--es", "testPrompt", encoded,
    ])


def ui_status_bar_text() -> str:
    """Return the workspace status-bar text ('Ready - N lines - HTML',
    'Working...', or 'Ready - describe what to build')."""
    try:
        subprocess.check_call(
            ["adb", "-s", DEVICE, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10,
        )
        out = subprocess.check_output(
            ["adb", "-s", DEVICE, "shell", "cat", "/sdcard/ui.xml"],
            text=True, timeout=10,
        )
    except Exception:
        return ""
    m = re.search(r'text="(Ready - [^"]+|Working[^"]*)"', out)
    return m.group(1) if m else ""


def collect_until_completion(timeout_s: int) -> tuple[str, str, list[str], dict]:
    """Poll the UI status bar AND logcat. Completion = either the status bar
    flips to 'Ready - N lines - <lang>' (template OR Gemma path), or we see
    a CodeWorkspace completion marker in logcat (Gemma path only).
    """
    start = time.time()
    answer = ""
    apply_log: list[str] = []
    code_length = 0
    full_text_len = 0
    path = "?"
    status = "TIMEOUT"
    status_text = ""
    saw_working = False
    poll_count = 0

    while time.time() - start < timeout_s:
        time.sleep(3.0)
        poll_count += 1
        # UI poll every other tick
        if poll_count % 2 == 0:
            status_text = ui_status_bar_text()
            if "Working" in status_text:
                saw_working = True
            mb = re.match(r"Ready - (\d+) lines - (.+)", status_text)
            if mb and saw_working:
                code_length = int(mb.group(1))
                if path == "?":
                    path = "template" if full_text_len == 0 else "gemma"
                status = "OK"
                # one final logcat scrape
                try:
                    dump = subprocess.check_output(
                        ["adb", "-s", DEVICE, "logcat", "-d",
                         "GemmaInference:V", "CodeWorkspace:V", "*:S"],
                        text=True, stderr=subprocess.DEVNULL, timeout=20,
                    )
                    apply_log = [ln for ln in dump.splitlines() if "CodeWorkspace" in ln][-30:]
                    tokens = re.findall(r"Received text content: '(.*?)\.\.\.'", dump)
                    if tokens:
                        answer = "".join(tokens)
                except Exception:
                    pass
                return status, answer, apply_log, {
                    "path": path, "code_length": code_length,
                    "full_text_len": full_text_len,
                    "ui_status": status_text,
                }
        try:
            dump = subprocess.check_output(
                ["adb", "-s", DEVICE, "logcat", "-d",
                 "GemmaInference:V", "CodeWorkspace:V", "MainActivity:V", "*:S"],
                text=True, stderr=subprocess.DEVNULL, timeout=20,
            )
        except subprocess.TimeoutExpired:
            continue

        lines = dump.splitlines()
        cw_lines = [ln for ln in lines if "CodeWorkspace" in ln]
        if cw_lines:
            apply_log = cw_lines[-30:]

        # Gemma token deltas
        tokens = re.findall(r"Received text content: '(.*?)\.\.\.'", dump)
        if tokens:
            answer = "".join(tokens)

        m_full = re.search(r"Streaming completed, fullText length=(\d+)", dump)
        if m_full:
            full_text_len = int(m_full.group(1))
            path = "gemma"

        m_apply = re.search(r"After apply: code length=(\d+)", dump)
        if m_apply:
            code_length = int(m_apply.group(1))
            if path == "?":
                path = "template" if full_text_len == 0 else "gemma"
            status = "OK"
            return status, answer, apply_log, {
                "path": path, "code_length": code_length,
                "full_text_len": full_text_len,
            }

        m_cmpl = re.search(r"Completed text length=(\d+), containsCodeBlock=", dump)
        if m_cmpl and not m_apply:
            # Explain/Debug: completion without auto-apply
            full_text_len = int(m_cmpl.group(1))
            path = "gemma"
            status = "OK"
            return status, answer, apply_log, {
                "path": path, "code_length": 0,
                "full_text_len": full_text_len,
            }

        if "applyResponseToEditor called" in dump:
            # Generation finishing; allow a couple more polls
            pass
        if re.search(r"GemmaInference.*?(error|exception)", dump, re.IGNORECASE):
            return "ERROR", answer, apply_log, {"path": "gemma", "code_length": 0, "full_text_len": 0}

    return status, answer, apply_log, {"path": path, "code_length": 0, "full_text_len": 0}


def main() -> None:
    out: list[dict] = []
    for tag, prompt in PROMPTS:
        print(f"\n[{tag}] {prompt}", flush=True)
        fire_intent(prompt)
        t0 = time.time()
        status, answer, apply_log, info = collect_until_completion(TIMEOUT_S)
        elapsed = round(time.time() - t0, 1)
        out.append({
            "tag": tag, "prompt": prompt, "status": status,
            "answer": answer, "answer_len": len(answer),
            "elapsed_s": elapsed, "info": info,
            "apply_log": apply_log[-12:],
        })
        print(f"  -> {status} in {elapsed}s, path={info['path']}, "
              f"code_length={info['code_length']}, gemma_text={info['full_text_len']}", flush=True)
    Path("workspace_e2e.json").write_text(json.dumps({"results": out}, indent=2))
    # Print short scorecard
    print("\nSummary:")
    print(f"  {'TAG':<12} {'STATUS':<8} {'PATH':<10} {'TIME':>6} {'CODE':>6} {'GEMMA':>6}")
    for r in out:
        info = r["info"]
        print(f"  {r['tag']:<12} {r['status']:<8} {info['path']:<10} "
              f"{r['elapsed_s']:>5}s {info['code_length']:>6} {info['full_text_len']:>6}")


if __name__ == "__main__":
    main()
