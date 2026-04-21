#!/bin/bash

send_question() {
    local question="$1"
    local screenshot_name="$2"
    
    # Tap the text input field (EditText around y=2095)
    adb shell input tap 466 2100
    sleep 0.5
    
    # Clear any existing text
    adb shell input keyevent KEYCODE_MOVE_END
    sleep 0.2
    # Select all + delete to clear
    adb shell "input keyevent --longpress KEYCODE_DEL" 2>/dev/null || true
    sleep 0.3
    
    # Type the question
    adb shell input text "$(echo "$question" | sed 's/ /%s/g' | sed 's/"/\\"/g')" 
    sleep 0.5
    
    # Tap send button (around 990, 2096)
    adb shell input tap 990 2096
    sleep 0.5
    
    # Wait for response (generous wait for on-device inference)
    sleep 30
    
    # Take screenshot
    adb exec-out screencap -p > "C:/Users/avina/Downloads/ai7/${screenshot_name}.png"
    echo "Captured: ${screenshot_name}.png for Q: $question"
}

# First, clear the field completely
adb shell input tap 466 2100
sleep 0.5

# Question 1: Chat mode - General knowledge
send_question "What%ssize%sis%sEarth%sin%skilometers%3F" "q1_earth_size"

