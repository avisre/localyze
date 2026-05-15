#!/usr/bin/env python3
"""Verify the empty-response recovery fix on Q2 (code) and Q5 (synthesis).

Both previously failed silently (user bubble, no answer pane). With the fix:
- If model emits 0 text tokens, retry once with thinking off.
- If still empty, save a polite fallback bubble.
Either way the user sees SOMETHING — the bug is silent emptiness.
"""
import json, re, subprocess, time
from pathlib import Path
from urllib.parse import quote

DEVICE = "a5523839"
PKG = "com.localyze"
COMPONENT = "com.localyze/.MainActivity"

COLD_START_WAIT_S = 120
GENERATION_MAX_S = 600
STABLE_TEXT_MS = 25000  # longer than v1 to avoid cutting mid-stream

QUESTIONS = [
    ("Q2_CODE_EDGES",
     "Write a Python function that returns the second-largest unique element in a list, or None if there isn't one. Handle empty list, single element, and all-duplicates cases."),
    ("Q5_SYNTHESIS",
     "Compare the impact of remote work on commercial real estate values in Tokyo, San Francisco, and Bangalore since 2020."),
]

CHROME = {
    "Localyze.ai", "Localyze....", "On-device  Context aware",
    "Chat", "Code", "Library", "Settings",
    "Message Localyze.ai...", "just now", "Web search complete", "-",
}


def fire(prompt: str) -> None:
    subprocess.call(["adb", "-s", DEVICE, "shell", "am", "force-stop", PKG])
    time.sleep(2)
    subprocess.call(["adb", "-s", DEVICE, "logcat", "-c"])
    time.sleep(0.5)
    encoded = quote(prompt).replace("%20", "%s")
    subprocess.call([
        "adb", "-s", DEVICE, "shell", "am", "start",
        "-n", COMPONENT, "--es", "chat_msg", encoded,
    ])


def logcat_dump() -> str:
    try:
        return subprocess.check_output(
            ["adb", "-s", DEVICE, "logcat", "-d",
             "GemmaInference:V", "ChatViewModel:V", "SendMessageUseCase:V", "MainActivity:V", "*:S"],
            text=True, timeout=15,
        )
    except Exception:
        return ""


def ui_texts() -> list[str]:
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


def wait_until_done(prompt: str) -> tuple[str, str, float, dict]:
    t0 = time.time()
    last_text = ""
    last_change = t0
    started = False
    info = {"first_token_s": None, "completed_log": False, "recovery_log": False}

    while True:
        elapsed = time.time() - t0
        if not started and elapsed > COLD_START_WAIT_S:
            return last_text, "EMPTY_NO_START", round(elapsed, 1), info
        if elapsed > GENERATION_MAX_S:
            return last_text, "TIMEOUT", round(elapsed, 1), info

        time.sleep(3.0)

        if started:
            dump = logcat_dump()
            if "Empty response after tool loop" in dump:
                info["recovery_log"] = True
            if "Streaming completed" in dump or "fullText length=" in dump or "onDone callback received" in dump:
                info["completed_log"] = True
                time.sleep(4.0)
                texts = ui_texts()
                cur = extract(texts, prompt)
                return cur, ("ANSWERED" if len(cur) >= 20 else "THIN" if cur else "EMPTY"), \
                       round(time.time() - t0, 1), info

        texts = ui_texts()
        cur = extract(texts, prompt)
        if cur and not started:
            started = True
            info["first_token_s"] = round(elapsed, 1)
        if cur != last_text:
            last_text = cur
            last_change = time.time()

        if started and (time.time() - last_change) * 1000 >= STABLE_TEXT_MS:
            return cur, ("ANSWERED" if len(cur) >= 20 else "THIN" if cur else "EMPTY"), \
                   round(time.time() - t0, 1), info


def main():
    out = []
    for i, (tag, q) in enumerate(QUESTIONS):
        print(f"\n[{i+1}/{len(QUESTIONS)} {tag}]\n  Q: {q[:90]}...", flush=True)
        fire(q)
        answer, verdict, elapsed, info = wait_until_done(q)
        shot = f"/tmp/verify_{tag}.png"
        subprocess.call(["adb", "-s", DEVICE, "shell", "screencap", "/sdcard/s.png"])
        subprocess.call(["adb", "-s", DEVICE, "pull", "/sdcard/s.png", shot],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        rec = {"tag": tag, "prompt": q, "answer": answer, "verdict": verdict,
               "elapsed_s": elapsed, "info": info, "screenshot": shot}
        out.append(rec)
        print(f"  -> {verdict} in {elapsed}s, {len(answer)} chars  info={info}", flush=True)
        if answer:
            print(f"     {answer[:400]!r}")
        Path("verify_q2q5.json").write_text(json.dumps({"results": out}, indent=2))


if __name__ == "__main__":
    main()
