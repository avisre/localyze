#!/usr/bin/env python3
"""Quick retest of reasoning questions after fixes."""
import subprocess, time, sys
from urllib.parse import quote

DEVICE = "a5523839"
PKG = "com.localyze"
ACTIVITY = "com.localyze/.MainActivity"

questions = [
    ("If a shirt costs $25 and is on sale for 20% off, what is the sale price?", r"\b20\b|\$20"),
    ("If today is Monday, what day will it be in 10 days?", r"\bthursday\b"),
    ("I have 5 apples. I give away 2 and buy 3 more. How many do I have now?", r"\b6\b|\bsix\b"),
    ("What comes next in this sequence: 2, 4, 8, 16, ?", r"\b32\b"),
    ("A bat and a ball cost $1.10. Bat costs $1 more. Ball cost?", r"0?\.05|5 cents"),
    ("If 5 machines make 5 widgets in 5 min, how long for 100 machines to make 100 widgets?", r"\b5\b"),
    ("In a race you overtake 2nd place, what position are you in?", r"\bsecond\b|\b2nd\b"),
    ("How many months have 28 days?", r"all|\b12\b|\btwelve\b|every"),
    ("Translate to French: Hello, how are you?", r"bonjour|salut|ça va|comment allez"),
    ("What is 20% of $25?", r"\b5\b|\$5|5 dollars"),
]

def cmd(c, text=True, timeout=30):
    return subprocess.run(c, capture_output=True, text=text, timeout=timeout)

def shell(*args, timeout=30):
    return cmd(["adb", "-s", DEVICE, "shell", *args], timeout=timeout)

def send_question(q):
    encoded = quote(q, safe="")
    shell("am", "start", "-W", "-n", ACTIVITY, "--es", "chat_msg", encoded, timeout=20)

def get_last_answer(question, after_ms):
    import sqlite3, tempfile
    from pathlib import Path
    tmpdir = tempfile.TemporaryDirectory()
    tmp_path = Path(tmpdir.name)
    for f in ("local_assistant_db", "local_assistant_db-wal", "local_assistant_db-shm"):
        r = cmd(["adb", "-s", DEVICE, "exec-out", "run-as", PKG, "cat", f"databases/{f}"], text=False)
        if r.returncode == 0 and r.stdout:
            data = r.stdout
            (tmp_path / f).write_bytes(data if isinstance(data, bytes) else data.encode())
    db = tmp_path / "local_assistant_db"
    if not db.exists():
        return ""
    conn = sqlite3.connect(db)
    conn.row_factory = sqlite3.Row
    user = conn.execute(
        "SELECT id, conversationId, timestamp FROM messages WHERE role='USER' AND content=? AND timestamp>=? ORDER BY id DESC LIMIT 1",
        (question, after_ms - 5000)
    ).fetchone()
    if not user:
        conn.close(); tmpdir.cleanup(); return ""
    assistant = conn.execute(
        "SELECT content FROM messages WHERE role='ASSISTANT' AND conversationId=? AND timestamp>=? AND length(trim(content))>0 ORDER BY timestamp ASC LIMIT 1",
        (user["conversationId"], user["timestamp"])
    ).fetchone()
    conn.close(); tmpdir.cleanup()
    return assistant["content"] if assistant else ""

import re
shell("am", "force-stop", PKG, timeout=10)
time.sleep(1)
# Set web search off
shell("am", "start", "-W", "-n", ACTIVITY, "--ez", "disable_web_search", "true", timeout=20)
time.sleep(2)

passed = 0
total = len(questions)
for i, (q, expected) in enumerate(questions, 1):
    start_ms = int(time.time() * 1000)
    shell("logcat", "-c", timeout=10)
    send_question(q)
    answer = ""
    for _ in range(60):
        time.sleep(1.5)
        answer = get_last_answer(q, start_ms)
        if answer.strip():
            break
    haystack = answer.lower()
    ok = bool(re.search(expected, haystack, re.IGNORECASE))
    refused = "can't verify" in haystack or "web search is currently off" in haystack or "web search is off" in haystack
    if ok and not refused:
        passed += 1
    status = "PASS" if (ok and not refused) else ("REFUSE" if refused else "FAIL")
    print(f"[{i:02d}/{total}] {status}: {q[:60]}...")
    preview = answer[:150].replace("\n", " | ")
    print(f"       => {preview}")

print(f"\nPASSED: {passed}/{total} ({round(passed/total*100)}%)")
