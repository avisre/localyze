#!/usr/bin/env python3
"""
50-question evaluation. Runs each question on the phone in the chosen
mode (online/offline), captures the answer via logcat, saves to JSON.

Usage:
  python3 run_eval50.py --mode online  --out eval50_online.json
  python3 run_eval50.py --mode offline --out eval50_offline.json
"""
from __future__ import annotations
import argparse, importlib.util, json, time
from pathlib import Path

spec = importlib.util.spec_from_file_location("rq", "run_quality_8q.py")
rq = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rq)

from eval50_questions import QUESTIONS

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default="a5523839")
    ap.add_argument("--mode", choices=["online", "offline"], required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--timeout", type=int, default=240)
    ap.add_argument("--start", type=int, default=0, help="resume index")
    args = ap.parse_args()

    web = args.mode == "online"
    out_path = Path(args.out)
    if args.start > 0 and out_path.exists():
        results = json.loads(out_path.read_text()).get("results", [])
    else:
        results = []

    for i, (tag, category, q) in enumerate(QUESTIONS):
        if i < args.start:
            continue
        print(f"\n[{i+1}/50 {category}] {tag}: {q[:78]}", flush=True)
        try:
            r = rq.run_one_question(args.device, tag, q,
                                    web_search=web,
                                    timeout_s=args.timeout,
                                    thinking=False)
            r["category"] = category
            r["mode"] = "online" if web else "offline"
            r["index"] = i
        except Exception as e:
            r = {"tag": tag, "q": q, "category": category,
                 "mode": "online" if web else "offline",
                 "status": "EXCEPTION", "error": str(e),
                 "elapsed_s": None, "answer": "", "answer_len": 0,
                 "n_tokens": 0, "path": "?", "index": i}
            print(f"  EXCEPTION: {e}", flush=True)
        results.append(r)
        out_path.write_text(json.dumps({"results": results}, indent=2))
    print(f"\nWrote {out_path} ({len(results)} results)", flush=True)

if __name__ == "__main__":
    main()
