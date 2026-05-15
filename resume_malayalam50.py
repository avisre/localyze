#!/usr/bin/env python3
"""Complete the Malayalam-50 run: re-roll failed Qs and finish unrun Qs.

Hardened ensure_app(): verifies BOTH that com.localyze is the running PID AND
that the chat input is visible. If the chat UI isn't there (e.g. app drifted
to home, splash still up, or model still loading), force-stops and relaunches,
then polls for the chat UI before giving up.
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
CHAT_UI_TIMEOUT = 60  # max wait for chat input after relaunch


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


def chat_ready(d):
    """True if the chat input and send button are both visible."""
    b = find_chat_bounds(d)
    return b["input"] is not None and b["send"] is not None


def wait_chat_ready(d, timeout=CHAT_UI_TIMEOUT):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if chat_ready(d):
            return True
        time.sleep(2)
    return False


def hard_relaunch(d):
    """Force-stop the app and re-launch from scratch, then wait for chat UI."""
    log("  hard relaunch: force-stop + start")
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)
    adb("shell", "am", "start", "-W", "-n", ACTIVITY, timeout=20)
    if not wait_chat_ready(d, timeout=CHAT_UI_TIMEOUT):
        log("  WARNING: chat UI still not visible after hard relaunch")
        return False
    return True


def ensure_app_ready(d):
    """Make sure app is running, foregrounded, AND showing chat input."""
    pid = adb("shell", "pidof", PKG).stdout.strip()
    if not pid:
        log("  app dead, relaunching")
        return hard_relaunch(d)
    if not chat_ready(d):
        log("  PID alive but chat UI missing — hard relaunch")
        return hard_relaunch(d)
    return True


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
    # also bail early if app dies mid-inference
    deadline = time.time() + budget
    while time.time() < deadline and not done["flag"]:
        time.sleep(2)
        # quick pid check every 2s
        pid = adb("shell", "pidof", PKG).stdout.strip()
        if not pid:
            done["flag"] = True
            done["dead"] = True
            break
    proc.terminate()
    try: proc.wait(timeout=3)
    except Exception: proc.kill()
    return done["flag"], done.get("dead", False)


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


def ask(d, question):
    if not ensure_app_ready(d):
        return "", 0, False, "chat_ui_unavailable"
    bounds = find_chat_bounds(d)
    # always new conversation
    if bounds["new_conv"]:
        d.click(*bounds["new_conv"])
        time.sleep(3)
    if not ensure_app_ready(d):
        return "", 0, False, "chat_ui_lost_after_newconv"
    bounds = find_chat_bounds(d)

    adb("logcat", "-c")
    d.click(*bounds["input"])
    time.sleep(1.2)
    d.send_keys(question)
    time.sleep(1.2)
    d.click(*bounds["send"])

    t0 = time.time()
    saw_done, app_died = wait_for_done(PER_Q_BUDGET)
    elapsed = int(time.time() - t0)
    time.sleep(SETTLE_AFTER_DONE)

    if app_died:
        return "", elapsed, saw_done, "app_died_mid_inference"
    if not chat_ready(d):
        return "", elapsed, saw_done, "chat_ui_lost_post_inference"
    return extract_response(d, question), elapsed, saw_done, "ok"


def main():
    bank = json.loads(BANK.read_text())
    # flatten questions in order with metadata
    plan = []
    qnum = 0
    for bucket in bank["buckets"]:
        for i, q in enumerate(bucket["questions"], 1):
            qnum += 1
            plan.append({"qnum": qnum, "bucket": bucket["name"],
                         "topic_idx": i, "question": q})
    total = len(plan)

    # Load existing results — keep successful ones, replace failed
    existing = {}
    if RESULTS_JSON.exists():
        for r in json.loads(RESULTS_JSON.read_text()):
            existing[r["qnum"]] = r
    elif RESULTS_JSONL.exists():
        for line in RESULTS_JSONL.read_text().splitlines():
            if line.strip():
                r = json.loads(line)
                existing[r["qnum"]] = r

    # to_run = anything missing or failed (no response or status != ok)
    def is_failed(r):
        return (not r.get("ok") or not r.get("response", "").strip()
                or r.get("capture_status") not in ("ok", "rerun_attempt_1",
                                                   "rerun_attempt_2", "rerun_attempt_3"))

    to_run = []
    for p in plan:
        ex = existing.get(p["qnum"])
        if ex is None or is_failed(ex):
            to_run.append(p)

    log(f"resume_malayalam50: {len(to_run)} of {total} questions need (re-)running")
    log(f"  failed/missing qnums: {[p['qnum'] for p in to_run]}")

    d = u2.connect(DEVICE)
    log(f"Connected: {d.info.get('productName')}")

    # ensure clean app state at start
    hard_relaunch(d)

    run_t0 = time.time()
    for i, p in enumerate(to_run, 1):
        q = p["question"]
        log(f"[{i}/{len(to_run)}] Q{p['qnum']} {p['bucket']}: {q}")
        try:
            response, took, done, status = ask(d, q)
        except Exception as e:
            log(f"  ERROR: {e}")
            response, took, done, status = "", 0, False, "exception"

        rec = {**p, "response": response, "wait_seconds": took,
               "saw_done": done, "capture_status": status,
               "ok": bool(response)}
        if "error" in (existing.get(p["qnum"]) or {}):
            rec["prior_error"] = existing[p["qnum"]].get("error")
        existing[p["qnum"]] = rec

        # persist incrementally
        ordered = [existing[k] for k in sorted(existing.keys())]
        RESULTS_JSON.write_text(json.dumps(ordered, ensure_ascii=False, indent=2))
        log(f"  ({took}s status={status}) {response[:200]}")

        elapsed = int(time.time() - run_t0)
        avg = elapsed / i
        rem = (len(to_run) - i) * avg
        log(f"  --- resume progress: {i}/{len(to_run)}, avg {avg:.0f}s/Q, ETA {rem/60:.0f} min ---")

    # final save
    ordered = [existing[k] for k in sorted(existing.keys())]
    RESULTS_JSON.write_text(json.dumps(ordered, ensure_ascii=False, indent=2))
    log(f"DONE. {len(ordered)} total results in {RESULTS_JSON}")


if __name__ == "__main__":
    main()
