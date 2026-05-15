#!/usr/bin/env python3
"""
Run all 35 practical questions on the attached device through the chat tab.

For each question:
  1. Force-stop the app (Q1 only — keeps the model warm for the rest)
  2. Fire chat_msg intent with the question
  3. Poll uiautomator dump every 5s for the assistant message bubble
  4. Stop polling when the assistant text is non-empty + stable for 2 polls
  5. Capture screenshot, save assistant text, append to JSON

Output: practical35_results.json + per-question screenshots in /tmp/p35_*.png
"""
from __future__ import annotations
import json, re, subprocess, time
from pathlib import Path
from urllib.parse import quote

DEVICE = "a5523839"
PKG = "com.localyze"
COMPONENT = "com.localyze/.MainActivity"
PER_Q_TIMEOUT = 240   # 4 min per question
POLL_INTERVAL = 5
STABLE_POLLS = 2


def fire(prompt: str, force_stop: bool) -> None:
    if force_stop:
        subprocess.call(["adb", "-s", DEVICE, "shell", "am", "force-stop", PKG])
        time.sleep(1)
    encoded = quote(prompt).replace("%20", "%s")
    subprocess.call([
        "adb", "-s", DEVICE, "shell", "am", "start",
        "-n", COMPONENT,
        "--es", "chat_msg", encoded,
    ])


def ui_dump_texts() -> list[str]:
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


def extract_assistant_text(texts: list[str], user_prompt: str) -> str:
    """The assistant message bubble is the LAST text node that's not the
    user's own prompt, not a UI chrome label, and not 'just now'/'streaming'.
    """
    chrome = {
        "Localyze.ai", "Localyze....", "On-device  Context aware",
        "Chat", "Code", "Library", "Settings", "Message Localyze.ai...",
        "just now", "On-device", "Web search complete",
    }
    candidates = []
    for t in texts:
        if t == user_prompt:                       continue
        if t in chrome:                            continue
        if t.startswith("On-device"):              continue
        if t.endswith("ago"):                      continue
        if t.startswith("Localyze"):               continue
        if t.lower() in {"sending...", "thinking..."}: continue
        candidates.append(t)
    # Last candidate is most-recent assistant bubble
    return candidates[-1] if candidates else ""


def run_one(tag: str, prompt: str, force_stop: bool) -> dict:
    print(f"\n[{tag}] {prompt[:80]}", flush=True)
    fire(prompt, force_stop)
    start = time.time()
    last_text = ""
    stable_count = 0
    while time.time() - start < PER_Q_TIMEOUT:
        time.sleep(POLL_INTERVAL)
        texts = ui_dump_texts()
        cur = extract_assistant_text(texts, prompt)
        if cur and cur == last_text:
            stable_count += 1
            if stable_count >= STABLE_POLLS and len(cur) > 10:
                break
        else:
            stable_count = 0
            last_text = cur
    elapsed = round(time.time() - start, 1)
    # Final screenshot
    shot = f"/tmp/p35_{tag}.png"
    subprocess.call(["adb", "-s", DEVICE, "shell", "screencap", "/sdcard/s.png"])
    subprocess.call(["adb", "-s", DEVICE, "pull", "/sdcard/s.png", shot],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    answer = last_text or ""
    asks_clarify = bool(re.search(
        r"Quick question first|follow.?up question|clarif|^1\.\s|please clarify",
        answer, re.IGNORECASE
    ))
    print(f"  -> {elapsed}s, {len(answer)} chars, asks_clarify={asks_clarify}")
    if answer:
        print(f"     {answer[:160]!r}")
    return {
        "tag": tag, "prompt": prompt, "elapsed_s": elapsed,
        "answer": answer, "answer_len": len(answer),
        "asks_clarify": asks_clarify, "screenshot": shot,
    }


def main() -> None:
    questions = json.loads(Path("practical35.json").read_text())["questions"]
    results: list[dict] = []
    out_path = Path("practical35_results.json")
    for i, item in enumerate(questions):
        force_stop = (i == 0)
        try:
            rec = run_one(item["tag"], item["q"], force_stop)
            rec["category"] = item.get("category", "?")
        except Exception as e:
            rec = {
                "tag": item["tag"], "prompt": item["q"], "category": item.get("category", "?"),
                "elapsed_s": None, "answer": "", "answer_len": 0,
                "asks_clarify": False, "error": str(e), "screenshot": None,
            }
            print(f"  EXCEPTION: {e}", flush=True)
        results.append(rec)
        out_path.write_text(json.dumps({"results": results}, indent=2))
    # Summary
    print("\n" + "="*80)
    print(f"{'TAG':<22} {'CAT':<10} {'TIME':>7} {'LEN':>5} {'CLARIFY':>8}")
    print("-"*80)
    for r in results:
        elapsed = f"{r['elapsed_s']}s" if r['elapsed_s'] is not None else "ERR"
        print(f"{r['tag']:<22} {r.get('category','?'):<10} {elapsed:>7} "
              f"{r['answer_len']:>5} {str(r.get('asks_clarify', False)):>8}")


if __name__ == "__main__":
    main()
