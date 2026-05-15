#!/usr/bin/env python3
"""Smoke-test the extended company coverage: VinFast, UK, EU, Switzerland,
Canada, Mexico. One query per region so the user can see each works."""
from __future__ import annotations
import json, subprocess, time, sys, re
from pathlib import Path
from urllib.parse import quote

sys.path.insert(0, str(Path(__file__).parent))
from run_general15b import (fire, ui_texts, extract_assistant, is_generating,
                            classify, CHROME, _norm)

DEVICE = "emulator-5554"
PKG = "com.localyze"
WAIT_S = 90


QUESTIONS = [
    ("VN_vinfast", "profit of VinFast in the last 3 years"),
    ("UK_shell",   "show Shell revenue for the last 3 fiscal years"),
    ("UK_astra",   "AstraZeneca revenue last 3 years"),
    ("DE_sap",     "SAP revenue for the past 3 years"),
    ("FR_lvmh",    "LVMH revenue for the last 3 years"),
    ("NL_asml",    "ASML revenue last 3 years"),
    ("CH_nestle",  "Nestle revenue for the past 3 years"),
    ("CA_shopify", "Shopify revenue last 3 years"),
    ("CA_rbc",     "RBC revenue for the past 3 years"),
    ("MX_amx",     "America Movil revenue last 3 years"),
]


def collect_until_complete(prompt: str) -> tuple[str, dict]:
    t0 = time.time()
    last = ""
    last_change = t0
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

    # Scroll to top and collect from a few positions
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

    fields: dict = {}
    m = re.search(r"Revenue \((USD|INR crore|CNY|EUR|GBP|CAD|CHF|MXN|JPY) ?(billions?|crore)?\)", text)
    fields["header"] = m.group(0) if m else None
    fields["years"] = re.findall(r"^(20\d{2})$", text, re.MULTILINE)
    m = re.search(r"(?:revenue|profit|net income).{0,30}(?:increased|declined|grew|fell|was roughly flat) from\s+(.+?)\s+in FY\d{4} to\s+(.+?)\s+in FY\d{4}", text, re.IGNORECASE)
    fields["first_value"], fields["last_value"] = (m.group(1), m.group(2)) if m else (None, None)
    return text, fields


def main() -> None:
    out = []
    out_path = Path("global_extended_results.json")
    print(f"Cross-region smoke test ({len(QUESTIONS)} Qs) on {DEVICE}\n")
    for i, (tag, q) in enumerate(QUESTIONS):
        force_stop = (i == 0)
        print(f"[{i+1:>2}/{len(QUESTIONS)} {tag}] {q}", flush=True)
        subprocess.call(["adb", "-s", DEVICE, "shell", "am", "force-stop", PKG])
        time.sleep(0.8)
        encoded = quote(q).replace("%20", "%s")
        subprocess.call(["adb", "-s", DEVICE, "shell", "am", "start",
                         "-n", f"{PKG}/.MainActivity", "--es", "chat_msg", encoded,
                         "--ez", "force_cpu", "true"])
        text, fields = collect_until_complete(q)
        ok = bool(fields["header"]) and len(fields["years"]) >= 2
        verdict = "PASS" if ok else "FAIL"
        shot = f"/tmp/ext_{tag}.png"
        subprocess.call(["adb", "-s", DEVICE, "shell", "screencap", "/sdcard/s.png"])
        subprocess.call(["adb", "-s", DEVICE, "pull", "/sdcard/s.png", shot],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        rec = {"tag": tag, "prompt": q, "verdict": verdict,
               "header": fields["header"], "years": fields["years"],
               "first": fields["first_value"], "last": fields["last_value"],
               "screenshot": shot}
        out.append(rec)
        out_path.write_text(json.dumps({"results": out}, indent=2))
        print(f"   -> {verdict}  header={fields['header']!r}  yrs={fields['years']}")
        print(f"      {fields['first_value']} -> {fields['last_value']}")
    print()
    tally = {"PASS": 0, "FAIL": 0}
    for r in out:
        tally[r["verdict"]] += 1
    print(f"Tally: {tally}")


if __name__ == "__main__":
    main()
