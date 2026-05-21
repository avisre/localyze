#!/usr/bin/env python3
"""GPU worker for the central question repo.

Watches /tmp/qrepo/pending/*.json — each is a question dropped by one of the
two tester agents. For each:
  1. Dedup by prompt hash against /tmp/qrepo/answered.jsonl
  2. If new, run through the GPU (Vulkan llama-completion) with the desktop
     app's exact system prompt + pre-filter
  3. Auto-grade with a simple rubric
  4. Append the result to /tmp/qrepo/answered.jsonl (the spectator reads this)
  5. Move the input file into /tmp/qrepo/done/

Pending JSON schema:
    { "id": "QXXX", "tester": "research"|"chat", "category": "...",
      "prompt": "...", "must_contain": [...], "must_not_contain": [...] }
"""

from __future__ import annotations

import datetime
import hashlib
import json
import sys
import time
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from run_bank250 import LLAMA_COMPLETION, MODEL_PATH, gemma_prompt, run_one
from pre_filter import decide as pre_filter_decide

REPO = Path("/tmp/qrepo")
PENDING = REPO / "pending"
DONE = REPO / "done"
ANSWERED = REPO / "answered.jsonl"


def prompt_hash(p: str) -> str:
    return hashlib.sha256(p.strip().lower().encode("utf-8")).hexdigest()[:16]


def already_answered() -> set[str]:
    seen = set()
    if not ANSWERED.exists():
        return seen
    with ANSWERED.open() as f:
        for line in f:
            try:
                obj = json.loads(line)
                seen.add(obj.get("prompt_hash", ""))
            except json.JSONDecodeError:
                continue
    return seen


def grade(answer: str, must_contain: list[str], must_not_contain: list[str]) -> tuple[str, str]:
    """Returns (grade, notes). grade ∈ {pass, partial, fail}."""
    if not answer.strip():
        return "fail", "empty answer"
    lo = answer.lower()
    mc_hit = [k for k in (must_contain or []) if k.lower() in lo]
    mc_miss = [k for k in (must_contain or []) if k.lower() not in lo]
    bans = [k for k in (must_not_contain or []) if k.lower() in lo]
    if bans:
        return "fail", f"banned token: {bans[:3]}"
    if must_contain and not mc_hit:
        return "fail", f"missing required: {mc_miss[:3]}"
    if must_contain and mc_miss:
        return "partial", f"partial match (missing {mc_miss[:3]})"
    if len(answer.strip()) < 20:
        return "partial", "answer very short"
    return "pass", "ok"


def append_answered(entry: dict) -> None:
    with ANSWERED.open("a") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")


def process_one(path: Path) -> None:
    try:
        with path.open() as f:
            q = json.load(f)
    except (json.JSONDecodeError, OSError) as e:
        print(f"  bad pending file {path.name}: {e}", flush=True)
        path.unlink(missing_ok=True)
        return

    prompt = q.get("prompt", "").strip()
    if not prompt:
        path.unlink(missing_ok=True)
        return

    seen = already_answered()
    ph = prompt_hash(prompt)
    if ph in seen:
        # Drop duplicate; tester should see this hash already in answered.jsonl.
        print(f"  [dup]  {q.get('id','?')}  {prompt[:60]}", flush=True)
        shutil.move(str(path), str(DONE / path.name))
        return

    # Pre-filter (matches desktop runtime behavior)
    pre = pre_filter_decide(prompt)
    if not pre.passthrough:
        answer, elapsed = pre.response, 0.0
        via = pre.reason
    else:
        prompt_text = gemma_prompt(prompt)
        answer, elapsed = run_one(prompt_text, n_predict=256, timeout=180, n_ctx=8192)
        via = "model"

    g, notes = grade(answer, q.get("must_contain", []), q.get("must_not_contain", []))
    entry = {
        "id": q.get("id", path.stem),
        "tester": q.get("tester", "?"),
        "category": q.get("category", ""),
        "prompt": prompt,
        "prompt_hash": ph,
        "answer": answer,
        "elapsed_s": round(elapsed, 2),
        "via": via,
        "grade": g,
        "notes": notes,
        "answered_at": datetime.datetime.now().isoformat(timespec="seconds"),
    }
    append_answered(entry)
    print(f"  [{g:7s}] {q.get('id','?'):>8s} {q.get('tester','?'):8s} "
          f"{prompt[:55]:<55s}  t={elapsed:.1f}s", flush=True)
    shutil.move(str(path), str(DONE / path.name))


def main() -> int:
    if not LLAMA_COMPLETION.exists():
        print(f"missing GPU binary: {LLAMA_COMPLETION}", file=sys.stderr); return 2
    if not MODEL_PATH.exists():
        print(f"missing model: {MODEL_PATH}", file=sys.stderr); return 2

    REPO.mkdir(exist_ok=True)
    PENDING.mkdir(exist_ok=True)
    DONE.mkdir(exist_ok=True)
    ANSWERED.touch()

    print(f"GPU worker started — binary={LLAMA_COMPLETION.name}, model={MODEL_PATH.name}", flush=True)
    print(f"watching {PENDING}/  (drain to {ANSWERED.name})", flush=True)
    idle_ticks = 0
    while True:
        files = sorted(PENDING.glob("*.json"))
        if not files:
            idle_ticks += 1
            if idle_ticks % 60 == 0:  # every minute when idle
                print(f"  idle ({idle_ticks}s) — answered={sum(1 for _ in ANSWERED.open())}", flush=True)
            time.sleep(1)
            continue
        idle_ticks = 0
        for p in files[:1]:   # process one at a time — GPU is the bottleneck
            process_one(p)


if __name__ == "__main__":
    sys.exit(main())
