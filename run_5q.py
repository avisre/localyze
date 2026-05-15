#!/usr/bin/env python3
"""Quick 5-question evaluation."""
import json, re, subprocess, time
from pathlib import Path
from urllib.parse import quote

DEVICE = "a5523839"
PKG = "com.localyze"
COMPONENT = "com.localyze/.MainActivity"
MAX_WAIT_S = 120
POLL_INTERVAL_S = 5
STABLE_POLLS = 2

QUESTIONS = [
    ("Q1_GAMING_PC",  "best gaming pc",
     "vague-opener (best X pattern) — expects clarify w/ budget/use/region"),
    ("Q2_NEGOTIATE",  "how to negotiate a higher salary",
     "how-to catch-all — expects clarify w/ starting point/time/format"),
    ("Q3_MOUNTAIN",   "what is the highest mountain in the world",
     "concrete factual — expects direct answer ('Mount Everest')"),
    ("Q4_PYRUST",     "is Python better than Rust for AI",
     "is-X-better-than pattern — expects clarify w/ situation/matters/region"),
    ("Q5_INVEST",     "I'm 28, single, $80K salary, want to start investing for retirement. What should I do first?",
     "long+specific — expects pass-through, real model answer"),
]

CHROME = {
    "Localyze.ai", "Localyze....", "On-device  Context aware",
    "Chat", "Code", "Library", "Settings",
    "Message Localyze.ai...", "just now", "Web search complete", "-",
}


def fire(prompt: str, force_stop: bool) -> None:
    if force_stop:
        subprocess.call(["adb", "-s", DEVICE, "shell", "am", "force-stop", PKG])
        time.sleep(1)
    encoded = quote(prompt).replace("%20", "%s")
    subprocess.call([
        "adb", "-s", DEVICE, "shell", "am", "start",
        "-n", COMPONENT, "--es", "chat_msg", encoded,
    ])


def ui_texts():
    try:
        subprocess.check_call(
            ["adb", "-s", DEVICE, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15,
        )
        out = subprocess.check_output(
            ["adb", "-s", DEVICE, "shell", "cat", "/sdcard/ui.xml"],
            text=True, timeout=15,
        )
    except Exception:
        return []
    return re.findall(r'text="([^"]+)"', out)


def extract(texts, prompt):
    out_lines, seen = [], False
    for t in texts:
        if t == prompt:
            seen = True; continue
        if not seen: continue
        if t in CHROME: continue
        out_lines.append(t)
    return "\n".join(out_lines).strip()


def main():
    out = []
    for i, (tag, q, expected) in enumerate(QUESTIONS):
        print(f"\n[{i+1}/5 {tag}] {q!r}")
        print(f"  expected: {expected}")
        fire(q, force_stop=(i == 0))
        start = time.time()
        last, stable = "", 0
        while time.time() - start < MAX_WAIT_S:
            time.sleep(POLL_INTERVAL_S)
            cur = extract(ui_texts(), q)
            if cur and cur == last:
                stable += 1
                if stable >= STABLE_POLLS: break
            else:
                stable, last = 0, cur
        elapsed = round(time.time() - start, 1)
        is_clarify = ("Quick question first" in last or "go ahead" in last)
        verdict = (
            "CLARIFIED" if is_clarify else
            "ANSWERED" if last and len(last) >= 20 else
            "THIN" if last else
            "EMPTY"
        )
        shot = f"/tmp/q5_{tag}.png"
        subprocess.call(["adb", "-s", DEVICE, "shell", "screencap", "/sdcard/s.png"])
        subprocess.call(["adb", "-s", DEVICE, "pull", "/sdcard/s.png", shot],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        rec = {"tag": tag, "prompt": q, "expected": expected,
               "answer": last, "verdict": verdict, "elapsed_s": elapsed,
               "screenshot": shot}
        out.append(rec)
        print(f"  -> {verdict} in {elapsed}s, {len(last)} chars")
        print(f"     {last[:200]!r}")
        Path("eval5.json").write_text(json.dumps({"results": out}, indent=2))


if __name__ == "__main__":
    main()
