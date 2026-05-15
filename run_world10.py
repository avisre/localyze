#!/usr/bin/env python3
"""One representative company per region — 10 companies covering
US, India, China, UK, Germany, France, Switzerland, Italy, Canada, Mexico."""
from __future__ import annotations
import json, subprocess, time, sys, re
from pathlib import Path
from urllib.parse import quote

sys.path.insert(0, str(Path(__file__).parent))
from run_general15b import ui_texts, CHROME

DEVICE = "emulator-5554"
PKG = "com.localyze"
WAIT_S = 90

QUESTIONS = [
    ("US_tesla",      "Tesla revenue over the past 3 years"),
    ("IN_reliance",   "Reliance revenue for the last 3 fiscal years"),
    ("CN_byd",        "BYD revenue over the past 3 years"),
    ("UK_gsk",        "GSK revenue for the last 3 years"),
    ("DE_volkswagen", "Volkswagen revenue for the past 3 years"),
    ("FR_totale",     "TotalEnergies revenue last 3 years"),
    ("CH_roche",      "Roche revenue for the past 3 years"),
    ("IT_stellantis", "Stellantis revenue last 3 years"),
    ("CA_enbridge",   "Enbridge revenue for the past 3 years"),
    ("MX_femsa",      "FEMSA revenue last 3 years"),
]


def fire_and_wait(prompt: str) -> tuple[str, dict]:
    subprocess.call(["adb", "-s", DEVICE, "shell", "am", "force-stop", PKG])
    time.sleep(0.8)
    encoded = quote(prompt).replace("%20", "%s")
    subprocess.call(["adb", "-s", DEVICE, "shell", "am", "start",
                     "-n", f"{PKG}/.MainActivity",
                     "--es", "chat_msg", encoded,
                     "--ez", "force_cpu", "true"])
    t0 = time.time()
    while time.time() - t0 < WAIT_S:
        time.sleep(3.0)
        try:
            log = subprocess.check_output(
                ["adb", "-s", DEVICE, "logcat", "-d", "ChatViewModel:V", "*:S"],
                text=True, timeout=8
            )
        except Exception:
            log = ""
        if "handleResponseEvent: Completed" in log.split("doSendMessage")[-1]:
            break
    # Scroll up + collect from multiple positions
    for _ in range(3):
        subprocess.call(["adb", "-s", DEVICE, "shell", "input", "swipe",
                        "540", "600", "540", "1900", "350"])
        time.sleep(0.5)
    seen: list[str] = []
    seen_set: set[str] = set()
    for _ in range(4):
        for t in ui_texts():
            if t not in seen_set and t not in CHROME and "Generating" not in t:
                seen_set.add(t); seen.append(t)
        subprocess.call(["adb", "-s", DEVICE, "shell", "input", "swipe",
                        "540", "1800", "540", "600", "350"])
        time.sleep(0.5)
    text = "\n".join(seen)
    fields = {}
    m = re.search(r"Revenue \(([^)]+)\)", text)
    fields["header"] = m.group(0) if m else None
    fields["years"] = re.findall(r"^(20\d{2})$", text, re.MULTILINE)
    m = re.search(r"(?:revenue|profit|net income).{0,30}(?:increased|declined|grew|fell|was roughly flat) from\s+(.+?)\s+in FY\d{4} to\s+(.+?)\s+in FY\d{4}", text, re.IGNORECASE)
    fields["first"], fields["last"] = (m.group(1), m.group(2)) if m else (None, None)
    return text, fields


def main() -> None:
    out = []
    out_path = Path("world10_results.json")
    print(f"Testing {len(QUESTIONS)} companies — one per region\n")
    for i, (tag, q) in enumerate(QUESTIONS):
        print(f"[{i+1:>2}/{len(QUESTIONS)} {tag}] {q}", flush=True)
        text, f = fire_and_wait(q)
        ok = bool(f["header"]) and len(f["years"]) >= 2
        verdict = "PASS" if ok else "FAIL"
        shot = f"/tmp/world_{tag}.png"
        subprocess.call(["adb", "-s", DEVICE, "shell", "screencap", "/sdcard/s.png"])
        subprocess.call(["adb", "-s", DEVICE, "pull", "/sdcard/s.png", shot],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        rec = {"tag": tag, "prompt": q, "verdict": verdict,
               "header": f["header"], "years": f["years"],
               "first": f["first"], "last": f["last"], "screenshot": shot}
        out.append(rec)
        out_path.write_text(json.dumps({"results": out}, indent=2))
        print(f"   -> {verdict}  {f['header']}  {f['years']}")
        print(f"      {f['first']} -> {f['last']}")
    print()
    tally = {"PASS": 0, "FAIL": 0}
    for r in out:
        tally[r["verdict"]] += 1
    print(f"Tally: {tally}")


if __name__ == "__main__":
    main()
