#!/usr/bin/env python3
"""Test code workspace website generation and revenue visualization."""
import subprocess, time, sys, sqlite3, tempfile
from pathlib import Path
from urllib.parse import quote

DEVICE = "a5523839"
PKG = "com.localyze"
ACTIVITY = "com.localyze/.MainActivity"

def run(cmd, text=True, timeout=30):
    return subprocess.run(cmd, capture_output=True, text=text, timeout=timeout)

def shell(*args, timeout=30):
    return run(["adb", "-s", DEVICE, "shell", *args], timeout=timeout)

def get_all_recent():
    tmpdir = tempfile.TemporaryDirectory()
    tmp_path = Path(tmpdir.name)
    for f in ("local_assistant_db", "local_assistant_db-wal", "local_assistant_db-shm"):
        r = run(["adb", "-s", DEVICE, "exec-out", "run-as", PKG, "cat", f"databases/{f}"], text=False)
        if r.returncode == 0 and r.stdout:
            data = r.stdout
            (tmp_path / f).write_bytes(data if isinstance(data, bytes) else data.encode())
    db = tmp_path / "local_assistant_db"
    if not db.exists(): return []
    conn = sqlite3.connect(db)
    conn.row_factory = sqlite3.Row
    msgs = conn.execute("SELECT role, content, toolName, timestamp FROM messages ORDER BY id DESC LIMIT 5").fetchall()
    conn.close(); tmpdir.cleanup()
    return msgs

# Start fresh
shell("am", "force-stop", PKG, timeout=10)
time.sleep(2)
shell("am", "start", "-W", "-n", ACTIVITY, "--ez", "enable_web_search", "true", timeout=20)
time.sleep(10)  # model load

print("=== TEST 1: Apple Revenue in Chat ===")
q1 = "Apple Inc annual revenue: 2021=$365B, 2022=$394B, 2023=$383B, 2024=$391B, 2025=$407B. Format as a table with Year and Revenue columns, then show the growth trend."
shell("am", "start", "-W", "-n", ACTIVITY, "--es", "chat_msg", q1, "--ez", "enable_web_search", "true", timeout=20)
print(f"Sent: {q1[:100]}...")
for _ in range(20):
    time.sleep(2)
    msgs = get_all_recent()
    for m in msgs:
        if m["role"] == "ASSISTANT" and m["content"].strip():
            print(f"Response: {m['content'][:300]}")
            break
    else: continue
    break

input("\nPress Enter for next test...")

print("\n=== TEST 2: Website Gen via Code Workspace ===")
# Code workspace uses triggerTest + testPrompt in debug mode
q2 = "Build a complete ecommerce landing page for KickX sneakers with hero, 6 product cards, cart sidebar"
shell("am", "start", "-W", "-n", ACTIVITY, "--ez", "triggerTest", "true", "--es", "testPrompt", q2, timeout=20)
print(f"Sent: {q2[:100]}...")
for _ in range(25):
    time.sleep(2)
    msgs = get_all_recent()
    for m in msgs:
        if m["role"] == "ASSISTANT" and m["content"].strip():
            content = m["content"]
            has_html = "<!DOCTYPE html>" in content or "<html" in content
            print(f"HTML generated: {has_html}, length: {len(content)}")
            print(f"Preview: {content[:300]}")
            break
    else: continue
    break

print("\n=== TEST 3: Simple Reasoning ===")
q3 = "What is 15% of $80? Show the calculation."
shell("am", "start", "-W", "-n", ACTIVITY, "--es", "chat_msg", q3, "--ez", "enable_web_search", "true", timeout=20)
print(f"Sent: {q3}")
for _ in range(15):
    time.sleep(2)
    msgs = get_all_recent()
    for m in msgs:
        if m["role"] == "ASSISTANT" and m["content"].strip():
            print(f"Response: {m['content'][:200]}")
            break
    else: continue
    break

# Final screenshot
shell("screencap", "-p", "/sdcard/test_final2.png", timeout=10)
run(["adb", "-s", DEVICE, "pull", "/sdcard/test_final2.png", "/tmp/localyze_final2.png"], timeout=10)
print("\nScreenshot: /tmp/localyze_final2.png")
