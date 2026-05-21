#!/usr/bin/env python3
"""Tester helper: submit a question to the central repo.

Tester agents call this script repeatedly. It writes a small JSON file to
/tmp/qrepo/pending/ that the GPU worker picks up. Stdout reports the assigned
id; the agent polls /tmp/qrepo/answered.jsonl to read graded results.

Usage:
    submit.py <tester> <category> "<prompt>" [--must "kw1,kw2"] [--ban "kw3"]
"""
from __future__ import annotations
import argparse
import hashlib
import json
import time
from pathlib import Path

PENDING = Path("/tmp/qrepo/pending")
ANSWERED = Path("/tmp/qrepo/answered.jsonl")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("tester", choices=["research", "chat"])
    ap.add_argument("category")
    ap.add_argument("prompt")
    ap.add_argument("--must", default="", help="comma-separated must_contain keywords")
    ap.add_argument("--ban",  default="", help="comma-separated must_not_contain keywords")
    args = ap.parse_args()

    PENDING.mkdir(parents=True, exist_ok=True)

    ph = hashlib.sha256(args.prompt.strip().lower().encode("utf-8")).hexdigest()[:16]
    # Dedup against existing answered + pending
    seen = set()
    if ANSWERED.exists():
        for line in ANSWERED.open():
            try:
                seen.add(json.loads(line).get("prompt_hash", ""))
            except Exception:
                pass
    for p in PENDING.glob("*.json"):
        try:
            seen.add(json.loads(p.read_text()).get("prompt_hash", ""))
        except Exception:
            pass
    if ph in seen:
        print(f"DUPLICATE {ph}", flush=True)
        return 3

    qid = f"Q-{ph[:8]}-{int(time.time()*1000)%1000:03d}"
    obj = {
        "id": qid,
        "tester": args.tester,
        "category": args.category,
        "prompt": args.prompt,
        "prompt_hash": ph,
        "must_contain":    [s.strip() for s in args.must.split(",") if s.strip()],
        "must_not_contain":[s.strip() for s in args.ban.split(",")  if s.strip()],
        "submitted_at": time.time(),
    }
    out = PENDING / f"{qid}.json"
    out.write_text(json.dumps(obj))
    print(f"SUBMITTED {qid}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
