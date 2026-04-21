#!/usr/bin/env python3
"""Ask the Localyze model 10 questions and capture screenshots of responses."""

import subprocess
import time
import os

SCRATCH = r"C:\Users\avina\Downloads\ai7"

def adb(cmd):
    result = subprocess.run(f'adb shell {cmd}', shell=True, capture_output=True, text=True, timeout=10)
    return result.stdout.strip()

def adb_screenshot(name):
    path = os.path.join(SCRATCH, f"{name}.png")
    subprocess.run(f'adb exec-out screencap -p > "{path}"', shell=True, timeout=10)
    return path

def adb_tap(x, y):
    adb(f'input tap {x} {y}')

def type_text(text):
    """Type text using adb shell input text with proper encoding."""
    encoded = text.replace(' ', '%s').replace('?', '%3F').replace("'", '%27').replace('!', '%21').replace(',', '%2C').replace(':', '%3A').replace(';', '%3B').replace('"', '%22').replace('\n', '')
    adb(f'input text "{encoded}"')

def ask_question(question, qnum):
    """Ask a question, wait for response, capture screenshot."""
    print(f"\n>>> Q{qnum}: {question}")
    
    # Tap text input field (approximately center of the text field)
    adb_tap(466, 2100)
    time.sleep(1)
    
    # Type the question
    type_text(question)
    time.sleep(1)
    
    # Tap send button
    adb_tap(990, 2096)
    time.sleep(30)  # wait for model inference
    
    # Capture
    path = adb_screenshot(f"q{qnum}")
    
    # Dump UI for text extraction
    adb('uiautomator dump /sdcard/ui.xml')
    result = adb('cat /sdcard/ui.xml')
    
    # Extract text from UI
    import re
    texts = re.findall(r'text="([^"]*)"', result)
    # Filter out empty, system, and nav items
    meaningful = [t for t in texts if t and t not in ('', 'Chat', 'Capabilities', 'Settings') 
                  and 'Ask anything' not in t and 'New conversation' not in t]
    
    print(f"    Response texts: {meaningful[-3:] if len(meaningful) > 3 else meaningful}")
    
    # Tap "New conversation" for next question
    adb_tap(115, 1906)
    time.sleep(2)

# 10 questions across different parameters
questions = [
    # 1. Chat mode - General knowledge
    "What is the capital of Japan",
    # 2. Chat mode - Creative writing
    "Tell me a short joke",
    # 3. Math/Logic
    "What is 15 percent of 200",
    # 4. Factual recall
    "How many planets are in our solar system",
    # 5. Practical advice
    "How do I boil an egg",
    # 6. Code/Technical
    "What is Python programming language",
    # 7. Personal/Emotional
    "How can I manage stress better",
    # 8. Tool-triggering: system info
    "What is my battery level",
    # 9. Tool-triggering: time
    "What time is it right now",
    # 10. Tool-triggering: memory
    "Remember that I like dark mode",
]

# Start fresh - make sure we're on the chat screen
print("Starting question sequence...")
for i, q in enumerate(questions, 1):
    ask_question(q, i)
    print(f"    Screenshot saved: q{i}.png")

print("\nDone! All 10 questions asked and captured.")