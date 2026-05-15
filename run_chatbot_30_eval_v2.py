#!/usr/bin/env python3
"""
Localyze 30-Question Evaluation Script v2
Faster: 15s timeout, proper quoting, real-time logging
"""
import subprocess
import time
import json
import re
import os
import sys

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


def send_and_capture(question, idx, category):
    t0 = time.time()
    # Clear logcat
    subprocess.run([ADB, "shell", "logcat", "-c"], capture_output=True)

    # Send intent with proper list-based quoting
    subprocess.run([
        ADB, "shell", "am", "start", "-n", ACTIVITY,
        "-a", "android.intent.action.SEND",
        "--es", "chat_msg", question
    ], capture_output=True)

    response_text = ""
    elapsed = 0
    max_wait = 18.0
    ui_path = "/sdcard/eval_dump.xml"

    while elapsed < max_wait:
        time.sleep(0.8)
        elapsed = time.time() - t0

        subprocess.run([ADB, "shell", "uiautomator", "dump", ui_path],
                       capture_output=True, timeout=10)
        result = subprocess.run([ADB, "shell", "cat", ui_path],
                                capture_output=True, text=True, timeout=10)

        texts = re.findall(r'text="([^"]+)" resource-id="" class="android.widget.TextView"', result.stdout)

        filtered = [t for t in texts if t not in [
            "Localyze.ai", "On-device", "Chat", "Code", "Library", "Settings",
            "Hello! I'm Localyze.ai, your helpful, friendly AI assistant running entirely on-device for privacy. How can I help you today?",
            "Message Localyze.ai...", "just now", "Hello"
        ] and len(t) > 5]

        if filtered:
            # Last text is latest assistant response
            response_text = filtered[-1]
            # Verify it's not just the question itself (right-aligned)
            if response_text != question:
                break

    t1 = time.time()
    rt = round(t1 - t0, 2)

    logs = subprocess.run([ADB, "shell", "logcat", "-d"], capture_output=True, text=True).stdout
    tool_used = any(x in logs.lower() for x in ["web_search", "duckduckgo", "bing", "wikipedia"])

    print(f"  [{category}] Q{idx:02d} | {rt:5.1f}s | tool={tool_used} | {response_text[:100]}")

    return {
        "category": category,
        "index": idx,
        "question": question,
        "response_time_sec": rt,
        "response_text": response_text,
        "tool_triggered": tool_used,
    }


def run_all():
    results = []
    print("=" * 75)
    print(" LOCALYZE 30-QUESTION EVALUATION v2")
    print(" Device: OnePlus NE2211 | Gemma 4 E4B GPU")
    print("=" * 75)

    print("\n--- PART 1: KNOWLEDGE (15 questions) ---")
    for i, q in enumerate(KNOWLEDGE_QS, 1):
        r = send_and_capture(q, i, "KNOWLEDGE")
        results.append(r)
        time.sleep(1.5)

    print("\n--- PART 2: WEB SEARCH (15 questions) ---")
    for i, q in enumerate(WEB_SEARCH_QS, 1):
        r = send_and_capture(q, i, "WEB_SEARCH")
        results.append(r)
        time.sleep(1.5)

    # Save results
    ts = time.strftime("%Y%m%d_%H%M%S")
    json_path = os.path.join(LOG_DIR, f"eval30_{ts}.json")
    md_path = os.path.join(LOG_DIR, f"eval30_{ts}.md")

    with open(json_path, "w") as f:
        json.dump(results, f, indent=2)

    # Build markdown
    times = [r["response_time_sec"] for r in results if r["response_text"]]
    tool_count = sum(1 for r in results if r["tool_triggered"])

    lines = [
        "# Localyze 30-Question Evaluation Report",
        f"**Date:** {time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"**Device:** OnePlus NE2211 | Android 16 | Gemma 4 E4B GPU",
        "",
        "## Summary",
        f"- Total: {len(results)} questions",
        f"- Knowledge: {len([r for r in results if r['category']=='KNOWLEDGE'])}",
        f"- Web Search: {len([r for r in results if r['category']=='WEB_SEARCH'])}",
        f"- Avg response time: {round(sum(times)/len(times),2)}s" if times else "- No responses captured",
        f"- Tool triggered: {tool_count}/{len(results)}",
        "",
        "| # | Category | Time | Tool | Question | Response |",
        "|---|----------|------|------|----------|----------|"
    ]

    for r in results:
        q = r["question"][:45].replace("|", "\\|")
        a = r["response_text"][:70].replace("|", "\\|").replace("\n", " ")
        tool = "Yes" if r["tool_triggered"] else "No"
        lines.append(f"| {r['index']:02d} | {r['category']} | {r['response_time_sec']}s | {tool} | {q} | {a} |")

    with open(md_path, "w") as f:
        f.write("\n".join(lines))

    print(f"\n{'='*75}")
    print(f" DONE: {json_path}")
    print(f"       {md_path}")
    print(f"{'='*75}")


if __name__ == "__main__":
    run_all()