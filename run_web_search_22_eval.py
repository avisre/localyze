#!/usr/bin/env python3
"""
Run 22 live web-search chatbot use cases through the Android UI.

This is intentionally stricter than the broad chatbot matrix:
- web search must be enabled
- each answer must include sourced URLs
- canned "could not search" answers fail
"""

from __future__ import annotations

import json
import os
import re
import sqlite3
import subprocess
import time
import xml.etree.ElementTree as ET
from datetime import datetime
from typing import Dict, List

import run_chatbot_matrix_eval as app


WEB_QUESTIONS = [
    {"id": 1, "category": "Finance", "question": "What is the current Federal Funds rate set by the US Federal Reserve?"},
    {"id": 2, "category": "Finance", "question": "What is the latest US inflation CPI update?"},
    {"id": 3, "category": "Finance", "question": "How is the US stock market performing today?"},
    {"id": 4, "category": "Crypto", "question": "What are the latest cryptocurrency regulation headlines?"},
    {"id": 5, "category": "Technology", "question": "What are the latest features in Android 16?"},
    {"id": 6, "category": "Technology", "question": "What is the latest iPhone iOS update news?"},
    {"id": 7, "category": "Technology", "question": "What are the latest OpenAI model or API updates?"},
    {"id": 8, "category": "Technology", "question": "What is the latest news about AI chips and Nvidia?"},
    {"id": 9, "category": "Culture", "question": "Who won the Oscar for Best Picture in 2026?"},
    {"id": 10, "category": "Culture", "question": "What are the top trending movies in India this week?"},
    {"id": 11, "category": "Culture", "question": "What are the biggest music headlines this week?"},
    {"id": 12, "category": "World News", "question": "What is the current status of the India-UK free trade agreement?"},
    {"id": 13, "category": "Policy", "question": "What are the latest developments in AI regulation and the EU AI Act?"},
    {"id": 14, "category": "Environment", "question": "What are the latest climate change initiatives announced at recent global summits?"},
    {"id": 15, "category": "Sports", "question": "What is the latest Premier League title race news?"},
    {"id": 16, "category": "Sports", "question": "What is the latest NBA playoff news?"},
    {"id": 17, "category": "Weather", "question": "What is the weather forecast for New York City today?"},
    {"id": 18, "category": "Health", "question": "What are the latest public health headlines this week?"},
    {"id": 19, "category": "Housing", "question": "What are the latest US mortgage rate updates today?"},
    {"id": 20, "category": "Business", "question": "What is the latest electric vehicle market news?"},
    {"id": 21, "category": "Science", "question": "What are the latest space launch headlines?"},
    {"id": 22, "category": "Energy", "question": "What are the latest oil price and energy market updates?"},
]


FAILURE_PHRASES = [
    "couldn't get reliable live results",
    "could not get reliable live results",
    "live search request failed",
    "live search returned no usable",
    "web search is currently off",
    "web search is disabled",
    "no web results",
    "unable to retrieve",
    "couldn't find",
    "could not find",
    "returned an error",
]


def configure_output_dirs() -> None:
    app.RESULTS_DIR = os.path.join("chatbot_test_results", "web22")
    app.SNAP_DIR = os.path.join(app.RESULTS_DIR, "screenshots")
    app.UI_DIR = os.path.join(app.RESULTS_DIR, "ui")
    os.makedirs(app.RESULTS_DIR, exist_ok=True)
    os.makedirs(app.SNAP_DIR, exist_ok=True)
    os.makedirs(app.UI_DIR, exist_ok=True)


def safe_dump_ui(local_file: str) -> ET.Element:
    os.makedirs(os.path.dirname(local_file), exist_ok=True)
    last_error: Exception | None = None
    for _ in range(4):
        try:
            proc = subprocess.run(
                ["adb", "exec-out", "uiautomator", "dump", "/dev/tty"],
                capture_output=True,
                timeout=25,
                check=True,
            )
            raw = proc.stdout.decode("utf-8", errors="ignore")
            end = raw.rfind("</hierarchy>")
            if end != -1:
                xml_text = raw[: end + len("</hierarchy>")]
                with open(local_file, "w", encoding="utf-8") as f:
                    f.write(xml_text)
                return ET.fromstring(xml_text)
        except Exception as exc:  # noqa: BLE001
            last_error = exc
        time.sleep(0.8)
    raise RuntimeError("Unable to dump Android UI") from last_error


def clean_answer(answer: str) -> str:
    ignored = {"MOCK MODE", "Used: web_search"}
    lines: List[str] = []
    for raw in answer.splitlines():
        line = raw.strip()
        if not line or line in ignored:
            continue
        if lines and lines[-1] == line:
            continue
        lines.append(line)
    return "\n".join(lines).strip()


def collect_visible_answer(root, question: str) -> str:
    chunks: List[str] = [clean_answer(app.extract_answer_text(root, question))]
    for i in range(3):
        app.swipe(540, 1900, 540, 820, 300)
        root = app.dump_ui(os.path.join(app.UI_DIR, f"scroll_collect_{i}.xml"))
        chunks.append(clean_answer(app.extract_answer_text(root, question)))

    seen = set()
    merged: List[str] = []
    for chunk in chunks:
        for line in chunk.splitlines():
            key = line.strip()
            if key and key not in seen:
                seen.add(key)
                merged.append(key)
    return "\n".join(merged).strip()


def score_web_answer(answer: str) -> Dict[str, float | bool | str]:
    lower = answer.lower()
    url_count = len(re.findall(r"https?://", answer))
    has_sources_section = "sources" in lower
    has_key_points = "key points" in lower or len(re.findall(r"(^|\n)-\s+", answer)) >= 2
    failure = any(phrase in lower for phrase in FAILURE_PHRASES)

    source_score = 10.0 if url_count >= 2 else (7.0 if url_count == 1 else 0.0)
    clarity_score = 10.0 if len(answer) >= 220 and has_key_points else (8.0 if len(answer) >= 160 else 5.0)
    formatting_score = 10.0 if has_sources_section and has_key_points else (8.0 if has_sources_section else 5.0)
    honesty_score = 0.0 if failure else 10.0
    completeness_score = 10.0 if len(answer) >= 320 else (8.0 if len(answer) >= 220 else 5.0)
    overall = round((source_score + clarity_score + formatting_score + honesty_score + completeness_score) / 5.0, 2)

    return {
        "source_score": source_score,
        "clarity_score": clarity_score,
        "formatting_score": formatting_score,
        "honesty_score": honesty_score,
        "completeness_score": completeness_score,
        "overall": overall,
        "url_count": float(url_count),
        "passed": bool(overall >= 8.5 and url_count >= 1 and not failure),
        "notes": "pass" if overall >= 8.5 and url_count >= 1 and not failure else "needs improvement",
    }


def pull_app_database() -> str | None:
    db_dir = os.path.join(app.RESULTS_DIR, "db")
    os.makedirs(db_dir, exist_ok=True)
    pulled_any = False
    for name in ("local_assistant_db", "local_assistant_db-wal", "local_assistant_db-shm"):
        proc = subprocess.run(
            ["adb", "exec-out", "run-as", app.PACKAGE, "cat", f"databases/{name}"],
            capture_output=True,
            timeout=20,
            check=False,
        )
        if proc.returncode == 0 and proc.stdout:
            with open(os.path.join(db_dir, name), "wb") as f:
                f.write(proc.stdout)
            pulled_any = True
    return os.path.join(db_dir, "local_assistant_db") if pulled_any else None


def hydrate_saved_answers(rows: List[dict]) -> None:
    db_path = pull_app_database()
    if not db_path or not os.path.exists(db_path):
        return
    con = sqlite3.connect(db_path)
    saved = con.execute(
        """
        SELECT u.content, a.content
        FROM messages u
        JOIN messages a
          ON a.conversationId = u.conversationId
         AND a.role = 'ASSISTANT'
        WHERE u.role = 'USER'
        ORDER BY a.id DESC
        LIMIT ?
        """,
        (max(60, len(rows) * 3),),
    ).fetchall()
    answer_by_question = {}
    for question, answer in saved:
        answer_by_question.setdefault(question, answer.strip())

    for row in rows:
        saved_answer = answer_by_question.get(row["question"])
        if saved_answer:
            row["ui_visible_answer"] = row["answer"]
            row["answer"] = saved_answer
            row["score"] = score_web_answer(saved_answer)


def write_markdown(rows: List[dict], md_path: str) -> None:
    passed = sum(1 for row in rows if row["score"]["passed"])
    avg = round(sum(row["score"]["overall"] for row in rows) / len(rows), 2) if rows else 0.0
    lines = [
        "# Web Search 22-Question Eval",
        "",
        f"- Generated: {datetime.now().isoformat(timespec='seconds')}",
        f"- Passed: {passed}/{len(rows)}",
        f"- Average: {avg}/10",
        "",
        "| Q# | Category | Score | URLs | Result |",
        "|---:|---|---:|---:|---|",
    ]
    for row in rows:
        result = "PASS" if row["score"]["passed"] else "FAIL"
        lines.append(
            f"| {row['id']} | {row['category']} | {row['score']['overall']:.2f} | "
            f"{int(row['score']['url_count'])} | {result} |"
        )
    lines.append("")
    lines.append("## Answers")
    for row in rows:
        lines.append("")
        lines.append(f"### Q{row['id']}: {row['question']}")
        lines.append("")
        lines.append("```text")
        lines.append(row["answer"] or "(no extracted answer)")
        lines.append("```")
    with open(md_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


def main() -> None:
    configure_output_dirs()
    app.dump_ui = safe_dump_ui
    devices = app.run_cmd(["adb", "devices"], timeout=10, check=True).stdout
    if "\tdevice" not in devices:
        raise SystemExit("No connected ADB device found.")

    print("[web22] enabling network and web search", flush=True)
    app.set_network(online=True)
    app.ensure_app_foreground()
    app.ensure_web_search_on()

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    rows: List[dict] = []
    for q in WEB_QUESTIONS:
        print(f"[web22] Q{q['id']}/22: {q['question']}", flush=True)
        app.open_new_conversation()
        app.send_question(q["question"])
        root = app.wait_for_generation(timeout_sec=80)

        screenshot = os.path.join(app.SNAP_DIR, f"q{q['id']:02d}.png")
        ui_dump = os.path.join(app.UI_DIR, f"q{q['id']:02d}.xml")
        app.capture_screenshot(screenshot)
        raw = open(os.path.join(app.UI_DIR, "wait_dump.xml"), "r", encoding="utf-8", errors="ignore").read()
        end = raw.rfind("</hierarchy>")
        with open(ui_dump, "w", encoding="utf-8") as f:
            f.write(raw[: end + len("</hierarchy>")] if end != -1 else raw)

        answer = collect_visible_answer(root, q["question"])
        score = score_web_answer(answer)
        rows.append({
            "id": q["id"],
            "category": q["category"],
            "question": q["question"],
            "answer": answer,
            "score": score,
            "screenshot": screenshot,
            "ui_dump": ui_dump,
        })

    hydrate_saved_answers(rows)

    json_path = os.path.join(app.RESULTS_DIR, f"web22_{timestamp}.json")
    md_path = os.path.join(app.RESULTS_DIR, f"web22_{timestamp}.md")
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(rows, f, indent=2, ensure_ascii=False)
    write_markdown(rows, md_path)
    print(f"Saved: {json_path}")
    print(f"Saved: {md_path}")


if __name__ == "__main__":
    main()
