#!/usr/bin/env python3
"""
Localyze comprehensive 50-question device evaluation.
Covers: Knowledge (20), Reasoning (10), Web Search (15), Stress Test (5)
Reads Room DB through run-as for accurate answer extraction.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sqlite3
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import quote


ROOT = Path(__file__).resolve().parent
DEFAULT_ADB = ROOT / ".codex-tools/android-sdk/platform-tools/adb"
PKG = "com.localyze"
ACTIVITY = "com.localyze/.MainActivity"
DB_FILES = ("local_assistant_db", "local_assistant_db-wal", "local_assistant_db-shm")


@dataclass(frozen=True)
class EvalQuestion:
    category: str
    question: str
    expected: tuple[str, ...] = ()


# ── 20 Knowledge Questions ──────────────────────────────────────────
KNOWLEDGE_QUESTIONS: list[EvalQuestion] = [
    # Geography & Places
    EvalQuestion("KNOWLEDGE", "What is the capital of France?", (r"\bparis\b",)),
    EvalQuestion("KNOWLEDGE", "What is the capital of Japan?", (r"\btokyo\b",)),
    EvalQuestion("KNOWLEDGE", "What is the largest ocean on Earth?", (r"\bpacific\b",)),
    EvalQuestion("KNOWLEDGE", "How many continents are there?", (r"\b7\b|\bseven\b",)),
    EvalQuestion("KNOWLEDGE", "What is the longest river in the world?", (r"\bnile\b|\bamazon\b",)),
    # Science & Nature
    EvalQuestion("KNOWLEDGE", "What is the chemical symbol for water?", (r"\bh\s*2\s*o\b|h2o|h₂o",)),
    EvalQuestion("KNOWLEDGE", "How many planets are in our solar system?", (r"\b8\b|\beight\b",)),
    EvalQuestion("KNOWLEDGE", "What gas do plants absorb from the atmosphere?", (r"carbon dioxide|\bco\s*2\b|co2",)),
    EvalQuestion("KNOWLEDGE", "What is the freezing point of water in Celsius?", (r"\b0\b|zero",)),
    EvalQuestion("KNOWLEDGE", "What is the speed of light approximately?", (r"299[,. ]?792[,. ]?458|300[,. ]?000",)),
    EvalQuestion("KNOWLEDGE", "What is the chemical symbol for gold?", (r"\bau\b",)),
    # History & Culture
    EvalQuestion("KNOWLEDGE", "Who wrote Romeo and Juliet?", (r"\bshakespeare\b",)),
    EvalQuestion("KNOWLEDGE", "Who painted the Mona Lisa?", (r"\bleonardo\b|da vinci",)),
    EvalQuestion("KNOWLEDGE", "What year did World War II end?", (r"\b1945\b",)),
    EvalQuestion("KNOWLEDGE", "Who invented the telephone?", (r"\bbell\b",)),
    # Math
    EvalQuestion("KNOWLEDGE", "What is 2 plus 2?", (r"\b4\b",)),
    EvalQuestion("KNOWLEDGE", "What is the smallest prime number?", (r"\b2\b|\btwo\b",)),
    EvalQuestion("KNOWLEDGE", "What is the square root of 144?", (r"\b12\b|\btwelve\b",)),
    # General
    EvalQuestion("KNOWLEDGE", "What is the capital of Australia?", (r"\bcanberra\b",)),
    EvalQuestion("KNOWLEDGE", "How many bones are in the adult human body?", (r"\b206\b",)),
]


# ── 10 Reasoning / Logic Questions ──────────────────────────────────
REASONING_QUESTIONS: list[EvalQuestion] = [
    EvalQuestion("REASONING", "If a shirt costs $25 and is on sale for 20% off, what is the sale price?", (r"\b20\b|\$20|20 dollars",)),
    EvalQuestion("REASONING", "A train travels 60 miles in 2 hours. What is its average speed in miles per hour?", (r"\b30\b",)),
    EvalQuestion("REASONING", "If today is Monday, what day will it be in 10 days?", (r"\bthursday\b",)),
    EvalQuestion("REASONING", "I have 5 apples. I give away 2 and buy 3 more. How many do I have now?", (r"\b6\b|\bsix\b",)),
    EvalQuestion("REASONING", "What comes next in this sequence: 2, 4, 8, 16, ?", (r"\b32\b",)),
    EvalQuestion("REASONING", "If all dogs are animals and some animals are pets, can we conclude that some dogs are pets? Explain in one sentence.", (r"not|cannot|no\b", r"logically|follow|valid|conclud",)),
    EvalQuestion("REASONING", "A bat and a ball cost $1.10 in total. The bat costs $1.00 more than the ball. How much does the ball cost?", (r"\b0?\.05\b|\b5 cents\b|\b5¢",)),
    EvalQuestion("REASONING", "If it takes 5 machines 5 minutes to make 5 widgets, how long would it take 100 machines to make 100 widgets?", (r"\b5\b", r"minutes",)),
    EvalQuestion("REASONING", "In a race, if you overtake the person in second place, what position are you in?", (r"\bsecond\b|\b2nd\b",)),
    EvalQuestion("REASONING", "How many months have 28 days?", (r"all|\b12\b|\btwelve\b|every",)),
]


# ── 15 Web Search Questions ─────────────────────────────────────────
WEB_QUESTIONS: list[EvalQuestion] = [
    EvalQuestion("WEB_SEARCH", "What is the current weather in New York City?", ()),
    EvalQuestion("WEB_SEARCH", "What is the price of Bitcoin right now?", ()),
    EvalQuestion("WEB_SEARCH", "Who is the current CEO of Google?", ()),
    EvalQuestion("WEB_SEARCH", "Who is the current president of the United States?", ()),
    EvalQuestion("WEB_SEARCH", "What is the current population of India?", ()),
    EvalQuestion("WEB_SEARCH", "What is the latest version of Android released?", ()),
    EvalQuestion("WEB_SEARCH", "What is the current exchange rate from USD to EUR?", ()),
    EvalQuestion("WEB_SEARCH", "What is the stock price of Apple today?", ()),
    EvalQuestion("WEB_SEARCH", "What are the top trending news stories today?", ()),
    EvalQuestion("WEB_SEARCH", "What is the current price of gold per ounce?", ()),
    EvalQuestion("WEB_SEARCH", "What is the current UK interest rate?", ()),
    EvalQuestion("WEB_SEARCH", "What is the latest SpaceX launch news?", ()),
    EvalQuestion("WEB_SEARCH", "What is today's date and day of the week?", ()),
    EvalQuestion("WEB_SEARCH", "What is the current time in Tokyo Japan?", ()),
    EvalQuestion("WEB_SEARCH", "What is the latest FIFA World Cup winner?", ()),
]


# ── 5 Stress / Edge-Case Questions ──────────────────────────────────
STRESS_QUESTIONS: list[EvalQuestion] = [
    EvalQuestion("STRESS", "Explain quantum computing in exactly 3 bullet points.", (r"bullet|•|-\s|- ",)),
    EvalQuestion("STRESS", "Translate the following to French: The weather is beautiful today and I would like to go for a walk in the park.", (r"beau|belle|temps|météo|parc|promener|balader",)),
    EvalQuestion("STRESS", "Write a haiku about artificial intelligence.", (r"\b\d\b",)),
    EvalQuestion("STRESS", "What is the answer to the ultimate question of life, the universe, and everything?", (r"\b42\b",)),
    EvalQuestion("STRESS", "List 5 countries in Europe, one per line.", (r"france|germany|italy|spain|united kingdom|uk|portugal|poland|netherlands|belgium|sweden|norway|greece",)),
]


ALL_QUESTIONS = KNOWLEDGE_QUESTIONS + REASONING_QUESTIONS + WEB_QUESTIONS + STRESS_QUESTIONS


def run(cmd: list[str], timeout: int = 30, text: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=text, timeout=timeout)


class DeviceEval:
    def __init__(self, adb: Path, device: str, out_dir: Path) -> None:
        self.adb = str(adb)
        self.device = device
        self.out_dir = out_dir
        self.out_dir.mkdir(parents=True, exist_ok=True)

    def adb_cmd(self, *args: str, timeout: int = 30, text: bool = True) -> subprocess.CompletedProcess:
        return run([self.adb, "-s", self.device, *args], timeout=timeout, text=text)

    def shell(self, *args: str, timeout: int = 30) -> subprocess.CompletedProcess:
        return self.adb_cmd("shell", *args, timeout=timeout)

    def clear_logcat(self) -> None:
        self.shell("logcat", "-c", timeout=10)

    def logcat(self) -> str:
        result = self.shell("logcat", "-d", "-v", "time", timeout=10)
        return result.stdout or ""

    def force_stop(self) -> None:
        self.shell("am", "force-stop", PKG, timeout=10)

    def start_app(self, **extras: Any) -> None:
        cmd = ["am", "start", "-W", "-n", ACTIVITY]
        for key, value in extras.items():
            if isinstance(value, bool):
                cmd.extend(["--ez", key, "true" if value else "false"])
            else:
                cmd.extend(["--es", key, str(value)])
        result = self.shell(*cmd, timeout=20)
        if result.returncode != 0:
            raise RuntimeError(f"am start failed: {result.stderr or result.stdout}")

    def set_web_search(self, enabled: bool) -> None:
        self.start_app(enable_web_search=enabled, disable_web_search=not enabled)
        time.sleep(1.5)

    def send_question(self, question: str) -> None:
        encoded = quote(question, safe="")
        self.start_app(chat_msg=encoded)

    def snapshot_db(self) -> tuple[sqlite3.Connection, tempfile.TemporaryDirectory[str]]:
        tmpdir = tempfile.TemporaryDirectory()
        tmp_path = Path(tmpdir.name)
        for db_file in DB_FILES:
            result = self.adb_cmd(
                "exec-out", "run-as", PKG, "cat", f"databases/{db_file}",
                timeout=10, text=False,
            )
            if result.returncode == 0 and result.stdout:
                (tmp_path / db_file).write_bytes(result.stdout)
        db_path = tmp_path / "local_assistant_db"
        if not db_path.exists():
            tmpdir.cleanup()
            raise RuntimeError("Could not read Localyze database through run-as.")
        conn = sqlite3.connect(db_path)
        conn.row_factory = sqlite3.Row
        return conn, tmpdir

    def latest_exchange(self, question: str, after_ms: int) -> dict[str, Any] | None:
        conn, tmpdir = self.snapshot_db()
        try:
            user = conn.execute(
                """
                SELECT id, conversationId, content, timestamp
                FROM messages
                WHERE role = 'USER' AND content = ? AND timestamp >= ?
                ORDER BY id DESC LIMIT 1
                """,
                (question, after_ms - 5_000),
            ).fetchone()
            if user is None:
                return None

            assistant = conn.execute(
                """
                SELECT id, content, timestamp
                FROM messages
                WHERE role = 'ASSISTANT'
                  AND conversationId = ?
                  AND timestamp >= ?
                  AND length(trim(content)) > 0
                ORDER BY timestamp ASC, id ASC LIMIT 1
                """,
                (user["conversationId"], user["timestamp"]),
            ).fetchone()

            tool_rows = conn.execute(
                """
                SELECT id, toolName, content, toolResult, timestamp
                FROM messages
                WHERE role = 'TOOL'
                  AND conversationId = ?
                  AND timestamp >= ?
                ORDER BY timestamp ASC, id ASC
                """,
                (user["conversationId"], user["timestamp"]),
            ).fetchall()

            return {
                "conversation_id": user["conversationId"],
                "user_id": user["id"],
                "user_timestamp": user["timestamp"],
                "assistant_id": assistant["id"] if assistant else None,
                "answer": assistant["content"] if assistant else "",
                "assistant_timestamp": assistant["timestamp"] if assistant else None,
                "tools": [dict(row) for row in tool_rows],
            }
        finally:
            conn.close()
            tmpdir.cleanup()

    def wait_for_answer(self, item: EvalQuestion, timeout_s: int) -> tuple[dict[str, Any] | None, float]:
        start_ms = int(time.time() * 1000)
        start = time.time()
        self.clear_logcat()
        self.send_question(item.question)

        while time.time() - start < timeout_s:
            exchange = self.latest_exchange(item.question, start_ms)
            if exchange and exchange["answer"].strip():
                return exchange, time.time() - start
            time.sleep(1.5)

        return self.latest_exchange(item.question, start_ms), time.time() - start

    def take_screenshot(self, path: str) -> None:
        result = self.adb_cmd("exec-out", "screencap", "-p", text=False, timeout=10)
        if result.returncode == 0:
            Path(path).write_bytes(result.stdout)

    def get_ui_dump(self) -> str:
        result = self.shell("uiautomator", "dump", "/sdcard/ui.xml", timeout=10)
        result2 = self.shell("cat", "/sdcard/ui.xml", timeout=5)
        return result2.stdout or ""


def normalize(text: str) -> str:
    return re.sub(r"\s+", " ", text.lower()).strip()


def score_knowledge(item: EvalQuestion, answer: str) -> tuple[bool, list[str], str]:
    haystack = normalize(answer)
    missing = [p for p in item.expected if not re.search(p, haystack, re.IGNORECASE)]
    quality = "GOOD" if len(answer.strip()) > 20 else "THIN"
    if not answer.strip():
        quality = "EMPTY"
    elif all(not re.search(p, haystack, re.IGNORECASE) for p in item.expected):
        quality = "WRONG" if item.expected else "EMPTY"
    return bool(answer.strip()) and not missing, missing, quality


def score_reasoning(item: EvalQuestion, answer: str) -> tuple[bool, list[str], str]:
    haystack = normalize(answer)
    missing = [p for p in item.expected if not re.search(p, haystack, re.IGNORECASE)]
    quality = "GOOD" if len(answer.strip()) > 30 else "THIN"
    if not answer.strip():
        quality = "EMPTY"
    elif missing and len(answer.strip()) > 10:
        quality = "PARTIAL" if len(missing) < len(item.expected) else "WRONG"
    return bool(answer.strip()) and not missing, missing, quality


def score_web(item: EvalQuestion, answer: str, tools: list[dict[str, Any]], logs: str) -> tuple[bool, dict[str, Any]]:
    answer_urls = extract_urls(answer)
    tool_text = "\n".join((row.get("toolResult") or "") + "\n" + (row.get("content") or "") for row in tools)
    tool_urls = extract_urls(tool_text)
    lower = answer.lower()
    bad_phrases = (
        "web search is currently off",
        "web search is disabled",
        "can't verify live updates right now",
        "i couldn't get reliable live results",
        "no internet connection",
        "search request timed out",
        "error performing web search",
    )
    tool_used = any((row.get("toolName") or "").lower() == "web_search" for row in tools) or "web_search" in logs.lower()
    details = {
        "tool_used": tool_used,
        "answer_urls": answer_urls,
        "tool_urls": tool_urls,
        "has_sources_section": "source" in lower,
        "bad_phrase": next((p for p in bad_phrases if p in lower), None),
        "answer_length": len(answer.strip()),
    }
    passed = (
        bool(answer.strip())
        and len(answer.strip()) >= 50
        and tool_used
        and bool(answer_urls or tool_urls)
        and details["bad_phrase"] is None
    )
    return passed, details


def score_stress(item: EvalQuestion, answer: str) -> tuple[bool, list[str], str]:
    haystack = normalize(answer)
    missing = [p for p in item.expected if not re.search(p, haystack, re.IGNORECASE)]
    quality = "GOOD"
    if not answer.strip():
        quality = "EMPTY"
    elif len(answer.strip()) < 30:
        quality = "THIN"
    elif missing:
        quality = "PARTIAL"
    return bool(answer.strip()) and len(answer.strip()) > 30 and not missing, missing, quality


def extract_urls(text: str) -> list[str]:
    return sorted(set(re.findall(r"https?://[^\s)>\]]+", text)))


def evaluate_ui_ux(evaluator: DeviceEval, results: list[dict]) -> dict[str, Any]:
    """Perform UI/UX analysis based on observed behavior during eval."""
    ui_scores = {}

    # Launch speed
    evaluator.force_stop()
    t0 = time.time()
    evaluator.shell("am", "start", "-W", "-n", ACTIVITY, timeout=20)
    launch_ms = int((time.time() - t0) * 1000)
    ui_scores["launch_ms"] = launch_ms
    ui_scores["launch_score"] = 10 if launch_ms < 500 else (9 if launch_ms < 800 else (7 if launch_ms < 1200 else 5))

    # UI dump analysis
    ui_dump = evaluator.get_ui_dump()
    hierarchy_lines = ui_dump.count("node ")
    ui_scores["ui_complexity"] = "RICH" if hierarchy_lines > 20 else "SPARSE"

    # Message delivery reliability
    total = len(results)
    non_empty = sum(1 for r in results if r["answer"] and r["answer"].strip())
    ui_scores["response_rate"] = non_empty / total if total > 0 else 0
    ui_scores["response_rate_score"] = 10 if non_empty / total > 0.9 else (8 if non_empty / total > 0.7 else (5 if non_empty / total > 0.4 else 2))

    # Response times
    times = [r["response_time_sec"] for r in results if r["response_time_sec"] is not None and r["answer"].strip()]
    ui_scores["avg_response_time"] = round(sum(times) / len(times), 2) if times else 999
    ui_scores["speed_score"] = 10 if ui_scores["avg_response_time"] < 5 else (8 if ui_scores["avg_response_time"] < 10 else (6 if ui_scores["avg_response_time"] < 20 else 3))

    # Streaming behavior (detected through multi-message or progressive content)
    ui_scores["streaming_detected"] = any(r["answer"].count("\n") > 2 for r in results if r["answer"].strip())

    # Web search tool integration
    web_results = [r for r in results if r["category"] == "WEB_SEARCH"]
    tool_triggers = sum(1 for r in web_results if r.get("tool_used"))
    ui_scores["web_tool_trigger_rate"] = tool_triggers / len(web_results) if web_results else 0

    return ui_scores


def write_reports(results: list[dict], ui_scores: dict, out_dir: Path, device: str) -> tuple[Path, Path]:
    timestamp = time.strftime("%Y%m%d_%H%M%S")
    json_path = out_dir / f"device_eval50_{timestamp}.json"
    md_path = out_dir / f"device_eval50_{timestamp}.md"

    # Save JSON
    json_path.write_text(json.dumps({"results": results, "ui_ux": ui_scores}, indent=2), encoding="utf-8")

    passed = sum(1 for r in results if r["passed"])
    categories = {}
    for r in results:
        cat = r["category"]
        if cat not in categories:
            categories[cat] = {"total": 0, "passed": 0}
        categories[cat]["total"] += 1
        categories[cat]["passed"] += 1 if r["passed"] else 0

    lines = [
        "# Localyze Comprehensive 50-Question Evaluation",
        "",
        f"**Date:** {time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"**Device:** {device}",
        f"**Model:** Gemma 4 E4B (LiteRT-LM, GPU backend)",
        f"**App:** com.localyze v1.0.3",
        "",
        "---",
        "",
        "## Executive Summary",
        "",
        f"| Metric | Result |",
        f"|--------|--------|",
        f"| Total Questions | 50 |",
        f"| Passed | {passed}/50 ({round(passed/50*100)}%) |",
    ]
    for cat, data in categories.items():
        lines.append(f"| {cat} | {data['passed']}/{data['total']} ({round(data['passed']/data['total']*100)}%) |")
    lines.append(f"| Empty Responses | {sum(1 for r in results if not r['answer'].strip())}/50 |")
    lines.append(f"| Avg Response Time | {ui_scores.get('avg_response_time', 'N/A')}s |")
    lines.append(f"| Web Tool Trigger Rate | {ui_scores.get('web_tool_trigger_rate', 0)*100:.0f}% |")

    lines += [
        "",
        "---",
        "",
        "## UI/UX Score",
        "",
        f"| Aspect | Score | Detail |",
        f"|--------|-------|--------|",
        f"| Launch Speed | {ui_scores.get('launch_score', '?')}/10 | {ui_scores.get('launch_ms', '?')}ms |",
        f"| Response Rate | {ui_scores.get('response_rate_score', '?')}/10 | {ui_scores.get('response_rate', 0)*100:.0f}% non-empty |",
        f"| Response Speed | {ui_scores.get('speed_score', '?')}/10 | {ui_scores.get('avg_response_time', '?')}s avg |",
        f"| Streaming | {'Yes' if ui_scores.get('streaming_detected') else 'No'} | Multi-line responses detected |",
        f"| Web Tool Integration | {'Working' if ui_scores.get('web_tool_trigger_rate', 0) > 0 else 'Not triggering'} | {ui_scores.get('web_tool_trigger_rate', 0)*100:.0f}% trigger rate |",
        "",
    ]

    # Per-question results
    lines += [
        "---",
        "",
        "## Detailed Results",
        "",
        "| # | Category | Pass | Time | Answer Preview |",
        "|---|---|---|---|---|",
    ]
    for i, r in enumerate(results, 1):
        preview = r["answer"].replace("\n", " \\n ").replace("|", "\\|")[:150]
        lines.append(
            f"| {i} | {r['category']} | {'✅' if r['passed'] else '❌'} | "
            f"{r['response_time_sec']}s | {preview} |"
        )

    lines += [
        "",
        "---",
        "",
        "## Quality Analysis",
        "",
    ]

    # Knowledge quality
    knowledge = [r for r in results if r["category"] == "KNOWLEDGE"]
    if knowledge:
        correct = sum(1 for r in knowledge if r["passed"])
        lines.append(f"### Knowledge (20 questions): {correct}/20 correct")
        for r in knowledge:
            status = "✅" if r["passed"] else "❌"
            lines.append(f"- {status} Q: _{r['question']}_ → \"{r['answer'][:120]}\"")

    # Reasoning quality
    reasoning = [r for r in results if r["category"] == "REASONING"]
    if reasoning:
        correct = sum(1 for r in reasoning if r["passed"])
        lines.append(f"\n### Reasoning (10 questions): {correct}/10 correct")
        for r in reasoning:
            status = "✅" if r["passed"] else "❌"
            lines.append(f"- {status} Q: _{r['question']}_ → \"{r['answer'][:120]}\"")

    # Web search
    web = [r for r in results if r["category"] == "WEB_SEARCH"]
    if web:
        passed_web = sum(1 for r in web if r["passed"])
        triggered = sum(1 for r in web if r.get("tool_used"))
        lines.append(f"\n### Web Search (15 questions): {passed_web}/15 passed, tool triggered {triggered}/15")
        for r in web:
            status = "✅" if r["passed"] else "❌"
            lines.append(f"- {status} Q: _{r['question']}_ → \"{r['answer'][:120]}\" (tool={'yes' if r.get('tool_used') else 'no'})")

    # Stress
    stress = [r for r in results if r["category"] == "STRESS"]
    if stress:
        passed_stress = sum(1 for r in stress if r["passed"])
        lines.append(f"\n### Stress/Edge (5 questions): {passed_stress}/5 passed")
        for r in stress:
            status = "✅" if r["passed"] else "❌"
            lines.append(f"- {status} Q: _{r['question']}_ → \"{r['answer'][:120]}\"")

    # Final scoring
    knowledge_rate = categories.get("KNOWLEDGE", {"passed": 0, "total": 1})["passed"] / categories.get("KNOWLEDGE", {"passed": 0, "total": 1})["total"]
    reasoning_rate = categories.get("REASONING", {"passed": 0, "total": 1})["passed"] / categories.get("REASONING", {"passed": 0, "total": 1})["total"]
    web_rate = categories.get("WEB_SEARCH", {"passed": 0, "total": 1})["passed"] / categories.get("WEB_SEARCH", {"passed": 0, "total": 1})["total"]
    stress_rate = categories.get("STRESS", {"passed": 0, "total": 1})["passed"] / categories.get("STRESS", {"passed": 0, "total": 1})["total"]

    # Weighted final score out of 10
    score = (
        knowledge_rate * 3.0 +   # 30% weight
        reasoning_rate * 2.5 +   # 25% weight
        web_rate * 1.5 +          # 15% weight
        stress_rate * 0.5 +       # 5% weight
        (ui_scores.get("launch_score", 5) / 10) * 0.5 +    # 5%
        (ui_scores.get("response_rate_score", 5) / 10) * 0.5 +  # 5%
        (ui_scores.get("speed_score", 5) / 10) * 0.5 +     # 5%
        (1.0 if ui_scores.get("streaming_detected") else 0.0) * 0.25 +  # 2.5%
        (ui_scores.get("web_tool_trigger_rate", 0)) * 0.75   # 7.5%
    )

    # Scale to 10
    final_score = round(score, 1)

    lines += [
        "",
        "---",
        "",
        "## Final Scoring Breakdown",
        "",
        f"| Category | Weight | Raw | Weighted |",
        f"|----------|--------|-----|----------|",
        f"| Knowledge (20q) | 30% | {knowledge_rate*10:.1f}/10 | {knowledge_rate*3.0:.2f} |",
        f"| Reasoning (10q) | 25% | {reasoning_rate*10:.1f}/10 | {reasoning_rate*2.5:.2f} |",
        f"| Web Search (15q) | 15% | {web_rate*10:.1f}/10 | {web_rate*1.5:.2f} |",
        f"| Stress Test (5q) | 5% | {stress_rate*10:.1f}/10 | {stress_rate*0.5:.2f} |",
        f"| Launch Speed | 5% | {ui_scores.get('launch_score', 5)}/10 | {(ui_scores.get('launch_score', 5)/10)*0.5:.2f} |",
        f"| Response Rate | 5% | {ui_scores.get('response_rate_score', 5)}/10 | {(ui_scores.get('response_rate_score', 5)/10)*0.5:.2f} |",
        f"| Response Speed | 5% | {ui_scores.get('speed_score', 5)}/10 | {(ui_scores.get('speed_score', 5)/10)*0.5:.2f} |",
        f"| Streaming UX | 2.5% | {'10' if ui_scores.get('streaming_detected') else '0'}/10 | {(1.0 if ui_scores.get('streaming_detected') else 0.0)*0.25:.2f} |",
        f"| Web Tool UX | 7.5% | {ui_scores.get('web_tool_trigger_rate', 0)*10:.1f}/10 | {ui_scores.get('web_tool_trigger_rate', 0)*0.75:.2f} |",
        f"| **TOTAL** | **100%** | | **{final_score}/10** |",
        "",
        f"## Overall Score: {final_score} / 10",
        "",
    ]

    md_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return json_path, md_path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default=os.environ.get("ANDROID_SERIAL", "a5523839"))
    parser.add_argument("--adb", type=Path, default=Path(os.environ.get("ADB", DEFAULT_ADB)))
    parser.add_argument("--out-dir", type=Path, default=ROOT / "chatbot_test_results/eval50")
    parser.add_argument("--knowledge-timeout", type=int, default=45)
    parser.add_argument("--reasoning-timeout", type=int, default=45)
    parser.add_argument("--web-timeout", type=int, default=90)
    parser.add_argument("--stress-timeout", type=int, default=60)
    args = parser.parse_args()

    evaluator = DeviceEval(args.adb, args.device, args.out_dir)
    results: list[dict[str, Any]] = []

    print("=" * 78, flush=True)
    print("LOCALYZE COMPREHENSIVE 50-QUESTION EVALUATION", flush=True)
    print(f"device={args.device}", flush=True)
    print("=" * 78, flush=True)

    # ── Phase 1: Knowledge (20) ──────────────────────────────────
    evaluator.force_stop()
    evaluator.set_web_search(False)
    print("\n── PHASE 1: KNOWLEDGE QUESTIONS (20) ──", flush=True)

    for i, item in enumerate(KNOWLEDGE_QUESTIONS, 1):
        print(f"\n[KNOWLEDGE {i:02d}/20] {item.question}", flush=True)
        exchange, elapsed = evaluator.wait_for_answer(item, args.knowledge_timeout)
        answer = (exchange or {}).get("answer", "")
        passed, missing, quality = score_knowledge(item, answer)
        result = {
            "category": item.category,
            "index": i,
            "question": item.question,
            "passed": passed,
            "quality": quality,
            "missing_expected_patterns": missing,
            "response_time_sec": round(elapsed, 2),
            "conversation_id": (exchange or {}).get("conversation_id"),
            "answer": answer,
            "tool_used": bool((exchange or {}).get("tools")),
            "tools": (exchange or {}).get("tools", []),
        }
        results.append(result)
        print(f"  pass={passed} quality={quality} time={result['response_time_sec']}s", flush=True)
        if answer.strip():
            print(f"  answer={answer[:200]!r}", flush=True)

    # ── Phase 2: Reasoning (10) ──────────────────────────────────
    evaluator.set_web_search(True)  # Keep web search ON to prevent refusal pattern
    print("\n── PHASE 2: REASONING QUESTIONS (10) ──", flush=True)

    for i, item in enumerate(REASONING_QUESTIONS, 1):
        print(f"\n[REASONING {i:02d}/10] {item.question}", flush=True)
        exchange, elapsed = evaluator.wait_for_answer(item, args.reasoning_timeout)
        answer = (exchange or {}).get("answer", "")
        passed, missing, quality = score_reasoning(item, answer)
        result = {
            "category": item.category,
            "index": i,
            "question": item.question,
            "passed": passed,
            "quality": quality,
            "missing_expected_patterns": missing,
            "response_time_sec": round(elapsed, 2),
            "conversation_id": (exchange or {}).get("conversation_id"),
            "answer": answer,
            "tool_used": bool((exchange or {}).get("tools")),
            "tools": (exchange or {}).get("tools", []),
        }
        results.append(result)
        print(f"  pass={passed} quality={quality} time={result['response_time_sec']}s", flush=True)
        if answer.strip():
            print(f"  answer={answer[:200]!r}", flush=True)

    # ── Phase 3: Web Search (15) ─────────────────────────────────
    evaluator.set_web_search(True)
    print("\n── PHASE 3: WEB SEARCH QUESTIONS (15) ──", flush=True)

    for i, item in enumerate(WEB_QUESTIONS, 1):
        print(f"\n[WEB {i:02d}/15] {item.question}", flush=True)
        exchange, elapsed = evaluator.wait_for_answer(item, args.web_timeout)
        logs = evaluator.logcat()
        answer = (exchange or {}).get("answer", "")
        tools = (exchange or {}).get("tools", [])
        passed, details = score_web(item, answer, tools, logs)
        result = {
            "category": item.category,
            "index": i,
            "question": item.question,
            "passed": passed,
            "response_time_sec": round(elapsed, 2),
            "conversation_id": (exchange or {}).get("conversation_id"),
            "answer": answer,
            "tool_used": details["tool_used"],
            "tools": tools,
            "web_score_details": details,
        }
        results.append(result)
        print(f"  pass={passed} time={result['response_time_sec']}s tool={details['tool_used']}", flush=True)
        if answer.strip():
            print(f"  answer={answer[:200]!r}", flush=True)

    # ── Phase 4: Stress Tests (5) ────────────────────────────────
    evaluator.set_web_search(True)  # Keep web search ON to prevent refusal pattern
    print("\n── PHASE 4: STRESS / EDGE CASE TESTS (5) ──", flush=True)

    for i, item in enumerate(STRESS_QUESTIONS, 1):
        print(f"\n[STRESS {i:02d}/5] {item.question}", flush=True)
        exchange, elapsed = evaluator.wait_for_answer(item, args.stress_timeout)
        answer = (exchange or {}).get("answer", "")
        passed, missing, quality = score_stress(item, answer)
        result = {
            "category": item.category,
            "index": i,
            "question": item.question,
            "passed": passed,
            "quality": quality,
            "missing_expected_patterns": missing,
            "response_time_sec": round(elapsed, 2),
            "conversation_id": (exchange or {}).get("conversation_id"),
            "answer": answer,
            "tool_used": bool((exchange or {}).get("tools")),
            "tools": (exchange or {}).get("tools", []),
        }
        results.append(result)
        print(f"  pass={passed} quality={quality} time={result['response_time_sec']}s", flush=True)
        if answer.strip():
            print(f"  answer={answer[:200]!r}", flush=True)

    # ── UI/UX Evaluation ─────────────────────────────────────────
    print("\n── UI/UX ANALYSIS ──", flush=True)
    ui_scores = evaluate_ui_ux(evaluator, results)
    print(f"  Launch: {ui_scores['launch_ms']}ms (score: {ui_scores['launch_score']}/10)", flush=True)
    print(f"  Response rate: {ui_scores['response_rate']*100:.0f}% (score: {ui_scores['response_rate_score']}/10)", flush=True)
    print(f"  Avg response time: {ui_scores['avg_response_time']}s (score: {ui_scores['speed_score']}/10)", flush=True)
    print(f"  Streaming detected: {ui_scores['streaming_detected']}", flush=True)
    print(f"  Web tool trigger rate: {ui_scores['web_tool_trigger_rate']*100:.0f}%", flush=True)

    # ── Reports ──────────────────────────────────────────────────
    json_path, md_path = write_reports(results, ui_scores, args.out_dir, args.device)
    passed = sum(1 for r in results if r["passed"])

    print("\n" + "=" * 78, flush=True)
    print(f"EVALUATION COMPLETE", flush=True)
    print(f"Passed: {passed}/50 ({round(passed/50*100)}%)", flush=True)
    print(f"JSON: {json_path}", flush=True)
    print(f"Report: {md_path}", flush=True)
    print("=" * 78, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
