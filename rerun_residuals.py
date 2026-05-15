#!/usr/bin/env python3
"""Re-run questions whose v2 result was contaminated by either
(a) the calculator/web tool indicator pill leaking into the answer, or
(b) the assistant response scrolling past the visible viewport before
extraction. Uses the upgraded extractor in run_general15b.py."""
import json, subprocess, time, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from run_general15b import (fire, ui_texts, extract_assistant, is_generating,
                            classify, WAIT_S, DEVICE)

QUESTIONS = [
    # Batch 1 — calculator tool-pill leak
    ("Q04_math_div", "What is 144 divided by 12?"),
    ("Q13_temp",     "Convert 100 degrees Fahrenheit to Celsius."),
    # Batch 2 — calculator tool-pill leak
    ("Q21_pct",      "What is 20 percent of 250?"),
    ("Q22_speed",    "If I drive 240 miles in 4 hours, what is my average speed?"),
    # Batch 2 — long-answer scroll-off
    ("Q25_sql",      "Write a SQL query to get the second highest salary from a table named Employee with a column salary."),
]

out_path = Path("residuals_rerun.json")
out: list[dict] = []
print(f"Re-running {len(QUESTIONS)} residual cases with upgraded extractor "
      "(tool-pill filter + scroll-up fallback)\n")

for i, (tag, q) in enumerate(QUESTIONS):
    force_stop = (i == 0)
    print(f"[{i+1}/{len(QUESTIONS)} {tag}] {q}", flush=True)
    fire(q, force_stop)
    t0 = time.time()
    last = ""
    last_change = t0
    while time.time() - t0 < WAIT_S:
        time.sleep(4.0)
        texts = ui_texts()
        cur = extract_assistant(texts, q)
        gen = is_generating(texts)
        if cur != last:
            last = cur
            last_change = time.time()
        if not gen and cur and (time.time() - last_change) >= 10.0:
            break
    answer = last
    verdict = classify(answer)
    rec = {"tag": tag, "prompt": q, "verdict": verdict,
           "answer": answer, "answer_len": len(answer),
           "elapsed_s": round(time.time() - t0, 1)}
    out.append(rec)
    out_path.write_text(json.dumps({"results": out}, indent=2))
    print(f"   -> {verdict} | {len(answer)} chars | {rec['elapsed_s']}s")
    if answer:
        print(f"      {answer.replace(chr(10), ' | ')[:280]}")
    print()

print("\nDone:", {r['tag']: r['verdict'] for r in out})
