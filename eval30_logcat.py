#!/usr/bin/env python3
"""
Localyze 30-Question Evaluation v4
Extracts responses from GemmaInference logcat tokens (no uiautomator)
"""
import subprocess
import time
import json
import re
import os

ADB = "/home/hardoker77/Downloads/new/localyze-main/.codex-tools/android-sdk/platform-tools/adb"
ACT = "com.localyze/.MainActivity"
OUTDIR = "chatbot_test_results/eval30"
os.makedirs(OUTDIR, exist_ok=True)

KQ = [
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
    "What is the freezing point of water in Celsius?",
]

WQ = [
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
    "What are today's headlines from BBC News?",
]


def adb(*args, timeout=30):
    try:
        r = subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout)
        return r.stdout
    except subprocess.TimeoutExpired:
        return ""


def extract_tokens(logs):
    """Extract text tokens from GemmaInference logcat."""
    tokens = []
    for line in logs.splitlines():
        m = re.search(r"Received text content:\s*'([^']+)'", line)
        if m:
            tokens.append(m.group(1))
    return "".join(tokens)


def ask(q, idx, cat):
    print(f"\n[{cat}] Q{idx:02d}: {q}")
    adb("shell", "logcat", "-c")

    t0 = time.time()
    subprocess.run([
        ADB, "shell", "am", "start", "-n", ACT,
        "-a", "android.intent.action.SEND",
        "--es", "chat_msg", q
    ], capture_output=True)

    # Wait for onDone
    waited = 0
    for i in range(1, 35):
        time.sleep(1)
        logs = adb("shell", "logcat", "-d", "-s", "GemmaInference:D", timeout=10)
        if "onDone callback received" in logs:
            waited = i
            break

    # Give UI time to render
    time.sleep(0.5)

    # Get full logcat for token extraction
    all_logs = adb("shell", "logcat", "-d", "-s", "GemmaInference:D", timeout=10)
    response = extract_tokens(all_logs)

    t1 = time.time()
    total = round(t1 - t0, 1)

    # Check tool usage
    tool_logs = adb("shell", "logcat", "-d", timeout=10)
    tool_used = any(x in tool_logs.lower() for x in ["web_search", "duckduckgo", "bing", "wikipedia", "tooldispatcher"])

    # Clean response
    response = response.strip()
    # Remove system prompt leakage if any
    response = re.sub(r"<\|.*?\|>", "", response)
    response = re.sub(r"system.*?(user|assistant)", "", response, flags=re.DOTALL|re.IGNORECASE)

    ok = len(response) > 10
    status = "OK" if ok else "EMPTY"
    print(f"  -> {status} | {total}s | tool={tool_used} | {response[:120]}")

    return {
        "category": cat, "index": idx, "question": q,
        "response_time_sec": total, "response_text": response,
        "tool_triggered": tool_used, "status": status,
        "success": ok,
    }


def main():
    print("=" * 70)
    print(" LOCALYZE 30-Q EVAL v4 (logcat token extraction)")
    print(" OnePlus NE2211 | Android 16 | Gemma 4 E4B GPU")
    print("=" * 70)

    results = []
    print("\n--- PART 1: KNOWLEDGE (15) ---")
    for i, q in enumerate(KQ, 1):
        results.append(ask(q, i, "KNOWLEDGE"))

    print("\n--- PART 2: WEB SEARCH (15) ---")
    for i, q in enumerate(WQ, 1):
        results.append(ask(q, i, "WEB_SEARCH"))

    # Save
    ts = time.strftime("%Y%m%d_%H%M%S")
    jpath = os.path.join(OUTDIR, f"eval30_{ts}.json")
    mpath = os.path.join(OUTDIR, f"eval30_{ts}.md")

    with open(jpath, "w") as f:
        json.dump(results, f, indent=2)

    ok = [r for r in results if r["success"]]
    times = [r["response_time_sec"] for r in ok]
    tool_cnt = sum(1 for r in results if r["tool_triggered"])

    lines = [
        f"# Localyze 30-Question Evaluation Report",
        f"**Date:** {time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"**Device:** OnePlus NE2211 | Android 16 | Gemma 4 E4B GPU",
        "",
        "## Summary",
        f"- Total questions: {len(results)}",
        f"- Successful responses: {len(ok)}/{len(results)}",
        f"- Tool triggered: {tool_cnt}/{len(results)}",
    ]
    if times:
        lines.extend([
            f"- Min time: {min(times)}s",
            f"- Max time: {max(times)}s",
            f"- Avg time: {round(sum(times)/len(times),1)}s",
        ])
    lines.append("")

    lines.extend([
        "| # | Cat | Time | Tool | Status | Question | Response |",
        "|---|-----|------|------|--------|----------|----------|"
    ])
    for r in results:
        q = r["question"][:40].replace("|", "\\|")
        a = r["response_text"][:55].replace("|", "\\|").replace("\n", " ")
        lines.append(f"| {r['index']:02d} | {r['category'][:3]} | {r['response_time_sec']}s | {'Y' if r['tool_triggered'] else 'N'} | {r['status']} | {q} | {a} |")

    with open(mpath, "w") as f:
        f.write("\n".join(lines))

    print(f"\n{'='*70}")
    print(f" DONE: {jpath}")
    print(f" Success: {len(ok)}/{len(results)}")
    print(f"{'='*70}")


if __name__ == "__main__":
    main()