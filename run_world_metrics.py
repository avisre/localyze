#!/usr/bin/env python3
"""10 financial queries covering Revenue / Profit / Balance Sheet across
multiple countries — demonstrates the new TOTAL_ASSETS metric and the
expanded 10-per-country company list."""
from __future__ import annotations
import json, re, subprocess, time, sys
from pathlib import Path
from urllib.parse import quote

sys.path.insert(0, str(Path(__file__).parent))
from run_general15b import fire, ui_texts, is_generating, CHROME, _norm

DEVICE = "emulator-5554"
PKG = "com.localyze"
WAIT_S = 60


def reset_and_fire(prompt: str) -> None:
    subprocess.call(["adb", "-s", DEVICE, "shell", "am", "force-stop", PKG])
    time.sleep(1)
    encoded = quote(prompt).replace("%20", "%s")
    subprocess.call([
        "adb", "-s", DEVICE, "shell", "am", "start",
        "-n", f"{PKG}/.MainActivity", "--es", "chat_msg", encoded,
        "--ez", "force_cpu", "true"
    ])


def collect(prompt: str) -> tuple[str, dict]:
    t0 = time.time()
    for _ in range(int(WAIT_S / 3)):
        time.sleep(3)
        try:
            log = subprocess.check_output(
                ["adb", "-s", DEVICE, "logcat", "-d", "ChatViewModel:V", "*:S"],
                text=True, timeout=8
            )
        except Exception:
            log = ""
        if "handleResponseEvent: Completed" in log.split("doSendMessage")[-1]:
            break
    time.sleep(2)
    seen: list[str] = []
    seen_set: set[str] = set()
    for _ in range(3):
        subprocess.call(["adb", "-s", DEVICE, "shell", "input", "swipe",
                        "540", "600", "540", "1900", "350"])
        time.sleep(0.5)
    for _ in range(4):
        for t in ui_texts():
            if t not in seen_set and t not in CHROME and "Generating" not in t:
                seen_set.add(t); seen.append(t)
        subprocess.call(["adb", "-s", DEVICE, "shell", "input", "swipe",
                        "540", "1800", "540", "600", "350"])
        time.sleep(0.6)
    text = "\n".join(seen)
    fields = {}
    m = re.search(r"([\w \./&()\-]+? (?:Revenue|Net income|Total assets)) \((?:Last \d+ Fiscal Years|Latest Fiscal Year)\)", text)
    fields["title"] = m.group(0).strip() if m else None
    m = re.search(r"(Revenue|Net income|Total assets) \((USD|INR|CNY|EUR|GBP|CAD|CHF|MXN) (?:billions|crore)\)", text)
    fields["header"] = m.group(0) if m else None
    fields["years"] = re.findall(r"^(20\d{2})$", text, re.MULTILINE)
    m = re.search(r"(?:increased|declined|was roughly flat).*?from\s+(.+?)\s+in FY\d{4} to\s+(.+?)\s+in FY\d{4}", text)
    if m:
        fields["range"] = f"{m.group(1)} → {m.group(2)}"
    return text, fields


QUESTIONS = [
    ("US_apple_rev",   "Apple revenue for the last 3 fiscal years", "revenue"),
    ("US_apple_prof",  "Apple profit for the last 3 fiscal years", "profit"),
    ("US_apple_bs",    "Apple balance sheet for the last 3 fiscal years", "assets"),
    ("US_msft_bs",     "Microsoft total assets for the last 3 fiscal years", "assets"),
    ("IN_hdfc_rev",    "HDFC Bank revenue for the last 3 fiscal years", "revenue"),
    ("IN_hdfc_prof",   "HDFC Bank net income for the past 3 years", "profit"),
    ("IN_hdfc_bs",     "HDFC Bank total assets for the last 3 fiscal years", "assets"),
    ("CN_baba_prof",   "Alibaba profit for the past 3 years", "profit"),
    ("UK_hsbc_bs",     "HSBC balance sheet for the last 3 fiscal years", "assets"),
    ("CA_rbc_bs",      "RBC total assets for the past 3 years", "assets"),
]


def main() -> None:
    out: list[dict] = []
    out_path = Path("world_metrics_results.json")
    print(f"World metrics: {len(QUESTIONS)} queries on {DEVICE}\n")
    for i, (tag, q, metric) in enumerate(QUESTIONS):
        print(f"[{i+1:>2}/{len(QUESTIONS)} {tag}] {q}", flush=True)
        reset_and_fire(q)
        text, fields = collect(q)
        ok = fields.get("header") is not None
        verdict = "PASS" if ok else "FAIL"
        shot = f"/tmp/wm_{tag}.png"
        subprocess.call(["adb", "-s", DEVICE, "shell", "screencap", "/sdcard/s.png"])
        subprocess.call(["adb", "-s", DEVICE, "pull", "/sdcard/s.png", shot],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        rec = {"tag": tag, "prompt": q, "metric": metric, "verdict": verdict,
               "header": fields.get("header"), "years": fields.get("years"),
               "range": fields.get("range"), "screenshot": shot}
        out.append(rec)
        out_path.write_text(json.dumps({"results": out}, indent=2))
        print(f"   -> {verdict}  metric={metric}  header={fields.get('header')}  "
              f"years={fields.get('years')}")
        if fields.get("range"):
            print(f"      {fields['range']}")
    tally = {"PASS": 0, "FAIL": 0}
    for r in out: tally[r["verdict"]] += 1
    print(f"\nTally: {tally}")


if __name__ == "__main__":
    main()
