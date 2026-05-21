"""Validates that the <viz> regex used by all three native parsers
(VizParser.cs / VizParser.swift / VizParser.cpp) extracts blocks correctly.
Mirrors the same regex; if this passes, the platform parsers behave the same."""

from __future__ import annotations

import re
import unittest

# Same regex as VizParser on each platform — keep these in sync.
BLOCK_RX = re.compile(
    r'<viz\s+([^>]+?)(?:/>|>(.*?)</viz>)',
    re.DOTALL,
)
ATTR_RX = re.compile(r'(\w+)\s*=\s*(?:"([^"]*)"|\'([^\']*)\')')


def parse_blocks(text: str):
    out = []
    for m in BLOCK_RX.finditer(text):
        attrs_str, inner = m.group(1), m.group(2)
        attrs = {}
        for am in ATTR_RX.finditer(attrs_str):
            k = am.group(1)
            v = am.group(2) if am.group(2) is not None else am.group(3)
            attrs[k] = v
        out.append({"attrs": attrs, "inner": inner})
    return out


class VizParserTests(unittest.TestCase):
    def test_self_closing_chart(self) -> None:
        text = '<viz type="chart" kind="bar" title="Q sales" data="[]"/>'
        blocks = parse_blocks(text)
        self.assertEqual(len(blocks), 1)
        b = blocks[0]
        self.assertEqual(b["attrs"]["type"], "chart")
        self.assertEqual(b["attrs"]["kind"], "bar")
        self.assertEqual(b["attrs"]["title"], "Q sales")
        self.assertIsNone(b["inner"])

    def test_block_with_inner_code(self) -> None:
        text = '<viz type="code" lang="python">print(1 + 1)</viz>'
        blocks = parse_blocks(text)
        self.assertEqual(len(blocks), 1)
        self.assertEqual(blocks[0]["attrs"]["type"], "code")
        self.assertEqual(blocks[0]["inner"], "print(1 + 1)")

    def test_multiple_blocks_in_one_message(self) -> None:
        text = '''
Some thinking here.
<viz type="chart" kind="line" data="[]"/>
And then a table:
<viz type="table" data="[]" editable="true"/>
'''
        blocks = parse_blocks(text)
        self.assertEqual(len(blocks), 2)
        types = [b["attrs"]["type"] for b in blocks]
        self.assertEqual(types, ["chart", "table"])

    def test_single_quotes_in_attrs(self) -> None:
        text = "<viz type='chart' kind='bar' data='[]'/>"
        blocks = parse_blocks(text)
        self.assertEqual(len(blocks), 1)
        self.assertEqual(blocks[0]["attrs"]["type"], "chart")

    def test_inner_with_xml_chars(self) -> None:
        text = '<viz type="code" lang="html"><div>hi</div></viz>'
        blocks = parse_blocks(text)
        # Greedy match would over-consume; DOTALL non-greedy must stop at first </viz>.
        self.assertEqual(len(blocks), 1)
        self.assertIn("<div>hi</div>", blocks[0]["inner"])

    def test_no_block(self) -> None:
        self.assertEqual(parse_blocks("just text"), [])


if __name__ == "__main__":
    unittest.main()
