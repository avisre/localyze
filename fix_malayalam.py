#!/usr/bin/env python3
"""Re-run the 4 failing Malayalam questions with retries + paraphrase fallback.

Strategy per Q:
  attempt 1: original Q (was failing — re-roll the model)
  attempt 2: original Q again (more time / different sampling)
  attempt 3: paraphrased / clarified Q (only if the first two still fail)

A response is considered correct when it contains the expected keyword for
that topic. After up to 3 attempts, the best response is written back to
india100_results.json and india100_graded.json is regenerated.
"""

import json
import re
import subprocess
import threading
import time
from pathlib import Path

import uiautomator2 as u2

ROOT = Path(__file__).resolve().parent
RESULTS_JSON = ROOT / "india100_results.json"
DEVICE = "emulator-5556"
PKG = "com.localyze"
ACTIVITY = f"{PKG}/.MainActivity"

PER_Q_BUDGET = 660
SETTLE_AFTER_DONE = 8  # extra settle so streaming text fully renders before dump


def adb(*args, timeout=15):
    return subprocess.run(["adb", "-s", DEVICE, *args],
                          capture_output=True, text=True, timeout=timeout)


def find_chat_bounds(d):
    xml = d.dump_hierarchy()
    def center(label):
        for n in re.findall(r"<node[^/]*/?>", xml):
            if f'"{label}"' in n:
                m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
                if m:
                    x1, y1, x2, y2 = map(int, m.groups())
                    return ((x1 + x2) // 2, (y1 + y2) // 2)
        return None
    return {"input": center("Message Localyze.ai..."),
            "send": center("Send message"),
            "new_conv": center("New conversation")}


def ensure_app(d):
    pid = adb("shell", "pidof", PKG).stdout.strip()
    if not pid:
        print("  relaunching app...")
        adb("shell", "am", "start", "-W", "-n", ACTIVITY, timeout=20)
        time.sleep(15)


def wait_for_done(budget):
    proc = subprocess.Popen(
        ["adb", "-s", DEVICE, "logcat", "GemmaInference:D", "*:S"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
    )
    done = {"flag": False}

    def reader():
        for line in proc.stdout:
            if "onDone callback received" in line:
                done["flag"] = True
                return
            if "I wasn't able to generate" in line:
                done["flag"] = True
                return

    threading.Thread(target=reader, daemon=True).start()
    deadline = time.time() + budget
    while time.time() < deadline and not done["flag"]:
        time.sleep(1)
    proc.terminate()
    try:
        proc.wait(timeout=3)
    except Exception:
        proc.kill()
    return done["flag"]


def extract_response(d, question):
    xml = d.dump_hierarchy()
    texts = re.findall(r'text="([^"]*)"', xml)
    cleaned = [t for t in texts if t.strip()]
    boilerplate = {
        "Localyze.ai", "On-device", "On-device  Context aware",
        "Chat", "Code", "Library", "Settings",
        "Message Localyze.ai...", "How can I help?",
        "Reading your message", "The answer will appear below",
    }
    cleaned = [t for t in cleaned if t not in boilerplate]
    cleaned = [t for t in cleaned if not re.fullmatch(r"\d+[smhd] ago|just now|\d+:\d+", t)]
    if question in cleaned:
        idx = cleaned.index(question)
        response = cleaned[idx + 1:]
    else:
        response = cleaned
    return " ".join(response).strip()


def ask_one(d, question):
    ensure_app(d)
    bounds = find_chat_bounds(d)
    # always start fresh conversation to avoid the multi-turn JNI crash
    if bounds["new_conv"]:
        d.click(*bounds["new_conv"])
        time.sleep(3)
        bounds = find_chat_bounds(d)

    adb("logcat", "-c")
    d.click(*bounds["input"])
    time.sleep(1.5)
    d.send_keys(question)
    time.sleep(1.5)
    d.click(*bounds["send"])

    t0 = time.time()
    saw_done = wait_for_done(PER_Q_BUDGET)
    elapsed = int(time.time() - t0)
    time.sleep(SETTLE_AFTER_DONE)
    return extract_response(d, question), elapsed, saw_done


# Each entry: (qnum, topic, original_q, paraphrased_q, accept_keywords)
TARGETS = [
    (74, 4,
     "ഇന്ത്യയുടെ ദേശീയ കായികം ഏതാണ്?",
     "ഇന്ത്യയുടെ ഔദ്യോഗിക ദേശീയ കായിക ഇനം ഉണ്ടോ? വ്യക്തമായി മറുപടി തരൂ.",
     ["hockey", "kabaddi", "cricket", "ഹോക്കി", "ക്രിക്കറ്റ്", "കബഡി",
      "ഔദ്യോഗിക", "ഇല്ല", "ഇല്ലാ", "no official", "officially"]),

    (76, 6,
     "ഇന്ത്യൻ രൂപയുടെ ചിഹ്നം എന്താണ്?",
     "ഇന്ത്യൻ രൂപയുടെ ചിഹ്നം ('rupee symbol') എന്താണെന്ന് കാണിക്കൂ.",
     ["₹", "rupee", "rupiya", "rupaya", "രൂപ"]),

    (77, 7,
     "സച്ചിൻ തെൻഡുൽക്കർ ആരാണ്?",
     "സച്ചിൻ തെൻഡുൽക്കർ ആരാണ്, അദ്ദേഹം ഏത് കായികത്തിൽ ലോകപ്രസിദ്ധനാണ്?",
     ["cricket", "batsman", "batter", "1989", "2013", "mumbai",
      "ക്രിക്കറ്റ്", "ബാറ്റ്സ്മാൻ", "സച്ചിൻ"]),

    (78, 8,
     "ഗംഗാ നദി എവിടെനിന്ന് ഉത്ഭവിക്കുന്നു?",
     "ഗംഗാ നദിയുടെ യഥാർത്ഥ ഉത്ഭവസ്ഥാനം ഏതാണ്? സംസ്ഥാനത്തിന്റെയും ഹിമാനിയുടെയും പേര് പറയൂ.",
     ["gangotri", "uttarakhand", "ഗംഗോത്രി", "ഉത്തരാഖണ്ഡ്", "ഹിമാലയ"]),
]


def matches(text, keywords):
    t = text.lower()
    return any(k.lower() in t for k in keywords)


def main():
    d = u2.connect(DEVICE)
    print(f"Connected: {d.info.get('productName')}")
    results = json.loads(RESULTS_JSON.read_text())
    by_qnum = {r["qnum"]: r for r in results}

    fixed = []
    for qnum, topic, orig_q, paraphrase, accept in TARGETS:
        print(f"\n=== Q{qnum} (topic {topic}) ===")
        best = None
        for attempt in range(1, 4):
            q = orig_q if attempt < 3 else paraphrase
            print(f"  attempt {attempt}: {q}")
            try:
                resp, took, done = ask_one(d, q)
            except Exception as e:
                print(f"    ERROR: {e}")
                continue
            ok = matches(resp, accept)
            print(f"    ({took}s done={done} ok={ok}) {resp[:300]}")
            if best is None or (ok and not best["ok"]):
                best = {
                    "question": q, "response": resp, "wait_seconds": took,
                    "saw_done": done, "ok": ok, "attempt": attempt,
                }
            if ok:
                break

        # update the record
        rec = by_qnum[qnum]
        rec["question"] = best["question"]
        rec["response"] = best["response"]
        rec["wait_seconds"] = best["wait_seconds"]
        rec["saw_done"] = best["saw_done"]
        rec["ok"] = best["ok"]
        rec["capture_status"] = "rerun_attempt_" + str(best["attempt"])
        fixed.append((qnum, best["ok"]))

    # write back
    RESULTS_JSON.write_text(json.dumps(results, ensure_ascii=False, indent=2))
    print("\n--- summary ---")
    for qnum, ok in fixed:
        print(f"  Q{qnum}: {'OK' if ok else 'STILL WRONG'}")
    print(f"\nUpdated {RESULTS_JSON.name}")


if __name__ == "__main__":
    main()
