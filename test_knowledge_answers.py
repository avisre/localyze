#!/usr/bin/env python3
"""
Localyze Knowledge Answer Test
Tests 15 non-web-search questions and scores the model's answers
"""
import subprocess
import time
import json
import re
import os
import sys

ADB = "/home/hardoker77/Downloads/new/localyze-main/.codex-tools/android-sdk/platform-tools/adb"
ACTIVITY = "com.localyze/.MainActivity"
LOG_DIR = "chatbot_test_results/knowledge_test"

os.makedirs(LOG_DIR, exist_ok=True)

# 15 Knowledge questions (no web search needed)
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


def extract_all_text(xml_content):
    """Extract all text-like content from XML dump"""
    all_texts = []
    
    # Extract from text="..." attributes on TextView elements
    text_attrs = re.findall(r'text="([^"]+)"', xml_content)
    all_texts.extend(text_attrs)
    
    # Extract from content-desc="..." attributes (Compose UI text)
    content_descs = re.findall(r'content-desc="([^"]+)"', xml_content)
    all_texts.extend(content_descs)
    
    return all_texts


def send_and_capture(question, idx):
    """Send question to app and capture response"""
    t0 = time.time()
    subprocess.run([ADB, "shell", "logcat", "-c"], capture_output=True)
    
    subprocess.run([
        ADB, "shell", "am", "start", "-n", ACTIVITY,
        "-a", "android.intent.action.SEND",
        "--es", "chat_msg", question
    ], capture_output=True)
    
    response_text = ""
    elapsed = 0
    max_wait = 25.0
    ui_path = "/sdcard/eval_dump.xml"
    
    while elapsed < max_wait:
        time.sleep(1.0)
        elapsed = time.time() - t0
        
        subprocess.run([ADB, "shell", "uiautomator", "dump", ui_path],
                       capture_output=True, timeout=10)
        result = subprocess.run([ADB, "shell", "cat", ui_path],
                                capture_output=True, text=True, timeout=10)
        
        # Extract all text from XML
        texts = extract_all_text(result.stdout)
        
        # Filter out UI elements
        ui_elements = [
            "Localyze.ai", "On-device", "Chat", "Code", "Library", "Settings",
            "Hello! I'm Localyze.ai, your helpful, friendly AI assistant running entirely on-device for privacy. How can I help you today?",
            "Message Localyze.ai...", "just now", "Hello",
            "Report / Flag response", "Send", "Send message", "Tap to reply",
            "Share", "Backup text", "Backups", "Restore", "Subscription",
            "Restore from backup", "Restore your conversations", "Restore purchase",
            "Recover from Google Play", "Manage subscription", "Billing is handled by Google Play",
            "No active Google Play subscription", "Unavailable", "Backup passphrase",
            "Export", "Import", "Copy", "Export passphrase", "Import backup",
            "New conversation", "Privacy status", "Open conversations", "Start recording"
        ]
        
        filtered = [t for t in texts if t not in ui_elements and len(t) > 5]
        
        # Remove question from filtered results
        filtered = [t for t in filtered if t != question]
        
        if filtered:
            response_text = filtered[-1]
            break
    
    t1 = time.time()
    rt = round(t1 - t0, 2)
    
    logs = subprocess.run([ADB, "shell", "logcat", "-d"], capture_output=True, text=True).stdout
    tool_used = any(x in logs.lower() for x in ["web_search", "duckduckgo", "bing", "wikipedia"])
    
    print(f"  [Q{idx:02d}] {rt:5.1f}s | {'WEB' if tool_used else 'LOCAL'} | {response_text[:100]}")
    
    return {
        "index": idx,
        "question": question,
        "response_time_sec": rt,
        "response_text": response_text,
        "tool_triggered": tool_used,
    }


def score_answer(question, response, expected_answer):
    """Score the answer based on accuracy and completeness"""
    score = 0
    max_score = 10
    
    if not response or not response.strip():
        return 0, "Empty response"
    
    response_lower = response.lower().strip()
    expected_lower = expected_answer.lower().strip()
    
    # Exact match - full points
    if response_lower == expected_lower or expected_lower in response_lower:
        return max_score, "Exact match"
    
    # Fuzzy matching for factual answers
    keywords = expected_lower.split()
    matched_keywords = sum(1 for kw in keywords if kw in response_lower and len(kw) > 2)
    keyword_ratio = matched_keywords / max(len(keywords), 1)
    
    if keyword_ratio >= 0.7:
        score = int(max_score * keyword_ratio)
        return score, f"Good match ({keyword_ratio*100:.0f}%)"
    elif keyword_ratio >= 0.4:
        score = int(max_score * keyword_ratio)
        return score, f"Partial match ({keyword_ratio*100:.0f}%)"
    else:
        # Check if answer contains relevant information
        if len(response) > 10:
            return max(1, max_score // 3), "Some relevant info but incorrect key details"
        else:
            return 1, "Minimal/irrelevant response"


def run_all():
    """Run all 15 knowledge questions and score answers"""
    results = []
    print("=" * 80)
    print(" LOCALYZE KNOWLEDGE ANSWER TEST")
    print(" 15 Questions - Testing on-device model responses (NO web search)")
    print("=" * 80)
    
    # Expected answers for scoring
    expected_answers = [
        "Paris",           # 1
        "4",               # 2
        "William Shakespeare",  # 3
        "H2O",             # 4
        "8",               # 5
        "299,792,458 m/s", # 6 (approx)
        "Leonardo da Vinci", # 7
        "Pacific Ocean",   # 8
        "1945",            # 9
        "2",               # 10
        "Alexander Graham Bell",  # 11
        "Tokyo",           # 12
        "7",               # 13
        "Carbon dioxide",  # 14
        "0°C"              # 15
    ]
    
    print("\n--- RUNNING 15 KNOWLEDGE QUESTIONS ---")
    for i, q in enumerate(KNOWLEDGE_QS, 1):
        r = send_and_capture(q, i)
        expected = expected_answers[i-1]
        score, reason = score_answer(q, r["response_text"], expected)
        r["score"] = score
        r["max_score"] = 10
        r["expected"] = expected
        r["reason"] = reason
        results.append(r)
        time.sleep(1.5)
    
    # Calculate total score
    total_score = sum(r["score"] for r in results)
    max_total = len(results) * 10
    avg_score = total_score / len(results)
    
    # Check for any web search calls (should be 0 for these questions)
    web_search_calls = sum(1 for r in results if r["tool_triggered"])
    
    # Save results
    ts = time.strftime("%Y%m%d_%H%M%S")
    json_path = os.path.join(LOG_DIR, f"knowledge_test_{ts}.json")
    md_path = os.path.join(LOG_DIR, f"knowledge_test_{ts}.md")
    
    with open(json_path, "w") as f:
        json.dump(results, f, indent=2)
    
    # Build markdown report
    lines = [
        "# Localyze Knowledge Answer Test Report",
        f"**Date:** {time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"**Device:** OnePlus NE2211 | Android 16 | Gemma 4 E4B GPU",
        "",
        "## Summary",
        f"- **Total Questions:** {len(results)}",
        f"- **Web Search Used:** {web_search_calls}/{len(results)} (should be 0)",
        f"- **Total Score:** {total_score}/{max_total}",
        f"- **Average Score:** {round(avg_score, 2)}/10",
        "",
        "## Score Breakdown",
    ]
    
    # Add individual scores table
    lines.append("| # | Question | Response | Score | Max | Reason |")
    lines.append("|---|----------|----------|-------|-----|--------|")
    
    for r in results:
        q = r["question"][:40].replace("|", "\\|")
        a = r["response_text"][:50].replace("|", "\\|").replace("\n", " ")
        lines.append(f"| {r['index']:02d} | {q} | {a} | {r['score']} | {r['max_score']} | {r['reason']} |")
    
    # Add section for web search detection
    lines.extend([
        "",
        "## Web Search Detection Check",
        f"- Web search should NOT be triggered for knowledge questions",
        f"- Actual web search calls: {web_search_calls}",
        f"- Status: {'PASS - No web search used' if web_search_calls == 0 else 'WARNING - Web search was used'}",
        "",
        "## Test Questions Used (No Web Search Needed)",
    ])
    
    for i, q in enumerate(KNOWLEDGE_QS, 1):
        lines.append(f"{i}. {q}")
    
    with open(md_path, "w") as f:
        f.write("\n".join(lines))
    
    print(f"\n{'='*80}")
    print(f" RESULTS SUMMARY")
    print(f" {'='*80}")
    print(f" Total Score: {total_score}/{max_total} ({round(avg_score, 2)}/10 avg)")
    print(f" Web Search Used: {web_search_calls}/{len(results)} (should be 0)")
    print(f" {'='*80}")
    print(f" Results saved to:")
    print(f"   JSON: {json_path}")
    print(f"   Markdown: {md_path}")
    print(f" {'='*80}")
    
    # Exit with error if web search was used for knowledge questions
    if web_search_calls > 0:
        print(f"\n[WARNING] Web search was used for {web_search_calls} knowledge question(s)!")
    
    return results


if __name__ == "__main__":
    run_all()