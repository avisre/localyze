#!/usr/bin/env python3
"""Run 40 practical day-to-day questions on the device, strictly serial."""
from __future__ import annotations
import json, re, subprocess, time
from pathlib import Path
from urllib.parse import quote

DEVICE = "a5523839"
PKG = "com.localyze"
COMPONENT = "com.localyze/.MainActivity"
WAIT_S = 90


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


def classify(answer: str) -> str:
    if not answer:
        return "EMPTY"
    if "Quick question first" in answer or "go ahead" in answer:
        return "CLARIFIED"
    if len(answer) < 20:
        return "THIN"
    return "ANSWERED"


def main() -> None:
    questions = json.loads(Path("practical40.json").read_text())["questions"]
    out: list[dict] = []
    out_path = Path("practical40_results.json")
    for i, item in enumerate(questions):
        force_stop = (i == 0)
        tag = item["tag"]; q = item["q"]; cat = item.get("category", "?")
        print(f"\n[{i+1}/40 {tag} ({cat})] {q!r}", flush=True)
        fire(q, force_stop)
        time.sleep(WAIT_S)
        texts = ui_texts()
        answer = extract_assistant(texts, q)
        verdict = classify(answer)
        shot = f"/tmp/p40_{tag}.png"
        subprocess.call(["adb", "-s", DEVICE, "shell", "screencap", "/sdcard/s.png"])
        subprocess.call(["adb", "-s", DEVICE, "pull", "/sdcard/s.png", shot],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        rec = {
            "tag": tag, "category": cat, "prompt": q, "verdict": verdict,
            "answer": answer, "answer_len": len(answer), "screenshot": shot,
        }
        out.append(rec)
        out_path.write_text(json.dumps({"results": out}, indent=2))
        print(f"  -> {verdict}, {len(answer)} chars")
        if answer:
            print(f"     {answer[:140]!r}")

    print("\n" + "=" * 80)
    by_verdict = {}
    by_cat = {}
    for r in out:
        by_verdict[r["verdict"]] = by_verdict.get(r["verdict"], 0) + 1
        cat_v = (r["category"], r["verdict"])
        by_cat[cat_v] = by_cat.get(cat_v, 0) + 1
    print(f"Tally: {dict(sorted(by_verdict.items()))}")


if __name__ == "__main__":
    main()
