#!/usr/bin/env python3
"""
Pretty-print Localyze answers from quality_8q_*.json so I can grade them.
"""
import json, sys
from pathlib import Path

def show(p: Path):
    if not p.exists():
        print(f"[missing] {p}")
        return
    data = json.loads(p.read_text())
    for mode_key, results in data.items():
        print(f"\n{'='*72}\nMODE: {mode_key.upper()}  ({p.name})\n{'='*72}")
        for r in results:
            print(f"\n--- {r['tag']} ({r.get('status','?')}) "
                  f"{r.get('elapsed_s','?')}s "
                  f"{r.get('answer_len','?')} chars ---")
            print(f"Q: {r['q']}")
            ans = r.get("answer","").strip()
            print(f"A:")
            print(ans if ans else "(empty)")

if __name__ == "__main__":
    paths = [Path(a) for a in sys.argv[1:]] or [
        Path("quality_8q_online.json"),
        Path("quality_8q_offline.json"),
    ]
    for p in paths:
        show(p)
