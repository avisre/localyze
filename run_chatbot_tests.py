#!/usr/bin/env python3
"""
Localyze Chatbot Test Automation Script
========================================
Automates testing 15 questions across finance, tech, culture & news
categories on the Localyze Android app via ADB.

Prerequisites:
1. Android device connected via ADB (adb devices shows device)
2. Device UNLOCKED (you must manually unlock the phone)
3. Localyze app installed (com.localyze)
4. WiFi ON for online testing phase

Usage:
    python run_chatbot_tests.py --online     # Run online tests (WiFi ON, web search enabled)
    python run_chatbot_tests.py --offline    # Run offline tests (WiFi OFF, web search disabled)
    python run_chatbot_tests.py --all        # Run both phases
"""

import subprocess
import time
import os
import sys
import argparse
import json
from datetime import datetime

# ============================================================
# Test Questions - 15 questions across 4 categories
# ============================================================
QUESTIONS = [
    # FINANCE (Q1-Q4)
    {"id": 1, "category": "Finance", "question": "What is the current Federal Funds rate set by the US Federal Reserve?", "needs_internet": True, "type": "Current Data"},
    {"id": 2, "category": "Finance", "question": "Explain what a yield curve inversion means and why it matters for the economy", "needs_internet": False, "type": "Educational"},
    {"id": 3, "category": "Finance", "question": "What are the latest trends in cryptocurrency regulation in 2025?", "needs_internet": True, "type": "Current News"},
    {"id": 4, "category": "Finance", "question": "How does compound interest work, and what is the difference between APR and APY?", "needs_internet": False, "type": "Educational"},
    # TECHNOLOGY (Q5-Q8)
    {"id": 5, "category": "Technology", "question": "What are the latest features in Android 16?", "needs_internet": True, "type": "Current News"},
    {"id": 6, "category": "Technology", "question": "Explain how large language models work in simple terms", "needs_internet": False, "type": "Educational"},
    {"id": 7, "category": "Technology", "question": "What is the current state of quantum computing in 2025?", "needs_internet": True, "type": "Current News"},
    {"id": 8, "category": "Technology", "question": "What is the difference between REST and GraphQL APIs, and when should you use each?", "needs_internet": False, "type": "Educational"},
    # CULTURE (Q9-Q11)
    {"id": 9, "category": "Culture", "question": "What movies won the major categories at the 2025 Oscars?", "needs_internet": True, "type": "Current News"},
    {"id": 10, "category": "Culture", "question": "Explain the cultural significance of Diwali and how it is celebrated", "needs_internet": False, "type": "Educational"},
    {"id": 11, "category": "Culture", "question": "What are the biggest music trends shaping pop culture in 2025?", "needs_internet": True, "type": "Current News"},
    # OTHER NEWS (Q12-Q15)
    {"id": 12, "category": "World News", "question": "What is the current status of the India-UK free trade agreement negotiations?", "needs_internet": True, "type": "Current Affairs"},
    {"id": 13, "category": "Environment", "question": "What are the main climate change initiatives announced at recent global summits?", "needs_internet": True, "type": "Current News"},
    {"id": 14, "category": "General", "question": "Explain what the Nobel Prize is and how laureates are selected", "needs_internet": False, "type": "Educational"},
    {"id": 15, "category": "Tech Policy", "question": "What are the latest developments in AI regulation and the EU AI Act?", "needs_internet": True, "type": "Current News"},
]

# ============================================================
# Configuration
# ============================================================
PACKAGE = "com.localyze"
ACTIVITY = "com.localyze/.MainActivity"
RESULTS_DIR = "chatbot_test_results"
SCREENSHOT_DIR = os.path.join(RESULTS_DIR, "screenshots")
RESPONSE_WAIT = 45  # seconds to wait for AI response
TAP_DELAY = 2       # seconds between taps
TEXT_DELAY = 1       # seconds after typing text

# ============================================================
# ADB Helper Functions
# ============================================================
def adb(cmd):
    """Run an ADB command and return output."""
    result = subprocess.run(f"adb shell {cmd}", shell=True, capture_output=True, text=True, timeout=60)
    return result.stdout.strip()

def adb_local(cmd):
    """Run a local command (like adb pull)."""
    result = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=60)
    return result.stdout.strip()

def is_device_connected():
    """Check if an ADB device is connected."""
    output = adb_local("adb devices")
    lines = output.strip().split('\n')
    for line in lines[1:]:
        if '\tdevice' in line:
            return True
    return False

def is_localyze_foreground():
    """Check if Localyze app is in the foreground."""
    output = adb("dumpsys window | grep mCurrentFocus")
    return PACKAGE in output

def get_current_focus():
    """Get the current foreground window."""
    output = adb("dumpsys window | grep mCurrentFocus")
    return output

def is_keyguard_showing():
    """Check if the lock screen is showing."""
    output = adb("dumpsys window | grep -E 'mIsShowing|mDreamingLockscreen'")
    return "mIsShowing=true" in output or "mDreamingLockscreen=true" in output

def wake_and_unlock():
    """Attempt to wake and unlock the device."""
    adb("input keyevent KEYCODE_WAKEUP")
    time.sleep(0.5)
    # Swipe up to dismiss lock screen
    adb("input swipe 360 2400 360 800")
    time.sleep(1)
    adb("input swipe 360 2400 360 800")
    time.sleep(1)

def launch_localyze():
    """Force-start Localyze app."""
    adb(f"am force-stop {PACKAGE}")
    time.sleep(1)
    adb(f"am start -W -n {ACTIVITY}")
    time.sleep(3)

def take_screenshot(device_path, local_path):
    """Take a screenshot and pull it to local machine."""
    adb(f"screencap -p {device_path}")
    adb_local(f"adb pull {device_path} {local_path}")
    return os.path.exists(local_path)

def tap_text_field():
    """Tap on the chat text input field (bottom of screen)."""
    adb("input tap 720 2800")
    time.sleep(TAP_DELAY)

def type_text(text):
    """Type text using ADB, handling special characters."""
    # ADB input text doesn't support spaces and some special chars
    # Use ADB keyboard events for better compatibility
    # Replace problematic characters
    escaped = text.replace(" ", "%s").replace("&", "\\&").replace("<", "\\<").replace(">", "\\>")
    escaped = escaped.replace("(", "\\(").replace(")", "\\)").replace(";", "\\;")
    escaped = escaped.replace(",", "\\,").replace("'", "\\'").replace("?", "\\?")
    escaped = escaped.replace("!", "\\!").replace('"', '\\"')
    adb(f"input text '{escaped}'")
    time.sleep(TEXT_DELAY)

def press_enter():
    """Press the Enter key to submit the message."""
    adb("input keyevent KEYCODE_ENTER")

def press_back():
    """Press Back button."""
    adb("input keyevent KEYCODE_BACK")

def start_new_chat():
    """Navigate to start a new chat conversation."""
    # Press back to go to conversation list, then start new
    press_back()
    time.sleep(1)
    # Try tapping the new chat FAB (typically bottom-right area)
    adb("input tap 1300 2900")
    time.sleep(2)

def dump_ui_to_file(local_path):
    """Dump UI hierarchy for analysis."""
    adb("uiautomator dump /sdcard/ui_dump_test.xml")
    adb_local(f"adb pull /sdcard/ui_dump_test.xml {local_path}")

# ============================================================
# Test Execution
# ============================================================
def run_test_question(q, mode="online"):
    """Run a single test question and capture the result."""
    qid = q["id"]
    question = q["question"]
    category = q["category"]
    suffix = "online" if mode == "online" else "offline"
    
    print(f"\n  [{qid}/15] Q{qid}: {question[:60]}...")
    print(f"         Category: {category} | Mode: {suffix}")
    
    # Start fresh conversation
    # Tap on text field
    tap_text_field()
    
    # Type the question
    type_text(question)
    time.sleep(1)
    
    # Submit
    press_enter()
    
    # Wait for response
    print(f"         Waiting for AI response ({RESPONSE_WAIT}s)...")
    time.sleep(RESPONSE_WAIT)
    
    # Take screenshot
    screenshot_device = f"/sdcard/test_q{qid}_{suffix}.png"
    screenshot_local = os.path.join(SCREENSHOT_DIR, f"q{qid}_{suffix}.png")
    success = take_screenshot(screenshot_device, screenshot_local)
    
    if success:
        print(f"         ✓ Screenshot saved: {screenshot_local}")
    else:
        print(f"         ✗ Failed to capture screenshot")
    
    # Dump UI for text extraction
    ui_local = os.path.join(SCREENSHOT_DIR, f"q{qid}_{suffix}_ui.xml")
    dump_ui_to_file(ui_local)
    
    # Go back for next question
    # Press back to go to conversation list
    press_back()
    time.sleep(1)
    
    return {"question_id": qid, "mode": suffix, "screenshot": screenshot_local, "ui_dump": ui_local}

def run_all_questions(mode="online"):
    """Run all 15 test questions."""
    os.makedirs(SCREENSHOT_DIR, exist_ok=True)
    
    print(f"\n{'='*60}")
    print(f"  Localyze Chatbot Test - {mode.upper()} Mode")
    print(f"  Time: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"{'='*60}")
    
    # Verify device
    if not is_device_connected():
        print("ERROR: No ADB device found. Please connect device and try again.")
        return []
    
    print(f"  Device connected: ✓")
    
    # Check keyguard
    if is_keyguard_showing():
        print("\n  ⚠ WARNING: Lock screen is active!")
        print("  Please UNLOCK your phone now.")
        print("  Press Enter when device is unlocked...")
        input()
        
        # Verify unlock
        for attempt in range(5):
            if not is_keyguard_showing():
                break
            print(f"  Still locked... attempt {attempt+1}/5. Swipe to unlock...")
            wake_and_unlock()
            time.sleep(2)
        
        if is_keyguard_showing():
            print("  ✗ Could not unlock device. Please unlock manually and retry.")
            return []
    
    print(f"  Keyguard dismissed: ✓")
    
    # Launch app
    launch_localyze()
    
    # Verify app is in foreground
    focus = get_current_focus()
    if PACKAGE not in focus:
        print(f"  ⚠ App not in foreground. Current focus: {focus}")
        print(f"  Attempting to bring app to front...")
        adb(f"am start -n {ACTIVITY}")
        time.sleep(3)
        focus = get_current_focus()
        if PACKAGE not in focus:
            print(f"  ✗ Could not bring Localyze to foreground.")
            print(f"  Please open the Localyze app manually and press Enter...")
            input()
    
    print(f"  App in foreground: ✓")
    print(f"  Current focus: {focus}")
    
    # Run each question
    results = []
    for q in QUESTIONS:
        result = run_test_question(q, mode)
        results.append(result)
    
    # Save results metadata
    results_file = os.path.join(RESULTS_DIR, f"results_{mode}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json")
    with open(results_file, 'w') as f:
        json.dump({
            "mode": mode,
            "timestamp": datetime.now().isoformat(),
            "questions": QUESTIONS,
            "results": results
        }, f, indent=2, default=str)
    
    print(f"\n{'='*60}")
    print(f"  {mode.upper()} Testing Complete!")
    print(f"  Screenshots saved to: {SCREENSHOT_DIR}")
    print(f"  Results saved to: {results_file}")
    print(f"{'='*60}")
    
    return results

# ============================================================
# Main
# ============================================================
def main():
    parser = argparse.ArgumentParser(description="Localyze Chatbot Test Automation")
    parser.add_argument("--online", action="store_true", help="Run online tests (WiFi ON)")
    parser.add_argument("--offline", action="store_true", help="Run offline tests (WiFi OFF)")
    parser.add_argument("--all", action="store_true", help="Run both online and offline tests")
    parser.add_argument("--wait", type=int, default=RESPONSE_WAIT, help="Seconds to wait for AI response")
    
    args = parser.parse_args()
    
    global RESPONSE_WAIT
    RESPONSE_WAIT = args.wait
    
    if not any([args.online, args.offline, args.all]):
        parser.print_help()
        print("\nPlease specify --online, --offline, or --all")
        sys.exit(1)
    
    if args.online or args.all:
        print("\n📋 PHASE 1: ONLINE TESTING")
        print("   Please ensure:")
        print("   1. WiFi is ON")
        print("   2. Web search is enabled in Localyze Settings")
        print("   3. Device is unlocked")
        print("\n   Press Enter to start...")
        input()
        run_all_questions("online")
    
    if args.offline or args.all:
        print("\n📋 PHASE 2: OFFLINE TESTING")
        print("   Please ensure:")
        print("   1. WiFi is OFF")
        print("   2. Web search is disabled in Localyze Settings")
        print("   3. Device is unlocked")
        print("\n   Press Enter to start...")
        input()
        run_all_questions("offline")
    
    print("\n✅ All testing complete!")
    print(f"   Review screenshots in: {SCREENSHOT_DIR}")
    print(f"   Fill in evaluation scores in: CHATBOT_TEST_RESULTS.md")

if __name__ == "__main__":
    main()