#!/usr/bin/env python3
"""
Localyze 20-Question Quality Evaluation Script

Tests the Localyze Android app with 10 knowledge (offline) and 10 web-search
(online) questions, scoring each response for quality out of 10.

Infrastructure: ADB, Room DB polling via run-as, intent-based question sending.
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


# ---------------------------------------------------------------------------
# Question sets
# ---------------------------------------------------------------------------

KNOWLEDGE_QUESTIONS: list[EvalQuestion] = [
    EvalQuestion("KNOWLEDGE", "What is the capital of France?", (r"\bparis\b",)),
    EvalQuestion("KNOWLEDGE", "What is 2 + 2?", (r"\b4\b",)),
    EvalQuestion("KNOWLEDGE", "Who wrote Romeo and Juliet?", (r"\bshakespeare\b",)),
    EvalQuestion("KNOWLEDGE", "What is the chemical symbol for water?", (r"\bh\s*2\s*o\b|h2o|h₂o",)),
    EvalQuestion("KNOWLEDGE", "How many planets are in our solar system?", (r"\b8\b|\beight\b",)),
    EvalQuestion("KNOWLEDGE", "What is the speed of light approximately?", (r"299[, ]?792[, ]?458|300[, ]?000",)),
    EvalQuestion("KNOWLEDGE", "Who painted the Mona Lisa?", (r"\bleonardo\b|da vinci",)),
    EvalQuestion("KNOWLEDGE", "What is the largest ocean on Earth?", (r"\bpacific\b",)),
    EvalQuestion("KNOWLEDGE", "What year did World War II end?", (r"\b1945\b",)),
    EvalQuestion("KNOWLEDGE", "What is the smallest prime number?", (r"\b2\b|\btwo\b",)),
]

WEB_QUESTIONS: list[EvalQuestion] = [
    EvalQuestion("WEB_SEARCH", "Who is the current CEO of Google?", (r"sundar pichai",)),
    EvalQuestion("WEB_SEARCH", "What was the score of the latest FIFA World Cup final?", (r"argentina", r"france", r"3-3|3\u20133", r"4-2|4\u20132|penalt")),
    EvalQuestion("WEB_SEARCH", "What is the current population of India?", (r"india", r"population", r"billion|1[.,]\\d")),
    EvalQuestion("WEB_SEARCH", "What is the latest version of Android released?", (r"android", r"16|17", r"stable|released|beta")),
    EvalQuestion("WEB_SEARCH", "Who is the current president of the United States?", (r"donald trump|trump",)),
    EvalQuestion("WEB_SEARCH", "What is the current GDP growth rate of China?", (r"china", r"gdp", r"[45](?:\.\d)?\s*%")),
    EvalQuestion("WEB_SEARCH", "Who won the most recent Nobel Prize in Literature?", (r"krasznahorkai|nobel", r"literature")),
    EvalQuestion("WEB_SEARCH", "What is the latest news about SpaceX?", (r"spacex",)),
    EvalQuestion("WEB_SEARCH", "What is the stock price of Apple today?", (r"apple|aapl", r"stock|price|\$")),
    EvalQuestion("WEB_SEARCH", "What are today's headlines from BBC News?", (r"bbc", r"headline|news")),
]


# ---------------------------------------------------------------------------
# ADB / Device helpers
# ---------------------------------------------------------------------------

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
        for attempt in range(3):
            tmpdir = tempfile.TemporaryDirectory()
            tmp_path = Path(tmpdir.name)
            for db_file in DB_FILES:
                result = self.adb_cmd(
                    "exec-out",
                    "run-as",
                    PKG,
                    "cat",
                    f"databases/{db_file}",
                    timeout=10,
                    text=False,
                )
                if result.returncode == 0 and result.stdout:
                    (tmp_path / db_file).write_bytes(result.stdout)
            db_path = tmp_path / "local_assistant_db"
            if not db_path.exists():
                tmpdir.cleanup()
                raise RuntimeError("Could not read Localyze database through run-as.")
            try:
                conn = sqlite3.connect(db_path)
                conn.row_factory = sqlite3.Row
                conn.execute("SELECT 1").fetchone()
                return conn, tmpdir
            except sqlite3.DatabaseError:
                try:
                    conn.close()
                except Exception:
                    pass
                tmpdir.cleanup()
                if attempt < 2:
                    time.sleep(0.5)
                else:
                    raise

    def latest_exchange(self, question: str, after_ms: int) -> dict[str, Any] | None:
        conn, tmpdir = self.snapshot_db()
        try:
            user = conn.execute(
                """
                SELECT id, conversationId, content, timestamp
                FROM messages
                WHERE role = 'USER' AND content = ? AND timestamp >= ?
                ORDER BY id DESC
                LIMIT 1
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
                ORDER BY timestamp ASC, id ASC
                LIMIT 1
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
            try:
                exchange = self.latest_exchange(item.question, start_ms)
            except sqlite3.DatabaseError:
                time.sleep(1.25)
                continue
            if exchange and exchange["answer"].strip():
                return exchange, time.time() - start
            time.sleep(1.25)

        try:
            return self.latest_exchange(item.question, start_ms), time.time() - start
        except sqlite3.DatabaseError:
            return None, time.time() - start


# ---------------------------------------------------------------------------
# Quality scoring
# ---------------------------------------------------------------------------

def normalize(text: str) -> str:
    return re.sub(r"\s+", " ", text.lower()).strip()


def extract_urls(text: str) -> list[str]:
    return sorted(set(re.findall(r"https?://[^\s)>\]]+", text)))


def score_knowledge_quality(item: EvalQuestion, answer: str) -> tuple[int, dict[str, Any]]:
    """
    Knowledge quality rubric (0-10):
    - Has content: 2 pts (non-empty)
    - Correctness: 3 pts (all expected patterns match)
    - Length: 2 pts (>=80 chars = 2, >=40 chars = 1)
    - Formatting: 3 pts (has structure/sources = 3, markdown = 2, plain long text = 1)
    """
    score = 0
    details: dict[str, Any] = {}
    stripped = answer.strip()
    lower = stripped.lower()
    haystack = normalize(answer)

    # 1. Has content (2 pts)
    has_content = bool(stripped)
    if has_content:
        score += 2
    details["has_content"] = has_content
    details["has_content_score"] = 2 if has_content else 0

    # 2. Correctness (3 pts)
    missing = [pattern for pattern in item.expected if not re.search(pattern, haystack, re.IGNORECASE)]
    correct = has_content and not missing
    correctness_score = 3 if correct else (2 if has_content and len(missing) <= 1 else (1 if has_content else 0))
    score += correctness_score
    details["correctness"] = correct
    details["missing_expected_patterns"] = missing
    details["correctness_score"] = correctness_score

    # 3. Length (2 pts)
    length = len(stripped)
    if length >= 80:
        length_score = 2
    elif length >= 40:
        length_score = 1
    else:
        length_score = 0
    score += length_score
    details["length"] = length
    details["length_score"] = length_score

    # 4. Formatting (3 pts)
    has_markdown = any(c in stripped for c in ["**", "*", "`", "#", "[", "]("])
    has_sources = "source" in lower or "reference" in lower or "citation" in lower
    has_structure = has_sources or re.search(r"\n\s*[-•*]\s+", stripped) or re.search(r"\n\s*\d+\.\s+", stripped)

    if has_structure:
        fmt_score = 3
    elif has_markdown:
        fmt_score = 2
    elif length >= 40:
        fmt_score = 1
    else:
        fmt_score = 0
    score += fmt_score
    details["has_markdown"] = has_markdown
    details["has_sources"] = has_sources
    details["has_structure"] = has_structure
    details["formatting_score"] = fmt_score

    details["total_score"] = score
    return score, details


def score_web_quality(item: EvalQuestion, answer: str, tools: list[dict[str, Any]], logs: str) -> tuple[int, dict[str, Any]]:
    """
    Web-search quality rubric (0-10):
    - Has content: 1 pt (non-empty)
    - Tool triggered: 2 pts (web_search tool invoked)
    - Has URLs: 2 pts (answer or tool result contains URLs)
    - Correctness: 3 pts (expected patterns match)
    - Formatting: 2 pts (sources section + markdown = 2, markdown only = 1)
    """
    score = 0
    details: dict[str, Any] = {}
    stripped = answer.strip()
    lower = stripped.lower()
    haystack = normalize(answer)

    # 1. Has content (1 pt)
    has_content = bool(stripped)
    if has_content:
        score += 1
    details["has_content"] = has_content
    details["has_content_score"] = 1 if has_content else 0

    # 2. Tool triggered (2 pts)
    tool_used = any((row.get("toolName") or "").lower() == "web_search" for row in tools) or "web_search" in logs.lower()
    tool_score = 2 if tool_used else 0
    score += tool_score
    details["tool_used"] = tool_used
    details["tool_triggered_score"] = tool_score

    # 3. Has URLs (2 pts)
    answer_urls = extract_urls(answer)
    tool_text = "\n".join((row.get("toolResult") or "") + "\n" + (row.get("content") or "") for row in tools)
    tool_urls = extract_urls(tool_text)
    has_urls = bool(answer_urls or tool_urls)
    url_score = 2 if has_urls else 0
    score += url_score
    details["answer_urls"] = answer_urls
    details["tool_urls"] = tool_urls
    details["has_urls"] = has_urls
    details["urls_score"] = url_score

    # 4. Correctness (3 pts)
    missing = [pattern for pattern in item.expected if not re.search(pattern, haystack, re.IGNORECASE)]
    correct = has_content and not missing
    correctness_score = 3 if correct else (2 if has_content and len(missing) <= 1 else (1 if has_content else 0))
    score += correctness_score
    details["correctness"] = correct
    details["missing_expected_patterns"] = missing
    details["correctness_score"] = correctness_score

    # 5. Formatting (2 pts)
    has_markdown = any(c in stripped for c in ["**", "*", "`", "#", "[", "]("])
    has_sources = "source" in lower or "reference" in lower or "citation" in lower
    if has_sources and has_markdown:
        fmt_score = 2
    elif has_markdown or has_sources:
        fmt_score = 1
    else:
        fmt_score = 0
    score += fmt_score
    details["has_markdown"] = has_markdown
    details["has_sources"] = has_sources
    details["formatting_score"] = fmt_score

    details["total_score"] = score
    return score, details


# ---------------------------------------------------------------------------
# Report generation
# ---------------------------------------------------------------------------

def write_reports(results: list[dict[str, Any]], out_dir: Path) -> tuple[Path, Path]:
    timestamp = time.strftime("%Y%m%d_%H%M%S")
    json_path = out_dir / f"eval20_quality_{timestamp}.json"
    md_path = out_dir / f"eval20_quality_{timestamp}.md"
    json_path.write_text(json.dumps(results, indent=2), encoding="utf-8")

    knowledge = [r for r in results if r["category"] == "KNOWLEDGE"]
    web = [r for r in results if r["category"] == "WEB_SEARCH"]

    knowledge_scores = [r["quality_score"] for r in knowledge]
    web_scores = [r["quality_score"] for r in web]
    all_scores = [r["quality_score"] for r in results]

    avg_knowledge = round(sum(knowledge_scores) / len(knowledge_scores), 2) if knowledge_scores else 0
    avg_web = round(sum(web_scores) / len(web_scores), 2) if web_scores else 0
    avg_overall = round(sum(all_scores) / len(all_scores), 2) if all_scores else 0

    times = [r["response_time_sec"] for r in results if r["response_time_sec"] is not None]
    avg_time = round(sum(times) / len(times), 2) if times else 0

    lines = [
        "# Localyze 20-Question Quality Evaluation Report",
        "",
        f"- **Date:** {time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"- **Device:** physical Android device ({results[0].get('device', 'unknown') if results else 'unknown'})",
        f"- **Package:** {PKG}",
        "",
        "## Summary",
        "",
        f"| Metric | Value |",
        f"|--------|-------|",
        f"| Total questions | {len(results)} |",
        f"| Knowledge questions | {len(knowledge)} |",
        f"| Web-search questions | {len(web)} |",
        f"| Average knowledge quality | {avg_knowledge} / 10 |",
        f"| Average web-search quality | {avg_web} / 10 |",
        f"| **Overall average quality** | **{avg_overall} / 10** |",
        f"| Average response time | {avg_time}s |",
        "",
        "## Per-Question Results",
        "",
    ]

    # Knowledge section
    lines.extend([
        "### Part 1: Knowledge (Offline)",
        "",
        "| # | Question | Quality | Content | Correct | Length | Format | Time | Preview |",
        "|---|----------|--------:|--------:|--------:|-------:|-------:|------:|---------|",
    ])
    for r in knowledge:
        d = r["quality_details"]
        preview = r["answer"].replace("\n", " ").replace("|", "\\|")[:80]
        lines.append(
            f"| {r['index']:02d} | {r['question'][:40]} | "
            f"{r['quality_score']}/10 | {d['has_content_score']} | {d['correctness_score']} | "
            f"{d['length_score']} | {d['formatting_score']} | {r['response_time_sec']}s | {preview} |"
        )
    lines.append("")

    # Web-search section
    lines.extend([
        "### Part 2: Web Search (Online)",
        "",
        "| # | Question | Quality | Content | Tool | URLs | Correct | Format | Time | Preview |",
        "|---|----------|--------:|--------:|-----:|-----:|--------:|-------:|------:|---------|",
    ])
    for r in web:
        d = r["quality_details"]
        preview = r["answer"].replace("\n", " ").replace("|", "\\|")[:80]
        lines.append(
            f"| {r['index']:02d} | {r['question'][:40]} | "
            f"{r['quality_score']}/10 | {d['has_content_score']} | {d['tool_triggered_score']} | "
            f"{d['urls_score']} | {d['correctness_score']} | {d['formatting_score']} | "
            f"{r['response_time_sec']}s | {preview} |"
        )
    lines.append("")

    # Score distribution
    lines.extend([
        "## Score Distribution",
        "",
        "| Score | Knowledge | Web Search |",
        "|-------|----------:|-----------:|",
    ])
    for s in range(0, 11):
        k_count = sum(1 for r in knowledge if r["quality_score"] == s)
        w_count = sum(1 for r in web if r["quality_score"] == s)
        lines.append(f"| {s}/10 | {k_count} | {w_count} |")
    lines.append("")

    # Detailed JSON reference
    lines.extend([
        "## Raw Data",
        "",
        f"Full JSON results saved to: `{json_path}`",
        "",
    ])

    md_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return json_path, md_path


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description="Localyze 20-Question Quality Evaluation")
    parser.add_argument("--device", default=os.environ.get("ANDROID_SERIAL", "a5523839"))
    parser.add_argument("--adb", type=Path, default=Path(os.environ.get("ADB", DEFAULT_ADB)))
    parser.add_argument("--out-dir", type=Path, default=ROOT / "chatbot_test_results/eval20_quality")
    parser.add_argument("--knowledge-timeout", type=int, default=35)
    parser.add_argument("--web-timeout", type=int, default=75)
    args = parser.parse_args()

    evaluator = DeviceEval(args.adb, args.device, args.out_dir)
    results: list[dict[str, Any]] = []

    print("=" * 78, flush=True)
    print("LOCALYZE 20-QUESTION QUALITY EVALUATION", flush=True)
    print(f"device={args.device}", flush=True)
    print("=" * 78, flush=True)

    # -----------------------------------------------------------------------
    # Part 1: Knowledge (offline)
    # -----------------------------------------------------------------------
    print("\n--- PART 1: KNOWLEDGE (10 questions, web search OFF) ---", flush=True)
    evaluator.force_stop()
    time.sleep(1)
    evaluator.set_web_search(False)

    for index, item in enumerate(KNOWLEDGE_QUESTIONS, 1):
        print(f"\n[KNOWLEDGE {index:02d}/10] {item.question}", flush=True)
        exchange, elapsed = evaluator.wait_for_answer(item, args.knowledge_timeout)
        answer = (exchange or {}).get("answer", "")
        score, details = score_knowledge_quality(item, answer)
        result = {
            "category": item.category,
            "index": index,
            "question": item.question,
            "quality_score": score,
            "quality_details": details,
            "response_time_sec": round(elapsed, 2),
            "conversation_id": (exchange or {}).get("conversation_id"),
            "answer": answer,
            "device": args.device,
        }
        results.append(result)
        print(
            f"quality={score}/10 "
            f"(content={details['has_content_score']} correct={details['correctness_score']} "
            f"length={details['length_score']} format={details['formatting_score']}) "
            f"time={result['response_time_sec']}s "
            f"answer={answer[:160]!r}",
            flush=True,
        )

    # -----------------------------------------------------------------------
    # Part 2: Web Search (online)
    # -----------------------------------------------------------------------
    print("\n--- PART 2: WEB SEARCH (10 questions, web search ON) ---", flush=True)
    evaluator.force_stop()
    time.sleep(1)
    evaluator.set_web_search(True)

    for index, item in enumerate(WEB_QUESTIONS, 1):
        print(f"\n[WEB {index:02d}/10] {item.question}", flush=True)
        exchange, elapsed = evaluator.wait_for_answer(item, args.web_timeout)
        logs = evaluator.logcat()
        answer = (exchange or {}).get("answer", "")
        tools = (exchange or {}).get("tools", [])
        score, details = score_web_quality(item, answer, tools, logs)
        result = {
            "category": item.category,
            "index": index,
            "question": item.question,
            "quality_score": score,
            "quality_details": details,
            "response_time_sec": round(elapsed, 2),
            "conversation_id": (exchange or {}).get("conversation_id"),
            "answer": answer,
            "tool_used": details["tool_used"],
            "tools": tools,
            "device": args.device,
        }
        results.append(result)
        print(
            f"quality={score}/10 "
            f"(content={details['has_content_score']} tool={details['tool_triggered_score']} "
            f"urls={details['urls_score']} correct={details['correctness_score']} "
            f"format={details['formatting_score']}) "
            f"time={result['response_time_sec']}s "
            f"answer={answer[:160]!r}",
            flush=True,
        )

    # -----------------------------------------------------------------------
    # Reports
    # -----------------------------------------------------------------------
    json_path, md_path = write_reports(results, args.out_dir)
    all_scores = [r["quality_score"] for r in results]
    avg = round(sum(all_scores) / len(all_scores), 2) if all_scores else 0

    print("\n" + "=" * 78, flush=True)
    print(f"RESULT: Average quality score = {avg} / 10", flush=True)
    print(f"JSON: {json_path}", flush=True)
    print(f"MD:   {md_path}", flush=True)
    print("=" * 78, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
