#!/usr/bin/env python3
"""Run every question that was used to evaluate the Android Localyze model
through the desktop GPU pipeline (Vulkan llama-completion + the same system
prompt the desktop app applies at runtime).

Loads all Android eval banks under the repo root (golden50_results.json,
eval50_*.json, full250_retest_bank.json, next250_bank.json, next150_bank.json,
web150_bank.json, fail48_bank.json, redteam20_bank.json, vague10_bank.json,
fresh10_bank.json, malayalam50_bank.json), deduplicates by exact prompt, and
scores each answer against expected_keywords / must_contain / must_not_contain
plus the per-bank global_forbidden_substrings.

Writes desktop/tests/android_eval_results.json with per-question outcomes and
per-source pass-rate breakdown.

Usage:
    run_android_full.py [--limit N] [--resume]
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

# Reuse the prompt / scoring / process-launch from the 250-bank harness so we
# get the exact same system prompt, preamble strip, and GPU args.
sys.path.insert(0, str(Path(__file__).parent))
from run_bank250 import (
    LLAMA_COMPLETION,
    MODEL_PATH,
    SYSTEM_PROMPT_CHAT,
    gemma_prompt,
    run_one,
    strip_thinking_preamble,
)
from pre_filter import decide as pre_filter_decide

REPO_ROOT = Path(__file__).resolve().parents[2]
DESKTOP_TESTS = Path(__file__).resolve().parent
RESULTS = DESKTOP_TESTS / "android_eval_results.json"

# Each bank: (filename, source-tag, accessor). Accessor returns a flat list of
# {id, prompt, expected_keywords, must_contain, must_not_contain, category}.

def _norm(item: dict, default_id: str, source: str) -> dict:
    return {
        "id": item.get("id", default_id),
        "source": source,
        "prompt": item["prompt"],
        "category": item.get("category", "uncategorized"),
        "expected_keywords": item.get("expected_keywords", []) or [],
        "must_contain":     item.get("must_contain", []) or [],
        "must_not_contain": item.get("must_not_contain", []) or [],
        "web_dependent":    bool(item.get("web_dependent", False)),
    }


def load_bank_questions(path: Path, source_tag: str) -> tuple[list[dict], list[str]]:
    with path.open() as f:
        d = json.load(f)
    gforb = d.get("global_forbidden_substrings", []) if isinstance(d, dict) else []
    out: list[dict] = []
    if isinstance(d, dict) and "questions" in d:
        for i, q in enumerate(d["questions"]):
            if "prompt" in q:
                out.append(_norm(q, f"{source_tag}_{i:03d}", source_tag))
    elif isinstance(d, dict) and "buckets" in d:
        # malayalam50_bank.json: list of {name, questions: [{prompt, ...}]}
        for b in d["buckets"]:
            for i, q in enumerate(b.get("questions", [])):
                if isinstance(q, dict) and "prompt" in q:
                    out.append(_norm({**q, "category": b.get("name", source_tag)},
                                     f"{source_tag}_{i:03d}", source_tag))
                elif isinstance(q, str):
                    out.append(_norm(
                        {"prompt": q, "category": b.get("name", source_tag)},
                        f"{source_tag}_{i:03d}", source_tag,
                    ))
    elif isinstance(d, dict) and "results" in d:
        # eval50_offline.json / eval50_online.json — already-evaluated
        # records; we extract the prompts only (column 'q' in this schema).
        for i, r in enumerate(d["results"]):
            p = r.get("q") or r.get("prompt")
            if not p:
                continue
            out.append({
                "id": f"{source_tag}_{i:03d}",
                "source": source_tag,
                "prompt": p,
                "category": r.get("tag", source_tag),
                "expected_keywords": [],
                "must_contain":     [],
                "must_not_contain": [],
                "web_dependent":    bool(r.get("web_search", False)),
            })
    elif isinstance(d, dict) and "records" in d:
        # golden50_results.json — keep prompts; no per-q scoring criteria, so
        # we only check global forbidden substrings + non-empty answer.
        for i, r in enumerate(d["records"]):
            out.append({
                "id": r.get("id", f"{source_tag}_{i:03d}"),
                "source": source_tag,
                "prompt": r["prompt"],
                "category": r.get("category", source_tag),
                "expected_keywords": [],
                "must_contain":     [],
                "must_not_contain": [],
                "web_dependent":    False,
            })
    return out, gforb


BANKS = [
    ("golden50_results.json",     "golden50"),
    ("eval50_offline.json",       "eval50_offline"),
    ("eval50_online.json",        "eval50_online"),
    ("full250_retest_bank.json",  "full250_retest"),
    ("next250_bank.json",         "next250"),
    ("next150_bank.json",         "next150"),
    ("web150_bank.json",          "web150"),
    ("fail48_bank.json",          "fail48"),
    ("redteam20_bank.json",       "redteam20"),
    ("vague10_bank.json",         "vague10"),
    ("fresh10_bank.json",         "fresh10"),
    ("malayalam50_bank.json",     "malayalam50"),
]


def collect_all() -> tuple[list[dict], list[str]]:
    questions: list[dict] = []
    global_forbidden: set[str] = set()
    seen_prompts: set[str] = set()
    for fname, tag in BANKS:
        path = REPO_ROOT / fname
        if not path.exists():
            print(f"  (skip missing) {fname}", flush=True)
            continue
        qs, gforb = load_bank_questions(path, tag)
        global_forbidden.update(gforb)
        kept = 0
        for q in qs:
            key = q["prompt"].strip().lower()
            if key in seen_prompts:
                continue
            seen_prompts.add(key)
            questions.append(q)
            kept += 1
        print(f"  loaded {fname}: {len(qs)} (kept {kept} after dedup)", flush=True)
    return questions, sorted(global_forbidden)


def score(answer: str, expected_keywords: list[str], must_contain: list[str],
          must_not_contain: list[str], global_forbidden: list[str]) -> tuple[bool, dict]:
    lo = answer.lower()
    hits = [k for k in expected_keywords if k.lower() in lo]
    missing = [k for k in expected_keywords if k.lower() not in lo]
    mc_hits   = [k for k in must_contain if k.lower() in lo]
    mc_missing = [k for k in must_contain if k.lower() not in lo]
    bans   = [k for k in must_not_contain if k.lower() in lo]
    g_bans = [k for k in global_forbidden if k.lower() in lo]

    # Pass criteria:
    #   - if expected_keywords nonempty → at least 1 hit
    #   - if must_contain nonempty       → ALL must hit
    #   - zero must_not_contain hits
    #   - zero global_forbidden hits
    #   - non-empty answer
    expected_ok = (not expected_keywords) or (len(hits) > 0)
    mc_ok       = (not must_contain) or (len(mc_missing) == 0)
    nonempty    = bool(answer.strip())
    pass_ = nonempty and expected_ok and mc_ok and (not bans) and (not g_bans)
    return pass_, {
        "matched_keywords": hits,
        "missed_keywords": missing,
        "must_contain_missing": mc_missing,
        "must_not_hits": bans,
        "global_forbidden_hits": g_bans,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0, help="run first N questions only (0 = all)")
    ap.add_argument("--resume", action="store_true",
                    help="merge with existing android_eval_results.json")
    ap.add_argument("--predict", type=int, default=192)
    ap.add_argument("--ctx", type=int, default=8192)
    ap.add_argument("--timeout", type=int, default=180)
    args = ap.parse_args()

    if not LLAMA_COMPLETION.exists():
        print(f"missing: {LLAMA_COMPLETION}", file=sys.stderr)
        return 2
    if not MODEL_PATH.exists():
        print(f"missing: {MODEL_PATH}", file=sys.stderr)
        return 2

    print(f"using: {LLAMA_COMPLETION}")
    print(f"model: {MODEL_PATH}")
    print(f"backend: {'Vulkan (GPU)' if 'build-vulkan' in str(LLAMA_COMPLETION) else 'CPU'}")
    print()
    print("loading banks…")
    questions, global_forbidden = collect_all()
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
        # Pre-filter: intercept obvious prompt-injection and vague openers
        # with canned safe responses, exactly the way the desktop app does at
        # runtime via SystemPromptBuilder + ClarificationOrchestrator.
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
        # One-line Q&A so the watcher sees each prompt and its answer.
        q_short = q["prompt"].replace("\n", " ")[:90]
        a_short = answer.replace("\n", " ").strip()[:120]
        print(
            f"[{i:4d}/{len(questions)}] {mark} {q['source']:14s} {pre_tag:7s}  "
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
