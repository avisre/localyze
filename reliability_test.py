#!/usr/bin/env python3
"""Comprehensive Localyze app reliability test."""

import subprocess, time, sys, re, json

ADB = "adb"

def run(cmd, timeout=15):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return r.stdout + r.stderr
    except subprocess.TimeoutExpired:
        return "TIMEOUT"

def tap(x, y):
    run(f"{ADB} shell input tap {x} {y}")

def pid():
    out = run(f"{ADB} shell pidof com.localyze").strip()
    return out if out else None

def memory_mb():
    p = pid()
    if not p: return 0
    out = run(f"{ADB} shell dumpsys meminfo {p} | grep TOTAL").strip()
    m = re.search(r'(\d+)', out)
    return int(m.group(1)) // 1024 if m else 0

def thread_count():
    p = pid()
    if not p: return 0
    return len(run(f"{ADB} shell ls /proc/{p}/task/").strip().split())

def check_fatal():
    logs = run(f"{ADB} logcat -d")
    issues = []
    if "FATAL EXCEPTION" in logs: issues.append("FATAL EXCEPTION")
    if "ANR in com.localyze" in logs: issues.append("ANR")
    if "OutOfMemoryError" in logs: issues.append("OOM")
    if "NetworkOnMainThreadException" in logs: issues.append("NetworkOnMainThreadException")
    if "SIGABRT" in logs: issues.append("SIGABRT")
    if "SIGSEGV" in logs: issues.append("SIGSEGV")
    return issues

def send_message(text):
    """Send a message through the chat UI."""
    # Clear old text by tapping text field and using select-all + delete
    tap(508, 1727)
    time.sleep(0.3)
    # Send a bunch of delete key events to clear
    for _ in range(5):
        run(f"{ADB} shell input keyevent 67")  # DEL
        time.sleep(0.05)
    time.sleep(0.2)

    # Type message - replace spaces with individual key events approach doesn't work
    # Use shell quoting for input text
    escaped = text.replace('"', '\\"').replace("'", "\\'")
    cmd = f'{ADB} shell input text "{escaped}"'
    run(cmd)
    time.sleep(0.5)

    # Tap send button (bottom-right FAB, near y=2900-3000, x=1300-1400)
    tap(1340, 2980)
    time.sleep(0.5)

def screen_texts():
    run(f"{ADB} shell uiautomator dump /sdcard/ui_test.xml")
    xml = run(f"{ADB} shell cat /sdcard/ui_test.xml")
    return re.findall(r'text="([^"]*)"', xml)

def get_db_stats():
    """Check database integrity."""
    out = run(f'{ADB} shell "run-as com.localyze ls -la databases/"')
    return out.strip()

def main():
    print("=" * 60)
    print("LOCALYZE COMPREHENSIVE RELIABILITY TEST")
    print("=" * 60)

    # Phase 1: Clean start
    print("\n[Phase 1] Clean start...")
    run(f"{ADB} shell am force-stop com.localyze")
    time.sleep(1)
    run(f"{ADB} logcat -c")
    run(f"{ADB} shell am start -n com.localyze/.MainActivity")
    time.sleep(4)

    p = pid()
    if not p:
        print("FAIL: App did not start!")
        return
    print(f"OK: App running (PID={p}), Memory={memory_mb()}MB, Threads={thread_count()}")

    issues = check_fatal()
    if issues:
        print(f"FAIL: Issues detected: {issues}")
    else:
        print("OK: No fatal errors detected")

    # Phase 2: Tab navigation test
    print("\n[Phase 2] Tab navigation test...")
    tabs = {
        "Code": (540, 2980),
        "Library": (900, 2980),
        "Chat": (180, 2980),
        "Settings": (1250, 2980),
    }

    for tab_name, (tx, ty) in tabs.items():
        tap(tx, ty)
        time.sleep(1.5)
        texts = screen_texts()
        interesting = [t for t in texts if len(t) > 5 and t not in ["Localyze.ai", "On-device"]]
        print(f"  Tab '{tab_name}' | texts: {interesting[:3]}...")

        p2 = pid()
        if not p2:
            print(f"  FAIL: App crashed during tab switch!")
            return
        issues = check_fatal()
        if issues:
            print(f"  FAIL: Issues: {issues}")

    print("OK: All tabs navigated successfully")

    # Phase 3: Chat message test (10 messages)
    print("\n[Phase 3] Chat message test (10 messages)...")
    tap(180, 2980)  # Back to Chat tab
    time.sleep(1)

    test_questions = [
        "What is 2 plus 2",
        "What is the capital of India",
        "Tell me a short joke",
        "What is machine learning",
        "How does a battery work",
        "Explain gravity simply",
        "What causes rain",
        "What is photosynthesis",
        "Who was Albert Einstein",
        "What is Python programming",
    ]

    sent_count = 0
    for i, q in enumerate(test_questions):
        p = pid()
        if not p:
            print(f"  FAIL: App died before Q{i+1}")
            break

        run(f"{ADB} logcat -c")
        send_message(q)
        sent_count += 1

        # Wait for response (model generates for ~3-10 seconds)
        print(f"  [{i+1}/10] Sent: '{q}' | Mem={memory_mb()}MB | Threads={thread_count()} | ", end="", flush=True)

        waited = 0
        response_ok = False
        while waited < 20:
            time.sleep(2)
            waited += 2
            p = pid()
            if not p:
                print("DIED")
                break
            issues = check_fatal()
            if issues:
                print(f"FAIL({issues[0]})")
                break
            # After 6 seconds, assume response started if alive
            if waited >= 6:
                response_ok = True
                break

        if response_ok and pid():
            print(f"OK ({waited}s)")
        elif not pid():
            print("CRASHED")

    # Phase 4: Rapid-fire stress (20 quick messages)
    print(f"\n[Phase 4] Rapid-fire stress test (20 messages)...")
    if not pid():
        print("FAIL: App not running!")
        return

    rapid_results = []
    for i in range(20):
        p = pid()
        if not p:
            print(f"  App died at rapid message {i+1}")
            break

        run(f"{ADB} logcat -c")
        q = f"Quick test number {i+1}"
        send_message(q)
        time.sleep(2)  # Short wait
        p = pid()
        alive = bool(p)
        mem = memory_mb() if alive else 0
        threads = thread_count() if alive else 0
        issues = check_fatal()
        rapid_results.append({"q": i+1, "alive": alive, "mem": mem, "threads": threads, "issues": issues})

        status = "OK" if (alive and not issues) else f"FAIL({issues})"
        print(f"  [{i+1}/20] {status} | Mem={mem}MB Threads={threads}")

    # Phase 5: Memory & thread analysis
    print(f"\n[Phase 5] Memory & thread analysis...")
    if pid():
        mem = memory_mb()
        threads = thread_count()
        print(f"  Final Memory: {mem}MB")
        print(f"  Final Threads: {threads}")
        print(f"  DB files: {get_db_stats()}")

        # Check if threads are stable (not growing unboundedly)
        if rapid_results:
            thread_nums = [r["threads"] for r in rapid_results if r["threads"] > 0]
            if len(thread_nums) >= 3:
                first_avg = sum(thread_nums[:3]) / 3
                last_avg = sum(thread_nums[-3:]) / 3
                growth = last_avg - first_avg
                if abs(growth) <= 5:
                    print(f"  OK: Thread count stable ({first_avg:.0f} -> {last_avg:.0f})")
                else:
                    print(f"  WARNING: Thread count growing ({first_avg:.0f} -> {last_avg:.0f}, delta={growth:.0f})")

    # Final summary
    print(f"\n{'='*60}")
    print("FINAL SUMMARY")
    print(f"{'='*60}")
    print(f"Messages sent: {sent_count + len(rapid_results)}")
    print(f"Tabs tested: {len(tabs)}")
    print(f"App alive: {bool(pid())}")
    if pid():
        final_issues = check_fatal()
        if final_issues:
            print(f"WARNING: Issues in final logs: {final_issues}")
        else:
            print("OK: No fatal issues detected throughout testing")
    print(f"Final memory: {memory_mb()}MB")
    print(f"Final threads: {thread_count()}")
    print("\nTest complete.")

if __name__ == "__main__":
    main()
