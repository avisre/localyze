#!/usr/bin/env python3
"""5 questions a practicing doctor might ask, with the patient harness.

Tests breadth: clinical guidelines, drug interactions, differential
diagnosis, patient communication, medical professional knowledge.
No coding questions.
"""
import json, re, subprocess, time
from pathlib import Path
from urllib.parse import quote

DEVICE = "a5523839"
PKG = "com.localyze"
COMPONENT = "com.localyze/.MainActivity"

COLD_START_WAIT_S = 240   # First-token wait — must beat slow cold start.
GENERATION_MAX_S = 600
STABLE_TEXT_MS = 30000    # Only consulted AFTER real model content shows up.

QUESTIONS = [
    ("Q1_DIABETES_GUIDELINE",
     "What is the first-line pharmacological treatment for a newly diagnosed type 2 diabetes patient who is obese with no other comorbidities, and what HbA1c target would you set?",
     "guideline + target"),
    ("Q2_DRUG_INTERACTION",
     "A patient on warfarin for atrial fibrillation is starting amiodarone. How would you adjust their INR monitoring frequency in the first month, and would you anticipate a dose change?",
     "drug interaction practical"),
    ("Q3_DIFFERENTIAL",
     "A 45-year-old woman presents with sudden-onset right lower quadrant pain, no fever, normal white cell count, and no nausea. Walk me through your differential diagnosis and the next imaging step.",
     "differential + imaging"),
    ("Q4_PATIENT_COMM",
     "How would you explain a positive BRCA1 mutation result to a 38-year-old patient who is anxious about her cancer risk and has two young children? Outline what you would say.",
     "communication + empathy"),
    ("Q5_DOC_FORMAT",
     "Briefly explain the components of a SOAP note and how it differs from a HEADSS assessment. When would you use each?",
     "medical documentation"),
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
    # Also try prompt-prefix match in case the prompt text wraps to >1
    # element in uiautomator output and we never see an exact match.
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
    """Detect the 'Generating...' subtitle that the app shows during inference."""
    return any("Generating" in t for t in texts)


def wait_until_done(prompt: str) -> tuple[str, str, float, dict]:
    """Logcat-primary completion detection.

    The previous version counted the user-prompt bubble as 'started' and
    then exited on a 25s stable-text window — which fired on cold starts
    before the model produced any output. New rules:

      * 'Generation finished' = onDone log appears OR Generating subtitle
        was seen and is now gone.
      * Stable-text fallback only counts AFTER we've seen content that is
        clearly NOT just the prompt bubble (length > 1.2x prompt OR the
        Generating subtitle has already appeared).
      * Cold-start budget bumped to 240s.
    """
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
        if "Empty response after tool loop" in dump:
            info["recovery_log"] = True
        log_done = ("onDone callback received" in dump or
                    "Streaming completed" in dump)

        texts = ui_texts()
        cur = extract(texts, prompt)
        gen_now = is_generating(texts)
        if gen_now:
            seen_generating = True
            info["saw_generating"] = True

        # Track first non-trivial content (something longer than just the
        # echoed prompt — a real model answer, not the user bubble).
        meaningful = len(cur) > prompt_len * 1.2 or (cur and seen_generating)
        if meaningful and info["first_token_s"] is None:
            info["first_token_s"] = round(elapsed, 1)

        if cur != last_text:
            last_text = cur
            last_change = time.time()

        # Authoritative completion signal: logcat says onDone.
        if log_done:
            info["completed_log"] = True
            time.sleep(4.0)
            texts = ui_texts()
            cur = extract(texts, prompt)
            verdict = "ANSWERED" if len(cur) >= 20 else "THIN" if cur else "EMPTY"
            return cur, verdict, round(time.time() - t0, 1), info

        # Generating subtitle was seen and is now gone — also a completion signal.
        if seen_generating and not gen_now and meaningful:
            time.sleep(3.0)
            texts = ui_texts()
            cur = extract(texts, prompt)
            verdict = "ANSWERED" if len(cur) >= 20 else "THIN" if cur else "EMPTY"
            return cur, verdict, round(time.time() - t0, 1), info

        # Stable-text fallback only counts after we have meaningful content.
        if meaningful and (time.time() - last_change) * 1000 >= STABLE_TEXT_MS:
            verdict = "ANSWERED" if len(cur) >= 20 else "THIN" if cur else "EMPTY"
            return cur, verdict, round(time.time() - t0, 1), info

        # Cold-start budget — only fires if nothing meaningful AND no
        # generation indicator has shown up.
        if elapsed > COLD_START_WAIT_S and not seen_generating and not meaningful:
            return cur, "EMPTY_NO_START", round(elapsed, 1), info


def main():
    out = []
    for i, (tag, q, expected) in enumerate(QUESTIONS):
        print(f"\n[{i+1}/{len(QUESTIONS)} {tag}]\n  Q: {q[:90]}...\n  expected: {expected}", flush=True)
        fire(q)
        answer, verdict, elapsed, info = wait_until_done(q)
        is_polite_fallback = "wasn't able to generate" in answer.lower()
        if is_polite_fallback:
            verdict = "FALLBACK"
        shot = f"/tmp/doc5_{tag}.png"
        subprocess.call(["adb", "-s", DEVICE, "shell", "screencap", "/sdcard/s.png"])
        subprocess.call(["adb", "-s", DEVICE, "pull", "/sdcard/s.png", shot],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        rec = {"tag": tag, "prompt": q, "expected": expected,
               "answer": answer, "verdict": verdict, "elapsed_s": elapsed,
               "info": info, "screenshot": shot}
        out.append(rec)
        print(f"  -> {verdict} in {elapsed}s, {len(answer)} chars  info={info}", flush=True)
        if answer:
            print(f"     {answer[:400]!r}")
        Path("doctor5.json").write_text(json.dumps({"results": out}, indent=2))


if __name__ == "__main__":
    main()
