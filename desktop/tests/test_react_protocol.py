"""Validates the ReAct wire protocol: <tool>/<tool_result>/<final> parsing.
All three platforms use identical regex patterns; this pins them in one place.

Tool-call protocol is documented at shared/research-agent.md.
"""

from __future__ import annotations

import json
import re
import unittest


TOOL_RX  = re.compile(r'<tool\s+name="([^"]+)"\s*>([\s\S]*?)</tool>')
FINAL_RX = re.compile(r'<final>([\s\S]*?)</final>')


def match_tool(text: str):
    m = TOOL_RX.search(text)
    if not m:
        return None
    name, args_json = m.group(1), m.group(2).strip()
    try:
        args = json.loads(args_json) if args_json else {}
    except json.JSONDecodeError:
        args = {}
    return {"name": name, "args": args, "raw_args": args_json}


def match_final(text: str):
    m = FINAL_RX.search(text)
    return m.group(1).strip() if m else None


class ToolCallParsing(unittest.TestCase):
    def test_simple_call(self):
        text = '<tool name="memory.search">{"query":"auth"}</tool>'
        c = match_tool(text)
        self.assertEqual(c["name"], "memory.search")
        self.assertEqual(c["args"], {"query": "auth"})

    def test_multiline_call(self):
        text = '''
Let me check memory.

<tool name="files.search">
{"query": "session token", "limit": 3}
</tool>
'''
        c = match_tool(text)
        self.assertEqual(c["name"], "files.search")
        self.assertEqual(c["args"]["query"], "session token")
        self.assertEqual(c["args"]["limit"], 3)

    def test_first_tool_wins(self):
        text = '<tool name="a">{}</tool><tool name="b">{}</tool>'
        c = match_tool(text)
        self.assertEqual(c["name"], "a")

    def test_no_tool(self):
        self.assertIsNone(match_tool("just thinking"))

    def test_empty_args(self):
        c = match_tool('<tool name="system.info"></tool>')
        self.assertEqual(c["name"], "system.info")
        self.assertEqual(c["args"], {})

    def test_bad_args_become_empty(self):
        c = match_tool('<tool name="calc">not json</tool>')
        self.assertEqual(c["args"], {})


class FinalParsing(unittest.TestCase):
    def test_basic(self):
        self.assertEqual(match_final("<final>hi</final>"), "hi")

    def test_multiline(self):
        text = '<final>\nLine 1.\n\nLine 2.\n</final>'
        self.assertEqual(match_final(text), "Line 1.\n\nLine 2.")

    def test_no_final(self):
        self.assertIsNone(match_final("no answer yet"))

    def test_final_with_viz_inside(self):
        text = '<final>Here is the chart: <viz type="chart" kind="bar" data="[]"/></final>'
        body = match_final(text)
        self.assertIn('<viz type="chart"', body)


if __name__ == "__main__":
    unittest.main()
