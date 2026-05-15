#!/usr/bin/env python3
"""Re-roll Q25 and Q28 (math) that completed inference but lost UI capture."""

import json
import re
import subprocess
import threading
import time
from pathlib import Path
import uiautomator2 as u2

ROOT = Path(__file__).resolve().parent
RESULTS_JSON = ROOT / "malayalam50_results.json"
DEVICE = "emulator-5556"
PKG = "com.localyze"
ACTIVITY = f"{PKG}/.MainActivity"


def adb(*a, t=15):
    return subprocess.run(["adb", "-s", DEVICE, *a], capture_output=True, text=True, timeout=t)


def find_bounds(d):
    xml = d.dump_hierarchy()
    def c(label):
        for n in re.findall(r"<node[^/]*/?>", xml):
            if f'"{label}"' in n:
                m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
                if m:
                    x1, y1, x2, y2 = map(int, m.groups())
                    return ((x1 + x2) // 2, (y1 + y2) // 2)
        return None
    return {"i": c("Message Localyze.ai..."), "s": c("Send message"), "n": c("New conversation")}


def hard_relaunch(d):
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)
    adb("shell", "am", "start", "-W", "-n", ACTIVITY, t=20)
    for _ in range(30):
        if find_bounds(d)["i"]: return True
        time.sleep(2)
    return False


def stream_tokens(stop_event):
    """Capture streaming tokens from logcat so we don't depend on UI dump."""
    proc = subprocess.Popen(
        ["adb", "-s", DEVICE, "logcat", "GemmaInference:D", "*:S"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
    )
    pieces = []
    done = {"flag": False}

    def reader():
        for line in proc.stdout:
            if stop_event.is_set():
                break
            m = re.search(r"Received text content: '(.*?)\.\.\.'", line)
            if m:
                pieces.append(m.group(1))
            if "onDone callback received" in line:
                done["flag"] = True
                break

    t = threading.Thread(target=reader, daemon=True)
    t.start()
    return proc, t, pieces, done


TARGETS = [(25, "200-ന്റെ 15 ശതമാനം എത്രയാണ്?"),
           (28, "144-ന്റെ വർഗമൂലം എന്താണ്?")]


def main():
    d = u2.connect(DEVICE)
    data = json.loads(RESULTS_JSON.read_text())
    by_q = {r["qnum"]: r for r in data}

    for qnum, q in TARGETS:
        print(f"\n=== Q{qnum}: {q} ===")
        hard_relaunch(d)
        b = find_bounds(d)
        if b["n"]:
            d.click(*b["n"]); time.sleep(3); b = find_bounds(d)

        adb("logcat", "-c")
        stop = threading.Event()
        proc, reader_t, pieces, done = stream_tokens(stop)

        d.click(*b["i"]); time.sleep(1.5)
        d.send_keys(q); time.sleep(1.5)
        d.click(*b["s"])

        t0 = time.time()
        deadline = t0 + 660
        while time.time() < deadline and not done["flag"]:
            time.sleep(2)
        elapsed = int(time.time() - t0)
        stop.set()
        proc.terminate()
        try: proc.wait(timeout=3)
        except Exception: proc.kill()

        # Reconstruct response from token stream
        response = "".join(pieces).strip()
        print(f"  ({elapsed}s done={done['flag']} tokens={len(pieces)})")
        print(f"  A: {response[:300]}")

        by_q[qnum]["response"] = response
        by_q[qnum]["wait_seconds"] = elapsed
        by_q[qnum]["saw_done"] = done["flag"]
        by_q[qnum]["capture_status"] = "logcat_stream_capture"
        by_q[qnum]["ok"] = bool(response)

    ordered = [by_q[k] for k in sorted(by_q.keys())]
    RESULTS_JSON.write_text(json.dumps(ordered, ensure_ascii=False, indent=2))
    print(f"\nUpdated {RESULTS_JSON.name}")


if __name__ == "__main__":
    main()
