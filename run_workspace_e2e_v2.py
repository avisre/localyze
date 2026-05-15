#!/usr/bin/env python3
"""Simple direct e2e: fire each prompt, wait, screenshot, dump UI to extract
the assistant message + status bar text. No logcat dependence."""
from __future__ import annotations
import json, re, subprocess, time
from pathlib import Path
from urllib.parse import quote

DEVICE = "a5523839"
PKG = "com.localyze"
COMPONENT = "com.localyze/.MainActivity"

PROMPTS = [
    # First prompt force-stops + cold-starts the app & engine (template
    # path returns instantly while Gemma loads in the background). Later
    # prompts reuse the warm engine.
    ("ws_landing",  "build a simple landing page for a coffee shop",  60, True),
    ("ws_explain",  "explain what a Python list comprehension is",   180, False),
    ("ws_debug",    "find the bug: def avg(xs): return sum(xs)/len(xs)", 180, False),
]


def fire(prompt: str, force_stop: bool) -> None:
    if force_stop:
        subprocess.call(["adb", "-s", DEVICE, "shell", "am", "force-stop", PKG])
        time.sleep(1.0)
    encoded = quote(prompt).replace("%20", "%s")
    subprocess.call([
        "adb", "-s", DEVICE, "shell", "am", "start",
        "-n", COMPONENT,
        "--ez", "triggerTest", "true",
        "--es", "testPrompt", encoded,
    ])


def ui_dump() -> str:
    subprocess.check_call(
        ["adb", "-s", DEVICE, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15,
    )
    return subprocess.check_output(
        ["adb", "-s", DEVICE, "shell", "cat", "/sdcard/ui.xml"],
        text=True, timeout=15,
    )


def extract_state(xml: str) -> dict:
    status_m = re.search(r'text="(Ready - [^"]+|Working[^"]*)"', xml)
    status = status_m.group(1) if status_m else ""
    # Extract texts
    texts = re.findall(r'text="([^"]+)"', xml)
    # Find assistant text (after the "Assistant" label)
    assistant_text = ""
    try:
        i = texts.index("Assistant")
        # Next text node is the response
        if i + 1 < len(texts):
            assistant_text = texts[i + 1]
    except ValueError:
        pass
    # Detect Preview tab + code lines
    code_lines = 0
    cm = re.match(r"Ready - (\d+) lines", status)
    if cm:
        code_lines = int(cm.group(1))
    return {
        "status": status,
        "code_lines": code_lines,
        "assistant_text": assistant_text,
        "all_texts_count": len(texts),
    }


CANNED_LOADING = "AI model is currently loading"


def main() -> None:
    out = []
    for tag, prompt, wait_s, force_stop in PROMPTS:
        print(f"\n[{tag}] {prompt} (wait<= {wait_s}s, force_stop={force_stop})", flush=True)
        fire(prompt, force_stop)
        start = time.time()
        last_state: dict = {}
        seen_working = False
        while time.time() - start < wait_s:
            time.sleep(5)
            try:
                xml = ui_dump()
                state = extract_state(xml)
                last_state = state
                if "Working" in state.get("status", ""):
                    seen_working = True
                # Done conditions:
                # 1. Template path: status == "Ready - N lines"
                # 2. Real Gemma path: assistant text exists, NOT canned, and len > 30
                # 3. Canned-loading is still streaming → keep waiting unless
                #    we've waited > wait_s (for the warm-up phase).
                txt = state.get("assistant_text", "") or ""
                if state["code_lines"] > 0:
                    break
                if txt and CANNED_LOADING not in txt and len(txt) > 30:
                    break
            except Exception as e:
                print(f"  poll exception: {e}")
        # Take screenshot for record
        screenshot = f"/tmp/ws_{tag}.png"
        subprocess.call(["adb", "-s", DEVICE, "shell", "screencap", "/sdcard/s.png"])
        subprocess.call(["adb", "-s", DEVICE, "pull", "/sdcard/s.png", screenshot],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        elapsed = round(time.time() - start, 1)
        rec = {
            "tag": tag, "prompt": prompt,
            "elapsed_s": elapsed, "screenshot": screenshot,
            **last_state,
        }
        out.append(rec)
        print(f"  -> {elapsed}s status='{last_state.get('status','')}' "
              f"code_lines={last_state.get('code_lines',0)} "
              f"assistant_len={len(last_state.get('assistant_text',''))}", flush=True)
        if last_state.get('assistant_text'):
            print(f"     assistant: {last_state['assistant_text'][:160]!r}")
    Path("workspace_e2e_v2.json").write_text(json.dumps({"results": out}, indent=2))
    print("\nDone.")


if __name__ == "__main__":
    main()
