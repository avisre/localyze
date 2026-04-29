#!/usr/bin/env python3
"""
Run a 30-question chatbot matrix on-device:
- 15 questions in online mode (web search enabled, network on)
- 15 questions in offline mode (web search disabled, network off)

Outputs:
- chatbot_test_results/matrix_<timestamp>.json
- chatbot_test_results/matrix_<timestamp>.md
- screenshots and UI dumps per question
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import time
import urllib.parse
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime
from typing import Dict, List, Optional, Tuple


PACKAGE = "com.localyze"
ACTIVITY = "com.localyze/.MainActivity"
RESULTS_DIR = "chatbot_test_results"
SNAP_DIR = os.path.join(RESULTS_DIR, "screenshots")
UI_DIR = os.path.join(RESULTS_DIR, "ui")


QUESTIONS = [
    {"id": 1, "category": "Finance", "question": "What is the current Federal Funds rate set by the US Federal Reserve?"},
    {"id": 2, "category": "Finance", "question": "Explain what a yield curve inversion means and why it matters for the economy"},
    {"id": 3, "category": "Finance", "question": "What are the latest trends in cryptocurrency regulation in 2025?"},
    {"id": 4, "category": "Finance", "question": "How does compound interest work, and what is the difference between APR and APY?"},
    {"id": 5, "category": "Technology", "question": "What are the latest features in Android 16?"},
    {"id": 6, "category": "Technology", "question": "Explain how large language models work in simple terms"},
    {"id": 7, "category": "Technology", "question": "What is the current state of quantum computing in 2025?"},
    {"id": 8, "category": "Technology", "question": "What is the difference between REST and GraphQL APIs, and when should you use each?"},
    {"id": 9, "category": "Culture", "question": "What movies won the major categories at the 2025 Oscars?"},
    {"id": 10, "category": "Culture", "question": "Explain the cultural significance of Diwali and how it is celebrated"},
    {"id": 11, "category": "Culture", "question": "What are the biggest music trends shaping pop culture in 2025?"},
    {"id": 12, "category": "World News", "question": "What is the current status of the India-UK free trade agreement negotiations?"},
    {"id": 13, "category": "Environment", "question": "What are the main climate change initiatives announced at recent global summits?"},
    {"id": 14, "category": "General", "question": "Explain what the Nobel Prize is and how laureates are selected"},
    {"id": 15, "category": "Tech Policy", "question": "What are the latest developments in AI regulation and the EU AI Act?"},
]


IGNORE_EXACT = {
    "Localyze",
    "On-device",
    "Chat",
    "Library",
    "Settings",
    "Message Localyze...",
    "Quick actions",
    "New chat",
    "Add text file",
    "What do you remember?",
    "Enable web",
    "Recent conversations",
    "See all",
    "Open saved chats",
    "Web search off",
    "Web search on",
    "Web search is off",
    "Enable",
    "AI runs on your device",
    "No cloud chat",
    "Gemma 4 E4B",
    "Hi! I'm Localyze.",
    "How can I help you today?",
    "Start a focused chat",
    "Ask locally with Gemma",
    "Analyze an image",
    "Attach a picture and ask",
    "Open conversations",
    "Privacy status",
}


TIME_RE = re.compile(r"^(\d+[smhd] ago|just now)$", re.IGNORECASE)
BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def run_cmd(
    args: List[str],
    timeout: int = 30,
    check: bool = True,
    text: bool = True
) -> subprocess.CompletedProcess:
    return subprocess.run(args, capture_output=True, text=text, timeout=timeout, check=check)


def adb_shell(cmd: str, timeout: int = 30, check: bool = True) -> subprocess.CompletedProcess:
    return run_cmd(["adb", "shell", cmd], timeout=timeout, check=check)


def adb_exec_out_to_file(shell_cmd: str, local_path: str, timeout: int = 30) -> None:
    # Use cmd redirection for binary-safe output on Windows.
    cmdline = f'adb exec-out {shell_cmd} > "{local_path}"'
    run_cmd(["cmd", "/c", cmdline], timeout=timeout, check=True)


def parse_bounds(bounds: str) -> Optional[Tuple[int, int, int, int]]:
    m = BOUNDS_RE.match(bounds or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return x1, y1, x2, y2


def center_of(node: ET.Element) -> Optional[Tuple[int, int]]:
    b = parse_bounds(node.attrib.get("bounds", ""))
    if not b:
        return None
    x1, y1, x2, y2 = b
    return ((x1 + x2) // 2, (y1 + y2) // 2)


def tap(x: int, y: int) -> None:
    adb_shell(f"input tap {x} {y}", timeout=10)
    time.sleep(0.7)


def swipe(x1: int, y1: int, x2: int, y2: int, ms: int = 250) -> None:
    adb_shell(f"input swipe {x1} {y1} {x2} {y2} {ms}", timeout=10)
    time.sleep(0.9)


def dump_ui(local_file: str) -> ET.Element:
    os.makedirs(os.path.dirname(local_file), exist_ok=True)
    raw = ""
    last_error: Optional[Exception] = None
    for _ in range(4):
        try:
            adb_exec_out_to_file("uiautomator dump /dev/tty", local_file, timeout=20)
            raw = open(local_file, "r", encoding="utf-8", errors="ignore").read()
            if "</hierarchy>" in raw:
                break
        except Exception as exc:  # noqa: BLE001
            last_error = exc
        time.sleep(0.8)
        ensure_app_foreground()

    if "</hierarchy>" not in raw:
        # Fallback path for devices where /dev/tty dump is flaky.
        adb_shell("uiautomator dump /sdcard/ui_eval.xml", timeout=20, check=False)
        run_cmd(["adb", "pull", "/storage/emulated/0/ui_eval.xml", local_file], timeout=20, check=True)
        raw = open(local_file, "r", encoding="utf-8", errors="ignore").read()

    end = raw.rfind("</hierarchy>")
    if end == -1:
        if last_error is not None:
            raise RuntimeError("Unable to parse UI dump") from last_error
        raise RuntimeError("Unable to parse UI dump: missing </hierarchy>")
    xml_text = raw[: end + len("</hierarchy>")]
    root = ET.fromstring(xml_text)
    return root


def find_nodes(root: ET.Element, *, text: Optional[str] = None, contains_text: Optional[str] = None,
               content_desc: Optional[str] = None, min_y: Optional[int] = None,
               checkable: Optional[bool] = None) -> List[ET.Element]:
    out: List[ET.Element] = []
    for node in root.iter("node"):
        t = node.attrib.get("text", "")
        c = node.attrib.get("content-desc", "")
        if text is not None and t != text:
            continue
        if contains_text is not None and contains_text.lower() not in t.lower():
            continue
        if content_desc is not None and c != content_desc:
            continue
        if checkable is not None and (node.attrib.get("checkable", "false") == "true") != checkable:
            continue
        if min_y is not None:
            center = center_of(node)
            if not center or center[1] < min_y:
                continue
        out.append(node)
    return out


def tap_node(node: ET.Element) -> bool:
    c = center_of(node)
    if not c:
        return False
    tap(c[0], c[1])
    return True


def ensure_app_foreground() -> None:
    adb_shell(f"am start -W -n {ACTIVITY}", timeout=20)
    time.sleep(1.2)


def go_tab(tab_text: str) -> None:
    root = dump_ui(os.path.join(UI_DIR, "tab_dump.xml"))
    candidates = sorted(
        find_nodes(root, text=tab_text, min_y=2600),
        key=lambda n: center_of(n)[1] if center_of(n) else 9999
    )
    if candidates:
        tap_node(candidates[0])
        return
    # Fallback bottom nav coordinates.
    if tab_text == "Chat":
        tap(220, 2920)
    elif tab_text == "Library":
        tap(720, 2920)
    elif tab_text == "Settings":
        tap(1220, 2920)


def open_new_conversation() -> None:
    go_tab("Chat")
    root = dump_ui(os.path.join(UI_DIR, "newconv_dump.xml"))
    candidates = find_nodes(root, content_desc="New conversation")
    if candidates and tap_node(candidates[0]):
        time.sleep(0.9)
        return
    tap(1260, 280)
    time.sleep(0.9)


def current_web_search_status() -> Optional[str]:
    root = dump_ui(os.path.join(UI_DIR, "webstatus_dump.xml"))
    if find_nodes(root, text="Web search on") or find_nodes(root, text="Web search is on"):
        return "on"
    if find_nodes(root, text="Web search off") or find_nodes(root, text="Web search is off"):
        return "off"
    return None


def ensure_web_search_on() -> None:
    open_new_conversation()
    status = current_web_search_status()
    if status == "on":
        return
    root = dump_ui(os.path.join(UI_DIR, "web_on_dump.xml"))
    # Try banner button first.
    btn = find_nodes(root, text="Enable")
    if btn:
        tap_node(btn[0])
        time.sleep(1.0)
    else:
        quick = find_nodes(root, text="Enable web")
        if quick:
            tap_node(quick[0])
            time.sleep(1.0)
    # One more check.
    _ = current_web_search_status()


def ensure_web_search_off() -> None:
    open_new_conversation()
    status = current_web_search_status()
    if status == "off":
        return

    go_tab("Settings")
    # Scroll until Assistant section is visible, then toggle first switch in that section.
    for _ in range(8):
        root = dump_ui(os.path.join(UI_DIR, "web_off_settings_dump.xml"))
        assistant = find_nodes(root, text="Assistant")
        if assistant:
            ay = center_of(assistant[0])[1] if center_of(assistant[0]) else 0
            support = find_nodes(root, text="Support & Safety")
            sy = center_of(support[0])[1] if support and center_of(support[0]) else 99999
            toggles = []
            for node in find_nodes(root, checkable=True):
                c = center_of(node)
                if not c:
                    continue
                if ay < c[1] < sy:
                    toggles.append((c[1], node))
            toggles.sort(key=lambda item: item[0])
            if toggles:
                web_toggle = toggles[0][1]
                checked = web_toggle.attrib.get("checked", "false") == "true"
                if checked:
                    tap_node(web_toggle)
                    time.sleep(1.0)
                break
        swipe(720, 2300, 720, 900, 300)

    go_tab("Chat")
    open_new_conversation()


def set_network(online: bool) -> None:
    if online:
        adb_shell("svc wifi enable", timeout=10, check=False)
        adb_shell("svc data enable", timeout=10, check=False)
    else:
        adb_shell("svc wifi disable", timeout=10, check=False)
        adb_shell("svc data disable", timeout=10, check=False)
    time.sleep(1.0)


def send_question(question: str) -> None:
    encoded = urllib.parse.quote(question, safe="")
    run_cmd(
        ["adb", "shell", "am", "start", "-n", ACTIVITY, "--es", "chat_msg", encoded],
        timeout=20,
        check=True
    )


def wait_for_generation(timeout_sec: int = 95) -> ET.Element:
    started = time.time()
    latest_root: Optional[ET.Element] = None
    while time.time() - started < timeout_sec:
        latest_root = dump_ui(os.path.join(UI_DIR, "wait_dump.xml"))
        stop_btn = find_nodes(latest_root, content_desc="Stop generation")
        busy_text = (
            find_nodes(latest_root, text="Reading your message")
            or find_nodes(latest_root, text="Writing answer")
            or find_nodes(latest_root, text="Thinking through the request")
        )
        if not stop_btn and not busy_text:
            # Give the UI a beat to settle.
            time.sleep(1.2)
            latest_root = dump_ui(os.path.join(UI_DIR, "wait_dump.xml"))
            return latest_root
        time.sleep(2.2)
    return latest_root if latest_root is not None else dump_ui(os.path.join(UI_DIR, "wait_dump.xml"))


def capture_screenshot(path: str) -> None:
    remote = "/sdcard/ui_eval_screen.png"
    adb_shell(f"screencap -p {remote}", timeout=20, check=True)
    run_cmd(["adb", "pull", "/storage/emulated/0/ui_eval_screen.png", path], timeout=20, check=True)


def extract_answer_text(root: ET.Element, question: str) -> str:
    texts = [n.attrib.get("text", "").strip() for n in root.iter("node")]
    out: List[str] = []
    prev = ""
    for t in texts:
        if not t:
            continue
        if t in IGNORE_EXACT:
            continue
        if TIME_RE.match(t):
            continue
        if t.lower() in {"just now"}:
            continue
        if t == question or question.lower() in t.lower():
            continue
        if t.startswith("Version "):
            continue
        if t in {"-", "•"}:
            # keep bullets only if we already have some content
            if out:
                out.append(t)
            continue
        if t == prev:
            continue
        prev = t
        out.append(t)
    return "\n".join(out).strip()


def is_current_question(question: str) -> bool:
    q = question.lower()
    return bool(re.search(
        r"\b(latest|current|today|2025|2026|won|trends|status|developments|headlines|features|rate)\b",
        q
    ))


def score_answer(answer: str, question: str, mode: str) -> Dict[str, float]:
    q_current = is_current_question(question)
    length = len(answer)
    has_structure = any(marker in answer for marker in ["##", "###", "- ", "|", "Sources", "source"])
    has_url = ("http://" in answer) or ("https://" in answer)
    mentions_offline = "offline" in answer.lower() or "can't fetch" in answer.lower() or "web search is currently off" in answer.lower()

    accuracy = 10.0 if length > 120 else (7.0 if length > 60 else 4.0)
    clarity = 10.0 if has_structure and length > 120 else (8.0 if length > 90 else 6.0)
    completeness = 10.0 if length > 180 else (8.0 if length > 110 else 6.0)
    formatting = 10.0 if has_structure else (8.0 if "\n" in answer else 6.0)

    if mode == "online":
        source = 10.0 if has_url else (8.0 if "source" in answer.lower() else 5.0)
        overall = round((accuracy + clarity + completeness + formatting + source) / 5.0, 2)
        return {
            "accuracy": accuracy,
            "clarity": clarity,
            "completeness": completeness,
            "formatting": formatting,
            "source_citation": source,
            "overall": overall,
        }

    # offline mode
    if q_current:
        offline_grace = 10.0 if mentions_offline else 6.0
    else:
        offline_grace = 10.0
    overall = round((accuracy + clarity + completeness + formatting + offline_grace) / 5.0, 2)
    return {
        "accuracy": accuracy,
        "clarity": clarity,
        "completeness": completeness,
        "formatting": formatting,
        "offline_grace": offline_grace,
        "overall": overall,
    }


@dataclass
class EvalRow:
    mode: str
    qid: int
    category: str
    question: str
    answer: str
    score: Dict[str, float]
    screenshot: str
    ui_dump: str


def run_phase(mode: str) -> List[EvalRow]:
    assert mode in {"online", "offline"}
    online = mode == "online"

    set_network(online=online)
    ensure_app_foreground()
    if online:
        ensure_web_search_on()
    else:
        ensure_web_search_off()

    rows: List[EvalRow] = []
    for q in QUESTIONS:
        qid = q["id"]
        question = q["question"]
        category = q["category"]
        print(f"[{mode}] Q{qid}/15: {question}")

        open_new_conversation()
        send_question(question)
        root = wait_for_generation(timeout_sec=95)

        snap_name = f"q{qid}_{mode}.png"
        ui_name = f"q{qid}_{mode}.xml"
        snap_path = os.path.join(SNAP_DIR, snap_name)
        ui_path = os.path.join(UI_DIR, ui_name)
        capture_screenshot(snap_path)

        # Save normalized UI XML.
        raw = open(os.path.join(UI_DIR, "wait_dump.xml"), "r", encoding="utf-8", errors="ignore").read()
        end = raw.rfind("</hierarchy>")
        xml_text = raw[: end + len("</hierarchy>")] if end != -1 else raw
        with open(ui_path, "w", encoding="utf-8") as f:
            f.write(xml_text)

        answer = extract_answer_text(root, question)
        score = score_answer(answer, question, mode)
        rows.append(EvalRow(
            mode=mode,
            qid=qid,
            category=category,
            question=question,
            answer=answer,
            score=score,
            screenshot=snap_path,
            ui_dump=ui_path,
        ))
    return rows


def to_markdown(rows: List[EvalRow], out_md: str) -> None:
    online_rows = [r for r in rows if r.mode == "online"]
    offline_rows = [r for r in rows if r.mode == "offline"]

    def avg(items: List[EvalRow]) -> float:
        if not items:
            return 0.0
        return round(sum(r.score["overall"] for r in items) / len(items), 2)

    lines: List[str] = []
    lines.append("# Chatbot Matrix Results")
    lines.append("")
    lines.append(f"- Generated: {datetime.now().isoformat(timespec='seconds')}")
    lines.append(f"- Online average: {avg(online_rows)}/10")
    lines.append(f"- Offline average: {avg(offline_rows)}/10")
    lines.append(f"- Overall average: {avg(rows)}/10")
    lines.append("")

    for mode in ("online", "offline"):
        lines.append(f"## {mode.capitalize()} Results")
        lines.append("")
        lines.append("| Q# | Category | Overall | Notes |")
        lines.append("|---|---|---:|---|")
        mode_rows = [r for r in rows if r.mode == mode]
        for r in mode_rows:
            notes = "Sources included" if mode == "online" and ("http" in r.answer or "https://" in r.answer) else ""
            if mode == "offline" and is_current_question(r.question):
                if "offline" in r.answer.lower():
                    notes = "Graceful offline handling"
            lines.append(f"| {r.qid} | {r.category} | {r.score['overall']:.2f} | {notes} |")
        lines.append("")

    lines.append("## Extracted Answers")
    lines.append("")
    for r in rows:
        lines.append(f"### {r.mode.upper()} Q{r.qid}: {r.question}")
        lines.append("")
        lines.append("```text")
        lines.append(r.answer if r.answer else "(no extracted answer)")
        lines.append("```")
        lines.append("")

    with open(out_md, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


def main() -> None:
    os.makedirs(RESULTS_DIR, exist_ok=True)
    os.makedirs(SNAP_DIR, exist_ok=True)
    os.makedirs(UI_DIR, exist_ok=True)

    # Quick device check.
    devices = run_cmd(["adb", "devices"], timeout=10, check=True).stdout
    if "\tdevice" not in devices:
        raise SystemExit("No connected ADB device found.")

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    print("Running online phase...")
    online_rows = run_phase("online")
    print("Running offline phase...")
    offline_rows = run_phase("offline")
    all_rows = online_rows + offline_rows

    json_path = os.path.join(RESULTS_DIR, f"matrix_{timestamp}.json")
    md_path = os.path.join(RESULTS_DIR, f"matrix_{timestamp}.md")
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(
            [
                {
                    "mode": r.mode,
                    "qid": r.qid,
                    "category": r.category,
                    "question": r.question,
                    "answer": r.answer,
                    "score": r.score,
                    "screenshot": r.screenshot,
                    "ui_dump": r.ui_dump,
                }
                for r in all_rows
            ],
            f,
            indent=2,
            ensure_ascii=False,
        )
    to_markdown(all_rows, md_path)
    print(f"Saved: {json_path}")
    print(f"Saved: {md_path}")


if __name__ == "__main__":
    main()
