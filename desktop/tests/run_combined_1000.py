#!/usr/bin/env python3
"""Combined eval: Android 12-bank pool (778 deduped) + desktop bank250 (250 new).

After dedup against the Android pool, the merged set is ~1021 questions.
Runs them all on GPU (Vulkan llama-completion) through the same system
prompt + pre-filter the desktop app uses at runtime, and writes
desktop/tests/combined_1000_results.json.

Usage:
    run_combined_1000.py [--limit N] [--resume]
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from run_bank250 import LLAMA_COMPLETION, MODEL_PATH, gemma_prompt, run_one
from run_android_full import collect_all as collect_android, score
from pre_filter import decide as pre_filter_decide

REPO_ROOT = Path(__file__).resolve().parents[2]
DESKTOP_TESTS = Path(__file__).resolve().parent
RESULTS = DESKTOP_TESTS / "combined_1000_results.json"


def collect_combined() -> tuple[list[dict], list[str]]:
    android, gforb = collect_android()
    seen = {q["prompt"].lower().strip() for q in android}
    with (DESKTOP_TESTS / "bank250.json").open() as f:
        bk = json.load(f)
    gforb_set = set(gforb) | set(bk.get("global_forbidden_substrings", []))
    added = 0
    for q in bk["questions"]:
        key = q["prompt"].lower().strip()
        if key in seen:
            continue
        seen.add(key)
        android.append({
            "id": q["id"],
            "source": "bank250",
            "prompt": q["prompt"],
            "category": q.get("category", "uncategorized"),
            "expected_keywords": q.get("expected_keywords", []) or [],
            "must_contain":     [],
            "must_not_contain": q.get("must_not_contain", []) or [],
            "web_dependent":    False,
        })
        added += 1
    print(f"  added {added} new questions from bank250 (after dedup)", flush=True)
    return android, sorted(gforb_set)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--predict", type=int, default=192)
    ap.add_argument("--ctx", type=int, default=8192)
    ap.add_argument("--timeout", type=int, default=180)
    args = ap.parse_args()

    if not LLAMA_COMPLETION.exists():
        print(f"missing: {LLAMA_COMPLETION}", file=sys.stderr); return 2
    if not MODEL_PATH.exists():
        print(f"missing: {MODEL_PATH}", file=sys.stderr); return 2

    print(f"using: {LLAMA_COMPLETION}")
    print(f"backend: {'Vulkan (GPU)' if 'build-vulkan' in str(LLAMA_COMPLETION) else 'CPU'}")
    print()
    print("loading banks…")
    questions, global_forbidden = collect_combined()
    print(f"\ntotal questions (deduped): {len(questions)}")
    print(f"global forbidden substrings: {len(global_forbidden)}")
    if args.limit:
        questions = questions[:args.limit]

    existing: dict[str, dict] = {}
    if args.resume and RESULTS.exists():
        with RESULTS.open() as f:
            prev = json.load(f)
            for r in prev.get("results", []):
                existing[r["id"]] = r

    started = time.time()
    out_results: list[dict] = list(existing.values())
    pass_count = sum(1 for r in out_results if r.get("pass"))

    for i, q in enumerate(questions, 1):
        if args.resume and q["id"] in existing:
            continue
        pre = pre_filter_decide(q["prompt"])
        if not pre.passthrough:
            answer = pre.response
            elapsed = 0.0
            pre_tag = pre.reason
        else:
            prompt_text = gemma_prompt(q["prompt"])
            answer, elapsed = run_one(
                prompt_text, n_predict=args.predict, timeout=args.timeout,
                n_ctx=args.ctx,
            )
            pre_tag = "model"
        ok, detail = score(
            answer, q["expected_keywords"], q["must_contain"],
            q["must_not_contain"], global_forbidden,
        )
        if ok:
            pass_count += 1
        row = {
            "id": q["id"],
            "source": q["source"],
            "category": q["category"],
            "prompt": q["prompt"][:200],
            "answer": answer[:800],
            "elapsed_s": round(elapsed, 2),
            "pass": ok,
            "via": pre_tag,
            **detail,
        }
        out_results.append(row)
        eta = (time.time() - started) / max(1, i) * (len(questions) - i)
        mark = "✓" if ok else "✗"
        q_short = q["prompt"].replace("\n", " ")[:90]
        a_short = answer.replace("\n", " ").strip()[:120]
        print(
            f"[{i:4d}/{len(questions)}] {mark} {q['source']:14s} {pre_tag:9s}  "
            f"Q: {q_short}\n"
            f"          A: {a_short}\n"
            f"          → pass={pass_count}/{i}  t={elapsed:.1f}s  ETA={int(eta//60)}m{int(eta%60):02d}s",
            flush=True,
        )

        with RESULTS.open("w") as f:
            json.dump({
                "started_at": started,
                "duration_s": round(time.time() - started, 2),
                "completed": len(out_results),
                "pass_count": pass_count,
                "global_forbidden_substrings": global_forbidden,
                "results": out_results,
            }, f, indent=2)

    print(
        f"\nDone. {pass_count}/{len(out_results)} passed in "
        f"{int(time.time() - started)}s. Report: {RESULTS}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
