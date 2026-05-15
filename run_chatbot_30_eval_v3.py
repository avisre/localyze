#!/usr/bin/env python3
"""
Localyze 30-Question Evaluation Script v3
Uses logcat polling for completion detection + single UIAutomator dump
"""
import subprocess
import time
import json
import re
import os

ADB = "/home/hardoker77/Downloads/new/localyze-main/.codex-tools/android-sdk/platform-tools/adb"
ACTIVITY = "com.localyze/.MainActivity"
LOG_DIR = "chatbot_test_results/eval30"

os.makedirs(LOG_DIR, exist_ok=True)

KNOWLEDGE_QS = [
    "What is the capital of France?",
    "What is 2 plus 2?",
    "Who wrote Romeo and Juliet?",
    "What is the chemical symbol for water?",
    "How many planets are in our solar system?",
    "What is the speed of light approximately?",
    "Who painted the Mona Lisa?",
    "What is the largest ocean on Earth?",
    "What year did World War II end?",
    "What is the smallest prime number?",
    "Who invented the telephone?",
    "What is the capital of Japan?",
    "How many continents are there?",
    "What gas do plants absorb from the atmosphere?",
    "What is the freezing point of water in Celsius?"
]

WEB_SEARCH_QS = [
    "What is the current weather in New York City?",
    "What is the price of Bitcoin right now?",
    "Who is the current CEO of Google?",
    "What was the score of the latest FIFA World Cup final?",
    "What movies are playing in theaters this week?",
    "What is the current population of India?",
    "What is the latest news about SpaceX?",
    "What is the stock price of Apple today?",
    "Who won the most recent Nobel Prize in Literature?",
    "What is the current exchange rate USD to EUR?",
    "What are the top trending topics on Twitter right now?",
    "What is the latest version of Android released?",
    "Who is the current president of the United States?",
    "What is the current GDP growth rate of China?",
    "What are today's headlines from BBC News?"
]


def run_adb(*args, timeout=30, text=True):
    try:
        return subprocess.run([ADB, *args], capture_output=True, text=text, timeout=timeout)
    except subprocess.TimeoutExpired:
        return None


def clear_logcat():
    subprocess.run([ADB, "shell", "logcat", "-c"], capture_output=True)


def wait_for_inference_done(max_wait=25.0):
    """Poll logcat for onDone callback. Returns True if found."""
    t0 = time.time()
    while time.time() - t0 < max_wait:
        time.sleep(1.0)
        r = run_adb("shell", "logcat", "-d", "-s", "GemmaInference:D", timeout=10)
        if r and "onDone callback received" in r.stdout:
            return True, round(time.time() - t0, 2)
    return False, round(time.time() - t0, 2)


def extract_response_text():
    """Single uiautomator dump, extract assistant response."""
    r = run_adb("shell", "uiautomator", "dump", "/sdcard/eval_final.xml", timeout=15)
    if not r:
        return ""
    r = run_adb("shell", "cat", "/sdcard/eval_final.xml", timeout=10)
    if not r:
        return ""

    texts = re.findall(r'text="([^"]+)" resource-id="" class="android.widget.TextView"', r.stdout)
    # Filter UI chrome
    filtered = [t for t in texts if t not in [
        "Localyze.ai", "On-device", "Chat", "Code", "Library", "Settings",
        "Hello! I'm Localyze.ai, your helpful, friendly AI assistant running entirely on-device for privacy. How can I help you today?",
        "Message Localyze.ai...", "just now", "Hello"
    ] and len(t) > 5]

    if not filtered:
        return ""
    # The latest assistant response (not user question)
    # User questions are right-aligned short texts; assistant is left-aligned long
    # Take the longest remaining text as response
    return max(filtered, key=len)


def send_question(question, idx, category):
    print(f"\n[{category}] Q{idx:02d}: {question[:50]}")
    clear_logcat()

    t0 = time.time()
    # Send via intent
    subprocess.run([
        ADB, "shell", "am", "start", "-n", ACTIVITY,
        "-a", "android.intent.action.SEND",
        "--es", "chat_msg", question
    ], capture_output=True)

    # Wait for inference completion via logcat
    done, inference_time = wait_for_inference_done(max_wait=22.0)

    # Give a moment for UI to update
    time.sleep(0.5)

    response_text = extract_response_text() if done else ""

    t1 = time.time()
    total_time = round(t1 - t0, 2)

    # Check if tool was triggered
    logs = run_adb("shell", "logcat", "-d", timeout=10)
    tool_used = False
    if logs:
        tool_used = any(x in logs.stdout.lower() for x in ["web_search", "duckduckgo", "bing", "wikipedia", "tooldispatcher"])

    status = "OK" if response_text else "TIMEOUT/EMPTY"
    print(f"  -> {status} | total={total_time}s inference_wait={inference_time}s | tool={tool_used}")
    print(f"  -> {response_text[:120]}")

    return {
        "category": category,
        "index": idx,
        "question": question,
        "total_time_sec": total_time,
        "inference_wait_sec": inference_time,
        "response_text": response_text,
        "tool_triggered": tool_used,
        "success": bool(response_text),
    }


def run_eval():
    results = []
    print("=" * 75)
    print(" LOCALYZE 30-QUESTION EVALUATION v3")
    print(" OnePlus NE2211 | Android 16 | Gemma 4 E4B GPU")
    print("=" * 75)

    # Ensure fresh start
    subprocess.run([ADB, "shell", "am", "force-stop", "com.localyze"], capture_output=True)
    time.sleep(1)
    subprocess.run([ADB, "shell", "am", "start", "-n", ACTIVITY], capture_output=True)
    time.sleep(4)
    # Tap "New conversation" to clear previous
    subprocess.run([ADB, "shell", "input", "tap", "1280", "280"], capture_output=True)
    time.sleep(1)

    print("\n--- PART 1: KNOWLEDGE (15) ---")
    for i, q in enumerate(KNOWLEDGE_QS, 1):
        r = send_question(q, i, "KNOWLEDGE")
        results.append(r)
        time.sleep(1.5)

    print("\n--- PART 2: WEB SEARCH (15) ---")
    for i, q in enumerate(WEB_SEARCH_QS, 1):
        r = send_question(q, i, "WEB_SEARCH")
        results.append(r)
        time.sleep(1.5)

    # Save
    ts = time.strftime("%Y%m%d_%H%M%S")
    jpath = os.path.join(LOG_DIR, f"eval30_{ts}.json")
    mpath = os.path.join(LOG_DIR, f"eval30_{ts}.md")

    with open(jpath, "w") as f:
        json.dump(results, f, indent=2)

    ok_results = [r for r in results if r["success"]]
    times = [r["total_time_sec"] for r in ok_results]
    tool_count = sum(1 for r in results if r["tool_triggered"])

    lines = [
        "# Localyze 30-Question Evaluation Report",
        f"**Date:** {time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"**Device:** OnePlus NE2211 | Android 16 | Gemma 4 E4B GPU",
        "",
        "## Summary",
        f"- Total questions: {len(results)}",
        f"- Successful responses: {len(ok_results)}/{len(results)}",
        f"- Knowledge answered: {len([r for r in ok_results if r['category']=='KNOWLEDGE'])}/15",
        f"- Web search answered: {len([r for r in ok_results if r['category']=='WEB_SEARCH'])}/15",
        f"- Tool triggered: {tool_count}/{len(results)}",
    ]
    if times:
        lines.extend([
            f"- Min response time: {min(times)}s",
            f"- Max response time: {max(times)}s",
            f"- Avg response time: {round(sum(times)/len(times),2)}s",
        ])
    lines.append("")

    lines.extend([
        "| # | Category | Time | Tool | Question | Response |",
        "|---|----------|------|------|----------|----------|"
    ])
    for r in results:
        q = r["question"][:45].replace("|", "\\|")
        a = r["response_text"][:60].replace("|", "\\|").replace("\n", " ")
        t = "Yes" if r["tool_triggered"] else "No"
        s = "OK" if r["success"] else "FAIL"
        lines.append(f"| {r['index']:02d} | {r['category']} | {r['total_time_sec']}s | {t} | {q} | {s}: {a} |")

    with open(mpath, "w") as f:
        f.write("\n".join(lines))

    print(f"\n{'='*75}")
    print(f" COMPLETE: {jpath}")
    print(f"           {mpath}")
    print(f" Success rate: {len(ok_results)}/{len(results)}")
    print(f"{'='*75}")


if __name__ == "__main__":
    run_eval()