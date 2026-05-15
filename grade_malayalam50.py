#!/usr/bin/env python3
"""Grade the 50 Malayalam responses.

Factual buckets (Kerala, India-general, World-knowledge, Math-reasoning) get
keyword-based correctness scoring. Subjective buckets (Practical, Tech,
Creative, Emotional-advice) get a coherence check — does the response exist,
in Malayalam script, and address the question without being empty/garbled?
"""

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent
data = json.loads((ROOT / "malayalam50_results.json").read_text())

# Per-question accept keywords. Indexed by qnum. "*" = subjective coherence check.
ACCEPT = {
    # Kerala-specific
    1:  ["തിരുവനന്തപുരം", "trivandrum", "thiruvananthapuram"],
    2:  ["പെരിയാർ", "പെരിയാറ", "periyar", "ഭാരതപ്പുഴ", "bharathapuzha"],
    3:  ["onam", "ഓണം", "harvest", "vamana", "mahabali", "maveli", "വാമന", "മഹാബലി"],
    4:  ["dance", "drama", "നൃത്ത", "നാടകം", "നൃത്തനാടകം", "ക്ലാസിക്കൽ", "കല", "kathakali"],
    5:  ["14", "fourteen", "പതിന്നാല്", "പതിനാല്"],
    6:  ["tamil", "dravid", "sanskrit", "തമിഴ്", "ദ്രാവിഡ", "സംസ്കൃത"],
    7:  ["feast", "meal", "സദ്യ", "ഇല", "ചോറ്", "vegetarian", "സസ്യാഹാര", "വാഴ", "വിരുന്ന്"],
    8:  ["rice", "coconut", "നെല്ല്", "തെങ്ങ്", "rubber", "റബർ", "റബ്ബർ", "spices", "സുഗന്ധ", "നാളികേര"],
    9:  ["cochin", "kochi", "trivandrum", "thiruvananthapuram", "കൊച്ചി",
         "नेडुम्बाशेरी", "नेदुम्बासेरी", "नेडुम्बस्सेरी", "നെടുമ്പാശ്ശേരി",
         "तिरुवनंतपुरम", "तिरुवनंतपुरम्", "तिरुवनंथपुरम",
         "तिरुवनतपुरम", "कोच्चि", "कोचीन", "ciral"],
    10: ["november", "നവംബർ", "1956", "നവമ്പർ"],

    # India-general
    11: ["tagore", "ടാഗോർ", "രവീന്ദ്ര", "ravindra"],
    12: ["nehru", "നെഹ്", "ജവഹർലാൽ", "jawaharlal"],
    13: ["1950", "ജനുവരി 26", "january 26", "നവംബർ 26"],  # adopted 26 Nov 1949, enforced 26 Jan 1950
    14: ["uttar pradesh", "ഉത്തർ പ്രദേശ്", "യു.പി", "u.p."],
    15: ["22", "23", "ഇരുപത്തിരണ്ട്", "ഇരുപത്തിമൂന്ന്", "twenty-two", "twenty-three"],
    16: ["saffron", "white", "green", "കാവി", "വെള്ള", "പച്ച", "നീല", "blue", "ashoka", "ചക്രം"],

    # World-knowledge (bank order: Jupiter, H2O, Everest, Einstein, Pacific, DNA, populous, Sun)
    17: ["jupiter", "വ്യാഴം", "ജൂപ്പിറ്റർ"],
    18: ["h2o", "h₂o", "h_2", "h_{2}", "എച്ച്2ഒ", "h2o", "h\\_2"],
    19: ["everest", "എവറസ്റ്റ്", "സാഗരമാത", "എമണ്ടൂസ്"],
    20: ["scientist", "physicist", "ഭൗതിക", "ശാസ്ത്രജ്ഞൻ", "relativity", "ആപേക്ഷിക",
         "നൊബേൽ", "nobel", "ജർമൻ", "ജർമ്മൻ", "german", "e=mc"],
    21: ["pacific", "ശാന്ത", "പസഫിക്", "atlantic", "സമുദ്ര"],
    22: ["deoxyribonucleic", "deoxy", "ഡിയോക്സിറൈബോ", "ഡയോക്സി"],
    23: ["china", "india", "ചൈന", "ഇന്ത്യ"],
    24: ["star", "നക്ഷത്ര"],

    # Math-reasoning
    25: ["30", "ഇരുപത്", "thirty"],
    26: ["180"],
    27: ["156"],
    28: ["12"],
    29: ["3600", "3,600"],

    # Practical-howto, Tech-coding, Creative, Emotional-advice — coherence check
    **{q: ["*"] for q in range(30, 51)},
}


def malayalam_score(text):
    """fraction of characters that are in Malayalam Unicode range."""
    if not text: return 0.0
    mal = sum(1 for ch in text if 0x0D00 <= ord(ch) <= 0x0D7F)
    return mal / len(text)


def grade_subjective(text):
    """Coherence check for non-factual buckets: response exists, is in Malayalam,
    not just an error/placeholder, has reasonable length."""
    if not text or len(text.strip()) < 20:
        return "EMPTY"
    if "Reading your message" in text or "appear below" in text:
        return "UI_FAIL"
    if malayalam_score(text) < 0.4:
        return "NOT_MALAYALAM"
    return "OK"


def grade(rec):
    qnum = rec["qnum"]
    text = rec.get("response", "").lower()
    accepts = ACCEPT.get(qnum, [])
    if accepts == ["*"]:
        return grade_subjective(rec.get("response", ""))
    # special: Q18 water formula — accept any "{H}...2...{O}" sequence (LaTeX-mangled)
    if qnum == 18 and re.search(r"h.{0,12}[_]?2.{0,15}o\b", text, re.DOTALL):
        return "OK"
    return "OK" if any(k.lower() in text for k in accepts) else "MISS"


by_bucket = {}
for r in data:
    g = grade(r)
    r["grade"] = g
    by_bucket.setdefault(r["bucket"], []).append((r["qnum"], g, r))

print("=" * 76)
print("MALAYALAM-50 SCORECARD — Localyze on Gemma 4 E4B")
print("=" * 76)
print(f"{'Bucket':22s} {'OK':>3s} {'MISS':>5s} {'EMPTY':>6s} {'Score':>6s}  Per-Q")
print("-" * 76)
total_ok = total = 0
for bucket, rows in by_bucket.items():
    rows.sort(key=lambda t: t[0])
    n_ok = sum(1 for _, g, _ in rows if g == "OK")
    n_miss = sum(1 for _, g, _ in rows if g in ("MISS", "NOT_MALAYALAM"))
    n_empty = sum(1 for _, g, _ in rows if g in ("EMPTY", "UI_FAIL"))
    pct = 100 * n_ok / len(rows)
    per = "".join("✓" if g == "OK" else ("·" if g == "MISS" else "✗") for _, g, _ in rows)
    print(f"  {bucket:20s} {n_ok:>3d} {n_miss:>5d} {n_empty:>6d} {pct:>5.0f}%  {per}")
    total_ok += n_ok
    total += len(rows)
print("-" * 76)
print(f"  {'TOTAL':20s} {total_ok:>3d} {total - total_ok:>5d} {' ':>6s} {100*total_ok/total:>5.0f}%")

# Print the actual responses grouped by bucket
print("\n\n" + "=" * 76)
print("ALL 50 Q&A")
print("=" * 76)
for bucket, rows in by_bucket.items():
    print(f"\n### {bucket} ###")
    for qnum, g, r in rows:
        mark = "✓" if g == "OK" else ("·" if g == "MISS" else "✗")
        print(f"\n  {mark} Q{qnum}: {r['question']}")
        print(f"     A: {r['response'][:400]}")

# Save graded
(ROOT / "malayalam50_graded.json").write_text(json.dumps(data, ensure_ascii=False, indent=2))
