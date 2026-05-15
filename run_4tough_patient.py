#!/usr/bin/env python3
"""Patient harness: send Q, wait for Gemma to FINISH, then move on.

Uses logcat events for completion detection so a slow Gemma run doesn't
get cut off prematurely. Hard cap is 10 min per question.
"""
import json, re, subprocess, time
from pathlib import Path
from urllib.parse import quote

DEVICE = "a5523839"
PKG = "com.localyze"
COMPONENT = "com.localyze/.MainActivity"

# Generous caps: a real user might wait 5-10 min for a complex answer.
COLD_START_WAIT_S = 90       # max time to first token
GENERATION_MAX_S = 600       # hard cap once streaming starts
STABLE_TEXT_MS = 15000       # require 15s of unchanging text to declare done

QUESTIONS = [
    ("Q2_CODE_EDGES",
     "Write a Python function that returns the second-largest unique element in a list, or None if there isn't one. Handle empty list, single element, and all-duplicates cases.",
     "code w/ edge cases"),
    ("Q3_ETHICS",
     "I found someone's wallet with $500 cash and an ID showing an address across town. Walk me through the ethics and practical steps.",
     "ethics + practical"),
    ("Q5_SYNTHESIS",
     "Compare the impact of remote work on commercial real estate values in Tokyo, San Francisco, and Bangalore since 2020.",
     "cross-domain synthesis"),
    ("Q1_TRICK_LOGIC_RETRY",
     "If 5 cats catch 5 mice in 5 minutes, how many cats are needed to catch 100 mice in 100 minutes?",
     "trick logic — should reason out 5 cats"),
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
             "GemmaInference:V", "ChatViewModel:V", "CodeWorkspace:V", "MainActivity:V", "*:S"],
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
    """Wait for a Gemma generation to finish.

    Phase 1: wait up to COLD_START_WAIT_S for the FIRST sign of streaming
             (UI text appears OR logcat shows token).
    Phase 2: once streaming, poll UI until text is stable for STABLE_TEXT_MS
             OR a `Streaming completed` log appears OR GENERATION_MAX_S elapses.
    """
    t0 = time.time()
    last_text = ""
    last_change = t0
    started = False
    info = {"phase1_s": None, "first_token_s": None, "completed_log": False}

    while True:
        elapsed = time.time() - t0

        # Phase 1: cold-start budget
        if not started and elapsed > COLD_START_WAIT_S:
            return last_text, "EMPTY_NO_START", round(elapsed, 1), info
        # Phase 2: hard cap
        if elapsed > GENERATION_MAX_S:
            return last_text, "TIMEOUT", round(elapsed, 1), info

        time.sleep(3.0)

        # Watch for completion via logcat
        if started:
            dump = logcat_dump()
            if "Streaming completed" in dump or "fullText length=" in dump:
                info["completed_log"] = True
                # Drain a couple more polls so trailing tokens land in UI
                time.sleep(3.0)
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

        # Phase 2 stable-text completion: nothing changed for STABLE_TEXT_MS
        if started and (time.time() - last_change) * 1000 >= STABLE_TEXT_MS:
            return cur, ("ANSWERED" if len(cur) >= 20 else "THIN" if cur else "EMPTY"), \
                   round(time.time() - t0, 1), info


def main():
    out = []
    for i, (tag, q, expected) in enumerate(QUESTIONS):
        print(f"\n[{i+1}/4 {tag}]\n  Q: {q[:90]}...\n  expected: {expected}", flush=True)
        fire(q)
        answer, verdict, elapsed, info = wait_until_done(q)
        is_clarify = ("Quick question first" in answer or "go ahead" in answer)
        if is_clarify:
            verdict = "CLARIFIED"
        shot = f"/tmp/patient_{tag}.png"
        subprocess.call(["adb", "-s", DEVICE, "shell", "screencap", "/sdcard/s.png"])
        subprocess.call(["adb", "-s", DEVICE, "pull", "/sdcard/s.png", shot],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        rec = {"tag": tag, "prompt": q, "expected": expected,
               "answer": answer, "verdict": verdict, "elapsed_s": elapsed,
               "info": info, "screenshot": shot}
        out.append(rec)
        print(f"  -> {verdict} in {elapsed}s, {len(answer)} chars  info={info}", flush=True)
        if answer:
            print(f"     {answer[:300]!r}")
        Path("patient4.json").write_text(json.dumps({"results": out}, indent=2))


if __name__ == "__main__":
    main()
