#!/usr/bin/env python3
"""3 specific clinical-scenario questions, each in a fresh app process.

Same pattern style as the CHA2DS2-VASc test: concrete patient details,
treatment-choice question, "what changes if" twist on a renal/safety
constraint. Tests whether the tightened clarification policy lets the
model answer directly + how clinically accurate the answers are.
"""
import json, re, subprocess, time
from pathlib import Path
from urllib.parse import quote

DEVICE = "a5523839"
PKG = "com.localyze"
COMPONENT = "com.localyze/.MainActivity"

COLD_START_WAIT_S = 240
GENERATION_MAX_S = 600
STABLE_TEXT_MS = 30000

QUESTIONS = [
    ("Q1_HFrEF",
     "A 72-year-old woman with HFrEF (LVEF 28%), NYHA class II, eGFR 45 mL/min, K+ 4.8, on max-tolerated lisinopril and bisoprolol. What's the next drug class to add per ESC/AHA 2024 guidelines, and how does your choice change if her K+ was 5.4?"),
    ("Q2_CAP",
     "A 68-year-old man with CAP, CURB-65 of 1, no recent antibiotics, mild penicillin allergy (rash, no anaphylaxis). What's your first-line oral antibiotic, and how does the choice change if he has eGFR 30 and is on warfarin?"),
    ("Q3_T2DM",
     "A 58-year-old man with T2DM (HbA1c 8.4%), BMI 31, eGFR 55, MI one year ago, currently on metformin 1 g BID. What's the next agent to add per ADA 2024, and how does the choice change if he develops recurrent UTIs?"),
]

CHROME = {
    "Localyze.ai", "Localyze....", "On-device  Context aware",
    "Chat", "Code", "Library", "Settings",
    "Message Localyze.ai...", "just now", "Web search complete", "-",
}


def fire(prompt: str) -> None:
    subprocess.call(["adb", "-s", DEVICE, "shell", "am", "force-stop", PKG])
    time.sleep(2)
    subprocess.call(["adb", "-s", DEVICE, "logcat", "-c"])
    time.sleep(0.5)
    encoded = quote(prompt).replace("%20", "%s")
    subprocess.call([
        "adb", "-s", DEVICE, "shell", "am", "start",
        "-n", COMPONENT, "--es", "chat_msg", encoded,
    ])


def logcat_dump() -> str:
    try:
        return subprocess.check_output(
            ["adb", "-s", DEVICE, "logcat", "-d",
             "GemmaInference:V", "ChatViewModel:V", "SendMessageUseCase:V", "MainActivity:V", "*:S"],
            text=True, timeout=15,
        )
    except Exception:
        return ""


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


def extract(texts, prompt):
    out_lines, seen = [], False
    for t in texts:
        if t == prompt:
            seen = True; continue
        if not seen: continue
        if t in CHROME: continue
        out_lines.append(t)
    if not out_lines:
        prefix = prompt[:40]
        for t in texts:
            if t.startswith(prefix):
                seen = True; continue
            if not seen: continue
            if t in CHROME: continue
            out_lines.append(t)
    return "\n".join(out_lines).strip()


def is_generating(texts: list[str]) -> bool:
    return any("Generating" in t for t in texts)


def wait_until_done(prompt: str) -> tuple[str, str, float, dict]:
    t0 = time.time()
    last_text = ""
    last_change = t0
    seen_generating = False
    info = {"first_token_s": None, "completed_log": False, "recovery_log": False,
            "saw_generating": False}
    prompt_len = len(prompt)

    while True:
        elapsed = time.time() - t0
        if elapsed > GENERATION_MAX_S:
            return last_text, "TIMEOUT", round(elapsed, 1), info

        time.sleep(3.0)

        dump = logcat_dump()
        if "Empty response after tool loop" in dump or "Bad-output recovery" in dump:
            info["recovery_log"] = True
        log_done = ("onDone callback received" in dump or
                    "Streaming completed" in dump)

        texts = ui_texts()
        cur = extract(texts, prompt)
        gen_now = is_generating(texts)
        if gen_now:
            seen_generating = True
            info["saw_generating"] = True

        meaningful = len(cur) > prompt_len * 1.2 or (cur and seen_generating)
        if meaningful and info["first_token_s"] is None:
            info["first_token_s"] = round(elapsed, 1)

        if cur != last_text:
            last_text = cur
            last_change = time.time()

        if log_done:
            info["completed_log"] = True
            time.sleep(4.0)
            texts = ui_texts()
            cur = extract(texts, prompt)
            verdict = "ANSWERED" if len(cur) >= 20 else "THIN" if cur else "EMPTY"
            return cur, verdict, round(time.time() - t0, 1), info

        if seen_generating and not gen_now and meaningful:
            time.sleep(3.0)
            texts = ui_texts()
            cur = extract(texts, prompt)
            verdict = "ANSWERED" if len(cur) >= 20 else "THIN" if cur else "EMPTY"
            return cur, verdict, round(time.time() - t0, 1), info

        if meaningful and (time.time() - last_change) * 1000 >= STABLE_TEXT_MS:
            verdict = "ANSWERED" if len(cur) >= 20 else "THIN" if cur else "EMPTY"
            return cur, verdict, round(time.time() - t0, 1), info

        if elapsed > COLD_START_WAIT_S and not seen_generating and not meaningful:
            return cur, "EMPTY_NO_START", round(elapsed, 1), info


def main():
    out = []
    for i, (tag, q) in enumerate(QUESTIONS):
        print(f"\n[{i+1}/{len(QUESTIONS)} {tag}]\n  Q: {q[:90]}...", flush=True)
        fire(q)
        answer, verdict, elapsed, info = wait_until_done(q)
        is_clarify = "Quick question first" in answer
        if is_clarify:
            verdict = "CLARIFIED"
        is_polite_fallback = "wasn't able to generate" in answer.lower()
        if is_polite_fallback:
            verdict = "FALLBACK"
        shot = f"/tmp/doc3_{tag}.png"
        subprocess.call(["adb", "-s", DEVICE, "shell", "screencap", "/sdcard/s.png"])
        subprocess.call(["adb", "-s", DEVICE, "pull", "/sdcard/s.png", shot],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        rec = {"tag": tag, "prompt": q, "answer": answer, "verdict": verdict,
               "elapsed_s": elapsed, "info": info, "screenshot": shot}
        out.append(rec)
        print(f"  -> {verdict} in {elapsed}s, {len(answer)} chars  info={info}", flush=True)
        if answer:
            print(f"     {answer[:500]!r}")
        Path("doctor3.json").write_text(json.dumps({"results": out}, indent=2))


if __name__ == "__main__":
    main()
