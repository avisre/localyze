#!/usr/bin/env python3
"""30 general questions across categories — fact, math, convert,
lifestyle, tech, advice, writing, explain, code. Same harness."""
from __future__ import annotations
import json, subprocess, time, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from run_general15b import (fire, ui_texts, extract_assistant, is_generating,
                            classify, WAIT_S, DEVICE)

QUESTIONS = [
    # ── Facts (8) ───────────────────────────────────────────────────────
    ("Q01_capital_de",  "What's the capital of Germany?", "fact"),
    ("Q02_chemical",    "What is the chemical formula for water?", "fact"),
    ("Q03_seasons",     "How many seasons are there in a year?", "fact"),
    ("Q04_continent",   "Which continent is Egypt in?", "fact"),
    ("Q05_olympics",    "How often are the Summer Olympics held?", "fact"),
    ("Q06_planet",      "Which planet is closest to the Sun?", "fact"),
    ("Q07_speed_sound", "What is the speed of sound in air?", "fact"),
    ("Q08_dna",         "What does DNA stand for?", "fact"),
    # ── Math (6) ────────────────────────────────────────────────────────
    ("Q09_simple_add",  "What is 47 plus 38?", "math"),
    ("Q10_percent",     "What is 15 percent of 200?", "math"),
    ("Q11_split_check", "Split a $90 dinner check between four people.", "math"),
    ("Q12_tip_calc",    "What is a 18 percent tip on a $65 meal?", "math"),
    ("Q13_sale_price",  "A laptop is $1200 with 15 percent off. What is the sale price?", "math"),
    ("Q14_speed_calc",  "If I walk 9 kilometers in 90 minutes, what is my speed in km per hour?", "math"),
    # ── Conversions (4) ─────────────────────────────────────────────────
    ("Q15_temp_conv",   "How many degrees Celsius is 75 degrees Fahrenheit?", "convert"),
    ("Q16_dist_conv",   "How many miles is 25 kilometers?", "convert"),
    ("Q17_height_conv", "How many centimeters is 6 feet?", "convert"),
    ("Q18_weight_conv", "How many grams is 3 ounces?", "convert"),
    # ── Lifestyle / health (4) ──────────────────────────────────────────
    ("Q19_water_day",   "How much water should I drink each day?", "lifestyle"),
    ("Q20_steps",       "How many steps a day are recommended?", "lifestyle"),
    ("Q21_pasta",       "How long do I cook spaghetti pasta?", "lifestyle"),
    ("Q22_freeze_chick","Can I refreeze chicken after thawing it?", "lifestyle"),
    # ── Tech (3) ────────────────────────────────────────────────────────
    ("Q23_clear_cache", "How do I clear the cache on my browser?", "tech"),
    ("Q24_storage",     "Why is my phone storage always full?", "tech"),
    ("Q25_2fa",         "What is two-factor authentication?", "tech"),
    # ── Writing (2) ─────────────────────────────────────────────────────
    ("Q26_meeting_req", "Write a short email asking my coworker to meet for 15 minutes tomorrow.", "writing"),
    ("Q27_birthday",    "Write a 3-sentence birthday message for a close friend.", "writing"),
    # ── Explain (2) ─────────────────────────────────────────────────────
    ("Q28_blockchain",  "Explain blockchain in simple terms.", "explain"),
    ("Q29_recession",   "What is a recession?", "explain"),
    # ── Code (1) ────────────────────────────────────────────────────────
    ("Q30_python_rev",  "Write a Python function that reverses a string.", "code"),
]


def main() -> None:
    out: list[dict] = []
    out_path = Path("general30_results.json")
    print(f"Running {len(QUESTIONS)} general questions on device {DEVICE}\n")

    for i, (tag, q, cat) in enumerate(QUESTIONS):
        force_stop = (i == 0)
        print(f"[{i+1:>2}/{len(QUESTIONS)} {tag}] {q}", flush=True)
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
        shot = f"/tmp/g30_{tag}.png"
        subprocess.call(["adb", "-s", DEVICE, "shell", "screencap", "/sdcard/s.png"])
        subprocess.call(["adb", "-s", DEVICE, "pull", "/sdcard/s.png", shot],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        rec = {"tag": tag, "category": cat, "prompt": q, "verdict": verdict,
               "answer": answer, "answer_len": len(answer),
               "elapsed_s": round(time.time() - t0, 1), "screenshot": shot}
        out.append(rec)
        out_path.write_text(json.dumps({"results": out}, indent=2))
        print(f"   -> {verdict} | {len(answer)} chars | {rec['elapsed_s']}s")
        if answer:
            print(f"      {answer.replace(chr(10), ' | ')[:200]}")

    print("\n" + "=" * 100)
    print(f"{'TAG':<18} {'CAT':<10} {'V':<10} {'LEN':>4}c  PROMPT")
    print("-" * 100)
    tally: dict[str, int] = {}
    for r in out:
        tally[r["verdict"]] = tally.get(r["verdict"], 0) + 1
        print(f"{r['tag']:<18} {r['category']:<10} {r['verdict']:<10} {r['answer_len']:>4}c  {r['prompt'][:48]}")
    print("-" * 100)
    print(f"Tally: {dict(sorted(tally.items()))}")
    print(f"Full results: {out_path.resolve()}")


if __name__ == "__main__":
    main()
