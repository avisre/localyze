#!/usr/bin/env python3
"""Quick re-run of Q08 (discount) to verify the Unicode-minus fix."""
import json, sys, time, subprocess
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from run_general15b import (fire, ui_texts, extract_assistant, is_generating,
                            classify, WAIT_S)

q = "A jacket is $80 with 25 percent off. What's the sale price?"
fire(q, force_stop=True)
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
print(f"Q08 discount → {classify(last)}, {len(last)} chars, {round(time.time()-t0,1)}s")
print(f"Answer: {last!r}")
