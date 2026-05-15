#!/usr/bin/env python3
"""Quick test - reasoning with web search ON."""
import subprocess, time, sys, re, sqlite3, tempfile
from pathlib import Path
from urllib.parse import quote

DEVICE = "a5523839"
PKG = "com.localyze"
ACTIVITY = "com.localyze/.MainActivity"

def run(cmd, text=True, timeout=30):
    return subprocess.run(cmd, capture_output=True, text=text, timeout=timeout)

def shell(*args, timeout=30):
    return run(["adb", "-s", DEVICE, "shell", *args], timeout=timeout)

def get_last_answer(question, after_ms):
    tmpdir = tempfile.TemporaryDirectory()
    tmp_path = Path(tmpdir.name)
    for f in ("local_assistant_db", "local_assistant_db-wal", "local_assistant_db-shm"):
        r = run(["adb", "-s", DEVICE, "exec-out", "run-as", PKG, "cat", f"databases/{f}"], text=False)
        if r.returncode == 0 and r.stdout:
            data = r.stdout
            (tmp_path / f).write_bytes(data if isinstance(data, bytes) else data.encode())
    db = tmp_path / "local_assistant_db"
    if not db.exists(): return ""
    conn = sqlite3.connect(db)
    conn.row_factory = sqlite3.Row
    user = conn.execute(
        "SELECT id, conversationId, timestamp FROM messages WHERE role='USER' AND content=? AND timestamp>=? ORDER BY id DESC LIMIT 1",
        (question, after_ms - 5000)
    ).fetchone()
    if not user: conn.close(); tmpdir.cleanup(); return ""
    assistant = conn.execute(
        "SELECT content FROM messages WHERE role='ASSISTANT' AND conversationId=? AND timestamp>=? AND length(trim(content))>0 ORDER BY timestamp ASC LIMIT 1",
        (user["conversationId"], user["timestamp"])
    ).fetchone()
    conn.close(); tmpdir.cleanup()
    return assistant["content"] if assistant else ""

questions = [
    ("If a shirt costs $25 and is on sale for 20% off, what is the sale price?", r"\b20\b|\$20|20 dollars"),
    ("If today is Monday, what day will it be in 10 days?", r"\bthursday\b"),
    ("I have 5 apples. I give away 2 and buy 3 more. How many do I have now?", r"\b6\b|\bsix\b"),
    ("What comes next in this sequence: 2, 4, 8, 16, ?", r"\b32\b"),
    ("What is 20 percent of 25 dollars?", r"\b5\b|\$5"),
    ("Translate to French: Hello", r"bonjour|salut"),
]

shell("am", "force-stop", PKG, timeout=10)
time.sleep(2)
# START WITH WEB SEARCH ENABLED (default)
shell("am", "start", "-W", "-n", ACTIVITY, timeout=20)
time.sleep(10)  # wait for model to load

passed = 0
total = len(questions)
for i, (q, expected) in enumerate(questions, 1):
    start_ms = int(time.time() * 1000)
    encoded = quote(q, safe="")
    shell("logcat", "-c", timeout=5)
    shell("am", "start", "-W", "-n", ACTIVITY, "--es", "chat_msg", encoded, timeout=20)
    answer = ""
    for _ in range(40):
        time.sleep(2)
        answer = get_last_answer(q, start_ms)
        if answer.strip(): break
    haystack = answer.lower()
    ok = bool(re.search(expected, haystack, re.IGNORECASE))
    refused = "can't verify" in haystack or "web search is currently off" in haystack
    if ok: passed += 1
    status = "PASS" if ok else ("REFUSE" if refused else "FAIL")
    print(f"[{i}/{total}] {status}: {q[:60]}")
    print(f"  => {answer[:200].replace(chr(10), ' | ')}")
    time.sleep(2)

print(f"\nPASSED: {passed}/{total} ({round(passed/total*100)}%)")
