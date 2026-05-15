#!/usr/bin/env python3
"""Final scenario tests: Apple revenue viz, website gen (code ws), math reasoning."""
import subprocess, time, sqlite3, tempfile
from pathlib import Path
from urllib.parse import quote

DEVICE = "a5523839"
PKG = "com.localyze"
ACTIVITY = "com.localyze/.MainActivity"

def run(cmd, text=True, timeout=30):
    return subprocess.run(cmd, capture_output=True, text=text, timeout=timeout)

def shell(*args, timeout=30):
    return run(["adb", "-s", DEVICE, "shell", *args], timeout=timeout)

def wait_for_assistant_response(timeout_s=40):
    """Poll DB until we get a non-empty assistant response or timeout."""
    start = time.time()
    last_content = ""
    while time.time() - start < timeout_s:
        time.sleep(1.5)
        tmpdir = tempfile.TemporaryDirectory()
        tmp_path = Path(tmpdir.name)
        for f in ("local_assistant_db", "local_assistant_db-wal", "local_assistant_db-shm"):
            r = run(["adb", "-s", DEVICE, "exec-out", "run-as", PKG, "cat", f"databases/{f}"], text=False)
            if r.returncode == 0 and r.stdout:
                data = r.stdout
                (tmp_path / f).write_bytes(data if isinstance(data, bytes) else data.encode())
        db = tmp_path / "local_assistant_db"
        if db.exists():
            conn = sqlite3.connect(db)
            conn.row_factory = sqlite3.Row
            msgs = conn.execute("SELECT role, content, toolName FROM messages ORDER BY id DESC LIMIT 5").fetchall()
            conn.close()
            for m in msgs:
                if m["role"] == "ASSISTANT" and m["content"].strip():
                    content = m["content"].strip()
                    if content != last_content:
                        tmpdir.cleanup()
                        return content, [dict(x) for x in msgs if x["role"] == "TOOL"]
                    last_content = content
        tmpdir.cleanup()
    return "", []

def send_chat(msg):
    shell("am", "start", "-W", "-n", ACTIVITY, "--es", "chat_msg", msg, "--ez", "enable_web_search", "true", timeout=20)

def send_code_ws(prompt):
    shell("am", "start", "-W", "-n", ACTIVITY, "--ez", "triggerTest", "true", "--es", "testPrompt", prompt, timeout=20)

# ── Start fresh ──
shell("am", "force-stop", PKG, timeout=10)
time.sleep(2)
shell("am", "start", "-W", "-n", ACTIVITY, "--ez", "enable_web_search", "true", timeout=20)
time.sleep(8)  # model load

print("=" * 70)
print("TEST 1: Apple Revenue Visualization (chat, data in prompt)")
print("=" * 70)
q1 = "Analyze this revenue data: Apple 2021:$365.8B, 2022:$394.3B, 2023:$383.3B, 2024:$391.0B, 2025:$406.9B. Create a markdown table. Calculate YoY growth %. Describe the trend."
send_chat(q1)
resp, tools = wait_for_assistant_response(30)
web_used = any("web_search" in str(t.get("toolName","")) for t in tools)
print(f"Web search used: {web_used}")
print(f"Response ({len(resp)} chars): {resp[:400].replace(chr(10),' | ')}")
has_table = "|" in resp and "Year" in resp.lower()
has_growth = "%" in resp
print(f"Has table: {has_table}, Has growth %: {has_growth}")
print(f"PASS: {has_table and has_growth}")

# ── Reset for code ws test ──
shell("am", "force-stop", PKG, timeout=10)
time.sleep(2)
shell("am", "start", "-W", "-n", ACTIVITY, "--ez", "enable_web_search", "true", timeout=20)
time.sleep(8)

print("\n" + "=" * 70)
print("TEST 2: Website Generation via Code Workspace")
print("=" * 70)
q2 = "Build a complete ecommerce landing page for KickX sneaker store with dark theme, hero banner, 6 product cards with prices, cart sidebar, and newsletter"
send_code_ws(q2)
resp, tools = wait_for_assistant_response(50)
has_html = "<!DOCTYPE html>" in resp or "<html" in resp or "```html" in resp
has_content = len(resp) > 200
print(f"Response length: {len(resp)} chars")
print(f"Has HTML: {has_html}, Has substantial content: {has_content}")
print(f"Preview: {resp[:300].replace(chr(10),' | ')}")
print(f"PASS: {has_html and has_content}")

# ── Reset ──
shell("am", "force-stop", PKG, timeout=10)
time.sleep(2)
shell("am", "start", "-W", "-n", ACTIVITY, "--ez", "enable_web_search", "true", timeout=20)
time.sleep(8)

print("\n" + "=" * 70)
print("TEST 3: Compound Interest Math (chat)")
print("=" * 70)
q3 = "Calculate compound interest: $10,000 at 7% annually for 10 years. Show each year's balance. What is the final amount?"
send_chat(q3)
resp, tools = wait_for_assistant_response(20)
web_used = any("web_search" in str(t.get("toolName","")) for t in tools)
has_calc = "$" in resp and any(c.isdigit() for c in resp)
refused = "can't verify" in resp.lower()
print(f"Web search used: {web_used}, Refused: {refused}")
print(f"Response: {resp[:400].replace(chr(10),' | ')}")
print(f"PASS: {has_calc and not refused}")

# ── Screenshot ──
shell("screencap", "-p", "/sdcard/final_test.png", timeout=10)
run(["adb", "-s", DEVICE, "pull", "/sdcard/final_test.png", "/tmp/localyze_final_test.png"], timeout=10)
print("\nScreenshot: /tmp/localyze_final_test.png")
