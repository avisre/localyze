#!/usr/bin/env python3
"""10-question demo on the emulator. Mix of deterministic (calc/financial)
and model paths so the user sees a variety of responses."""
from __future__ import annotations
import json, subprocess, time, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from run_general15b import (fire, ui_texts, extract_assistant, is_generating,
                            classify)

DEVICE = "emulator-5554"
PKG = "com.localyze"
WAIT_S = 240  # CPU backend on emulator is slow

QUESTIONS = [
    ("Q01_capital",   "What's the capital of Japan?"),
    ("Q02_math",      "What is 15 percent of 200?"),
    ("Q03_convert",   "Convert 100 degrees Fahrenheit to Celsius."),
    ("Q04_tcs",       "show TCS revenue for the last 3 fiscal years"),
    ("Q05_alibaba",   "plot Alibaba revenue for the past 3 years"),
    ("Q06_apple",     "show Apple revenue for the last 3 fiscal years"),
    ("Q07_seasons",   "How many seasons are there in a year?"),
    ("Q08_egg",       "How long do I boil an egg for soft-boiled?"),
    ("Q09_python",    "Write a Python one-liner that returns the squares of 1 to 5."),
    ("Q10_writeemail","Write a short sick-day email to my boss."),
]


def main() -> None:
    out: list[dict] = []
    out_path = Path("emu_10q_results.json")
    print(f"emulator demo: {len(QUESTIONS)} questions on {DEVICE}, WAIT_S={WAIT_S}\n")
    for i, (tag, q) in enumerate(QUESTIONS):
        force_stop = (i == 0)
        print(f"[{i+1:>2}/{len(QUESTIONS)} {tag}] {q}", flush=True)
        fire(q, force_stop)
        t0 = time.time()
        last = ""; last_change = t0
        while time.time() - t0 < WAIT_S:
            time.sleep(5.0)
            texts = ui_texts()
            cur = extract_assistant(texts, q)
            gen = is_generating(texts)
            if cur != last:
                last = cur; last_change = time.time()
            if not gen and cur and (time.time() - last_change) >= 12.0:
                break
        verdict = classify(last)
        shot = f"/tmp/emu10_{tag}.png"
        subprocess.call(["adb", "-s", DEVICE, "shell", "screencap", "/sdcard/s.png"])
        subprocess.call(["adb", "-s", DEVICE, "pull", "/sdcard/s.png", shot],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        rec = {"tag": tag, "prompt": q, "verdict": verdict, "answer": last,
               "answer_len": len(last), "elapsed_s": round(time.time() - t0, 1),
               "screenshot": shot}
        out.append(rec)
        out_path.write_text(json.dumps({"results": out}, indent=2))
        print(f"   -> {verdict} | {len(last)} chars | {rec['elapsed_s']}s")
        if last:
            print(f"      {last.replace(chr(10), ' | ')[:200]}")
    print("\nDone.")


if __name__ == "__main__":
    main()
