#!/usr/bin/env python3
"""Ask 15 general questions on the connected Android device and capture
each assistant response from the UI. Adapted from run_clarify15.py."""
from __future__ import annotations
import json, re, subprocess, time
from pathlib import Path
from urllib.parse import quote

DEVICE = "a5523839"
PKG = "com.localyze"
COMPONENT = "com.localyze/.MainActivity"
WAIT_S = 75  # generous wait for on-device inference

QUESTIONS = [
    ("Q01_capital",   "What is the capital of France?"),
    ("Q02_continents","How many continents are there on Earth?"),
    ("Q03_artist",    "Who painted the Mona Lisa?"),
    ("Q04_math_div",  "What is 144 divided by 12?"),
    ("Q05_moon",      "What year did humans first land on the Moon?"),
    ("Q06_joke",      "Tell me a short, family-friendly joke."),
    ("Q07_photo",     "Explain photosynthesis in one sentence."),
    ("Q08_breakfast", "List 3 healthy breakfast ideas, one bullet each."),
    ("Q09_speed",     "What is the speed of light in kilometers per second?"),
    ("Q10_translate", "Translate 'good morning' into Spanish."),
    ("Q11_hamlet",    "Who wrote the play Hamlet?"),
    ("Q12_mammal",    "What is the largest mammal on Earth?"),
    ("Q13_temp",      "Convert 100 degrees Fahrenheit to Celsius."),
    ("Q14_primes",    "Name 3 prime numbers between 10 and 30."),
    ("Q15_http",      "What does HTTP stand for?"),
]

CHROME = {
    "Localyze.ai", "Localyze....", "On-device  Context aware",
    "On-device", "Context aware",
    "Chat", "Code", "Library", "Settings", "Capabilities",
    "Message Localyze.ai...", "just now", "Web search complete", "-",
    "New conversation", "Ask anything",
}


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


def _norm(s: str) -> str:
    """Normalize for fuzzy prompt matching: lowercase + strip non-alnum."""
    return re.sub(r"[^a-z0-9]+", "", s.lower())


def extract_assistant(texts: list[str], prompt: str) -> str:
    """Find the user prompt in the text dump (fuzzy: ignore quote/punct
    differences from smart-quoting / xml-escaping), then return everything
    after it minus chrome and generating indicators."""
    target = _norm(prompt)
    out_lines: list[str] = []
    seen_user = False
    for t in texts:
        if not seen_user and _norm(t) == target:
            seen_user = True
            continue
        if not seen_user:
            continue
        if t in CHROME:
            continue
        if "Generating" in t:
            continue
        out_lines.append(t)
    return "\n".join(out_lines).strip()


def is_generating(texts: list[str]) -> bool:
    return any("Generating" in t for t in texts)


def classify(answer: str) -> str:
    if not answer:
        return "EMPTY"
    if "Quick question first" in answer or "Could you tell me more" in answer:
        return "CLARIFIED"
    if "wasn't able to generate" in answer.lower() or "sorry" in answer.lower()[:40]:
        return "FALLBACK"
    if len(answer) < 15:
        return "THIN"
    return "ANSWERED"


def main() -> None:
    out: list[dict] = []
    out_path = Path("general15_results.json")
    print(f"Running {len(QUESTIONS)} general questions on device {DEVICE}")
    print(f"Per-question wait: {WAIT_S}s  (~{len(QUESTIONS) * WAIT_S // 60} min total)\n")

    for i, (tag, q) in enumerate(QUESTIONS):
        force_stop = (i == 0)
        print(f"[{i+1:>2}/{len(QUESTIONS)} {tag}] {q}", flush=True)
        fire(q, force_stop)

        # Poll UI: stop early if response is stable for ~10s, else cap at WAIT_S.
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
            # Done: not generating, have content, stable >= 10s
            if not gen and cur and (time.time() - last_change) >= 10.0:
                break
        answer = last

        verdict = classify(answer)
        shot = f"/tmp/g15_{tag}.png"
        subprocess.call(["adb", "-s", DEVICE, "shell", "screencap", "/sdcard/s.png"])
        subprocess.call(["adb", "-s", DEVICE, "pull", "/sdcard/s.png", shot],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        rec = {
            "tag": tag, "prompt": q, "verdict": verdict,
            "answer": answer, "answer_len": len(answer),
            "elapsed_s": round(time.time() - t0, 1), "screenshot": shot,
        }
        out.append(rec)
        out_path.write_text(json.dumps({"results": out}, indent=2))
        print(f"   -> {verdict} | {len(answer)} chars | {rec['elapsed_s']}s")
        if answer:
            preview = answer.replace("\n", " | ")[:200]
            print(f"      {preview}")

    # Summary
    print("\n" + "=" * 90)
    print(f"{'TAG':<18} {'VERDICT':<14} {'LEN':>5}  PROMPT")
    print("-" * 90)
    tally: dict[str, int] = {}
    for r in out:
        tally[r["verdict"]] = tally.get(r["verdict"], 0) + 1
        print(f"{r['tag']:<18} {r['verdict']:<14} {r['answer_len']:>5}  {r['prompt'][:55]}")
    print("-" * 90)
    print(f"Tally: {dict(sorted(tally.items()))}")
    print(f"\nFull results: {out_path.resolve()}")


if __name__ == "__main__":
    main()
