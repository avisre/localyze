#!/usr/bin/env python3
"""Unified Localyze eval runner.

Runs a question bank (golden50, redteam20, or any compatible JSON) on a
target device (emulator or physical phone) and saves Q/A pairs with
property-assertion verdicts. Output feeds the LLM-judge step.

Usage:
    python3 run_eval.py --bank golden50_bank.json --device a5523839 \
            --web on --out golden50_results.json
"""
from __future__ import annotations
import argparse, json, re, subprocess, sys, time
from pathlib import Path
from urllib.parse import quote

sys.path.insert(0, str(Path(__file__).parent))
from run_general15b import CHROME, _norm

PKG = "com.localyze"
COMPONENT = f"{PKG}/.MainActivity"
# 20 min per question — covers worst-case CPU emulator runs on long-form
# answers. On phone/GPU most queries are 15-60s so this only affects the
# tail.
MAX_WAIT_S = 1200


def ui_texts(dev: str) -> list[str]:
    try:
        subprocess.check_call(
            ["adb", "-s", dev, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15,
        )
        out = subprocess.check_output(
            ["adb", "-s", dev, "shell", "cat", "/sdcard/ui.xml"],
            text=True, timeout=15,
        )
    except Exception:
        return []
    return re.findall(r'text="([^"]+)"', out)


def _close_shade_and_dialogs(dev: str) -> None:
    """Close the notification shade (in case a prior keyevent or swipe
    opened it) and dismiss any system 'Localyze has stopped' dialog by
    targeting its CLOSE button via uiautomator. We deliberately avoid
    keyevent 4 (back) because in some states it OPENS the shade instead
    of dismissing what we wanted."""
    try:
        # Collapse notification panel — safe no-op if not open.
        subprocess.call(
            ["adb", "-s", dev, "shell", "cmd", "statusbar", "collapse"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5,
        )
    except Exception:
        pass
    # If the crash dialog is up, its CLOSE button has resource-id
    # 'android:id/aerr_close' or text 'Close app' depending on Android
    # version. Try a uiautomator dump and look for it.
    try:
        subprocess.check_call(
            ["adb", "-s", dev, "shell", "uiautomator", "dump", "/sdcard/dlg.xml"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10,
        )
        out = subprocess.check_output(
            ["adb", "-s", dev, "shell", "cat", "/sdcard/dlg.xml"],
            text=True, timeout=10,
        )
        # If we see the "has stopped" crash dialog, find the bounds of
        # the CLOSE button and tap its centroid.
        if "has stopped" in out or "aerr_close" in out or "Close app" in out:
            import re as _re
            m = _re.search(
                r'(?:text="Close app"|resource-id="android:id/aerr_close")'
                r'[^/>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
                out,
            )
            if m:
                x = (int(m.group(1)) + int(m.group(3))) // 2
                y = (int(m.group(2)) + int(m.group(4))) // 2
                subprocess.call(["adb", "-s", dev, "shell", "input", "tap",
                                 str(x), str(y)],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        pass


def _is_process_alive(dev: str) -> bool:
    """True if the com.localyze process is currently running."""
    try:
        out = subprocess.check_output(
            ["adb", "-s", dev, "shell", "pidof", PKG],
            text=True, timeout=5,
        )
        return out.strip().isdigit()
    except Exception:
        return False


def _is_app_foreground(dev: str) -> bool:
    """True if com.localyze is the top resumed activity. False if the
    user is on launcher, settings, a system dialog, or the notification
    shade is open. Used before extraction to detect 'we lost the app'."""
    try:
        out = subprocess.check_output(
            ["adb", "-s", dev, "shell", "dumpsys", "activity", "activities"],
            text=True, timeout=8,
        )
    except Exception:
        return False
    for line in out.splitlines():
        if "topResumedActivity" in line:
            return PKG in line
    return False


def _refocus_app(dev: str) -> None:
    """Best-effort: collapse the shade and bring com.localyze to the
    foreground without changing app state (no force-stop)."""
    try:
        subprocess.call(["adb", "-s", dev, "shell", "cmd", "statusbar", "collapse"],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
        subprocess.call(["adb", "-s", dev, "shell", "am", "start",
                         "-n", COMPONENT, "--activity-single-top"],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=8)
    except Exception:
        pass


def fire(dev: str, prompt: str, web: bool, force_cpu: bool) -> bool:
    # Dismiss any leftover crash dialog from a prior SIGSEGV before we
    # try to launch. Without this the next launch can get stuck behind
    # the dialog and the runner scrapes the launcher screen.
    _close_shade_and_dialogs(dev)
    subprocess.call(["adb", "-s", dev, "shell", "am", "force-stop", PKG])
    time.sleep(1)
    encoded = quote(prompt).replace("%20", "%s")
    r = subprocess.run([
        "adb", "-s", dev, "shell", "am", "start",
        "-n", COMPONENT,
        "--es", "chat_msg", encoded,
        "--ez", "force_cpu", "true" if force_cpu else "false",
        "--ez", "allow_web_search", "true" if web else "false",
        # Enable thinking mode so the model reasons through ambiguous /
        # vague prompts before answering. Trades latency for quality.
        "--ez", "set_thinking", "true",
    ], capture_output=True, text=True, timeout=15)
    return "does not exist" not in ((r.stdout or "") + (r.stderr or ""))


_TIMESTAMP_RE = re.compile(r"^(just now|\d+[smhd] ago)$")
# Streaming placeholder shown WHILE the model is still generating. If we
# see this in the UI, the answer isn't done yet — wait and re-dump.
_STREAMING_PLACEHOLDERS = {
    "Writing answer", "The answer will appear below",
    # Thinking-mode placeholder. When set_thinking=true the UI shows
    # "Thinking..." while the model produces its chain of thought before
    # the final answer streams in.
    "Thinking...", "Thinking…",
}
# Environment overlays that have nothing to do with the model. When the
# emulator pops these up, we want to mark the run "inconclusive" rather
# than score the overlay text.
_OVERLAY_MARKERS = (
    "Something went wrong",
    "Check that Google Play is enabled",
    "Android System\n",
    "Display brightness",
    "Internet, AndroidWifi",
    "Serial console enabled",
    "WebView Browser Tester",
)


def _has_placeholder(texts: list[str]) -> bool:
    return any(p in t for p in _STREAMING_PLACEHOLDERS for t in [t for t in texts])


def _has_overlay(joined: str) -> bool:
    return any(m in joined for m in _OVERLAY_MARKERS)


def _dump_once(dev: str, prompt: str) -> tuple[str, list[str]]:
    target = _norm(prompt)
    seen: list[str] = []
    seen_set: set[str] = set()
    raw_seen: list[str] = []
    for _ in range(2):
        subprocess.call(["adb", "-s", dev, "shell", "input", "swipe",
                        "540", "600", "540", "1900", "350"])
        time.sleep(0.4)
    for _ in range(3):
        for t in ui_texts(dev):
            raw_seen.append(t)
            if t in _STREAMING_PLACEHOLDERS:
                continue  # placeholder is NOT chat content
            if t not in seen_set and t not in CHROME and "Generating" not in t:
                seen_set.add(t)
                seen.append(t)
        subprocess.call(["adb", "-s", dev, "shell", "input", "swipe",
                        "540", "1800", "540", "600", "350"])
        time.sleep(0.4)
    out_lines: list[str] = []
    after_prompt = False
    found = any(_norm(t) == target for t in seen)
    for t in seen:
        if found and not after_prompt:
            if _norm(t) == target:
                after_prompt = True
            continue
        if t == "Message Localyze.ai...":
            break
        if not found and _norm(t) == target:
            continue
        if _TIMESTAMP_RE.match(t):  # strip "just now" / "1m ago" lines
            continue
        out_lines.append(t)
    return "\n".join(out_lines).strip(), raw_seen


def extract_answer(dev: str, prompt: str) -> str:
    """Pull the final rendered answer. Retries while the streaming
    placeholder is on screen (model still generating). If the app is
    NOT in the foreground (shade open, launcher visible, system
    dialog), refocus before extracting — otherwise we scrape system UI
    text instead of the chat bubble."""
    for attempt in range(6):
        # If the app isn't foreground, try to bring it back before
        # scraping. Don't force-stop — we'd lose the answer state.
        if not _is_app_foreground(dev):
            _refocus_app(dev)
            time.sleep(2.0)
        out, raw = _dump_once(dev, prompt)
        joined = "\n".join(raw)
        # Env overlay — UI was hijacked, can't score this run.
        if _has_overlay(joined) and not out:
            time.sleep(3.0)
            continue
        # Placeholder still visible — the model is still streaming.
        if any(p in joined for p in _STREAMING_PLACEHOLDERS):
            time.sleep(4.0)
            continue
        if out:
            return out
        time.sleep(3.0)
    out, _ = _dump_once(dev, prompt)
    return out


# With thinking-mode-on the model can legitimately sit in its
# chain-of-thought for 90-180s before the first user-visible token
# streams. 240s = "very generous, but still ends a real wedge in <5min".
STALL_THRESHOLD_S = 240


def _check_native_crash(dev: str) -> bool:
    """Return True if the most recent logcat shows a native SIGSEGV /
    SIGABRT in our package — the LiteRT-LM JNI sometimes segfaults
    mid-inference. Short-circuit the wait when that happens so we don't
    burn 20 min after a dead process."""
    try:
        log = subprocess.check_output(
            ["adb", "-s", dev, "logcat", "-d", "libc:F", "AndroidRuntime:E", "*:S"],
            text=True, timeout=6,
        )
    except Exception:
        return False
    if "Fatal signal" in log and PKG in log:
        return True
    return False


def wait_for_completion(dev: str, t0: float) -> tuple[bool, str]:
    """Return (completed?, reason). reason ∈ {'completed','error',
    'native_crash','stall','timeout','process_died'}.

    Exits early on:
    - Completed event (normal end)
    - Error event (engine reported failure)
    - Fatal signal in logcat (LiteRT-LM SIGSEGV crashed the process)
    - Process is no longer running (the SIGSEGV killed it silently)
    - No StreamingToken progress for STALL_THRESHOLD_S (silent wedge)
    """
    last_token_count = 0
    last_token_seen_at = time.time()
    while time.time() - t0 < MAX_WAIT_S:
        time.sleep(1.5)
        try:
            log = subprocess.check_output(
                ["adb", "-s", dev, "logcat", "-d",
                 "ChatViewModel:V", "*:S"],
                text=True, timeout=8
            )
        except Exception:
            continue
        if _check_native_crash(dev):
            return False, "native_crash"
        if not _is_process_alive(dev):
            return False, "process_died"
        if "doSendMessage" not in log:
            continue
        tail = log.split("doSendMessage")[-1]
        if "handleResponseEvent: Completed" in tail:
            return True, "completed"
        if "handleResponseEvent: Error" in tail:
            return False, "error"
        cur = tail.count("StreamingToken")
        if cur > last_token_count:
            last_token_count = cur
            last_token_seen_at = time.time()
        elif time.time() - last_token_seen_at > STALL_THRESHOLD_S:
            return False, "stall"
    return False, "timeout"


def _normalize_for_match(s: str) -> str:
    """Strip diacritics, drop commas in numbers, lowercase. Lets the bank
    say "Brasilia" / "3600" / "mitochondria" and still match the model's
    "Brasília" / "3,600" / "mitochondrion".
    """
    import unicodedata
    nfkd = unicodedata.normalize("NFKD", s)
    no_diacritics = "".join(c for c in nfkd if not unicodedata.combining(c))
    # Drop commas only when they sit between digits (thousands separators).
    no_thousands = re.sub(r"(?<=\d),(?=\d)", "", no_diacritics)
    # Plural/singular tolerance: strip a trailing 'on'->'a' (mitochondrion
    # → mitochondria) — narrow, only triggered when keyword ends 'a' and
    # answer has 'on'. Cheap enough to do via a second contains check.
    return no_thousands.lower()


def assert_props(answer: str, q: dict, global_forbidden: list[str]) -> dict:
    """Return {assert_status, hits} where hits is the list of failing
    assertion strings. Empty hits = green."""
    hits: list[str] = []
    # Question-specific must_not_contain
    for s in q.get("must_not_contain", []) or []:
        if s and s in answer:
            hits.append(f"forbidden:{s!r}")
    for s in q.get("forbidden_keywords", []) or []:
        if s and s in answer:
            hits.append(f"forbidden:{s!r}")
    # Global property assertions
    for s in global_forbidden:
        if s and s in answer:
            hits.append(f"global_forbidden:{s!r}")
    # Normalize once for keyword comparisons.
    answer_norm = _normalize_for_match(answer)
    # Required substrings
    must_contain = q.get("must_contain", []) or []
    if must_contain:
        if not any(_normalize_for_match(s) in answer_norm for s in must_contain):
            hits.append(f"missing_must_contain:{must_contain}")
    # Expected keywords (any-match). Also accept singular↔plural English
    # variants (mitochondria/mitochondrion, fungus/fungi etc.) for stems
    # that end -a / -on / -us / -i, and US/UK spellings (color/colour).
    expected = q.get("expected_keywords", []) or []
    if expected:
        def _matches(kw: str) -> bool:
            n = _normalize_for_match(kw)
            if n in answer_norm: return True
            # Plural/singular: -a ↔ -on (mitochondria/mitochondrion)
            if n.endswith("a") and (n[:-1] + "on") in answer_norm: return True
            if n.endswith("on") and (n[:-2] + "a") in answer_norm: return True
            # -us ↔ -i (cactus/cacti)
            if n.endswith("us") and (n[:-2] + "i") in answer_norm: return True
            if n.endswith("i") and (n[:-1] + "us") in answer_norm: return True
            return False
        if not any(_matches(s) for s in expected):
            hits.append(f"missing_expected:{expected}")
    return {"assert_status": "PASS" if not hits else "FAIL", "hits": hits}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--bank", required=True, help="path to bank JSON")
    ap.add_argument("--device", required=True, help="adb device serial")
    ap.add_argument("--web", choices=["on", "off"], default="on")
    ap.add_argument("--force-cpu", action="store_true",
                    help="set force_cpu intent extra (emulator)")
    ap.add_argument("--out", required=True, help="path to write results JSON")
    args = ap.parse_args()

    bank = json.load(open(args.bank))
    questions = bank["questions"] if "questions" in bank else bank
    global_forbidden = bank.get("global_forbidden_substrings", []) if isinstance(bank, dict) else []
    print(f"=== {args.bank}: {len(questions)} questions, device={args.device}, "
          f"web={args.web}, force_cpu={args.force_cpu} ===")

    out_path = Path(args.out)
    existing: dict[str, dict] = {}
    if out_path.exists():
        try:
            existing = {r["id"]: r for r in json.load(open(out_path))["records"]}
        except Exception:
            existing = {}
    print(f"resume: {len(existing)} already saved")

    records: list[dict] = []
    for i, q in enumerate(questions, 1):
        qid = q["id"]
        if qid in existing:
            records.append(existing[qid])
            continue
        t0 = time.time()
        subprocess.call(["adb", "-s", args.device, "logcat", "-c"])
        time.sleep(0.5)
        ok = fire(args.device, q["prompt"], args.web == "on", args.force_cpu)
        if not ok:
            print(f"[{i}/{len(questions)}] {qid} !! intent not delivered", flush=True)
            continue
        completed, reason = wait_for_completion(args.device, t0)
        # If the LiteRT-LM JNI segfaulted, retry ONCE with a fresh
        # process. The crash dialog gets dismissed by the next fire().
        if reason in ("native_crash", "process_died"):
            print(f"    !! engine {reason} on {qid}; retrying once with fresh process", flush=True)
            _close_shade_and_dialogs(args.device)
            time.sleep(2.0)
            subprocess.call(["adb", "-s", args.device, "logcat", "-c"])
            time.sleep(0.5)
            t0 = time.time()
            ok = fire(args.device, q["prompt"], args.web == "on", args.force_cpu)
            if ok:
                completed, reason = wait_for_completion(args.device, t0)
        time.sleep(2.0)
        answer = extract_answer(args.device, q["prompt"])
        verdict = assert_props(answer, q, global_forbidden)
        elapsed = round(time.time() - t0, 1)
        rec = {
            "id": qid,
            "fingerprint": q.get("fingerprint", ""),
            "prompt": q["prompt"],
            "category": q.get("category", ""),
            "answer": answer,
            "answer_len": len(answer),
            "elapsed_s": elapsed,
            "completed_log": completed,
            "wait_reason": reason,
            "assert_status": verdict["assert_status"],
            "assert_hits": verdict["hits"],
            "expected_keywords": q.get("expected_keywords", []),
            "must_contain": q.get("must_contain", []),
            "must_not_contain": q.get("must_not_contain", []),
        }
        records.append(rec)
        existing[qid] = rec
        out_path.write_text(json.dumps({"records": list(existing.values())}, indent=2))
        flag = "PASS" if verdict["assert_status"] == "PASS" else "FAIL"
        print(f"[{i}/{len(questions)}] {qid} {q.get('fingerprint','')} | "
              f"{elapsed}s | {len(answer)}c | {flag} ({reason})", flush=True)
        if verdict["hits"]:
            print(f"    hits: {verdict['hits']}", flush=True)
        if answer:
            print(f"    A: {answer.replace(chr(10), ' / ')[:140]}", flush=True)

    passed = sum(1 for r in records if r["assert_status"] == "PASS")
    print(f"\n=== {passed}/{len(records)} passed property assertions ===")


if __name__ == "__main__":
    main()
