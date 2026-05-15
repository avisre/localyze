#!/usr/bin/env python3
"""5 tough questions to probe model limits."""
import json, re, subprocess, time
from pathlib import Path
from urllib.parse import quote

DEVICE = "a5523839"
PKG = "com.localyze"
COMPONENT = "com.localyze/.MainActivity"
MAX_WAIT_S = 180
POLL_INTERVAL_S = 5
STABLE_POLLS = 2

QUESTIONS = [
    ("Q1_TRICK_LOGIC",
     "If 5 cats catch 5 mice in 5 minutes, how many cats are needed to catch 100 mice in 100 minutes?",
     "trick logic — correct answer is 5 cats, not 100"),
    ("Q2_CODE_EDGES",
     "Write a Python function that returns the second-largest unique element in a list, or None if there isn't one. Handle empty list, single element, and all-duplicates cases.",
     "code with edge cases"),
    ("Q3_ETHICS",
     "I found someone's wallet with $500 cash and an ID showing an address across town. Walk me through the ethics and practical steps.",
     "ethics + practical — should give nuanced advice"),
    ("Q4_FACTUAL",
     "Was the Treaty of Versailles signed in 1918, 1919, or 1920? And which countries were the main signatories?",
     "factual — correct is 1919, signatories Allies vs Germany"),
    ("Q5_SYNTHESIS",
     "Compare the impact of remote work on commercial real estate values in Tokyo, San Francisco, and Bangalore since 2020.",
     "cross-domain synthesis"),
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
        print(f"\n[{i+1}/5 {tag}] {q[:80]!r}...")
        print(f"  expected: {expected}")
        fire(q, force_stop=True)
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
        shot = f"/tmp/tough_{tag}.png"
        subprocess.call(["adb", "-s", DEVICE, "shell", "screencap", "/sdcard/s.png"])
        subprocess.call(["adb", "-s", DEVICE, "pull", "/sdcard/s.png", shot],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        rec = {"tag": tag, "prompt": q, "expected": expected,
               "answer": last, "verdict": verdict, "elapsed_s": elapsed,
               "screenshot": shot}
        out.append(rec)
        print(f"  -> {verdict} in {elapsed}s, {len(last)} chars")
        print(f"     {last[:240]!r}")
        Path("tough5.json").write_text(json.dumps({"results": out}, indent=2))


if __name__ == "__main__":
    main()
