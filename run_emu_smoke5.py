#!/usr/bin/env python3
"""5-question emulator smoke test. Mix of deterministic (calc bypass)
and model paths. Confirms build works on emulator before broader tests."""
from __future__ import annotations
import json, sys, subprocess, time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from run_general15b import (fire, ui_texts, extract_assistant, is_generating,
                            classify, WAIT_S, DEVICE)

QUESTIONS = [
    ("S01_capital",   "What's the capital of Italy?"),               # model
    ("S02_math",      "What is 12 times 7?"),                        # calc bypass
    ("S03_temp",      "Convert 32 degrees Fahrenheit to Celsius."),  # calc bypass
    ("S04_advice",    "Give me 3 quick tips for staying focused."),  # model
    ("S05_explain",   "Explain WiFi in simple terms."),              # model
]


def main() -> None:
    out: list[dict] = []
    out_path = Path("emu_smoke5_results.json")
    print(f"emulator smoke test ({len(QUESTIONS)} Qs) on {DEVICE}; per-Q wait {WAIT_S}s\n")
    for i, (tag, q) in enumerate(QUESTIONS):
        force_stop = (i == 0)
        print(f"[{i+1}/{len(QUESTIONS)} {tag}] {q}", flush=True)
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
        rec = {"tag": tag, "prompt": q, "verdict": verdict, "answer": last,
               "answer_len": len(last), "elapsed_s": round(time.time() - t0, 1)}
        out.append(rec)
        out_path.write_text(json.dumps({"results": out}, indent=2))
        print(f"   -> {verdict} | {len(last)} chars | {rec['elapsed_s']}s")
        if last:
            print(f"      {last.replace(chr(10), ' | ')[:200]}")
    print(f"\nDone: {[(r['tag'], r['verdict']) for r in out]}")


if __name__ == "__main__":
    main()
