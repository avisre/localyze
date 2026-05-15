#!/usr/bin/env python3
"""Run 100 India-specific questions across 10 Indian languages on Localyze.

Hardened version with:
  - App-alive check before each question (relaunch if Localyze got killed)
  - Foreground-package guard (refuse to capture if launcher is on top)
  - logcat-based end-of-inference signal
  - Incremental JSONL save

Targets emulator-5556 (started for this test); does not touch 5554.
"""

import json
import re
import subprocess
import threading
import time
from pathlib import Path

import uiautomator2 as u2

ROOT = Path(__file__).resolve().parent
BANK = ROOT / "india100_bank.json"
RESULTS_JSON = ROOT / "india100_results.json"
RESULTS_JSONL = ROOT / "india100_results.jsonl"
LOG = ROOT / "india100.log"

DEVICE = "emulator-5556"
PKG = "com.localyze"
ACTIVITY = f"{PKG}/.MainActivity"

INPUT_XY = (540, 1363)
SEND_XY = (922, 1470)
NEW_CONV_XY = (959, 235)

PER_Q_BUDGET = 660  # match the rebuilt APK's 600s first-token timeout + headroom
SETTLE_AFTER_DONE = 3
APP_LAUNCH_WAIT = 8


def log(msg):
    line = f"[{time.strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)
    with LOG.open("a") as f:
        f.write(line + "\n")


def adb(*args, timeout=15):
    return subprocess.run(
        ["adb", "-s", DEVICE, *args],
        capture_output=True, text=True, timeout=timeout,
    )


def app_pid():
    r = adb("shell", "pidof", PKG)
    return r.stdout.strip()


def foreground_pkg():
    r = adb("shell", "dumpsys", "window")
    m = re.search(r"mCurrentFocus=Window\{[^}]*\s+(\S+)/", r.stdout)
    return m.group(1) if m else ""


def ensure_app(d):
    """Make sure Localyze is running and foregrounded. Relaunch if needed."""
    pid = app_pid()
    fg = foreground_pkg()
    if pid and fg == PKG:
        return False  # already fine
    log(f"  app not foregrounded (pid={pid!r} fg={fg!r}), launching...")
    adb("shell", "am", "start", "-W", "-n", ACTIVITY, timeout=20)
    time.sleep(APP_LAUNCH_WAIT)
    # nudge UI to let model auto-load
    return True


def find_chat_bounds(d):
    """Find current bounds of input field, send button, new-conversation."""
    xml = d.dump_hierarchy()
    def center(label):
        for n in re.findall(r"<node[^/]*/?>", xml):
            if f'"{label}"' in n:
                m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
                if m:
                    x1, y1, x2, y2 = map(int, m.groups())
                    return ((x1 + x2) // 2, (y1 + y2) // 2)
        return None
    return {
        "input": center("Message Localyze.ai...") or INPUT_XY,
        "send": center("Send message") or SEND_XY,
        "new_conv": center("New conversation") or NEW_CONV_XY,
    }


def _wait_for_done(budget):
    proc = subprocess.Popen(
        ["adb", "-s", DEVICE, "logcat", "GemmaInference:D", "*:S"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
    )
    done = {"flag": False, "reason": ""}

    def reader():
        for line in proc.stdout:
            if "onDone callback received" in line:
                done["flag"] = True
                done["reason"] = "onDone"
                return
            if "I wasn't able to generate" in line:
                done["flag"] = True
                done["reason"] = "model_error"
                return

    threading.Thread(target=reader, daemon=True).start()
    deadline = time.time() + budget
    while time.time() < deadline and not done["flag"]:
        time.sleep(1)
    proc.terminate()
    try:
        proc.wait(timeout=3)
    except Exception:
        proc.kill()
    return done["flag"], done["reason"]


def extract_response(d, question):
    xml = d.dump_hierarchy()
    texts = re.findall(r'text="([^"]*)"', xml)
    cleaned = [t for t in texts if t.strip()]
    boilerplate = {
        "Localyze.ai", "On-device", "On-device  Context aware",
        "Chat", "Code", "Library", "Settings",
        "Message Localyze.ai...", "How can I help?",
    }
    cleaned = [t for t in cleaned if t not in boilerplate]
    cleaned = [t for t in cleaned if not re.fullmatch(r"\d+[smhd] ago|just now|\d+:\d+", t)]
    if question in cleaned:
        idx = cleaned.index(question)
        response = cleaned[idx + 1 :]
    else:
        response = cleaned
    return " ".join(response).strip()


def ask(d, question, start_new_conversation=True):
    """Ask one question. Always opens a new conversation by default — reusing a
    conversation across messages crashes liblitertlm_jni.so on Devanagari input
    (verified via tombstone_03, SIGSEGV in memmove during 2nd send)."""
    relaunched = ensure_app(d)
    if relaunched:
        log("  waiting for model auto-init after relaunch")
        time.sleep(15)

    bounds = find_chat_bounds(d)
    if start_new_conversation and not relaunched:
        d.click(*bounds["new_conv"])
        time.sleep(2.5)
        bounds = find_chat_bounds(d)

    adb("logcat", "-c")
    d.click(*bounds["input"])
    time.sleep(1.0)
    d.send_keys(question)
    time.sleep(1.0)
    d.click(*bounds["send"])

    t0 = time.time()
    saw_done, reason = _wait_for_done(PER_Q_BUDGET)
    elapsed = int(time.time() - t0)
    time.sleep(SETTLE_AFTER_DONE)

    fg = foreground_pkg()
    if fg != PKG:
        log(f"  WARNING: foreground is {fg!r}, response may be invalid")
        response = ""
        capture_status = f"app_lost_fg_to_{fg}"
    else:
        response = extract_response(d, question)
        capture_status = reason or ("budget_timeout" if not saw_done else "ok")
    return response, elapsed, saw_done, capture_status


def main():
    bank = json.loads(BANK.read_text())
    d = u2.connect(DEVICE)
    log(f"Connected: {d.info.get('productName')} sdk={d.info.get('sdkInt')}")

    # Ensure app is up at start
    ensure_app(d)
    time.sleep(5)

    if RESULTS_JSONL.exists():
        RESULTS_JSONL.unlink()

    results = []
    qnum = 0
    total = sum(len(v["questions"]) for v in bank["languages"].values())
    log(f"Starting {total} questions across {len(bank['languages'])} languages")

    run_t0 = time.time()
    first_q = True
    for lang_code, lang in bank["languages"].items():
        log(f"=== {lang['name']} ({lang['script']}) ===")
        for i, q in enumerate(lang["questions"], 1):
            qnum += 1
            log(f"[{qnum}/{total}] {lang_code} #{i}: {q}")
            try:
                # always start a new conversation (reusing crashes the JNI lib on Devanagari)
                # — except for the very first Q which inherits the post-launch empty chat
                response, took, done, status = ask(
                    d, q, start_new_conversation=not first_q
                )
                first_q = False
                rec = {
                    "qnum": qnum, "lang": lang_code, "lang_name": lang["name"],
                    "script": lang["script"], "topic_idx": i,
                    "question": q, "response": response,
                    "wait_seconds": took, "saw_done": done,
                    "capture_status": status, "ok": bool(response),
                }
            except Exception as e:
                log(f"  ERROR: {e}")
                rec = {
                    "qnum": qnum, "lang": lang_code, "lang_name": lang["name"],
                    "script": lang["script"], "topic_idx": i,
                    "question": q, "response": "",
                    "wait_seconds": 0, "saw_done": False,
                    "capture_status": "exception", "ok": False,
                    "error": str(e),
                }
            results.append(rec)
            with RESULTS_JSONL.open("a") as f:
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
            log(f"  ({rec['wait_seconds']}s status={rec['capture_status']}) {rec['response'][:160]}")

            elapsed_total = int(time.time() - run_t0)
            avg = elapsed_total / qnum
            remaining = (total - qnum) * avg
            log(f"  --- progress: {qnum}/{total}, avg {avg:.0f}s/Q, ETA {remaining/60:.0f} min ---")

    RESULTS_JSON.write_text(json.dumps(results, ensure_ascii=False, indent=2))
    log(f"DONE. {len(results)} responses saved to {RESULTS_JSON}")


if __name__ == "__main__":
    main()
