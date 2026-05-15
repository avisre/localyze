#!/usr/bin/env python3
"""Aggregate per-batch Claude-judge scores into a single report.

Reads judge_batches/manifest.json and the corresponding
batch_XXX_scored.json files (one per batch — JSON array of score entries)
and emits judged_results.json + a printable summary.
"""
from __future__ import annotations
import argparse, json, statistics
from collections import defaultdict
from pathlib import Path


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--results", required=True, help="run_eval results JSON")
    ap.add_argument("--batches", required=True, help="judge_batches/ dir")
    ap.add_argument("--out", required=True, help="judged JSON output")
    args = ap.parse_args()

    bdir = Path(args.batches)
    manifest = json.load(open(bdir / "manifest.json"))
    eval_data = json.load(open(args.results))
    by_id = {r["id"]: r for r in eval_data["records"]}

    scores: dict[str, dict] = {}
    for entry in manifest:
        scored_path = bdir / f"batch_{entry['batch']:03d}_scored.json"
        if not scored_path.exists():
            print(f"!! missing {scored_path} — re-run judge for that batch")
            continue
        for s in json.load(open(scored_path)):
            scores[s["id"]] = s

    merged: list[dict] = []
    for qid, rec in by_id.items():
        merged.append({**rec, "judge": scores.get(qid)})
    Path(args.out).write_text(json.dumps({"records": merged}, indent=2))

    # ===== Aggregate report =====
    judged = [r for r in merged if r["judge"]]
    by_cat = defaultdict(list)
    for r in judged:
        by_cat[r["category"]].append(r)

    print(f"\n=== JUDGED: {len(judged)}/{len(merged)} records have scores ===\n")
    print(f"{'Category':<30} {'N':>3} {'Help':>5} {'Acc':>5} {'Safe':>5} {'Read':>5} {'AssertPass':>10}")
    print("-" * 75)

    def avg(rs, key):
        vs = [r["judge"][key] for r in rs if r["judge"].get(key) is not None]
        return statistics.mean(vs) if vs else 0

    for cat in sorted(by_cat):
        rs = by_cat[cat]
        passes = sum(1 for r in rs if r["assert_status"] == "PASS")
        print(f"{cat:<30} {len(rs):>3} "
              f"{avg(rs,'helpfulness'):>5.2f} {avg(rs,'accuracy'):>5.2f} "
              f"{avg(rs,'safety'):>5.2f} {avg(rs,'readability'):>5.2f} "
              f"{passes:>2}/{len(rs):>2}")

    print("-" * 75)
    overall = judged
    overall_pass = sum(1 for r in overall if r["assert_status"] == "PASS")
    print(f"{'OVERALL':<30} {len(overall):>3} "
          f"{avg(overall,'helpfulness'):>5.2f} {avg(overall,'accuracy'):>5.2f} "
          f"{avg(overall,'safety'):>5.2f} {avg(overall,'readability'):>5.2f} "
          f"{overall_pass:>2}/{len(overall):>2}")

    # Issue frequency
    issue_counts = defaultdict(int)
    for r in judged:
        for issue in r["judge"].get("issues", []) or []:
            issue_counts[issue] += 1
    if issue_counts:
        print("\nIssue frequency:")
        for k, v in sorted(issue_counts.items(), key=lambda x: -x[1]):
            print(f"  {k}: {v}")

    # Property-assertion fails (always worth flagging)
    fails = [r for r in merged if r["assert_status"] == "FAIL"]
    if fails:
        print(f"\nProperty-assertion FAILs ({len(fails)}):")
        for r in fails:
            print(f"  {r['id']} {r.get('fingerprint','')}: hits={r['assert_hits']}")


if __name__ == "__main__":
    main()
