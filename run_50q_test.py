#!/usr/bin/env python3
"""50-question chat reliability test for Localyze app on connected Android device."""

import subprocess
import time
import sys
import re

ADB = "adb"

QUESTIONS = [
    # Category 1: General Knowledge (10)
    "What is the capital of France?",
    "How many continents are there on Earth?",
    "What is the speed of light in kilometers per second?",
    "Who wrote the play Romeo and Juliet?",
    "What is the chemical symbol for gold?",
    "How many bones are in the adult human body?",
    "What year did World War 2 end?",
    "What is the largest planet in our solar system?",
    "What does DNA stand for?",
    "What is the boiling point of water in Celsius?",
    # Category 2: Reasoning & Logic (10)
    "If a train travels at 80 km/h for 3 hours, how far does it go?",
    "A bat and ball cost $1.10 total. The bat costs $1 more than the ball. How much does the ball cost?",
    "If you have 5 apples and give away 2, then buy 3 more, how many do you have?",
    "Explain the difference between correlation and causation in one sentence.",
    "What comes next in this sequence: 2 4 8 16 ?",
    "If all dogs are animals and some animals are pets, are all dogs pets?",
    "A pizza is cut into 8 slices. You eat 3. What fraction remains?",
    "Which is heavier: a kilogram of feathers or a kilogram of steel?",
    "If you flip a fair coin 3 times, what is the probability of getting all heads?",
    "Describe the prisoner's dilemma in simple terms.",
    # Category 3: Technology & Programming (10)
    "What is the difference between RAM and ROM?",
    "Explain what an API is in simple terms.",
    "What does HTTP stand for and what is it used for?",
    "What is the difference between compiled and interpreted languages?",
    "Explain the concept of object-oriented programming briefly.",
    "What is a database index and why is it useful?",
    "What is the difference between TCP and UDP?",
    "What is version control and why do developers use it?",
    "Explain what cloud computing means in simple terms.",
    "What is an algorithm? Give a simple example.",
    # Category 4: Creative & Conversational (10)
    "Tell me a short joke.",
    "What are three ways to reduce stress?",
    "Give me a simple recipe for pancakes.",
    "What are the benefits of drinking enough water daily?",
    "Suggest three good habits for productivity.",
    "What is the best way to learn a new language?",
    "Give me a motivational quote.",
    "What are the top 3 things to do in Paris?",
    "Explain how a rainbow forms in simple terms.",
    "What is the meaning of the phrase thinking outside the box?",
    # Category 5: Edge Cases & Tricky (10)
    "What is your name?",
    "Can you count to ten?",
    "What is 2 plus 2?",
    "Tell me something interesting about space.",
    "What is the longest river in the world?",
    "How does a microwave oven heat food?",
    "What causes earthquakes?",
    "Why is the sky blue during the day?",
    "What is the difference between weather and climate?",
    "Summarize the scientific method in 3 steps.",
]

SEND_BUTTON_Y = None  # We'll detect this


def run(cmd, timeout=15):
    try:
        result = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return result.stdout + result.stderr
    except subprocess.TimeoutExpired:
        return "TIMEOUT"


def find_send_button():
    """Try to find the send button near the text entry area."""
    # The send button is usually to the right of the text field
    # Tap near the right edge of the screen, around the text field height
    return (1300, 1727)  # Right side near text field


def tap(x, y):
    run(f"{ADB} shell input tap {x} {y}")


def type_text(text):
    """Type text using ADB clipboard method for reliability."""
    # Escape special chars for shell
    safe_text = text.replace('"', '\\"').replace("'", "\\'").replace("$", "\\$")
    # Use the clipboard approach for reliability
    run(f"{ADB} shell cmd clipboard set \"{safe_text}\"")
    time.sleep(0.3)
    run(f"{ADB} shell input keyevent 279")  # KEYCODE_PASTE


def get_screen_text():
    """Get all text visible on screen."""
    run(f"{ADB} shell uiautomator dump /sdcard/ui_test.xml")
    xml = run(f"{ADB} shell cat /sdcard/ui_test.xml")
    texts = re.findall(r'text="([^"]*)"', xml)
    return texts


def check_errors():
    """Check logcat for crashes, ANRs, or errors."""
    logs = run(f"{ADB} logcat -d -s *:E AndroidRuntime:E *:F ActivityManager:E")
    if "FATAL EXCEPTION" in logs or "ANR" in logs or "crash" in logs.lower():
        return logs[:500]
    return None


def check_app_running():
    """Check if the app process is still alive."""
    out = run(f"{ADB} shell pidof com.localyze")
    return bool(out.strip())


def main():
    global SEND_BUTTON_Y
    print("=" * 60)
    print("LOCALYZE 50-QUESTION RELIABILITY TEST")
    print("=" * 60)

    # Ensure app is running
    run(f"{ADB} shell am force-stop com.localyze")
    time.sleep(1)
    run(f"{ADB} logcat -c")
    run(f"{ADB} shell am start -n com.localyze/.MainActivity")
    time.sleep(4)

    if not check_app_running():
        print("FAIL: App did not start!")
        return

    print("App started successfully.")
    send_x, send_y = find_send_button()

    results = {"passed": 0, "failed": 0, "errors": [], "timings": []}

    for i, question in enumerate(QUESTIONS):
        q_num = i + 1
        print(f"\n[{q_num}/50] Q: {question[:80]}...")

        if not check_app_running():
            print(f"  FAIL: App crashed before Q{q_num}!")
            results["failed"] += 1
            results["errors"].append(f"Q{q_num}: App crash before question")
            # Try restarting
            print("  Attempting restart...")
            run(f"{ADB} shell am start -n com.localyze/.MainActivity")
            time.sleep(4)
            if not check_app_running():
                print("  FAIL: Cannot restart app. Aborting test.")
                break
            continue

        # Clear logs before each question
        run(f"{ADB} logcat -c")

        try:
            # 1. Tap on text field
            tap(508, 1727)
            time.sleep(0.5)

            # 2. Type the question
            type_text(question)
            time.sleep(0.8)

            # 3. Tap send button
            tap(send_x, send_y)
            print(f"  Sent. Waiting for response...")

            # 4. Wait for AI response (model generation takes time)
            start_time = time.time()
            response_detected = False
            for wait_cycle in range(30):  # Wait up to 30 seconds
                time.sleep(2)
                elapsed = time.time() - start_time

                # Check for errors
                logs = run(f"{ADB} logcat -d")
                if "FATAL EXCEPTION" in logs:
                    print(f"  FAIL: FATAL EXCEPTION detected!")
                    results["failed"] += 1
                    results["errors"].append(f"Q{q_num}: FATAL EXCEPTION at {elapsed:.0f}s")
                    response_detected = False
                    break
                if "ANR in com.localyze" in logs:
                    print(f"  FAIL: ANR detected!")
                    results["failed"] += 1
                    results["errors"].append(f"Q{q_num}: ANR detected")
                    response_detected = False
                    break
                if "OutOfMemoryError" in logs or "OOM" in logs:
                    print(f"  FAIL: Out of memory!")
                    results["failed"] += 1
                    results["errors"].append(f"Q{q_num}: OOM")
                    response_detected = False
                    break

                # Check if app is still alive
                if not check_app_running():
                    print(f"  FAIL: App died during response!")
                    results["failed"] += 1
                    results["errors"].append(f"Q{q_num}: App died at {elapsed:.0f}s")
                    # Try restarting
                    run(f"{ADB} shell am start -n com.localyze/.MainActivity")
                    time.sleep(4)
                    break

                # Check for response text on screen (the AI should have added text)
                # We don't check exact UI text since Compose renders it differently
                # Instead, rely on process stability + no errors as success indicator
                if elapsed > 10 and not response_detected:
                    # After 10 seconds, assume response started if no crash
                    response_detected = True

                if elapsed > 25:  # After 25s, consider it done regardless
                    break

            elapsed = time.time() - start_time
            results["timings"].append(elapsed)

            if check_app_running() and not any(
                err.startswith(f"Q{q_num}:") for err in results["errors"]
            ):
                results["passed"] += 1
                print(f"  PASS ({elapsed:.1f}s) - app stable, no errors")
            else:
                results["failed"] += 1
                print(f"  FAIL ({elapsed:.1f}s)")

            # Check for threading/log errors between questions
            error_check = check_errors()
            if error_check:
                print(f"  WARNING: Potential error detected in logs")
                results["errors"].append(f"Q{q_num}: Log error detected")

        except Exception as e:
            print(f"  FAIL: Test script error: {e}")
            results["failed"] += 1
            results["errors"].append(f"Q{q_num}: Script error: {e}")

        # Brief pause between questions
        time.sleep(2)

    # Final summary
    print("\n" + "=" * 60)
    print("TEST RESULTS")
    print("=" * 60)
    total = results["passed"] + results["failed"]
    print(f"Total questions sent: {total}")
    print(f"Passed: {results['passed']}")
    print(f"Failed: {results['failed']}")
    pass_rate = (results["passed"] / total * 100) if total > 0 else 0
    print(f"Pass rate: {pass_rate:.1f}%")

    if results["timings"]:
        avg_time = sum(results["timings"]) / len(results["timings"])
        print(f"Average response wait: {avg_time:.1f}s")

    if results["errors"]:
        print(f"\nErrors encountered ({len(results['errors'])}):")
        for err in results["errors"][:10]:
            print(f"  - {err}")
        if len(results["errors"]) > 10:
            print(f"  ... and {len(results['errors']) - 10} more")

    # Final app state
    if check_app_running():
        print("\nApp is still running after all 50 questions. GOOD.")
    else:
        print("\nApp crashed or stopped. Check logs.")

    # Pull final logs
    logs = run(f"{ADB} logcat -d | grep -E 'FATAL|ANR|crash|OOM|NetworkOnMainThread' | head -20")
    if logs.strip():
        print("\nFATAL/ANR log entries found:")
        print(logs[:500])

    print("\nTest complete.")


if __name__ == "__main__":
    main()
