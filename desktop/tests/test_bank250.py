"""Validates the structure + diversity of bank250.json — the 250-question
evaluation set for Localyze desktop. Does NOT run the model; just shape checks
so the bank stays consistent over time."""

from __future__ import annotations

import json
import re
import unittest
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BANK = ROOT / "tests" / "bank250.json"


class Bank250Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        with BANK.open() as f:
            cls.bank = json.load(f)
        cls.questions = cls.bank["questions"]

    def test_exactly_250_questions(self):
        self.assertEqual(len(self.questions), 250, f"expected 250, got {len(self.questions)}")

    def test_every_question_has_required_fields(self):
        for q in self.questions:
            self.assertIn("id", q)
            self.assertIn("category", q)
            self.assertIn("prompt", q)
            self.assertIn("expected_keywords", q)
            self.assertIn("must_not_contain", q)
            self.assertTrue(isinstance(q["expected_keywords"], list))
            self.assertTrue(isinstance(q["must_not_contain"], list))

    def test_ids_are_unique_and_well_formed(self):
        rx = re.compile(r"^B\d{3}$")
        ids = [q["id"] for q in self.questions]
        for qid in ids:
            self.assertRegex(qid, rx, f"bad id {qid}")
        self.assertEqual(len(set(ids)), len(ids), "duplicate ids")

    def test_prompts_are_nonempty_and_distinct(self):
        prompts = [q["prompt"].strip() for q in self.questions]
        for p in prompts:
            self.assertTrue(len(p) >= 8, f"prompt too short: {p!r}")
        self.assertEqual(len(set(prompts)), len(prompts), "duplicate prompts")

    def test_categories_match_declared_list(self):
        declared = set(self.bank["_categories"])
        seen = set(q["category"] for q in self.questions)
        unknown = seen - declared
        self.assertFalse(unknown, f"questions use undeclared categories: {unknown}")

    def test_each_declared_category_has_at_least_5(self):
        # Sanity: no near-empty category. Sets a useful floor for regression coverage.
        c = Counter(q["category"] for q in self.questions)
        for cat in self.bank["_categories"]:
            self.assertGreaterEqual(c[cat], 5, f"category '{cat}' has only {c[cat]} questions")

    def test_largest_category_does_not_dominate(self):
        # No single category should make up more than 1/3 of the bank.
        c = Counter(q["category"] for q in self.questions)
        most_common, n = c.most_common(1)[0]
        self.assertLessEqual(n, 84, f"category '{most_common}' has {n} (>33% of bank)")

    def test_expected_keywords_or_must_not_contain_present(self):
        # Every question should have *some* scoring signal — at least one expected_keyword
        # OR a must_not_contain entry. Otherwise it's untestable.
        for q in self.questions:
            self.assertTrue(
                q["expected_keywords"] or q["must_not_contain"],
                f"{q['id']} has no scoring criteria"
            )

    def test_safety_questions_decline(self):
        # Every question in the 'safety' category should expect a refusal-style answer.
        safety = [q for q in self.questions if q["category"] == "safety"]
        self.assertGreaterEqual(len(safety), 3)
        refusal_tokens = {"can't", "won't", "decline", "support", "help", "988"}
        for q in safety:
            ks = " ".join(q["expected_keywords"]).lower()
            self.assertTrue(
                any(tok in ks for tok in refusal_tokens),
                f"{q['id']} (safety) doesn't expect a refusal: {q['expected_keywords']}"
            )

    def test_global_forbidden_substrings_listed(self):
        # The schema should declare global forbidden tokens (UI/test scaffolding leaks).
        self.assertIn("global_forbidden_substrings", self.bank)
        self.assertGreater(len(self.bank["global_forbidden_substrings"]), 0)


if __name__ == "__main__":
    unittest.main()
