#!/usr/bin/env python3
"""Fire 10 diverse questions on the physical OnePlus phone (GPU backend)
and score against expected keywords. If 10/10 pass, the APK is judged
shareable."""
from __future__ import annotations
import json, re, subprocess, time, sys
from pathlib import Path
from urllib.parse import quote

sys.path.insert(0, str(Path(__file__).parent))
from run_general15b import CHROME, _norm

DEVICE = "a5523839"


def ui_texts(dev: str) -> list[str]:
    try:
        subprocess.check_call(
            ["adb", "-s", dev, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15,
        )
        out = subprocess.check_output(
            ["adb", "-s", dev, "shell", "cat", "/sdcard/ui.xml"],
            text=True, timeout=15,
        )
    except Exception:
        return []
    return re.findall(r'text="([^"]+)"', out)
PKG = "com.localyze"
COMPONENT = f"{PKG}/.MainActivity"
MAX_WAIT_S = 600

QUESTION_IDS = ["Q001","Q031","Q066","Q091","Q121","Q151","Q171","Q196","Q226","Q256"]


def fire(prompt: str) -> bool:
    subprocess.call(["adb", "-s", DEVICE, "shell", "am", "force-stop", PKG])
    time.sleep(1)
    encoded = quote(prompt).replace("%20", "%s")
    r = subprocess.run([
        "adb", "-s", DEVICE, "shell", "am", "start",
        "-n", COMPONENT,
        "--es", "chat_msg", encoded,
        "--ez", "force_cpu", "false",
        "--ez", "allow_web_search", "true",
    ], capture_output=True, text=True, timeout=15)
    return "does not exist" not in (r.stdout + r.stderr)


def extract_answer(prompt: str) -> str:
    target = _norm(prompt)
    seen: list[str] = []
    seen_set: set[str] = set()
    for _ in range(2):
        subprocess.call(["adb", "-s", DEVICE, "shell", "input", "swipe",
                        "540", "600", "540", "1900", "350"])
        time.sleep(0.4)
    for _ in range(3):
        for t in ui_texts(DEVICE):
            if t not in seen_set and t not in CHROME and "Generating" not in t:
                seen_set.add(t)
                seen.append(t)
        subprocess.call(["adb", "-s", DEVICE, "shell", "input", "swipe",
                        "540", "1800", "540", "600", "350"])
        time.sleep(0.4)
    out_lines: list[str] = []
    after_prompt = False
    found = any(_norm(t) == target for t in seen)
    for t in seen:
        if found and not after_prompt:
            if _norm(t) == target:
                after_prompt = True
            continue
        if t == "Message Localyze.ai...":
            break
        if not found and _norm(t) == target:
            continue
        out_lines.append(t)
    return "\n".join(out_lines).strip()


def main() -> None:
    bank = {q['id']: q for q in json.load(open('q500_bank.json'))}
    picks = [bank[i] for i in QUESTION_IDS]
    results: list[dict] = []
    for i, q in enumerate(picks, 1):
        t0 = time.time()
        subprocess.call(["adb", "-s", DEVICE, "logcat", "-c"])
        time.sleep(0.5)
        ok = fire(q['prompt'])
        if not ok:
            print(f"[{i}/10] {q['id']} !! intent not delivered", flush=True)
            continue
        completed = False
        while time.time() - t0 < MAX_WAIT_S:
            time.sleep(2.0)
            try:
                log = subprocess.check_output(
                    ["adb", "-s", DEVICE, "logcat", "-d",
                     "ChatViewModel:V", "*:S"],
                    text=True, timeout=8
                )
            except Exception:
                continue
            if "doSendMessage" in log and "handleResponseEvent: Completed" in log.split("doSendMessage")[-1]:
                completed = True
                break
        time.sleep(2.0)
        answer = extract_answer(q['prompt'])
        elapsed = round(time.time() - t0, 1)
        kws = q.get('expected_keywords') or []
        kw_hit = any(k.lower() in answer.lower() for k in kws) if kws else None
        verdict = "PASS" if kw_hit else "FAIL" if kw_hit is False else "N/A"
        results.append({
            'id': q['id'], 'cat': q['category'], 'prompt': q['prompt'],
            'kws': kws, 'answer': answer, 'elapsed_s': elapsed,
            'verdict': verdict, 'completed': completed,
        })
        print(f"[{i}/10] {q['id']} {q['category']} | {elapsed}s | {verdict}", flush=True)
        print(f"   Q: {q['prompt']}")
        print(f"   A: {answer[:200]}", flush=True)
    Path('phone10_results.json').write_text(json.dumps(results, indent=2))
    passed = sum(1 for r in results if r['verdict'] == 'PASS')
    print(f"\n=== {passed}/{len(results)} PASSED ===")


if __name__ == "__main__":
    main()
