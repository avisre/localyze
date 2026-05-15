#!/usr/bin/env python3
"""
Localyze 30-Question Evaluation - FINAL
Uses logcat token extraction, proper quoting via shell=True
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


def run_shell(cmd, timeout=30):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return r.stdout, r.stderr
    except subprocess.TimeoutExpired:
        return "", ""


def clear_logcat():
    run_shell(f'{ADB} shell logcat -c')


def extract_tokens_from_logcat():
    out, _ = run_shell(f'{ADB} shell logcat -d -s GemmaInference:D', timeout=15)
    tokens = []
    for line in out.splitlines():
        m = re.search(r"Received text content:\s*'([^']+)'", line)
        if m:
            tokens.append(m.group(1))
    return "".join(tokens).strip()


def ask(q, idx, cat):
    print(f"\n[{cat}] Q{idx:02d}: {q}")
    clear_logcat()

    t0 = time.time()
    # Send via intent with full command as single shell string
    run_shell(f'{ADB} shell \'am start -n {ACT} -a android.intent.action.SEND --es chat_msg "{q}"\'')

    # Wait for onDone
    waited = 0
    for i in range(1, 35):
        time.sleep(1)
        out, _ = run_shell(f'{ADB} shell logcat -d -s GemmaInference:D', timeout=10)
        if "onDone callback received" in out:
            waited = i
            break

    time.sleep(0.5)
    response = extract_tokens_from_logcat()
    t1 = time.time()
    total = round(t1 - t0, 1)

    # Check tool
    tool_out, _ = run_shell(f'{ADB} shell logcat -d', timeout=10)
    tool_used = any(x in tool_out.lower() for x in ["web_search", "duckduckgo", "bing", "wikipedia", "tooldispatcher"])

    ok = len(response) > 10
    status = "OK" if ok else "EMPTY"
    print(f"  -> {status} | {total}s | tool={tool_used} | {response[:100]}")
    return {
        "category": cat, "index": idx, "question": q,
        "response_time_sec": total, "response_text": response,
        "tool_triggered": tool_used, "status": status, "success": ok,
    }


def main():
    results = []
    print("=" * 70)
    print(" LOCALYZE 30-Q EVALUATION FINAL")
    print(" OnePlus NE2211 | Android 16 | Gemma 4 E4B GPU")
    print("=" * 70)

    print("\n--- KNOWLEDGE (15) ---")
    for i, q in enumerate(KQ, 1):
        results.append(ask(q, i, "KNOWLEDGE"))

    print("\n--- WEB SEARCH (15) ---")
    for i, q in enumerate(WQ, 1):
        results.append(ask(q, i, "WEB_SEARCH"))

    ts = time.strftime("%Y%m%d_%H%M%S")
    jpath = os.path.join(OUTDIR, f"eval30_{ts}.json")
    mpath = os.path.join(OUTDIR, f"eval30_{ts}.md")

    with open(jpath, "w") as f:
        json.dump(results, f, indent=2)

    ok_results = [r for r in results if r["success"]]
    times = [r["response_time_sec"] for r in ok_results]
    tool_cnt = sum(1 for r in results if r["tool_triggered"])

    lines = [
        "# Localyze 30-Question Evaluation Report",
        f"**Date:** {time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"**Device:** OnePlus NE2211 | Android 16 | Gemma 4 E4B GPU",
        "",
        "## Summary",
        f"- Total: {len(results)}",
        f"- Successful: {len(ok_results)}/{len(results)}",
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
    print(f" Success: {len(ok_results)}/{len(results)}")
    print(f"{'='*70}")


if __name__ == "__main__":
    main()