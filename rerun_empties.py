#!/usr/bin/env python3
"""Re-run only the 4 EMPTY questions from batch 2 with the patched
extractor that falls back when the prompt scrolled off-screen."""
import json, subprocess
from pathlib import Path

# Load the harness from run_general15b
import sys
sys.path.insert(0, str(Path(__file__).parent))
from run_general15b import fire, ui_texts, extract_assistant, is_generating, classify, WAIT_S, DEVICE
import time

EMPTIES = [
    ("Q18_author",  "Who wrote the novel 1984?"),
    ("Q25_sql",     "Write a SQL query to get the second highest salary from a table named Employee with a column salary."),
    ("Q28_pack",    "I am going to Tokyo for 5 days in winter. List 6 essential items to pack."),
    ("Q30_dns",     "How does DNS resolve a domain name? Explain in 4 short bullets."),
]

out_path = Path("general15b_empties_rerun.json")
out: list[dict] = []
print(f"Re-running {len(EMPTIES)} previously-EMPTY questions with improved extractor\n")

for i, (tag, q) in enumerate(EMPTIES):
    force_stop = (i == 0)
    print(f"[{i+1}/{len(EMPTIES)} {tag}] {q}", flush=True)
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
        print(f"      {answer.replace(chr(10), ' | ')[:240]}")

print()
for r in out:
    print(f"{r['tag']:<14} {r['verdict']:<10} {r['answer_len']:>4}c")
