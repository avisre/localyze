#!/usr/bin/env python3
"""Ask 50 Malayalam questions across 8 topic buckets on Localyze.

Same hardened pattern as run_india100.py: ADBKeyboard for Unicode input,
new conversation per question (multi-turn JNI crashes on Indic input),
logcat onDone signal to detect inference end, incremental JSONL save.
"""

import json
import re
import subprocess
import threading
import time
from pathlib import Path

import uiautomator2 as u2

ROOT = Path(__file__).resolve().parent
BANK = ROOT / "malayalam50_bank.json"
RESULTS_JSON = ROOT / "malayalam50_results.json"
RESULTS_JSONL = ROOT / "malayalam50_results.jsonl"
LOG = ROOT / "malayalam50.log"

DEVICE = "emulator-5556"
PKG = "com.localyze"
ACTIVITY = f"{PKG}/.MainActivity"

PER_Q_BUDGET = 660
SETTLE_AFTER_DONE = 6


def log(msg):
    line = f"[{time.strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)
    with LOG.open("a") as f:
        f.write(line + "\n")


def adb(*args, timeout=15):
    return subprocess.run(["adb", "-s", DEVICE, *args],
                          capture_output=True, text=True, timeout=timeout)


def find_chat_bounds(d):
    xml = d.dump_hierarchy()
    def center(label):
        for n in re.findall(r"<node[^/]*/?>", xml):
            if f'"{label}"' in n:
                m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
                if m:
                    x1, y1, x2, y2 = map(int, m.groups())
                    return ((x1 + x2) // 2, (y1 + y2) // 2)
        return None
    return {"input": center("Message Localyze.ai..."),
            "send": center("Send message"),
            "new_conv": center("New conversation")}


def ensure_app(d):
    pid = adb("shell", "pidof", PKG).stdout.strip()
    if not pid:
        log("  relaunching app...")
        adb("shell", "am", "start", "-W", "-n", ACTIVITY, timeout=20)
        time.sleep(15)
        return True
    return False


def wait_for_done(budget):
    proc = subprocess.Popen(
        ["adb", "-s", DEVICE, "logcat", "GemmaInference:D", "*:S"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
    )
    done = {"flag": False}

    def reader():
        for line in proc.stdout:
            if "onDone callback received" in line or "I wasn't able to generate" in line:
                done["flag"] = True
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
    return done["flag"]


def extract_response(d, question):
    xml = d.dump_hierarchy()
    texts = re.findall(r'text="([^"]*)"', xml)
    cleaned = [t for t in texts if t.strip()]
    boilerplate = {
        "Localyze.ai", "On-device", "On-device  Context aware",
        "Chat", "Code", "Library", "Settings",
        "Message Localyze.ai...", "How can I help?",
        "Reading your message", "The answer will appear below",
    }
    cleaned = [t for t in cleaned if t not in boilerplate]
    cleaned = [t for t in cleaned if not re.fullmatch(r"\d+[smhd] ago|just now|\d+:\d+", t)]
    if question in cleaned:
        idx = cleaned.index(question)
        response = cleaned[idx + 1:]
    else:
        response = cleaned
    return " ".join(response).strip()


def ask(d, question, is_first):
    relaunched = ensure_app(d)
    if relaunched:
        time.sleep(15)
    bounds = find_chat_bounds(d)
    if not is_first and not relaunched:
        d.click(*bounds["new_conv"])
        time.sleep(3)
        bounds = find_chat_bounds(d)

    adb("logcat", "-c")
    d.click(*bounds["input"])
    time.sleep(1.2)
    d.send_keys(question)
    time.sleep(1.2)
    d.click(*bounds["send"])

    t0 = time.time()
    saw_done = wait_for_done(PER_Q_BUDGET)
    elapsed = int(time.time() - t0)
    time.sleep(SETTLE_AFTER_DONE)

    fg = adb("shell", "dumpsys", "window").stdout
    m = re.search(r"mCurrentFocus=Window\{[^}]*\s+(\S+)/", fg)
    fg_pkg = m.group(1) if m else ""
    if fg_pkg != PKG:
        return "", elapsed, saw_done, f"app_lost_fg_to_{fg_pkg}"
    return extract_response(d, question), elapsed, saw_done, "ok"


def main():
    bank = json.loads(BANK.read_text())
    d = u2.connect(DEVICE)
    log(f"Connected: {d.info.get('productName')} sdk={d.info.get('sdkInt')}")

    if RESULTS_JSONL.exists():
        RESULTS_JSONL.unlink()

    ensure_app(d)
    time.sleep(3)

    results = []
    qnum = 0
    total = sum(len(bk["questions"]) for bk in bank["buckets"])
    log(f"Starting {total} Malayalam questions across {len(bank['buckets'])} buckets")

    run_t0 = time.time()
    is_first = True
    for bucket in bank["buckets"]:
        log(f"=== {bucket['name']} ({len(bucket['questions'])} Qs) ===")
        for i, q in enumerate(bucket["questions"], 1):
            qnum += 1
            log(f"[{qnum}/{total}] {bucket['name']} #{i}: {q}")
            try:
                response, took, done, status = ask(d, q, is_first)
                is_first = False
                rec = {
                    "qnum": qnum, "bucket": bucket["name"], "topic_idx": i,
                    "question": q, "response": response,
                    "wait_seconds": took, "saw_done": done,
                    "capture_status": status, "ok": bool(response),
                }
            except Exception as e:
                log(f"  ERROR: {e}")
                rec = {
                    "qnum": qnum, "bucket": bucket["name"], "topic_idx": i,
                    "question": q, "response": "",
                    "wait_seconds": 0, "saw_done": False,
                    "capture_status": "exception", "ok": False, "error": str(e),
                }
            results.append(rec)
            with RESULTS_JSONL.open("a") as f:
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
            log(f"  ({rec['wait_seconds']}s status={rec['capture_status']}) {rec['response'][:200]}")

            elapsed_total = int(time.time() - run_t0)
            avg = elapsed_total / qnum
            remaining = (total - qnum) * avg
            log(f"  --- progress: {qnum}/{total}, avg {avg:.0f}s/Q, ETA {remaining/60:.0f} min ---")

    RESULTS_JSON.write_text(json.dumps(results, ensure_ascii=False, indent=2))
    log(f"DONE. {len(results)} responses saved to {RESULTS_JSON}")


if __name__ == "__main__":
    main()
