#!/usr/bin/env python3
"""15-question live clarification test. Fires each prompt, waits 60s,
extracts the assistant message, categorizes the behavior."""
from __future__ import annotations
import json, re, subprocess, time
from pathlib import Path
from urllib.parse import quote

DEVICE = "a5523839"
PKG = "com.localyze"
COMPONENT = "com.localyze/.MainActivity"
WAIT_S = 60

# (tag, question, expectation)
#   "clarify" — orchestrator should ask follow-ups
#   "pass"    — should answer directly
QUESTIONS = [
    ("Q01_news_top",     "top 10 news",                      "clarify"),
    ("Q02_phone",        "best laptop",                       "clarify"),
    ("Q03_finance_help", "help me with my finances",          "clarify"),
    ("Q04_movie",        "recommend a movie",                 "clarify"),
    ("Q05_python_about", "tell me about Python",              "clarify"),
    ("Q06_trip",         "plan my trip",                      "clarify"),
    ("Q07_learn",        "what should I learn",               "clarify"),
    ("Q08_advice",       "give me advice",                    "clarify"),
    ("Q09_restaurant",   "best restaurant",                   "clarify"),
    ("Q10_stock",        "recommend a stock",                 "clarify"),
    ("Q11_capital",      "what is the capital of Japan",      "pass"),
    ("Q12_math",         "what is 25 percent of 80",          "pass"),
    ("Q13_weather",      "whats the weather",                 "clarify"),
    ("Q14_lang",         "best programming language",         "clarify"),
    ("Q15_specific",
     "I want to start an ecommerce business in India with a $5000 budget. What should I focus on first?",
     "pass"),
]


def fire(prompt: str, force_stop: bool) -> None:
    if force_stop:
        subprocess.call(["adb", "-s", DEVICE, "shell", "am", "force-stop", PKG])
        time.sleep(1)
    encoded = quote(prompt).replace("%20", "%s")
    subprocess.call([
        "adb", "-s", DEVICE, "shell", "am", "start",
        "-n", COMPONENT, "--es", "chat_msg", encoded,
    ])


def ui_texts() -> list[str]:
    try:
        subprocess.check_call(
            ["adb", "-s", DEVICE, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15,
        )
        out = subprocess.check_output(
            ["adb", "-s", DEVICE, "shell", "cat", "/sdcard/ui.xml"],
            text=True, timeout=15,
        )
    except Exception:
        return []
    return re.findall(r'text="([^"]+)"', out)


def extract_assistant(texts: list[str], prompt: str) -> str:
    chrome = {
        "Localyze.ai", "Localyze....", "On-device  Context aware",
        "Chat", "Code", "Library", "Settings",
        "Message Localyze.ai...", "just now", "Web search complete", "-",
    }
    out_lines = []
    seen_user = False
    for t in texts:
        if t == prompt:
            seen_user = True
            continue
        if not seen_user:
            continue
        if t in chrome:
            continue
        out_lines.append(t)
    return "\n".join(out_lines).strip()


def classify(answer: str, expectation: str) -> str:
    is_clarify = "Quick question first" in answer or "go ahead" in answer
    if expectation == "clarify":
        return "PASS_clarified" if is_clarify else "FAIL_no_clarify"
    else:  # pass
        if not answer:
            return "FAIL_empty"
        if is_clarify:
            return "WARN_unexpected_clarify"
        if len(answer) < 15:
            return "WARN_thin_answer"
        return "PASS_answered"


def main() -> None:
    out: list[dict] = []
    out_path = Path("clarify15_results.json")
    for i, (tag, q, expected) in enumerate(QUESTIONS):
        force_stop = (i == 0)
        print(f"\n[{i+1}/15 {tag}] {q!r} (expects: {expected})", flush=True)
        fire(q, force_stop)
        time.sleep(WAIT_S)
        texts = ui_texts()
        answer = extract_assistant(texts, q)
        verdict = classify(answer, expected)
        # Screenshot
        shot = f"/tmp/c15_{tag}.png"
        subprocess.call(["adb", "-s", DEVICE, "shell", "screencap", "/sdcard/s.png"])
        subprocess.call(["adb", "-s", DEVICE, "pull", "/sdcard/s.png", shot],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        rec = {
            "tag": tag, "prompt": q, "expected": expected, "verdict": verdict,
            "answer": answer, "answer_len": len(answer), "screenshot": shot,
        }
        out.append(rec)
        out_path.write_text(json.dumps({"results": out}, indent=2))
        print(f"  -> {verdict}, {len(answer)} chars")
        if answer:
            print(f"     {answer[:140]!r}")

    # Summary
    print("\n" + "=" * 80)
    print(f"{'TAG':<22} {'EXPECTED':<10} {'VERDICT':<22} {'LEN':>5}")
    print("-" * 80)
    by_verdict = {}
    for r in out:
        by_verdict[r["verdict"]] = by_verdict.get(r["verdict"], 0) + 1
        print(f"{r['tag']:<22} {r['expected']:<10} {r['verdict']:<22} {r['answer_len']:>5}")
    print("-" * 80)
    print(f"Tally: {dict(sorted(by_verdict.items()))}")


if __name__ == "__main__":
    main()
