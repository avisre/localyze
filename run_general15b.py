#!/usr/bin/env python3
"""Second batch of 15 general questions on the patched build.
Same harness as run_general15.py — different question set covering
broader categories so we don't just re-test the same surface."""
from __future__ import annotations
import json, re, subprocess, time
from pathlib import Path
from urllib.parse import quote

DEVICE = "emulator-5554"
PKG = "com.localyze"
COMPONENT = "com.localyze/.MainActivity"
WAIT_S = 240  # Emulator runs the CPU backend; raise per-question budget.

QUESTIONS = [
    # ── Knowledge / facts ────────────────────────────────────────────
    ("Q16_river",     "What is the longest river in the world?"),
    ("Q17_element",   "What is the chemical symbol for gold?"),
    ("Q18_author",    "Who wrote the novel 1984?"),
    ("Q19_war",       "In what year did World War II end?"),
    ("Q20_currency",  "What is the currency of Japan?"),
    # ── Math / reasoning ─────────────────────────────────────────────
    ("Q21_pct",       "What is 20 percent of 250?"),
    ("Q22_speed",     "If I drive 240 miles in 4 hours, what is my average speed?"),
    ("Q23_logic",     "If all roses are flowers and some flowers fade quickly, can we conclude that some roses fade quickly? Yes or no, and one short reason."),
    # ── Code / technical ─────────────────────────────────────────────
    ("Q24_python",    "Write a Python one-liner that returns the squares of numbers 1 to 5 as a list."),
    ("Q25_sql",       "Write a SQL query to get the second highest salary from a table named Employee with a column salary."),
    # ── Writing / structured ─────────────────────────────────────────
    ("Q26_summary",   "Summarize the plot of Romeo and Juliet in 3 sentences."),
    ("Q27_tips",      "List 4 short tips for improving sleep quality, one per bullet."),
    # ── Practical advice ─────────────────────────────────────────────
    ("Q28_pack",      "I am going to Tokyo for 5 days in winter. List 6 essential items to pack."),
    # ── Definition / explanation ─────────────────────────────────────
    ("Q29_quantum",   "Explain quantum entanglement in plain English in under 60 words."),
    ("Q30_dns",       "How does DNS resolve a domain name? Explain in 4 short bullets."),
]

CHROME = {
    # App chrome
    "Localyze.ai", "Localyze....", "On-device  Context aware",
    "On-device", "Context aware",
    "Chat", "Code", "Library", "Settings", "Capabilities",
    "Message Localyze.ai...", "just now",
    "New conversation", "Ask anything",
    # Bullet/divider markers rendered as their own Text composables
    "-",
    # Tool indicator pills (ToolIndicator.kt completedLabel/executingLabel).
    # These are NOT part of the assistant's text — they're a UI badge
    # rendered above/beside the message bubble. Including them in the
    # extracted answer makes responses look truncated (e.g. Q21 starts
    # with "Calculated / 50.0 is 20%…").
    "Web search complete", "Calculated", "Memory checked", "File read",
    "Calendar checked", "Contact found", "Alarm set", "Clipboard ready",
    "System info read", "Task updated", "Email drafted", "SMS drafted",
    "Searching the web…", "Calculating…", "Checking memory…",
    "Reading file…", "Reading calendar…", "Looking up contact…",
    "Setting alarm…", "Reading clipboard…", "Reading system info…",
    "Updating tasks…", "Drafting email…", "Drafting SMS…",
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
    return re.sub(r"[^a-z0-9]+", "", s.lower())


def _extract_after_prompt(texts: list[str], prompt: str) -> str:
    """Given a dump that includes the prompt, return text after it
    minus chrome and tool-indicator pills."""
    target = _norm(prompt)
    found_prompt = any(_norm(t) == target for t in texts)
    out_lines: list[str] = []
    seen_user = False
    for t in texts:
        if found_prompt and not seen_user:
            if _norm(t) == target:
                seen_user = True
            continue
        if t in CHROME:
            continue
        if "Generating" in t:
            continue
        # In fallback mode (no prompt anchor), still skip a stray prompt-match.
        if not found_prompt and _norm(t) == target:
            continue
        # Stop once we hit the input bar.
        if t == "Message Localyze.ai...":
            break
        out_lines.append(t)
    return "\n".join(out_lines).strip()


def extract_assistant(texts: list[str], prompt: str) -> str:
    """If the prompt is visible in the dump, return text after it.
    Otherwise fall back to all visible non-chrome text — that's the
    assistant response on its own.

    (We previously tried scroll-up swipes when the prompt wasn't found,
    but the swipes occasionally pulled the device away from the chat
    activity entirely. The fallback path captures responses cleanly
    without the swipe risk.)"""
    return _extract_after_prompt(texts, prompt)


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
    out_path = Path("general15b_results.json")
    print(f"Running {len(QUESTIONS)} additional general questions on device {DEVICE}")
    print(f"Per-question wait: {WAIT_S}s  (~{len(QUESTIONS) * WAIT_S // 60} min total)\n")

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
        shot = f"/tmp/g15b_{tag}.png"
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
