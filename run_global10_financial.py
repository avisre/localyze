#!/usr/bin/env python3
"""Verify the financial-visualization path returns structured data for
10 companies across US / India / China. Each prompt should produce:
  - a "Revenue (... billions/crore)" header
  - a numeric table
  - a bar chart rendered inline (extractor sees year/value rows)

Same harness pattern as run_general15b.py but with stricter pass/fail."""
from __future__ import annotations
import json, re, subprocess, time, sys
from pathlib import Path
from urllib.parse import quote

sys.path.insert(0, str(Path(__file__).parent))
from run_general15b import fire, ui_texts, is_generating, CHROME, _norm

DEVICE = "emulator-5554"
PKG = "com.localyze"
WAIT_S = 90  # financial path is fast (web_search + deterministic build); no model inference


def reset_and_fire(prompt: str) -> None:
    subprocess.call(["adb", "-s", DEVICE, "shell", "am", "force-stop", PKG])
    time.sleep(1)
    encoded = quote(prompt).replace("%20", "%s")
    subprocess.call([
        "adb", "-s", DEVICE, "shell", "am", "start",
        "-n", f"{PKG}/.MainActivity", "--es", "chat_msg", encoded,
    ])


def scroll_top() -> None:
    """Scroll back so the answer header is visible."""
    for _ in range(3):
        subprocess.call(["adb", "-s", DEVICE, "shell", "input", "swipe",
                        "540", "600", "540", "1900", "350"])
        time.sleep(0.5)


def collect_full_response(prompt: str) -> tuple[str, dict]:
    """Wait for Completed event, then collect text from multiple scrolls.
    Returns (joined_text, parsed_fields)."""
    t0 = time.time()
    completed = False
    while time.time() - t0 < WAIT_S:
        time.sleep(3.0)
        # Try to detect completion via logcat
        try:
            log = subprocess.check_output(
                ["adb", "-s", DEVICE, "logcat", "-d", "ChatViewModel:V", "*:S"],
                text=True, timeout=8
            )
        except Exception:
            log = ""
        if "handleResponseEvent: Completed" in log.split("doSendMessage")[-1]:
            completed = True
            break
    # Give the UI a moment to settle
    time.sleep(2)
    # Collect text from a few scroll positions so long answers are covered
    seen: list[str] = []
    seen_set: set[str] = set()
    scroll_top()
    for _ in range(4):
        for t in ui_texts():
            if t not in seen_set and t not in CHROME and "Generating" not in t:
                seen_set.add(t)
                seen.append(t)
        subprocess.call(["adb", "-s", DEVICE, "shell", "input", "swipe",
                        "540", "1800", "540", "600", "350"])
        time.sleep(0.6)
    text = "\n".join(seen)

    # Parse structured fields
    fields: dict = {"completed": completed}
    m = re.search(r"^([\w \./&()\-]+? Revenue (?:\(Last \d+ Fiscal Years\)|\(Latest Fiscal Year\)))$",
                  text, re.MULTILINE)
    fields["title"] = m.group(1).strip() if m else None
    m = re.search(r"Revenue \((USD billions|INR crore|CNY billions)\)", text)
    fields["table_header"] = m.group(0) if m else None
    fields["years"] = re.findall(r"^(20\d{2})$", text, re.MULTILINE)
    m = re.search(r"increased from\s+(.+?)\s+in FY\d{4} to\s+(.+?)\s+in FY\d{4}", text)
    if not m:
        m = re.search(r"declined from\s+(.+?)\s+in FY\d{4} to\s+(.+?)\s+in FY\d{4}", text)
    fields["first_value"], fields["last_value"] = (m.group(1), m.group(2)) if m else (None, None)
    return text, fields


QUESTIONS = [
    # ── US ──────────────────────────────────────────────────────────────
    ("US01_apple",    "show Apple revenue for the last 3 fiscal years"),
    ("US02_msft",     "plot Microsoft revenue for the last 3 fiscal years"),
    ("US03_nvda",     "show NVIDIA revenue for the last 3 years"),
    ("US04_tsla",     "Tesla revenue over the past 3 years"),
    # ── India ───────────────────────────────────────────────────────────
    ("IN01_tcs",      "show TCS revenue for the last 3 fiscal years"),
    ("IN02_reliance", "plot Reliance revenue for the past 3 years"),
    ("IN03_infy",     "Infosys revenue for the last 3 years"),
    # ── China ───────────────────────────────────────────────────────────
    ("CN01_baba",     "plot Alibaba revenue for the past 3 years"),
    ("CN02_tencent",  "show Tencent revenue for the last 3 years"),
    ("CN03_byd",      "BYD revenue over the past 3 years"),
]


def main() -> None:
    out: list[dict] = []
    out_path = Path("global10_financial_results.json")
    print(f"Testing financial visualization for {len(QUESTIONS)} companies on {DEVICE}\n")
    for i, (tag, q) in enumerate(QUESTIONS):
        print(f"[{i+1:>2}/{len(QUESTIONS)} {tag}] {q}", flush=True)
        reset_and_fire(q)
        text, fields = collect_full_response(q)

        ok_chart = bool(fields["table_header"]) and len(fields["years"]) >= 2
        verdict = "PASS" if ok_chart else "FAIL"

        # Capture screenshot for the record
        shot = f"/tmp/g10fin_{tag}.png"
        subprocess.call(["adb", "-s", DEVICE, "shell", "screencap", "/sdcard/s.png"])
        subprocess.call(["adb", "-s", DEVICE, "pull", "/sdcard/s.png", shot],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        rec = {
            "tag": tag, "prompt": q, "verdict": verdict,
            "title": fields["title"],
            "header": fields["table_header"],
            "years": fields["years"],
            "first_value": fields["first_value"],
            "last_value": fields["last_value"],
            "screenshot": shot,
        }
        out.append(rec)
        out_path.write_text(json.dumps({"results": out}, indent=2))
        print(f"   -> {verdict}  header={fields['table_header']!r}  years={fields['years']}")
        print(f"      summary: from {fields['first_value']} → {fields['last_value']}")

    print("\n" + "=" * 100)
    print(f"{'TAG':<14} {'VERDICT':<6}  HEADER                          YEARS               FROM → TO")
    print("-" * 100)
    tally = {"PASS": 0, "FAIL": 0}
    for r in out:
        tally[r["verdict"]] += 1
        hdr = (r["header"] or "—")[:32]
        yrs = ",".join(r["years"][:5]) if r["years"] else "—"
        print(f"{r['tag']:<14} {r['verdict']:<6}  {hdr:<32}  {yrs:<18}  "
              f"{(r['first_value'] or '—')} → {(r['last_value'] or '—')}")
    print(f"\nTally: {tally}  ({tally['PASS']}/{len(QUESTIONS)} passed)")
    print(f"Full results: {out_path.resolve()}")


if __name__ == "__main__":
    main()
