#!/usr/bin/env python3
"""25 questions a regular person would actually ask their phone:
quick facts, simple math, recipes, conversions, how-tos, tech basics,
writing help. Same harness as run_general15b.py."""
from __future__ import annotations
import json, re, subprocess, time, sys
from pathlib import Path
from urllib.parse import quote

sys.path.insert(0, str(Path(__file__).parent))
from run_general15b import (fire, ui_texts, extract_assistant, is_generating,
                            classify, WAIT_S, DEVICE)

QUESTIONS = [
    # ── Quick everyday facts ────────────────────────────────────────────
    ("Q01_dog_age",      "How long do dogs usually live?"),
    ("Q02_cup_oz",       "How many ounces are in a cup?"),
    ("Q03_capital_aus",  "What's the capital of Australia?"),
    ("Q04_year_today",   "What year is it?"),
    ("Q05_sleep_hours",  "How many hours of sleep do adults need?"),
    # ── Simple math / shopping ──────────────────────────────────────────
    ("Q06_tip",          "If a meal costs $48, what is a 20 percent tip?"),
    ("Q07_split_bill",   "Split a $135 bill three ways."),
    ("Q08_discount",     "A jacket is $80 with 25 percent off. What's the sale price?"),
    ("Q09_weight_conv",  "How many pounds is 70 kilograms?"),
    ("Q10_distance",     "How many kilometers is 50 miles?"),
    # ── Recipes / lifestyle ─────────────────────────────────────────────
    ("Q11_boil_egg",     "How long do I boil an egg for it to be soft-boiled?"),
    ("Q12_chicken_temp", "What internal temperature is chicken safe to eat at?"),
    ("Q13_fluffy_rice",  "How do I cook fluffy white rice on the stove?"),
    ("Q14_caffeine",     "How long does caffeine stay in your system?"),
    # ── Tech basics ─────────────────────────────────────────────────────
    ("Q15_wifi_pw",      "How do I find my Wi-Fi password on Android?"),
    ("Q16_screenshot",   "How do I take a screenshot on an iPhone 15?"),
    ("Q17_battery",      "Why does my phone battery drain so fast?"),
    # ── How-to / advice ─────────────────────────────────────────────────
    ("Q18_resume_tip",   "Give me 3 quick tips for writing a better resume."),
    ("Q19_hangover",     "What is a good way to get rid of a hangover?"),
    ("Q20_save_money",   "How can I save money on groceries each month?"),
    # ── Writing help ────────────────────────────────────────────────────
    ("Q21_email_sick",   "Write a short email to my boss saying I'm sick today and won't be in."),
    ("Q22_thx_note",     "Write a 2-sentence thank-you note for a wedding gift."),
    # ── Definitions / explainers ────────────────────────────────────────
    ("Q23_inflation",    "What is inflation in simple terms?"),
    ("Q24_protein",      "How much protein should I eat per day?"),
    ("Q25_passwd",       "Give me 3 rules for picking a strong password."),
]

CATEGORY = {
    "Q01_dog_age": "fact", "Q02_cup_oz": "fact", "Q03_capital_aus": "fact",
    "Q04_year_today": "fact", "Q05_sleep_hours": "fact",
    "Q06_tip": "math", "Q07_split_bill": "math", "Q08_discount": "math",
    "Q09_weight_conv": "convert", "Q10_distance": "convert",
    "Q11_boil_egg": "lifestyle", "Q12_chicken_temp": "lifestyle",
    "Q13_fluffy_rice": "lifestyle", "Q14_caffeine": "lifestyle",
    "Q15_wifi_pw": "tech", "Q16_screenshot": "tech", "Q17_battery": "tech",
    "Q18_resume_tip": "advice", "Q19_hangover": "advice", "Q20_save_money": "advice",
    "Q21_email_sick": "writing", "Q22_thx_note": "writing",
    "Q23_inflation": "explain", "Q24_protein": "advice", "Q25_passwd": "advice",
}


def main() -> None:
    out: list[dict] = []
    out_path = Path("regular25_results.json")
    print(f"Running {len(QUESTIONS)} regular-user questions on device {DEVICE}")
    print(f"Per-question wait: up to {WAIT_S}s\n")

    for i, (tag, q) in enumerate(QUESTIONS):
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
        shot = f"/tmp/r25_{tag}.png"
        subprocess.call(["adb", "-s", DEVICE, "shell", "screencap", "/sdcard/s.png"])
        subprocess.call(["adb", "-s", DEVICE, "pull", "/sdcard/s.png", shot],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        rec = {"tag": tag, "category": CATEGORY[tag], "prompt": q, "verdict": verdict,
               "answer": answer, "answer_len": len(answer),
               "elapsed_s": round(time.time() - t0, 1), "screenshot": shot}
        out.append(rec)
        out_path.write_text(json.dumps({"results": out}, indent=2))
        print(f"   -> {verdict} | {len(answer)} chars | {rec['elapsed_s']}s")
        if answer:
            print(f"      {answer.replace(chr(10), ' | ')[:200]}")

    print("\n" + "=" * 100)
    print(f"{'TAG':<18} {'CAT':<10} {'VERDICT':<10} {'LEN':>5}  PROMPT")
    print("-" * 100)
    tally: dict[str, int] = {}
    by_cat: dict[str, dict[str, int]] = {}
    for r in out:
        tally[r["verdict"]] = tally.get(r["verdict"], 0) + 1
        by_cat.setdefault(r["category"], {})
        by_cat[r["category"]][r["verdict"]] = by_cat[r["category"]].get(r["verdict"], 0) + 1
        print(f"{r['tag']:<18} {r['category']:<10} {r['verdict']:<10} "
              f"{r['answer_len']:>5}  {r['prompt'][:50]}")
    print("-" * 100)
    print(f"Tally: {dict(sorted(tally.items()))}")
    print("By category:")
    for cat in sorted(by_cat):
        print(f"  {cat:<10} {by_cat[cat]}")
    print(f"\nFull results: {out_path.resolve()}")


if __name__ == "__main__":
    main()
