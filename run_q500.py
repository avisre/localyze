#!/usr/bin/env python3
"""Drive the 500-question quality eval on the emulator.

For each question, fire two runs: web-search ON and web-search OFF.
Save Q/A pairs to JSON progressively (resumable). Output gets fed to
the LLM-judge agents downstream.

Resume: re-running picks up where it left off based on which (id, mode)
pairs are already in the results file."""
from __future__ import annotations
import json, re, subprocess, time, sys, signal
from pathlib import Path
from urllib.parse import quote

sys.path.insert(0, str(Path(__file__).parent))
from run_general15b import ui_texts, is_generating, CHROME, _norm

DEVICE = "emulator-5554"
PKG = "com.localyze"
COMPONENT = f"{PKG}/.MainActivity"

# Safety cap only — wait as long as the model needs. Most questions finish
# in 30-150s on CPU. Some long-form answers can run 5-8 min. Only abort if
# something is genuinely stuck.
MAX_WAIT_S = 900

BANK_PATH = Path("q500_bank.json")
RESULTS_PATH = Path("q500_results.json")


APK_PATH = "/home/hardoker77/Downloads/new/localyze-main (Copy)/app/build/outputs/apk/debug/app-debug.apk"
MODEL_HOST_PATH = "/tmp/localyze_model/gemma-4-E4B-it.litertlm"
MODEL_DEVICE_PATH = "files/models/gemma-4-E4B-it.litertlm"


def app_healthy() -> bool:
    """Heuristic check that the app's MainActivity is launchable."""
    try:
        out = subprocess.check_output(
            ["adb", "-s", DEVICE, "shell", "cmd", "package",
             "resolve-activity", "--brief", PKG],
            text=True, timeout=10
        )
        return "/.MainActivity" in out or "/MainActivity" in out
    except Exception:
        return False


def reinstall_app() -> None:
    """Recover from a broken package: uninstall, reinstall, re-push the model.
    Two-stage push: stage to /data/local/tmp, then cp into app's private dir
    via run-as (mkdir -p first since fresh install has no files/models/)."""
    print(">>> App broken; uninstalling + reinstalling + re-pushing model")
    subprocess.call(["adb", "-s", DEVICE, "uninstall", PKG])
    subprocess.call(["adb", "-s", DEVICE, "install", "-r", APK_PATH])
    # Stage to /data/local/tmp (shell-writable)
    subprocess.call(["adb", "-s", DEVICE, "shell", "mkdir", "-p", "/data/local/tmp/m"])
    subprocess.call(["adb", "-s", DEVICE, "push", MODEL_HOST_PATH,
                     "/data/local/tmp/m/g.lm"], timeout=300)
    # Then cp into app's private dir, ensuring files/models exists first
    subprocess.call([
        "adb", "-s", DEVICE, "shell",
        f"run-as {PKG} sh -c 'mkdir -p files/models && cp /data/local/tmp/m/g.lm {MODEL_DEVICE_PATH}'"
    ], timeout=120)
    subprocess.call(["adb", "-s", DEVICE, "shell", "rm", "/data/local/tmp/m/g.lm"])
    # Sanity: verify the model file is in place
    verify = subprocess.run(
        ["adb", "-s", DEVICE, "shell", f"run-as {PKG} ls -la {MODEL_DEVICE_PATH}"],
        capture_output=True, text=True, timeout=15
    )
    print(f">>> Reinstall complete; model: {(verify.stdout or verify.stderr).strip()[:120]}")


def fire(prompt: str, allow_web_search: bool, _retry: int = 0) -> bool:
    """Force-stop app and fire a fresh question with the requested mode.
    If the activity launch fails (broken package), reinstall and retry once.
    Returns True if the intent was actually delivered."""
    subprocess.call(["adb", "-s", DEVICE, "shell", "am", "force-stop", PKG])
    time.sleep(1)
    encoded = quote(prompt).replace("%20", "%s")
    result = subprocess.run([
        "adb", "-s", DEVICE, "shell", "am", "start",
        "-n", COMPONENT,
        "--es", "chat_msg", encoded,
        "--ez", "force_cpu", "true",
        "--ez", "allow_web_search", "true" if allow_web_search else "false",
    ], capture_output=True, text=True, timeout=15)
    out = (result.stdout or "") + (result.stderr or "")
    if "does not exist" in out and _retry < 1:
        reinstall_app()
        return fire(prompt, allow_web_search, _retry + 1)
    return "does not exist" not in out


def check_completed_in_log() -> bool:
    try:
        log = subprocess.check_output(
            ["adb", "-s", DEVICE, "logcat", "-d", "ChatViewModel:V", "*:S"],
            text=True, timeout=8
        )
    except Exception:
        return False
    return "handleResponseEvent: Completed" in log.split("doSendMessage")[-1]


def extract_answer(prompt: str) -> str:
    """Pull the assistant text after the prompt anchor; fall back to all
    non-chrome text if the prompt scrolled off."""
    target = _norm(prompt)
    seen: list[str] = []
    seen_set: set[str] = set()
    # Scroll up a couple times so the title/summary is in view
    for _ in range(2):
        subprocess.call(["adb", "-s", DEVICE, "shell", "input", "swipe",
                        "540", "600", "540", "1900", "350"])
        time.sleep(0.4)
    # Accumulate texts across a couple of scroll positions
    for _ in range(3):
        for t in ui_texts():
            if t not in seen_set and t not in CHROME and "Generating" not in t:
                seen_set.add(t)
                seen.append(t)
        subprocess.call(["adb", "-s", DEVICE, "shell", "input", "swipe",
                        "540", "1800", "540", "600", "350"])
        time.sleep(0.4)
    # If we can find the prompt anchor, take everything after it
    out_lines: list[str] = []
    after_prompt = False
    found = any(_norm(t) == target for t in seen)
    for t in seen:
        if found and not after_prompt:
            if _norm(t) == target:
                after_prompt = True
            continue
        # Stop at the input bar
        if t == "Message Localyze.ai...":
            break
        # Skip a stray prompt match in fallback mode
        if not found and _norm(t) == target:
            continue
        out_lines.append(t)
    return "\n".join(out_lines).strip()


def load_bank() -> list[dict]:
    with open(BANK_PATH) as f:
        data = json.load(f)
    return data if isinstance(data, list) else data["questions"]


def load_results() -> dict[str, dict]:
    """Map (id, mode) -> record so resume can skip done runs."""
    if not RESULTS_PATH.exists():
        return {}
    with open(RESULTS_PATH) as f:
        return json.load(f).get("by_id_mode", {})


def save_results(by_id_mode: dict[str, dict]) -> None:
    RESULTS_PATH.write_text(json.dumps({"by_id_mode": by_id_mode}, indent=2))


def main() -> None:
    bank = load_bank()
    print(f"Loaded {len(bank)} questions from {BANK_PATH}")
    by_id_mode = load_results()
    print(f"Resume: {len(by_id_mode)} runs already saved")

    # Order: alternate modes by question so a fresh-eye reviewer can see
    # online/offline side-by-side as the test progresses. Within each mode
    # we go in question order.
    total = len(bank) * 2
    done = 0
    # Pass 1: web ON for everything, then Pass 2: web OFF for everything.
    for mode_label, allow_web in [("online", True), ("offline", False)]:
        for q in bank:
            qid = q["id"]
            key = f"{qid}_{mode_label}"
            done_count = sum(1 for k in by_id_mode if k.endswith("_online") or k.endswith("_offline"))
            if key in by_id_mode:
                continue
            t0 = time.time()
            # Clear logcat so we only see this question's events.
            subprocess.call(["adb", "-s", DEVICE, "logcat", "-c"])
            time.sleep(0.5)
            delivered = fire(q["prompt"], allow_web)
            if not delivered:
                print(f"   !! intent not delivered for {qid} {mode_label}; skipping", flush=True)
                continue
            # Wait UNTIL the model finishes. Poll logcat for the Completed
            # event for THIS doSendMessage. No artificial cutoff — we wait
            # as long as inference needs, capped only at MAX_WAIT_S for
            # safety in case the engine wedges.
            completed = False
            while time.time() - t0 < MAX_WAIT_S:
                time.sleep(3.0)
                try:
                    log = subprocess.check_output(
                        ["adb", "-s", DEVICE, "logcat", "-d",
                         "ChatViewModel:V", "*:S"],
                        text=True, timeout=8
                    )
                except Exception:
                    continue
                # Look only at log lines after THIS doSendMessage. The
                # `-c` clear above means this should normally be from
                # scratch, but be defensive.
                if "doSendMessage" in log and "handleResponseEvent: Completed" in log.split("doSendMessage")[-1]:
                    completed = True
                    break
            time.sleep(2.0)  # let UI finalize render
            answer = extract_answer(q["prompt"])
            # Reject results that look like Android system UI captures.
            if any(marker in answer for marker in ("Display brightness", "Android System\n",
                                                    "Internet, AndroidWifi", "Serial console enabled")):
                print(f"   !! captured system UI for {qid}; reinstalling app", flush=True)
                reinstall_app()
                continue
            # Reject the app's "engine errored" fallback message. We want REAL
            # model output. If GPU shader compile or kernel dispatch failed,
            # let the engine recover on the next fire (rather than poisoning
            # results with the same fallback string for every question).
            fallback_markers = ("I wasn't able to generate a response",
                                "I wasn", "couldn't generate",
                                "Try rephrasing the question")
            if any(m in answer for m in fallback_markers) or not answer.strip():
                print(f"   !! engine fallback for {qid} ({len(answer)}c); skipping save & continuing", flush=True)
                # Give the engine a breather and force a fresh load next fire.
                time.sleep(5.0)
                continue
            elapsed = round(time.time() - t0, 1)
            # Forbidden keywords let regression tests assert the answer
            # never contains JSON keys, internal channel markers, etc.
            forbidden = q.get("forbidden_keywords", [])
            forbidden_hit = [k for k in forbidden if k in answer]
            rec = {
                "id": qid,
                "mode": mode_label,
                "prompt": q["prompt"],
                "category": q["category"],
                "expected_keywords": q.get("expected_keywords", []),
                "forbidden_keywords": forbidden,
                "forbidden_hit": forbidden_hit,
                "web_dependent": q.get("web_dependent", False),
                "deterministic_path": q.get("deterministic_path", False),
                "answer": answer,
                "answer_len": len(answer),
                "elapsed_s": elapsed,
                "completed_log": completed,
            }
            by_id_mode[key] = rec
            save_results(by_id_mode)
            done = len(by_id_mode)
            print(f"[{done}/{total}] {qid} {mode_label} | {elapsed}s | "
                  f"{len(answer)}c | {q['category']}", flush=True)
            if answer:
                print(f"   {answer.replace(chr(10), ' / ')[:140]}", flush=True)
    print(f"\nDone. {len(by_id_mode)} total runs saved to {RESULTS_PATH}")


if __name__ == "__main__":
    main()
