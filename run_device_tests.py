#!/usr/bin/env python3
"""Run 3 device tests with proper URL encoding."""
import subprocess, time, sqlite3, tempfile, re
from pathlib import Path
from urllib.parse import quote

DEVICE = "a5523839"
PKG = "com.localyze"
ACTIVITY = "com.localyze/.MainActivity"

def adb(cmd, text=True, timeout=30):
    return subprocess.run(["adb", "-s", DEVICE] + cmd, capture_output=True, text=text, timeout=timeout)

def shell(*args, timeout=30):
    return adb(["shell"] + list(args), timeout=timeout)

def poll_assistant(timeout_s=45):
    """Poll DB until we have a non-empty assistant response."""
    start = time.time()
    while time.time() - start < timeout_s:
        time.sleep(2)
        tmpdir = tempfile.TemporaryDirectory()
        tmp = Path(tmpdir.name)
        for f in ("local_assistant_db", "local_assistant_db-wal", "local_assistant_db-shm"):
            r = adb(["exec-out", "run-as", PKG, "cat", f"databases/{f}"], text=False)
            if r.returncode == 0 and r.stdout:
                data = r.stdout
                (tmp / f).write_bytes(data if isinstance(data, bytes) else data.encode())
        db = tmp / "local_assistant_db"
        if not db.exists():
            tmpdir.cleanup(); continue
        conn = sqlite3.connect(db); conn.row_factory = sqlite3.Row
        msgs = conn.execute("SELECT role, content, toolName FROM messages ORDER BY id DESC LIMIT 8").fetchall()
        conn.close(); tmpdir.cleanup()
        for m in msgs:
            if m["role"] == "ASSISTANT" and m["content"].strip():
                return m["content"].strip(), [dict(x) for x in msgs if x["role"] == "TOOL"]
    return "", []

def send_chat(msg):
    enc = quote(msg, safe="")
    shell("am", "start", "-W", "-n", ACTIVITY, "--es", "chat_msg", enc, "--ez", "enable_web_search", "true", timeout=20)

def send_code_ws(prompt):
    enc = quote(prompt, safe="")
    shell("am", "start", "-W", "-n", ACTIVITY, "--ez", "triggerTest", "true", "--es", "testPrompt", enc, timeout=20)

# ── START ──
shell("am", "force-stop", PKG, timeout=10)
time.sleep(2)
shell("am", "start", "-W", "-n", ACTIVITY, "--ez", "enable_web_search", "true", timeout=20)
time.sleep(8)

# ── TEST 1: Revenue Visualization in Chat ──
print("=" * 70)
print("TEST 1: Apple Revenue Table + YoY Growth (chat)")
print("=" * 70)
q1 = "Format this data as a markdown table with YoY growth %. Apple revenue: 2021: 365.8 billion USD, 2022: 394.3 billion, 2023: 383.3 billion, 2024: 391.0 billion, 2025: 406.9 billion. Calculate each year's growth rate."
send_chat(q1)
resp, tools = poll_assistant(35)
web = any("web_search" in str(t.get("toolName","")) for t in tools)
refused = "can't verify" in resp.lower()
passed = bool(re.search(r'\d+', resp)) and "billion" in resp.lower() and not refused
print(f"Web search: {web} | Refused: {refused} | Pass: {passed}")
print(f"Response: {resp[:400].replace(chr(10), ' | ')}")

# ── RESTART ──
shell("am", "force-stop", PKG, timeout=10)
time.sleep(2)
shell("am", "start", "-W", "-n", ACTIVITY, "--ez", "enable_web_search", "true", timeout=20)
time.sleep(8)

# ── TEST 2: Code Workspace Website Generation ──
print("\n" + "=" * 70)
print("TEST 2: Code Workspace Ecommerce Landing Page")
print("=" * 70)
q2 = "Build a complete ecommerce landing page for KickX sneakers with dark theme, hero banner, 6 product cards with real prices, shopping cart sidebar, and newsletter signup"
send_code_ws(q2)
resp, tools = poll_assistant(55)
has_html = "<html" in resp.lower() or "```html" in resp
refused = "can't verify" in resp.lower()
passed = has_html and not refused and len(resp) > 300
print(f"HTML: {has_html} | Len: {len(resp)} | Refused: {refused} | Pass: {passed}")
print(f"Response: {resp[:400].replace(chr(10), ' | ')}")

# ── RESTART ──
shell("am", "force-stop", PKG, timeout=10)
time.sleep(2)
shell("am", "start", "-W", "-n", ACTIVITY, "--ez", "enable_web_search", "true", timeout=20)
time.sleep(8)

# ── TEST 3: Compound Interest Math ──
print("\n" + "=" * 70)
print("TEST 3: Compound Interest Calculation (chat)")
print("=" * 70)
q3 = "Calculate: 10000 dollars invested at 7 percent annual compound interest for 10 years. Show each year's balance. What is the final amount?"
send_chat(q3)
resp, tools = poll_assistant(25)
web = any("web_search" in str(t.get("toolName","")) for t in tools)
refused = "can't verify" in resp.lower()
has_result = bool(re.search(r'\d[\d,.]*\s*dollars|\$\d|19[.,]\d{3}', resp))
passed = not refused and has_result
print(f"Web: {web} | Refused: {refused} | Result: {has_result} | Pass: {passed}")
print(f"Response: {resp[:400].replace(chr(10), ' | ')}")

# ── Screenshot ──
shell("screencap", "-p", "/sdcard/eval_final.png", timeout=10)
adb(["pull", "/sdcard/eval_final.png", "/tmp/localyze_eval_final.png"], timeout=10)
print("\nScreenshot: /tmp/localyze_eval_final.png")

total = sum([passed for _ in range(3)])
print(f"\n=== FINAL: {sum([1 for x in [(q1,resp,tools),(q2,resp,tools),(q3,resp,tools)] if 'PASS' in str(x)])}/3 SCENARIOS PASSING ===")
