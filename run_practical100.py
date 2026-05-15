#!/usr/bin/env python3
"""Run 100 practical questions on the device.

Smart polling: instead of waiting a fixed 90s per question, poll the UI
every 5s and break as soon as the assistant message bubble has stable
non-empty text for 2 polls. Vague openers that hit the orchestrator
return in ~5-10s; pass-throughs to Gemma get up to MAX_WAIT_S.
"""
from __future__ import annotations
import json, re, subprocess, time
from pathlib import Path
from urllib.parse import quote

DEVICE = "a5523839"
PKG = "com.localyze"
COMPONENT = "com.localyze/.MainActivity"
MAX_WAIT_S = 90       # cap per question
POLL_INTERVAL_S = 5
STABLE_POLLS = 2      # require this many identical polls before declaring done


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


CHROME = {
    "Localyze.ai", "Localyze....", "On-device  Context aware",
    "Chat", "Code", "Library", "Settings",
    "Message Localyze.ai...", "just now", "Web search complete", "-",
}


def extract_assistant(texts: list[str], prompt: str) -> str:
    out_lines = []
    seen_user = False
    for t in texts:
        if t == prompt:
            seen_user = True
            continue
        if not seen_user:
            continue
        if t in CHROME:
            continue
        out_lines.append(t)
    return "\n".join(out_lines).strip()


def classify(answer: str) -> str:
    if not answer:
        return "EMPTY"
    if "Quick question first" in answer or "go ahead" in answer:
        return "CLARIFIED"
    if len(answer) < 20:
        return "THIN"
    return "ANSWERED"


def run_one(tag: str, prompt: str, force_stop: bool) -> tuple[str, str, float]:
    """Returns (answer, verdict, elapsed_s). Polls until stable or MAX_WAIT_S."""
    fire(prompt, force_stop)
    start = time.time()
    last_answer = ""
    stable_count = 0
    while time.time() - start < MAX_WAIT_S:
        time.sleep(POLL_INTERVAL_S)
        texts = ui_texts()
        cur = extract_assistant(texts, prompt)
        # Done if we have stable non-empty content
        if cur and cur == last_answer:
            stable_count += 1
            if stable_count >= STABLE_POLLS:
                break
        else:
            stable_count = 0
            last_answer = cur
    elapsed = round(time.time() - start, 1)
    answer = last_answer
    return answer, classify(answer), elapsed


def main() -> None:
    questions = json.loads(Path("practical100.json").read_text())["questions"]
    out: list[dict] = []
    out_path = Path("practical100_results.json")
    for i, item in enumerate(questions):
        force_stop = (i == 0)
        tag = item["tag"]; q = item["q"]; cat = item.get("category", "?")
        print(f"\n[{i+1}/100 {tag} ({cat})] {q!r}", flush=True)
        try:
            answer, verdict, elapsed = run_one(tag, q, force_stop)
        except Exception as e:
            answer, verdict, elapsed = "", "EXCEPTION", -1
            print(f"  EXCEPTION: {e}")
        shot = f"/tmp/p100_{tag}.png"
        subprocess.call(["adb", "-s", DEVICE, "shell", "screencap", "/sdcard/s.png"])
        subprocess.call(["adb", "-s", DEVICE, "pull", "/sdcard/s.png", shot],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        rec = {
            "tag": tag, "category": cat, "prompt": q, "verdict": verdict,
            "answer": answer, "answer_len": len(answer),
            "elapsed_s": elapsed, "screenshot": shot,
        }
        out.append(rec)
        out_path.write_text(json.dumps({"results": out}, indent=2))
        print(f"  -> {verdict}, {len(answer)} chars, {elapsed}s")
        if answer:
            print(f"     {answer[:140]!r}")

    # Summary
    by_verdict: dict[str, int] = {}
    by_cat_verdict: dict[tuple, int] = {}
    for r in out:
        by_verdict[r["verdict"]] = by_verdict.get(r["verdict"], 0) + 1
        key = (r["category"], r["verdict"])
        by_cat_verdict[key] = by_cat_verdict.get(key, 0) + 1
    print("\n" + "=" * 80)
    print(f"Tally: {dict(sorted(by_verdict.items()))}")


if __name__ == "__main__":
    main()
