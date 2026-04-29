@echo off
REM ============================================================
REM Localyze Chatbot Test Script
REM Prerequisites: Device unlocked, Localyze app open, ADB connected
REM ============================================================
REM
REM INSTRUCTIONS:
REM 1. Unlock your phone
REM 2. Open the Localyze app
REM 3. Make sure WiFi is ON
REM 4. Run this script: run_chatbot_tests.bat
REM
REM The script will:
REM - Take screenshots of each test result
REM - Save them to the chatbot_test_results folder
REM - You can then evaluate the responses
REM ============================================================

set RESULTS_DIR=chatbot_test_results
set SCREENSHOT_DIR=%RESULTS_DIR%\screenshots

if not exist %RESULTS_DIR% mkdir %RESULTS_DIR%
if not exist %SCREENSHOT_DIR% mkdir %SCREENSHOT_DIR%

echo ============================================================
echo Localyze Chatbot Test Suite
echo ============================================================
echo.
echo Device check:
adb devices
echo.

REM Check if Localyze is in foreground
echo Checking if Localyze is running...
for /f "tokens=*" %%i in ('adb shell "dumpsys window | grep mCurrentFocus"') do set FOCUS=%%i
echo Current focus: %FOCUS%
echo.

echo ============================================================
echo PHASE 1: ONLINE TESTING (Web Search Enabled)
echo ============================================================
echo.
echo Please make sure:
echo  1. WiFi is ON
echo  2. Localyze app is open
echo  3. Web search is enabled in Settings
echo.
echo Press any key to start online testing...
pause >nul

REM Start Localyze app
adb shell am start -n com.localyze/.MainActivity

REM Wait for app to load
ping -n 4 127.0.0.1 >nul

REM Test Q1 - Finance: Federal Funds Rate
echo [1/15] Testing Q1: What is the current Federal Funds rate...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "What is the current Federal Funds rate set by the US Federal Reserve?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q1_online.png
adb pull /sdcard/test_q1_online.png %SCREENSHOT_DIR%\q1_online.png
echo Q1 screenshot saved.
echo.

REM New chat for Q2
echo [2/15] Testing Q2: Yield curve inversion...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "Explain what a yield curve inversion means and why it matters for the economy"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q2_online.png
adb pull /sdcard/test_q2_online.png %SCREENSHOT_DIR%\q2_online.png
echo Q2 screenshot saved.
echo.

REM New chat for Q3
echo [3/15] Testing Q3: Cryptocurrency regulation 2025...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "What are the latest trends in cryptocurrency regulation in 2025?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q3_online.png
adb pull /sdcard/test_q3_online.png %SCREENSHOT_DIR%\q3_online.png
echo Q3 screenshot saved.
echo.

REM New chat for Q4
echo [4/15] Testing Q4: Compound interest and APR vs APY...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "How does compound interest work, and what is the difference between APR and APY?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q4_online.png
adb pull /sdcard/test_q4_online.png %SCREENSHOT_DIR%\q4_online.png
echo Q4 screenshot saved.
echo.

REM New chat for Q5
echo [5/15] Testing Q5: Android 16 features...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "What are the latest features in Android 16?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q5_online.png
adb pull /sdcard/test_q5_online.png %SCREENSHOT_DIR%\q5_online.png
echo Q5 screenshot saved.
echo.

REM New chat for Q6
echo [6/15] Testing Q6: How LLMs work...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "Explain how large language models work in simple terms"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q6_online.png
adb pull /sdcard/test_q6_online.png %SCREENSHOT_DIR%\q6_online.png
echo Q6 screenshot saved.
echo.

REM New chat for Q7
echo [7/15] Testing Q7: Quantum computing 2025...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "What is the current state of quantum computing in 2025?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q7_online.png
adb pull /sdcard/test_q7_online.png %SCREENSHOT_DIR%\q7_online.png
echo Q7 screenshot saved.
echo.

REM New chat for Q8
echo [8/15] Testing Q8: REST vs GraphQL...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "What is the difference between REST and GraphQL APIs, and when should you use each?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q8_online.png
adb pull /sdcard/test_q8_online.png %SCREENSHOT_DIR%\q8_online.png
echo Q8 screenshot saved.
echo.

REM New chat for Q9
echo [9/15] Testing Q9: 2025 Oscars...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "What movies won the major categories at the 2025 Oscars?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q9_online.png
adb pull /sdcard/test_q9_online.png %SCREENSHOT_DIR%\q9_online.png
echo Q9 screenshot saved.
echo.

REM New chat for Q10
echo [10/15] Testing Q10: Diwali significance...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "Explain the cultural significance of Diwali and how it is celebrated"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q10_online.png
adb pull /sdcard/test_q10_online.png %SCREENSHOT_DIR%\q10_online.png
echo Q10 screenshot saved.
echo.

REM New chat for Q11
echo [11/15] Testing Q11: Music trends 2025...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "What are the biggest music trends shaping pop culture in 2025?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q11_online.png
adb pull /sdcard/test_q11_online.png %SCREENSHOT_DIR%\q11_online.png
echo Q11 screenshot saved.
echo.

REM New chat for Q12
echo [12/15] Testing Q12: India-UK FTA...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "What is the current status of the India-UK free trade agreement negotiations?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q12_online.png
adb pull /sdcard/test_q12_online.png %SCREENSHOT_DIR%\q12_online.png
echo Q12 screenshot saved.
echo.

REM New chat for Q13
echo [13/15] Testing Q13: Climate change initiatives...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "What are the main climate change initiatives announced at recent global summits?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q13_online.png
adb pull /sdcard/test_q13_online.png %SCREENSHOT_DIR%\q13_online.png
echo Q13 screenshot saved.
echo.

REM New chat for Q14
echo [14/15] Testing Q14: Nobel Prize...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "Explain what the Nobel Prize is and how laureates are selected"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q14_online.png
adb pull /sdcard/test_q14_online.png %SCREENSHOT_DIR%\q14_online.png
echo Q14 screenshot saved.
echo.

REM New chat for Q15
echo [15/15] Testing Q15: AI regulation and EU AI Act...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "What are the latest developments in AI regulation and the EU AI Act?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q15_online.png
adb pull /sdcard/test_q15_online.png %SCREENSHOT_DIR%\q15_online.png
echo Q15 screenshot saved.
echo.

echo ============================================================
echo PHASE 1 COMPLETE - Online screenshots saved to %SCREENSHOT_DIR%
echo ============================================================
echo.
echo Now please:
echo  1. Turn OFF WiFi on the device
echo  2. Disable Web Search in Localyze Settings
echo  3. Press any key to start offline testing...
pause >nul

echo ============================================================
echo PHASE 2: OFFLINE TESTING (No Internet, No Web Search)
echo ============================================================
echo.

REM Reopen the app fresh
adb shell am start -n com.localyze/.MainActivity
ping -n 4 127.0.0.1 >nul

REM Repeat all 15 questions offline (screenshots will have _offline suffix)
echo [1/15] Testing Q1 offline...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "What is the current Federal Funds rate set by the US Federal Reserve?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q1_offline.png
adb pull /sdcard/test_q1_offline.png %SCREENSHOT_DIR%\q1_offline.png

echo [2/15] Testing Q2 offline...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "Explain what a yield curve inversion means and why it matters for the economy"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q2_offline.png
adb pull /sdcard/test_q2_offline.png %SCREENSHOT_DIR%\q2_offline.png

echo [3/15] Testing Q3 offline...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "What are the latest trends in cryptocurrency regulation in 2025?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q3_offline.png
adb pull /sdcard/test_q3_offline.png %SCREENSHOT_DIR%\q3_offline.png

echo [4/15] Testing Q4 offline...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "How does compound interest work, and what is the difference between APR and APY?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q4_offline.png
adb pull /sdcard/test_q4_offline.png %SCREENSHOT_DIR%\q4_offline.png

echo [5/15] Testing Q5 offline...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "What are the latest features in Android 16?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q5_offline.png
adb pull /sdcard/test_q5_offline.png %SCREENSHOT_DIR%\q5_offline.png

echo [6/15] Testing Q6 offline...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "Explain how large language models work in simple terms"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q6_offline.png
adb pull /sdcard/test_q6_offline.png %SCREENSHOT_DIR%\q6_offline.png

echo [7/15] Testing Q7 offline...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "What is the current state of quantum computing in 2025?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q7_offline.png
adb pull /sdcard/test_q7_offline.png %SCREENSHOT_DIR%\q7_offline.png

echo [8/15] Testing Q8 offline...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "What is the difference between REST and GraphQL APIs, and when should you use each?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q8_offline.png
adb pull /sdcard/test_q8_offline.png %SCREENSHOT_DIR%\q8_offline.png

echo [9/15] Testing Q9 offline...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "What movies won the major categories at the 2025 Oscars?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q9_offline.png
adb pull /sdcard/test_q9_offline.png %SCREENSHOT_DIR%\q9_offline.png

echo [10/15] Testing Q10 offline...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "Explain the cultural significance of Diwali and how it is celebrated"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q10_offline.png
adb pull /sdcard/test_q10_offline.png %SCREENSHOT_DIR%\q10_offline.png

echo [11/15] Testing Q11 offline...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "What are the biggest music trends shaping pop culture in 2025?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q11_offline.png
adb pull /sdcard/test_q11_offline.png %SCREENSHOT_DIR%\q11_offline.png

echo [12/15] Testing Q12 offline...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "What is the current status of the India-UK free trade agreement negotiations?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q12_offline.png
adb pull /sdcard/test_q12_offline.png %SCREENSHOT_DIR%\q12_offline.png

echo [13/15] Testing Q13 offline...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "What are the main climate change initiatives announced at recent global summits?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q13_offline.png
adb pull /sdcard/test_q13_offline.png %SCREENSHOT_DIR%\q13_offline.png

echo [14/15] Testing Q14 offline...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "Explain what the Nobel Prize is and how laureates are selected"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q14_offline.png
adb pull /sdcard/test_q14_offline.png %SCREENSHOT_DIR%\q14_offline.png

echo [15/15] Testing Q15 offline...
adb shell input tap 720 2800
ping -n 2 127.0.0.1 >nul
adb shell input text "What are the latest developments in AI regulation and the EU AI Act?"
ping -n 1 127.0.0.1 >nul
adb shell input keyevent KEYCODE_ENTER
ping -n 30 127.0.0.1 >nul
adb shell screencap -p /sdcard/test_q15_offline.png
adb pull /sdcard/test_q15_offline.png %SCREENSHOT_DIR%\q15_offline.png

echo ============================================================
echo PHASE 2 COMPLETE - Offline screenshots saved to %SCREENSHOT_DIR%
echo ============================================================
echo.
echo All 30 screenshots (15 online + 15 offline) saved to: %SCREENSHOT_DIR%
echo Please review the screenshots and fill in the evaluation scores
echo in CHATBOT_TEST_RESULTS.md
echo.
echo Don't forget to turn WiFi back ON!
echo ============================================================
pause