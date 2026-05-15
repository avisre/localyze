#!/usr/bin/env python3
"""
50-user reliability eval driving Localyze through UIAutomator.
Each "user" = a fresh conversation (tap + button to start new convo).
App is kept warm after first cold start so we don't pay model-load cost 50x.
Tracks: success / empty / timeout / crash, latency, DB errors, ANRs.
"""
from __future__ import annotations

import json
import re
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path

ADB = "/usr/bin/adb"
DEV = "a5523839"
PKG = "com.localyze"
ACT = f"{PKG}/.MainActivity"

# Layout coordinates from UIAutomator dump (no keyboard)
EDIT_TAP = (720, 1767)
SEND_TAP = (1208, 2015)
NEW_CONV_TAP = (1264, 272)  # "+" content-desc=New conversation

PER_PROMPT_TIMEOUT = 75
POLL_S = 2.0
COLD_LOAD_WAIT = 30  # initial model warm-up


@dataclass
class P:
    cat: str
    text: str
    expected: tuple = ()


PROMPTS: list[P] = [
    # 20 KNOWLEDGE
    P("KNOWLEDGE", "What is the capital of France", (r"\bparis\b",)),
    P("KNOWLEDGE", "What is the capital of Japan", (r"\btokyo\b",)),
    P("KNOWLEDGE", "What is the capital of Australia", (r"\bcanberra\b",)),
    P("KNOWLEDGE", "What is the largest ocean on Earth", (r"\bpacific\b",)),
    P("KNOWLEDGE", "How many continents are there", (r"\b7\b|seven",)),
    P("KNOWLEDGE", "What is the longest river in the world", (r"\bnile\b|\bamazon\b",)),
    P("KNOWLEDGE", "What is the chemical symbol for water", (r"h\s*2\s*o|h₂o",)),
    P("KNOWLEDGE", "How many planets are in our solar system", (r"\b8\b|eight",)),
    P("KNOWLEDGE", "What gas do plants absorb from the atmosphere", (r"carbon dioxide|co\s*2",)),
    P("KNOWLEDGE", "What is the freezing point of water in Celsius", (r"\b0\b|zero",)),
    P("KNOWLEDGE", "What is the speed of light approximately", (r"299|300[\s,]?000",)),
    P("KNOWLEDGE", "What is the chemical symbol for gold", (r"\bau\b",)),
    P("KNOWLEDGE", "Who wrote Romeo and Juliet", (r"shakespeare",)),
    P("KNOWLEDGE", "Who painted the Mona Lisa", (r"leonardo|da vinci",)),
    P("KNOWLEDGE", "What year did World War II end", (r"\b1945\b",)),
    P("KNOWLEDGE", "Who invented the telephone", (r"\bbell\b",)),
    P("KNOWLEDGE", "What is 2 plus 2", (r"\b4\b|four",)),
    P("KNOWLEDGE", "What is the smallest prime number", (r"\b2\b|two",)),
    P("KNOWLEDGE", "What is the square root of 144", (r"\b12\b|twelve",)),
    P("KNOWLEDGE", "How many bones are in the adult human body", (r"\b206\b",)),
    # 10 REASONING
    P("REASONING", "If a shirt costs 25 dollars and is on sale for 20 percent off what is the sale price", (r"\b20\b",)),
    P("REASONING", "A train travels 60 miles in 2 hours what is its average speed in mph", (r"\b30\b",)),
    P("REASONING", "If today is Monday what day will it be in 10 days", (r"thursday",)),
    P("REASONING", "I have 5 apples I give away 2 and buy 3 more how many do I have now", (r"\b6\b|six",)),
    P("REASONING", "What comes next 2 4 8 16", (r"\b32\b",)),
    P("REASONING", "A bat and ball cost 1 dollar 10 cents the bat costs 1 dollar more than the ball how much is the ball in cents", (r"\b5\b",)),
    P("REASONING", "If 5 machines take 5 minutes to make 5 widgets how long for 100 machines to make 100 widgets", (r"\b5\b",)),
    P("REASONING", "In a race if you overtake the person in second place what position are you in", (r"second|\b2nd\b",)),
    P("REASONING", "How many months have 28 days", (r"all|\b12\b|twelve|every",)),
    P("REASONING", "If all dogs are animals are all animals dogs answer yes or no", (r"\bno\b",)),
    # 10 STRESS / FORMAT
    P("STRESS", "Translate to French The weather is beautiful today", (r"beau|belle|temps|météo|magnifique|aujourd",)),
    P("STRESS", "Write a haiku about the ocean", (r"\b\w+",)),
    P("STRESS", "What is the answer to life the universe and everything", (r"\b42\b",)),
    P("STRESS", "List 5 European countries one per line", (r"france|germany|italy|spain|portugal|poland|netherlands|sweden|norway|greece|belgium",)),
    P("STRESS", "Hi", (r"\b\w+",)),
    P("STRESS", "Explain photosynthesis in two sentences", (r"light|sun|chlorophyll|plant|carbon",)),
    P("STRESS", "What is 7 times 8", (r"\b56\b",)),
    P("STRESS", "Name three primary colors", (r"red|blue|yellow",)),
    P("STRESS", "Spell the word ENCYCLOPEDIA letter by letter", (r"e.*n.*c.*y.*c.*l",)),
    P("STRESS", "Write one sentence using exactly five words", (r"\b\w+\b\s+\b\w+\b\s+\b\w+\b\s+\b\w+\b\s+\b\w+\b",)),
    # 10 ROBUSTNESS
    P("ROBUST", "Tell me a joke", (r"\b\w+",)),
    P("ROBUST", "Continue this sentence The cat sat on the", (r"mat|chair|floor|sofa|couch|rug|bed|step|sill",)),
    P("ROBUST", "Define recursion in one line", (r"recurs|itself|function|repeat|call",)),
    P("ROBUST", "Name a fruit that starts with the letter K", (r"kiwi|kumquat|kiwano",)),
    P("ROBUST", "What rhymes with orange", (r"nothing|none|no rhyme|sporange|hinge|borange|door hinge",)),
    P("ROBUST", "Convert 100 Fahrenheit to Celsius", (r"\b37\b|\b38\b",)),
    P("ROBUST", "What is the boiling point of water in Fahrenheit", (r"\b212\b",)),
    P("ROBUST", "Give me one Python tip", (r"python|list|comprehens|with|enumerate|zip|dict|range|f-string|use",)),
    P("ROBUST", "What is 10 factorial", (r"3[\s,.]?628[\s,.]?800",)),
    P("ROBUST", "What does HTTP stand for", (r"hypertext\s*transfer\s*protocol|hypertext",)),
]
assert len(PROMPTS) == 50

OUT = Path(__file__).resolve().parent / f"eval50user_{time.strftime('%Y%m%d_%H%M%S')}"
SHOTS = OUT / "screenshots"


def adb_run(args: list[str], t: int = 30, binary: bool = False):
    kw = {"capture_output": True, "timeout": t}
    if not binary:
        kw["text"] = True
        kw["errors"] = "replace"
    return subprocess.run([ADB, "-s", DEV, *args], **kw)


def sh(*a, t=30) -> str:
    return adb_run(["shell", *a], t=t).stdout or ""


def screencap(path: Path):
    r = adb_run(["exec-out", "screencap", "-p"], t=15, binary=True)
    if r.returncode == 0 and r.stdout:
        path.write_bytes(r.stdout)


def dump_ui() -> str:
    sh("uiautomator", "dump", "/sdcard/ui.xml", t=15)
    return sh("cat", "/sdcard/ui.xml", t=10)


def text_nodes(xml: str) -> list[tuple[str, int, int, int, int]]:
    out = []
    for m in re.finditer(r'<node[^>]*?\stext="([^"]*)"[^>]*?\sbounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        t = m.group(1)
        if t.strip():
            out.append((t, int(m.group(2)), int(m.group(3)), int(m.group(4)), int(m.group(5))))
    return out


def tap(x, y):
    sh("input", "tap", str(x), str(y))


def type_text(text: str):
    # adb shell input text reserves %, &, +, ", ', \, *, (, ), <, >, |, $, !, ;, ?, space
    # Strategy: split into spaces; send words with "input text", and use keyevent for special chars.
    # Our prompts avoid most special chars; spaces become %s.
    # For "?" we just omit it (prompts above already drop it).
    safe_chunks = []
    for ch in text:
        if ch == " ":
            safe_chunks.append(" ")
        elif ch.isalnum() or ch in ",.-_:":
            safe_chunks.append(ch)
        else:
            # skip unsupported chars (we already designed prompts without them)
            continue
    s = "".join(safe_chunks).replace(" ", "%s")
    if s:
        sh("input", "text", s)


def collapse_shade():
    sh("cmd", "statusbar", "collapse")


def wake():
    sh("input", "keyevent", "KEYCODE_WAKEUP")
    sh("wm", "dismiss-keyguard")


CHROME_TEXTS = {
    "Localyze.ai", "On-device", "Chat", "Code", "Library", "Settings",
    "Message Localyze.ai...", "How can I help?", "Retry", "Stop generating",
}


def chat_body_text(xml: str, user_prompt: str) -> str:
    nodes = text_nodes(xml)
    body = []
    for t, x1, y1, x2, y2 in nodes:
        ts = t.strip()
        if not ts or ts in CHROME_TEXTS:
            continue
        if y1 < 200 or y1 > 1610:  # outside chat list region
            continue
        if ts == user_prompt.strip():
            continue
        body.append(ts)
    return "\n".join(body).strip()


def db_error_visible(xml: str) -> bool:
    return "Failed to create conversation" in xml or "file is not a database" in xml


def wait_for_response(prompt: str, timeout: float) -> tuple[str, float, bool, bool]:
    """Returns (answer, elapsed, timed_out, db_err).

    Only scrapes from the chat screen. If we drift off the chat surface
    mid-wait, we re-launch and continue waiting rather than reporting random
    text from another app/screen.
    """
    t0 = time.time()
    last = ""
    stable = 0
    db_err = False
    drifts = 0
    while time.time() - t0 < timeout:
        xml = dump_ui()
        if db_error_visible(xml):
            db_err = True
            return "", time.time() - t0, False, True
        if not is_chat_screen(xml):
            drifts += 1
            ensure_foreground()
            time.sleep(0.5)
            if drifts > 6:
                return last, time.time() - t0, True, False
            continue
        # Reset drift count whenever we confirm we are still on the chat surface
        drifts = 0
        cur = chat_body_text(xml, prompt)
        if cur:
            if cur == last:
                stable += 1
                if stable >= 2:
                    return cur, time.time() - t0, False, False
            else:
                stable = 0
                last = cur
        time.sleep(POLL_S)
    return last, time.time() - t0, True, False


def normalize_for_score(s: str) -> str:
    """LiteRT-LM streams sometimes concatenate tokens like 'is 4' into 'is4'
    or '144 is 12' into '1444 is2'. Insert spaces at letter/digit boundaries
    so word-boundary regex patterns can still match the substantive content."""
    out = re.sub(r"(?<=[A-Za-z])(?=\d)", " ", s)
    out = re.sub(r"(?<=\d)(?=[A-Za-z])", " ", out)
    return out


def score(p: P, ans: str) -> tuple[bool, str]:
    if not ans.strip():
        return False, "EMPTY"
    if len(ans.strip()) < 2:
        return False, "TRIVIAL"
    if not p.expected:
        return True, "OPEN_OK"
    h = normalize_for_score(ans).lower()
    for pat in p.expected:
        if not re.search(pat, h, re.IGNORECASE):
            return False, "WRONG"
    return True, "CORRECT"


def collect_logcat_issues(start_ms: int) -> dict:
    log = sh("logcat", "-d", "-v", "time", "-t", f"{int((time.time()*1000-start_ms)/1000)+5}", t=15)
    return {
        "fatal": bool(re.search(r"FATAL EXCEPTION.*com\.localyze|AndroidRuntime.*com\.localyze", log)),
        "anr": bool(re.search(r"ANR in com\.localyze", log)),
        "db_error": bool(re.search(r"file is not a database|Failed to create conversation", log)),
    }


def new_conversation():
    """Open a fresh chat. Verify the empty state (no prior messages) is showing
    before returning. If the + tap landed on a sheet or didn't fire, retry."""
    for attempt in range(3):
        tap(*NEW_CONV_TAP)
        time.sleep(1.0 + 0.3 * attempt)
        xml = dump_ui()
        empty = "How can I help?" in xml or "Message Localyze" in xml
        # Stale-conversation hints: a prior assistant message still visible
        # (timestamp text like "5m ago", "just now") indicates we did not reset.
        stale = bool(re.search(r"\b(\d+m ago|\d+h ago|\d+d ago)\b", xml))
        if empty and not stale:
            return
        # Try a back press to dismiss any blocker, then retry
        sh("input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.6)
    # Last resort: force-stop and re-launch the activity
    sh("am", "force-stop", PKG)
    time.sleep(0.6)
    adb_run(["shell", "am", "start", "-n", ACT], t=15)
    time.sleep(2.0)


def is_chat_screen(xml: str) -> bool:
    """Heuristic for whether the chat surface is foregrounded.

    Idle:       Send-message button is present.
    Empty:      "How can I help?" / "Message Localyze.ai…" placeholder.
    Streaming:  Send button is replaced by a Stop control while the model
                generates, so we also accept the "Stop generating" text or the
                chat-list scaffold (the "On-device Context aware" header).
    """
    return (
        'content-desc="Send message"' in xml
        or "Message Localyze.ai" in xml
        or "How can I help?" in xml
        or "Stop generating" in xml
        or 'content-desc="Stop generating"' in xml
        or "On-device" in xml
    )


def ensure_foreground():
    """Make sure Localyze (and the chat screen specifically) is foreground.

    A naive activity check is not enough — the harness has been observed
    scraping Settings, DebugToolTester, and the launcher when keyboard BACK
    accidentally navigated away. We verify the chat surface itself is up
    before returning, and force-restart MainActivity if not.
    """
    for attempt in range(3):
        out = sh("dumpsys", "window", t=15)
        focus_ok = (
            "com.localyze/com.localyze.MainActivity" in out
            and "mCurrentFocus=Window{" in out
        )
        m = re.search(r"mCurrentFocus=Window\{[^}]*\s+(\S+)\}", out)
        if m and "com.localyze" not in m.group(1):
            focus_ok = False
        chat_ok = focus_ok and is_chat_screen(dump_ui())
        if focus_ok and chat_ok:
            return
        adb_run(["shell", "am", "start", "-n", ACT], t=15)
        time.sleep(2.0 + attempt)


def run_one(idx: int, p: P, first: bool) -> dict:
    print(f"\n[{idx:02d}/50] ({p.cat}) {p.text}", flush=True)
    OUT.mkdir(parents=True, exist_ok=True)
    SHOTS.mkdir(parents=True, exist_ok=True)
    sh("logcat", "-c")
    start_ms = int(time.time() * 1000)

    # Always ensure Localyze is foreground first
    wake()
    collapse_shade()
    ensure_foreground()
    time.sleep(0.4)

    if not first:
        new_conversation()
        ensure_foreground()

    # Tap input field
    tap(*EDIT_TAP)
    time.sleep(0.5)
    type_text(p.text)
    time.sleep(0.6)
    # Only press BACK when IME is actually shown — pressing BACK with no IME
    # navigates back from the chat surface and breaks scraping.
    ime = sh("dumpsys", "input_method", t=10)
    if "mInputShown=true" in ime:
        sh("input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.7)
    ensure_foreground()
    # Find Send button dynamically and tap its center
    xml = dump_ui()
    m = re.search(r'<node[^>]*?\scontent-desc="Send message"[^>]*?\sbounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if m:
        sx = (int(m.group(1)) + int(m.group(3))) // 2
        sy = (int(m.group(2)) + int(m.group(4))) // 2
        tap(sx, sy)
    else:
        tap(*SEND_TAP)

    answer, elapsed, timed_out, db_err = wait_for_response(p.text, PER_PROMPT_TIMEOUT)
    issues = collect_logcat_issues(start_ms)
    passed, quality = score(p, answer)

    shot = SHOTS / f"{idx:02d}_{p.cat}.png"
    screencap(shot)

    rec = {
        "i": idx, "category": p.cat, "prompt": p.text,
        "answer": answer, "elapsed_s": round(elapsed, 2),
        "timed_out": timed_out, "db_error_ui": db_err,
        "passed": passed, "quality": quality,
        "logcat_fatal": issues["fatal"], "logcat_anr": issues["anr"], "logcat_db_error": issues["db_error"],
        "screenshot": shot.name,
    }
    print(f"  -> elapsed={rec['elapsed_s']}s pass={passed} q={quality} timeout={timed_out} db_err={db_err} fatal={issues['fatal']} anr={issues['anr']}", flush=True)
    if answer:
        print(f"  answer: {answer[:200]!r}", flush=True)
    return rec


def warmup():
    print("Cold-starting app and warming model...", flush=True)
    # Keep screen on for the full eval
    sh("svc", "power", "stayon", "usb")
    sh("settings", "put", "system", "screen_off_timeout", "1800000")
    sh("am", "force-stop", PKG)
    time.sleep(0.7)
    adb_run(["shell", "am", "start", "-W", "-n", ACT], t=20)
    time.sleep(COLD_LOAD_WAIT)
    wake()
    collapse_shade()
    time.sleep(0.5)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    SHOTS.mkdir(parents=True, exist_ok=True)
    print(f"Output dir: {OUT}", flush=True)
    warmup()

    results = []
    for i, p in enumerate(PROMPTS, 1):
        try:
            rec = run_one(i, p, first=(i == 1))
        except subprocess.TimeoutExpired as e:
            print(f"  ADB TIMEOUT: {e}", flush=True)
            rec = {"i": i, "category": p.cat, "prompt": p.text, "answer": "", "elapsed_s": -1,
                   "timed_out": True, "db_error_ui": False, "passed": False, "quality": "ADB_TIMEOUT",
                   "logcat_fatal": False, "logcat_anr": False, "logcat_db_error": False, "screenshot": ""}
        except Exception as e:
            print(f"  HARNESS ERROR: {e}", flush=True)
            rec = {"i": i, "category": p.cat, "prompt": p.text, "answer": "", "elapsed_s": -1,
                   "timed_out": False, "db_error_ui": False, "passed": False, "quality": "HARNESS_ERROR",
                   "logcat_fatal": False, "logcat_anr": False, "logcat_db_error": False, "screenshot": "",
                   "error": str(e)[:300]}
        results.append(rec)
        (OUT / "results.json").write_text(json.dumps(results, indent=2))

    n = len(results)
    passed = sum(1 for r in results if r["passed"])
    timeouts = sum(1 for r in results if r["timed_out"])
    db_errs = sum(1 for r in results if r["db_error_ui"] or r["logcat_db_error"])
    fatals = sum(1 for r in results if r["logcat_fatal"])
    anrs = sum(1 for r in results if r["logcat_anr"])
    pass_times = [r["elapsed_s"] for r in results if r["passed"] and r["elapsed_s"] > 0]
    avg_t = sum(pass_times) / len(pass_times) if pass_times else 0.0
    by_cat = {}
    for r in results:
        c = r["category"]
        by_cat.setdefault(c, {"total": 0, "passed": 0})
        by_cat[c]["total"] += 1
        by_cat[c]["passed"] += int(r["passed"])

    success_rate = passed / n
    fast_pass_rate = sum(1 for r in results if r["passed"] and r["elapsed_s"] < 15) / max(passed, 1)
    score10 = round(
        success_rate * 6.0
        + (1 - timeouts / n) * 1.5
        + (1 - db_errs / n) * 1.0
        + (1 - fatals / n) * 1.0
        + fast_pass_rate * 0.5,
        2,
    )

    summary = {
        "device": DEV, "package": PKG,
        "total": n, "passed": passed, "timeouts": timeouts,
        "db_errors": db_errs, "fatals": fatals, "anrs": anrs,
        "avg_pass_latency_s": round(avg_t, 2),
        "by_category": by_cat,
        "score_out_of_10": score10,
        "components": {
            "success_rate": round(success_rate, 3),
            "timeout_rate": round(timeouts / n, 3),
            "db_error_rate": round(db_errs / n, 3),
            "fatal_rate": round(fatals / n, 3),
            "fast_pass_rate": round(fast_pass_rate, 3),
        },
    }
    (OUT / "summary.json").write_text(json.dumps(summary, indent=2))
    print("\n" + "=" * 60, flush=True)
    print(f"PASSED: {passed}/{n}  timeouts: {timeouts}  db_err: {db_errs}  fatals: {fatals}  anrs: {anrs}", flush=True)
    print(f"AVG LATENCY (passed): {avg_t:.1f}s", flush=True)
    print(f"SCORE: {score10}/10", flush=True)
    print(f"Output: {OUT}", flush=True)


if __name__ == "__main__":
    main()
