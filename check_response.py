#!/usr/bin/env python3
"""Check the latest response in the database."""
import subprocess, time, sys, sqlite3, tempfile
from pathlib import Path

DEVICE = "a5523839"
PKG = "com.localyze"

def run(cmd, text=True, timeout=30):
    return subprocess.run(cmd, capture_output=True, text=text, timeout=timeout)

tmpdir = tempfile.TemporaryDirectory()
tmp_path = Path(tmpdir.name)
for f in ("local_assistant_db", "local_assistant_db-wal", "local_assistant_db-shm"):
    r = run(["adb", "-s", DEVICE, "exec-out", "run-as", PKG, "cat", f"databases/{f}"], text=False)
    if r.returncode == 0 and r.stdout:
        data = r.stdout
        (tmp_path / f).write_bytes(data if isinstance(data, bytes) else data.encode())

db = tmp_path / "local_assistant_db"
if not db.exists():
    print("DB not found")
    sys.exit(1)

conn = sqlite3.connect(db)
conn.row_factory = sqlite3.Row

# Get latest messages
msgs = conn.execute("SELECT role, content, toolName, timestamp FROM messages ORDER BY id DESC LIMIT 20").fetchall()
for m in msgs:
    role = m["role"]
    content = (m["content"] or "")[:200].replace('\n', ' | ')
    tool = m["toolName"] or ""
    print(f"[{role}] {tool} | {content}")

conn.close()
tmpdir.cleanup()
