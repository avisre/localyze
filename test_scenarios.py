#!/usr/bin/env python3
"""Test 3 scenarios: Apple revenue viz, website gen, code workspace prompt."""
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
    if not db.exists(): return "", []
    conn = sqlite3.connect(db)
    conn.row_factory = sqlite3.Row
    user = conn.execute(
        "SELECT id, conversationId, timestamp FROM messages WHERE role='USER' AND timestamp>=? ORDER BY id DESC LIMIT 1",
        (after_ms - 5000,)
    ).fetchone()
    if not user: conn.close(); tmpdir.cleanup(); return "", []
    assistant = conn.execute(
        "SELECT content FROM messages WHERE role='ASSISTANT' AND conversationId=? AND timestamp>=? ORDER BY timestamp ASC LIMIT 1",
        (user["conversationId"], user["timestamp"])
    ).fetchone()
    tools = conn.execute(
        "SELECT toolName, content, toolResult FROM messages WHERE role='TOOL' AND conversationId=? AND timestamp>=?",
        (user["conversationId"], user["timestamp"])
    ).fetchall()
    conn.close(); tmpdir.cleanup()
    return (assistant["content"] if assistant else ""), [dict(t) for t in tools]

def send_question(q):
    encoded = quote(q, safe="")
    shell("am", "start", "-W", "-n", ACTIVITY, "--es", "chat_msg", encoded, "--ez", "enable_web_search", "true", timeout=20)

scenarios = [
    ("Apple Revenue Viz", "Show me Apple Inc's annual revenue for the last 5 years (2021-2025). Present the data as a table with year and revenue in billions USD, then explain the trend."),
    ("Website Gen Test 1", "Build a complete modern ecommerce landing page for a sneaker store called 'KickX' with hero banner, product grid of 6 sneakers with names and prices, shopping cart sidebar, and a newsletter signup section. Use a dark purple and neon green color scheme."),
    ("Math + Reasoning", "If I invest $10,000 at 7% annual compound interest, how much will I have after 10 years? Show the calculation step by step and the growth each year."),
]

print("=" * 70)
print("LOCALYZE SCENARIO TESTS")
print("=" * 70)

for i, (name, question) in enumerate(scenarios, 1):
    print(f"\n── SCENARIO {i}: {name} ──")
    print(f"Q: {question[:100]}...")
    start_ms = int(time.time() * 1000)
    shell("logcat", "-c", timeout=5)
    send_question(question)

    answer = ""
    tools = []
    for attempt in range(35):
        time.sleep(2)
        answer, tools = get_last_answer(question, start_ms)
        if answer.strip():
            break
        if attempt % 5 == 0:
            print(f"  ...waiting ({attempt*2}s)...")

    elapsed = round(time.time() - (start_ms / 1000), 1)

    # Check passes
    web_search_used = any(t.get("toolName", "").lower() == "web_search" for t in tools)
    has_code_block = "```" in answer
    has_numbers = bool(re.search(r'\d+', answer))
    refused = "can't verify" in answer.lower() or "web search is currently off" in answer.lower()
    is_empty = not answer.strip()

    if is_empty:
        status = "EMPTY - model not responding"
    elif refused:
        status = "REFUSED - model refused to answer"
    elif name == "Apple Revenue Viz" and has_numbers and "billion" in answer.lower():
        status = "PASS - revenue data returned"
    elif name == "Website Gen Test 1" and has_code_block and ("html" in answer.lower()):
        status = "PASS - HTML code generated"
    elif name == "Math + Reasoning" and has_numbers:
        status = "PASS - calculation returned"
    else:
        status = "PARTIAL - check response"

    print(f"  Status: {status}")
    print(f"  Time: {elapsed}s")
    print(f"  Web search used: {web_search_used}")
    print(f"  Has code block: {has_code_block}")
    preview = answer[:300].replace('\n', ' | ')
    print(f"  Response: {preview}")
    print()

# Take screenshot
shell("screencap", "-p", "/sdcard/test_final.png", timeout=10)
run(["adb", "-s", DEVICE, "pull", "/sdcard/test_final.png", "/tmp/localyze_test_final.png"], timeout=10)
print("Screenshot saved to /tmp/localyze_test_final.png")
