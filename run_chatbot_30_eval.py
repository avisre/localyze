#!/usr/bin/env python3
"""
Localyze 30-Question Evaluation Script
15 On-device knowledge questions + 15 Web search required questions
Measures: response time, correctness, UI behavior
"""
import subprocess
import time
import json
import re
import os
import sys

ADB = "/home/hardoker77/Downloads/new/localyze-main/.codex-tools/android-sdk/platform-tools/adb"
PKG = "com.localyze"
ACTIVITY = "com.localyze/.MainActivity"

# --- 15 On-device knowledge questions (model should answer from parametric knowledge) ---
KNOWLEDGE_QUESTIONS = [
    "What is the capital of France?",
    "What is 2 + 2?",
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

# --- 15 Web search required questions (requires real-time / external info) ---
WEB_SEARCH_QUESTIONS = [
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

RESULTS = []
LOG_DIR = "chatbot_test_results/eval30"
os.makedirs(LOG_DIR, exist_ok=True)


def clear_logcat():
    subprocess.run([ADB, "shell", "logcat", "-c"], capture_output=True)


def get_logcat_since(tag_filter=None, lines=200):
    cmd = [ADB, "shell", "logcat", "-d", "-v", "threadtime"]
    if tag_filter:
        cmd.extend(["-s", tag_filter])
    result = subprocess.run(cmd, capture_output=True, text=True)
    return result.stdout


def send_question(q, idx, category):
    print(f"\n{'='*60}")
    print(f"[{category}] Q{idx}: {q}")
    print(f"{'='*60}")

    # Clear previous logs
    clear_logcat()

    # Record start time
    t0 = time.time()

    # Send the message via intent
    subprocess.run([
        ADB, "shell", "am", "start", "-n", ACTIVITY,
        "-a", "android.intent.action.SEND",
        "--es", "chat_msg", q
    ], capture_output=True)

    # Wait for response to appear in UI (poll for up to 60s)
    response_text = ""
    elapsed = 0
    poll_interval = 1.0
    max_wait = 60.0
    ui_dump_path = "/sdcard/eval_dump.xml"

    while elapsed < max_wait:
        time.sleep(poll_interval)
        elapsed = time.time() - t0

        # Dump UI and look for assistant response (not user message)
        subprocess.run([ADB, "shell", "uiautomator", "dump", ui_dump_path],
                       capture_output=True)
        result = subprocess.run([ADB, "shell", "cat", ui_dump_path],
                                capture_output=True, text=True)

        # Find all TextView nodes with text content
        texts = re.findall(r'text="([^"]+)" resource-id="" class="android.widget.TextView"', result.stdout)
        # Filter out UI chrome and welcome message
        filtered = [t for t in texts if t not in [
            "Localyze.ai", "On-device", "Chat", "Code", "Library", "Settings",
            "Hello",  # user sent "Hello" from before
            "Hello! I'm Localyze.ai, your helpful, friendly AI assistant running entirely on-device for privacy. How can I help you today?",
            "Message Localyze.ai...", "just now"
        ] and len(t) > 10]

        if filtered:
            # The last long text is likely the assistant's latest response
            response_text = filtered[-1]
            break

    t1 = time.time()
    response_time = round(t1 - t0, 2)

    # Also grab any tool-related logs
    logs = get_logcat_since()
    tool_used = "web_search" in logs.lower() or "duckduckgo" in logs.lower() or "bing" in logs.lower()

    print(f"  Response time: {response_time}s")
    print(f"  Response text: {response_text[:200]}...")
    print(f"  Tool triggered: {tool_used}")

    return {
        "category": category,
        "index": idx,
        "question": q,
        "response_time_sec": response_time,
        "response_text": response_text,
        "tool_triggered": tool_used,
        "logs": logs[-3000:]  # last 3KB of logs
    }


def manual_eval_prompt(result):
    """Display for manual correctness scoring"""
    print(f"\n  --- Manual Evaluation ---")
    print(f"  Q: {result['question']}")
    print(f"  A: {result['response_text'][:300]}")
    print(f"  Time: {result['response_time_sec']}s | Tool: {result['tool_triggered']}")
    print(f"  ---")
    # For automated runs, we'll do best-effort keyword scoring
    return result


def run_evaluation():
    print("=" * 70)
    print(" LOCALYZE 30-QUESTION EVALUATION")
    print(" Device: OnePlus NE2211 | Android 16 | Gemma 4 E4B")
    print("=" * 70)

    # --- Knowledge Questions ---
    print("\n" + "#" * 70)
    print("# PART 1: ON-DEVICE KNOWLEDGE QUESTIONS (15)")
    print("#" * 70)
    for i, q in enumerate(KNOWLEDGE_QUESTIONS, 1):
        r = send_question(q, i, "KNOWLEDGE")
        RESULTS.append(r)
        manual_eval_prompt(r)
        # Brief pause between questions
        time.sleep(2)

    # --- Web Search Questions ---
    print("\n" + "#" * 70)
    print("# PART 2: WEB SEARCH REQUIRED QUESTIONS (15)")
    print("#" * 70)
    for i, q in enumerate(WEB_SEARCH_QUESTIONS, 1):
        r = send_question(q, i, "WEB_SEARCH")
        RESULTS.append(r)
        manual_eval_prompt(r)
        time.sleep(2)

    # --- Summary ---
    save_results()


def save_results():
    timestamp = time.strftime("%Y%m%d_%H%M%S")
    json_path = os.path.join(LOG_DIR, f"eval30_{timestamp}.json")
    md_path = os.path.join(LOG_DIR, f"eval30_{timestamp}.md")

    with open(json_path, "w") as f:
        json.dump(RESULTS, f, indent=2)

    # Build Markdown report
    lines = [
        "# Localyze 30-Question Evaluation Report",
        f"**Date:** {time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"**Device:** OnePlus NE2211 (Android 16, API 36)",
        f"**Model:** Gemma 4 E4B (LiteRT-LM, GPU backend)",
        "",
        "## Summary Statistics",
        f"- Total Questions: {len(RESULTS)}",
        f"- Knowledge Questions: {len([r for r in RESULTS if r['category']=='KNOWLEDGE'])}",
        f"- Web Search Questions: {len([r for r in RESULTS if r['category']=='WEB_SEARCH'])}",
        ""
    ]

    # Response times
    times = [r["response_time_sec"] for r in RESULTS if r["response_text"]]
    if times:
        lines.extend([
            "### Response Times (successful responses)",
            f"- Min: {min(times)}s",
            f"- Max: {max(times)}s",
            f"- Avg: {round(sum(times)/len(times), 2)}s",
            ""
        ])

    # Tool usage
    tool_count = sum(1 for r in RESULTS if r["tool_triggered"])
    lines.extend([
        "### Tool Usage",
        f"- Questions triggering web_search: {tool_count}/{len(RESULTS)}",
        ""
    ])

    # Detailed results table
    lines.extend([
        "## Detailed Results",
        "",
        "| # | Category | Question | Time | Tool Used | Response Preview |",
        "|---|----------|----------|------|-----------|------------------|"
    ])

    for r in RESULTS:
        preview = r["response_text"][:80].replace("|", "\\|").replace("\n", " ")
        tool = "Yes" if r["tool_triggered"] else "No"
        lines.append(f"| {r['index']} | {r['category']} | {r['question'][:40]}... | {r['response_time_sec']}s | {tool} | {preview}... |")

    lines.extend([
        "",
        "## Notes",
        "- Correctness evaluation requires manual review of responses.",
        "- UI/UX observations: streaming tokens, scroll behavior, composer state.",
        "- Tool confirmation dialogs may block automated responses.",
    ])

    with open(md_path, "w") as f:
        f.write("\n".join(lines))

    print(f"\n{'='*70}")
    print("EVALUATION COMPLETE")
    print(f"  JSON: {json_path}")
    print(f"  Markdown: {md_path}")
    print(f"{'='*70}")


if __name__ == "__main__":
    run_evaluation()