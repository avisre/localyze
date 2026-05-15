#!/usr/bin/env python3
"""
8-question quality probe for Localyze, using logcat streaming.

The app's GemmaInferenceEngine logs each text-delta token as:
    D GemmaInference: Received text content: '<delta>...'
We concatenate these deltas to reconstruct the full assistant answer,
stopping at the next "onDone callback received" line.

This avoids the SQLCipher-encrypted Room DB and the Compose UI tree
that doesn't expose the rendered markdown text.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import threading
import time
from pathlib import Path
from urllib.parse import quote

PKG = "com.localyze"
ACTIVITY = f"{PKG}/.MainActivity"

# The format string in GemmaInferenceEngine.kt is:
#   Log.d(TAG, "Received text content: '${textContent.take(100)}...'")
# Each line ends with `...'` (literal three dots + close quote).
# Capture the delta between the first `'` after `content: ` and the trailing `...'`.
# A logcat line header: "MM-DD HH:MM:SS.mmm  PID  TID L TAG:"
HEADER_RE = re.compile(r"^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+ [VDIWE] ")
# Gemma-path markers (Log.d truncates at '\n' so newline deltas appear as
# unclosed-quote lines; treat those as "\n").
DELTA_RE = re.compile(r"Received text content: '(.*?)\.\.\.'\s*$")
NEWLINE_DELTA_RE = re.compile(r"Received text content: '\s*$")
ONDONE_RE = re.compile(r"onDone callback received")
ERR_RE    = re.compile(r"(LiteRT-LM error|JNI error|Generation failed|Response timeout)")
SENT_RE   = re.compile(r"Sending message to model")
# Curator-path markers (ChatViewModel emits StreamingToken events for
# curator-streamed text bypassing Gemma; chunks are truncated to 20 chars
# in the log so reconstruction is lossy, but enough for diagnosis).
CURATOR_TOKEN_RE = re.compile(r"ChatViewModel: StreamingToken: '(.*?)\.\.\.'\s*$")
CURATOR_TOKEN_NL_RE = re.compile(r"ChatViewModel: StreamingToken: '\s*$")
COMPLETED_RE = re.compile(r"ChatViewModel: (Completed$|handleResponseEvent: Completed)")

QUESTIONS = [
    ("Q1_TCP_UDP",       "Explain the difference between TCP and UDP and when to use each, in 4 short bullets."),
    ("Q2_TRAIN_MATH",    "A train leaves City A at 9:00 AM going 60 mph east. Another leaves City B at 9:30 AM going 75 mph west. The cities are 300 miles apart. When do they meet?"),
    ("Q3_CODE_RUN",      "Write a Python function longest_run(nums) that returns the length of the longest run of consecutive equal numbers in a list. Include one example call with output."),
    ("Q4_APOLOGY",       "Draft a 3-sentence apology email to a customer whose order was delayed by 5 days due to a warehouse fire."),
    ("Q5_HASH_TABLE",    "Explain to a beginner what a hash table is and why it is fast, with one analogy."),
    ("Q6_PYTHON_VERSION","What is the latest stable version of Python and when was it released?"),
    ("Q7_BTC_PRICE",     "What is the current Bitcoin price in USD?"),
    ("Q8_APPLES",        "I have 3 apples. I give you 2. Then I eat 1 of mine. Then you give me back 1. How many apples does each of us have at the end?"),
]


def adb(device: str, *args, timeout: int = 30, text: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["adb", "-s", device, *args],
                          capture_output=True, text=text, timeout=timeout)


def shell(device: str, *args, timeout: int = 30) -> subprocess.CompletedProcess:
    return adb(device, "shell", *args, timeout=timeout)


def force_stop(device: str) -> None:
    shell(device, "am", "force-stop", PKG, timeout=10)


def start_app(device: str, **extras) -> None:
    cmd = ["am", "start", "-W", "-n", ACTIVITY]
    for k, v in extras.items():
        if isinstance(v, bool):
            cmd += ["--ez", k, "true" if v else "false"]
        else:
            cmd += ["--es", k, str(v)]
    r = shell(device, *cmd, timeout=30)
    if r.returncode != 0 or "Error" in (r.stderr or ""):
        raise RuntimeError(f"am start failed: {r.stderr or r.stdout}")


def set_web_search(device: str, enabled: bool) -> None:
    start_app(device, enable_web_search=enabled, disable_web_search=not enabled)
    time.sleep(2.0)


def set_thinking(device: str, enabled: bool) -> None:
    start_app(device, set_thinking=enabled)
    time.sleep(2.0)


def send_question(device: str, q: str) -> None:
    start_app(device, chat_msg=quote(q, safe=""))


def dump_logcat(device: str, tags: tuple[str, ...] = ("GemmaInference", "ChatViewModel")) -> str:
    r = subprocess.run(
        ["adb", "-s", device, "logcat", "-d", "-s", *tags],
        capture_output=True, text=True, timeout=20,
    )
    return r.stdout


def join_logcat_entries(text: str) -> list[str]:
    """
    Each logcat entry starts with "MM-DD HH:MM:SS.mmm PID TID L TAG:".
    Lines that don't match the header are continuations of the previous
    entry (happens when the logged text contains literal '\n').
    """
    entries = []
    cur = None
    for raw in text.splitlines():
        if HEADER_RE.match(raw):
            if cur is not None:
                entries.append(cur)
            cur = raw
        else:
            if cur is None:
                cur = raw
            else:
                cur += "\n" + raw
    if cur is not None:
        entries.append(cur)
    return entries


def parse_logcat(text: str) -> dict:
    """
    Iterate physical lines. Captures both:
      1) Gemma path: "Received text content: '<delta>...'" + "onDone"
      2) Curator path: "ChatViewModel: StreamingToken: '<chunk>...'" + "Completed"
    Returns whichever is complete; prefers Gemma when both are present.
    """
    gemma_deltas, seen_send, seen_done, err = [], False, False, None
    curator_chunks, curator_completed = [], False
    for line in text.splitlines():
        if SENT_RE.search(line):
            seen_send = True
            continue
        if seen_send and "Received text content:" in line:
            m = DELTA_RE.search(line)
            if m:
                gemma_deltas.append(m.group(1))
            elif NEWLINE_DELTA_RE.search(line):
                gemma_deltas.append("\n")
            continue
        if seen_send and ONDONE_RE.search(line):
            seen_done = True
            continue
        m_err = ERR_RE.search(line)
        if m_err and err is None:
            err = m_err.group(0)
        # Curator-path: ChatViewModel
        if "ChatViewModel: StreamingToken:" in line:
            mc = CURATOR_TOKEN_RE.search(line)
            if mc:
                curator_chunks.append(mc.group(1))
            elif CURATOR_TOKEN_NL_RE.search(line):
                curator_chunks.append("\n")
            continue
        if COMPLETED_RE.search(line):
            curator_completed = True
            continue

    if seen_send:
        return {
            "path": "gemma",
            "deltas": gemma_deltas,
            "seen_send": True,
            "seen_done": seen_done,
            "err": err,
        }
    return {
        "path": "curator",
        "deltas": curator_chunks,
        "seen_send": False,
        "seen_done": curator_completed,
        "err": err,
    }


def run_one_question(device: str, tag: str, question: str,
                     web_search: bool, timeout_s: int,
                     thinking: bool = False) -> dict:
    print(f"\n[{tag}] Q: {question[:80]}...", flush=True)
    force_stop(device)
    time.sleep(1.0)
    set_thinking(device, thinking)
    time.sleep(1.0)
    force_stop(device)
    time.sleep(0.5)
    set_web_search(device, web_search)
    time.sleep(1.5)
    # Clear logcat so we only see this question's stream
    subprocess.run(["adb", "-s", device, "logcat", "-c"], timeout=10, check=False)
    time.sleep(0.5)
    t0 = time.time()
    send_question(device, question)

    # Poll dump-mode logcat until onDone or timeout
    deadline = t0 + timeout_s
    last_delta_count = 0
    stable_since = None
    parsed = {"deltas": [], "seen_send": False, "seen_done": False, "err": None}

    while time.time() < deadline:
        time.sleep(2.0)
        log = dump_logcat(device)
        parsed = parse_logcat(log)
        n = len(parsed["deltas"])
        if parsed["seen_done"]:
            # Wait one more cycle for any straggler deltas
            time.sleep(1.5)
            log = dump_logcat(device)
            parsed = parse_logcat(log)
            break
        if parsed["err"]:
            break
        # Stability fallback: if no new deltas for 15s after we've seen some, bail
        if n == last_delta_count and n > 0:
            if stable_since is None:
                stable_since = time.time()
            elif time.time() - stable_since > 15:
                break
        else:
            stable_since = None
            last_delta_count = n

    elapsed = round(time.time() - t0, 1)
    answer = "".join(parsed["deltas"])
    if parsed["err"]:
        status = "ERROR"
    elif parsed["seen_done"]:
        status = "OK"
    elif answer:
        status = "STALLED"
    else:
        status = "TIMEOUT"
    print(f"  -> {status} in {elapsed}s, {len(answer)} chars, "
          f"{len(parsed['deltas'])} tokens [{parsed['path']}]"
          f"{(', err=' + parsed['err']) if parsed['err'] else ''}",
          flush=True)
    return {
        "tag": tag, "q": question, "web_search": web_search,
        "elapsed_s": elapsed, "status": status,
        "answer": answer, "answer_len": len(answer),
        "n_tokens": len(parsed["deltas"]),
        "path": parsed["path"],
        "error": parsed["err"],
    }


def run_mode(device: str, mode_name: str, web_search: bool, timeout_s: int) -> list[dict]:
    print(f"\n=== MODE: {mode_name} (web_search={web_search}) ===", flush=True)
    results = []
    for tag, q in QUESTIONS:
        try:
            r = run_one_question(device, tag, q, web_search, timeout_s)
        except Exception as e:
            print(f"  EXCEPTION on {tag}: {e}", flush=True)
            r = {"tag": tag, "q": q, "web_search": web_search,
                 "status": "EXCEPTION", "error": str(e),
                 "elapsed_s": None, "answer": "", "answer_len": 0,
                 "n_tokens": 0, "tool_lines": []}
        r["mode"] = mode_name
        results.append(r)
        # Persist incrementally
    return results


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default="a5523839")
    ap.add_argument("--out", default="quality_8q_results.json")
    ap.add_argument("--timeout", type=int, default=240)
    ap.add_argument("--mode", choices=["online", "offline", "both"], default="both")
    args = ap.parse_args()

    out = {}
    if args.mode in ("online", "both"):
        out["online"] = run_mode(args.device, "ONLINE",
                                 web_search=True, timeout_s=args.timeout)
        Path(args.out).write_text(json.dumps(out, indent=2))
    if args.mode in ("offline", "both"):
        out["offline"] = run_mode(args.device, "OFFLINE",
                                  web_search=False, timeout_s=args.timeout)
        Path(args.out).write_text(json.dumps(out, indent=2))
    print(f"\nWrote {args.out}", flush=True)


if __name__ == "__main__":
    sys.exit(main())
