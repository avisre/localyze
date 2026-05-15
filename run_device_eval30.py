#!/usr/bin/env python3
"""
Device-backed Localyze 30-question evaluation.

This intentionally reads Localyze's Room database through run-as so the score is
based on actual persisted messages/tool rows, not fragile UI text scraping.
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
    EvalQuestion("KNOWLEDGE", "Who invented the telephone?", (r"\bbell\b",)),
    EvalQuestion("KNOWLEDGE", "What is the capital of Japan?", (r"\btokyo\b",)),
    EvalQuestion("KNOWLEDGE", "How many continents are there?", (r"\b7\b|\bseven\b",)),
    EvalQuestion("KNOWLEDGE", "What gas do plants absorb from the atmosphere?", (r"carbon dioxide|\bco\s*2\b|co2",)),
    EvalQuestion("KNOWLEDGE", "What is the freezing point of water in Celsius?", (r"\b0\b|zero",)),
]


WEB_QUESTIONS: list[EvalQuestion] = [
    EvalQuestion("WEB_SEARCH", "What is the current weather in New York City?", (r"new york|nyc", r"weather|forecast|temperature|rain|sun|cloud|storm")),
    EvalQuestion("WEB_SEARCH", "What is the price of Bitcoin right now?", (r"bitcoin|btc", r"\$|usd|price")),
    EvalQuestion("WEB_SEARCH", "Who is the current CEO of Google?", (r"sundar pichai",)),
    EvalQuestion("WEB_SEARCH", "What was the score of the latest FIFA World Cup final?", (r"argentina", r"france", r"3-3|3\u20133", r"4-2|4\u20132|penalt")),
    EvalQuestion("WEB_SEARCH", "What movies are playing in theaters this week?", (r"movie|film", r"theater|theatre|playing")),
    EvalQuestion("WEB_SEARCH", "What is the current population of India?", (r"india", r"population", r"billion|1[.,]\\d")),
    EvalQuestion("WEB_SEARCH", "What is the latest news about SpaceX?", (r"spacex",)),
    EvalQuestion("WEB_SEARCH", "What is the stock price of Apple today?", (r"apple|aapl", r"stock|price|\$")),
    EvalQuestion("WEB_SEARCH", "Who won the most recent Nobel Prize in Literature?", (r"krasznahorkai|nobel", r"literature")),
    EvalQuestion("WEB_SEARCH", "What is the current exchange rate USD to EUR?", (r"usd", r"eur", r"exchange|rate")),
    EvalQuestion("WEB_SEARCH", "What are the top trending topics on Twitter right now?", (r"twitter|x", r"trend")),
    EvalQuestion("WEB_SEARCH", "What is the latest version of Android released?", (r"android", r"16|17", r"stable|released|beta")),
    EvalQuestion("WEB_SEARCH", "Who is the current president of the United States?", (r"donald trump|trump",)),
    EvalQuestion("WEB_SEARCH", "What is the current GDP growth rate of China?", (r"china", r"gdp", r"[45](?:\.\d)?\s*%")),
    EvalQuestion("WEB_SEARCH", "What are today's headlines from BBC News?", (r"bbc", r"headline|news")),
]


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
            exchange = self.latest_exchange(item.question, start_ms)
            if exchange and exchange["answer"].strip():
                return exchange, time.time() - start
            time.sleep(1.25)

        return self.latest_exchange(item.question, start_ms), time.time() - start


def normalize(text: str) -> str:
    return re.sub(r"\s+", " ", text.lower()).strip()


def score_knowledge(item: EvalQuestion, answer: str) -> tuple[bool, list[str]]:
    haystack = normalize(answer)
    missing = [pattern for pattern in item.expected if not re.search(pattern, haystack, re.IGNORECASE)]
    return bool(answer.strip()) and not missing, missing


def extract_urls(text: str) -> list[str]:
    return sorted(set(re.findall(r"https?://[^\s)>\]]+", text)))


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
        "snippets did not expose one reliable exact value",
        "clearest sourced results are below",
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
        "bad_phrase": next((phrase for phrase in bad_phrases if phrase in lower), None),
        "answer_length": len(answer.strip()),
        "missing_expected_patterns": [
            pattern for pattern in item.expected if not re.search(pattern, lower, re.IGNORECASE)
        ],
    }
    passed = (
        bool(answer.strip())
        and len(answer.strip()) >= 80
        and tool_used
        and bool(answer_urls or tool_urls)
        and len(answer_urls) <= 2
        and details["bad_phrase"] is None
        and not details["missing_expected_patterns"]
    )
    return passed, details


def write_reports(results: list[dict[str, Any]], out_dir: Path, device: str) -> tuple[Path, Path]:
    timestamp = time.strftime("%Y%m%d_%H%M%S")
    json_path = out_dir / f"device_eval30_{timestamp}.json"
    md_path = out_dir / f"device_eval30_{timestamp}.md"
    json_path.write_text(json.dumps(results, indent=2), encoding="utf-8")

    passed = sum(1 for result in results if result["passed"])
    knowledge = [r for r in results if r["category"] == "KNOWLEDGE"]
    web = [r for r in results if r["category"] == "WEB_SEARCH"]
    tool_count = sum(1 for result in web if result["tool_used"])
    times = [r["response_time_sec"] for r in results if r["response_time_sec"] is not None]
    avg_time = round(sum(times) / len(times), 2) if times else 0

    lines = [
        "# Localyze Device Eval 30",
        "",
        f"- Date: {time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"- Device: {device}",
        f"- Total passed: {passed}/{len(results)}",
        f"- Knowledge passed: {sum(1 for r in knowledge if r['passed'])}/{len(knowledge)}",
        f"- Web-search passed: {sum(1 for r in web if r['passed'])}/{len(web)}",
        f"- Web-search tool triggered: {tool_count}/{len(web)}",
        f"- Average response time: {avg_time}s",
        "",
        "| # | Category | Pass | Time | Tool | Answer preview |",
        "|---|---|---:|---:|---:|---|",
    ]
    for index, result in enumerate(results, 1):
        preview = result["answer"].replace("\n", " ").replace("|", "\\|")[:120]
        lines.append(
            f"| {index} | {result['category']} | {'yes' if result['passed'] else 'no'} | "
            f"{result['response_time_sec']}s | {'yes' if result['tool_used'] else 'no'} | {preview} |"
        )
    md_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return json_path, md_path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default=os.environ.get("ANDROID_SERIAL", "a5523839"))
    parser.add_argument("--adb", type=Path, default=Path(os.environ.get("ADB", DEFAULT_ADB)))
    parser.add_argument("--out-dir", type=Path, default=ROOT / "chatbot_test_results/eval30")
    parser.add_argument("--knowledge-timeout", type=int, default=35)
    parser.add_argument("--web-timeout", type=int, default=75)
    args = parser.parse_args()

    evaluator = DeviceEval(args.adb, args.device, args.out_dir)
    results: list[dict[str, Any]] = []

    print("=" * 78, flush=True)
    print("LOCALYZE DEVICE EVAL 30", flush=True)
    print(f"device={args.device}", flush=True)
    print("=" * 78, flush=True)

    evaluator.force_stop()
    evaluator.set_web_search(False)

    for index, item in enumerate(KNOWLEDGE_QUESTIONS, 1):
        print(f"\n[KNOWLEDGE {index:02d}/15] {item.question}", flush=True)
        exchange, elapsed = evaluator.wait_for_answer(item, args.knowledge_timeout)
        logs = evaluator.logcat()
        answer = (exchange or {}).get("answer", "")
        passed, missing = score_knowledge(item, answer)
        result = {
            "category": item.category,
            "index": index,
            "question": item.question,
            "passed": passed,
            "missing_expected_patterns": missing,
            "response_time_sec": round(elapsed, 2),
            "conversation_id": (exchange or {}).get("conversation_id"),
            "answer": answer,
            "tool_used": bool((exchange or {}).get("tools")),
            "tools": (exchange or {}).get("tools", []),
        }
        results.append(result)
        print(f"pass={passed} time={result['response_time_sec']}s answer={answer[:180]!r}", flush=True)

    evaluator.set_web_search(True)

    for index, item in enumerate(WEB_QUESTIONS, 1):
        print(f"\n[WEB {index:02d}/15] {item.question}", flush=True)
        exchange, elapsed = evaluator.wait_for_answer(item, args.web_timeout)
        logs = evaluator.logcat()
        answer = (exchange or {}).get("answer", "")
        tools = (exchange or {}).get("tools", [])
        passed, details = score_web(item, answer, tools, logs)
        result = {
            "category": item.category,
            "index": index,
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
        print(
            "pass={passed} time={time}s tool={tool} urls={urls} answer={answer!r}".format(
                passed=passed,
                time=result["response_time_sec"],
                tool=details["tool_used"],
                urls=len(details["answer_urls"]),
                answer=answer[:180],
            ),
            flush=True,
        )

    json_path, md_path = write_reports(results, args.out_dir, args.device)
    passed = sum(1 for result in results if result["passed"])
    print("\n" + "=" * 78, flush=True)
    print(f"RESULT: {passed}/{len(results)} passed", flush=True)
    print(f"JSON: {json_path}", flush=True)
    print(f"MD: {md_path}", flush=True)
    print("=" * 78, flush=True)
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
