#!/usr/bin/env python3
"""TESTER A feeder — generates 1000 research/tool/multi-hop prompts and pushes
them to /tmp/qrepo/pending/ via submit.py. Throttles to keep the queue under
~80 files so the GPU worker doesn't fall too far behind."""
from __future__ import annotations
import random
import subprocess
import sys
import time
from pathlib import Path

SUBMIT = str(Path(__file__).parent / "submit.py")
PENDING = Path("/tmp/qrepo/pending")
ANSWERED = Path("/tmp/qrepo/answered.jsonl")
TARGET = 1000


def submit(category: str, prompt: str, must: str = "", ban: str = "") -> str:
    args = ["python3", SUBMIT, "research", category, prompt]
    if must: args += ["--must", must]
    if ban:  args += ["--ban", ban]
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=10)
        return r.stdout.strip()
    except Exception as e:
        return f"ERR {e}"


def gen_math_chain(n: int) -> list[tuple[str, str, str, str]]:
    out = []
    for _ in range(n):
        a, b, c = random.randint(2, 99), random.randint(2, 99), random.randint(2, 50)
        ops = random.choice([
            (f"({a} + {b}) × {c}",            str((a + b) * c)),
            (f"{a}² + {b}",                   str(a * a + b)),
            (f"{a} % of {b * 10}",            f"{a * b * 10 / 100:g}"),
            (f"{a} + {b} − {c}",              str(a + b - c)),
            (f"{a} × {b} + {c}",              str(a * b + c)),
            (f"{a + b}² ÷ {c}",               f"{(a + b)**2 / c:.2f}"),
            (f"compound interest on ${a*100} at {c}% for {b%10+2} years (annual)",
             f"{a*100*(1+c/100)**(b%10+2):.0f}"),
        ])
        out.append(("math_chain", f"Compute {ops[0]}.", ops[1], ""))
    return out


def gen_tool_call(n: int) -> list[tuple[str, str, str, str]]:
    bases = [
        ("Calculate 15% tip on a $87.50 bill.", "13.13"),
        ("Convert 100 km/h to mph.", "62"),
        ("How many seconds in 4.25 hours?", "15300"),
        ("Convert 350°F to °C.", "176"),
        ("What is 15 factorial?", "1307674368000"),
        ("Square root of 2025?", "45"),
        ("Cube of 12?", "1728"),
        ("Log base 10 of 100000?", "5"),
        ("Convert 5 lbs to kg.", "2.27"),
        ("Convert 2.5 miles to km.", "4.02"),
        ("Compound interest on $5000 at 4% for 5 years?", "6083"),
        ("Future value of $1000 at 6% for 10 years?", "1791"),
        ("How many minutes in 3 days?", "4320"),
        ("Sum of integers from 1 to 200.", "20100"),
        ("Permutations of 6 items.", "720"),
        ("Combinations of 10 choose 3.", "120"),
    ]
    out = []
    for p, m in bases:
        out.append(("tool_call", p, m, ""))
    while len(out) < n:
        a = random.randint(2, 200); b = random.randint(2, 50)
        out.append(("tool_call", f"What is {a} × {b}?", str(a*b), ""))
    return out[:n]


def gen_multi_hop(n: int) -> list[tuple[str, str, str, str]]:
    items = [
        ("A train leaves NYC at 9am going 60mph. Another leaves Chicago (790 miles away) at 10am going 70mph. When and where do they meet?", "12", ""),
        ("If a recipe for 4 serves needs 2.5 cups flour, how much for 7 servings?", "4.4", ""),
        ("You have $5,000 in stocks growing 8%/year and $3,000 in bonds growing 4%/year. Value after 5 years?", "11,000", ""),
        ("A car uses 7L per 100km. Tank is 50L. How far can it go on $80 of fuel at $1.85/L?", "618", ""),
        ("If 8 workers build a wall in 6 days, how many days for 12 workers (same pace)?", "4", ""),
        ("A printer prints 30 ppm. Cost is 5¢/page. Print a 240-page document — minutes + dollars?", "8", "12"),
        ("Mortgage of $300k at 6.5% over 30 years — approximate monthly payment?", "1896", ""),
        ("Yearly savings if I cut $4.50/day coffee for a year?", "1642", ""),
        ("Population doubles every 25 years. Start 1 million. After 100 years?", "16", ""),
        ("Rectangle 12m × 8m. Area in sq ft (1m=3.28ft)?", "1033", ""),
    ]
    out = [("multi_hop", p, m, b) for p, m, b in items]
    while len(out) < n:
        x = random.randint(5, 50); y = random.randint(2, 12)
        out.append(("multi_hop", f"A factory produces {x} widgets per worker per day. {y} workers work 5 days. Total widgets?", str(x*y*5), ""))
    return out[:n]


def gen_code_debug(n: int) -> list[tuple[str, str, str, str]]:
    items = [
        ("What's the bug in: `def avg(xs): return sum(xs)/len(xs)`?", "ZeroDivisionError", ""),
        ("Why does this print 11? `x=10; def f(): x+=1; print(x); x=11; f()`", "UnboundLocalError", ""),
        ("Find the bug: `for i in range(len(arr)): arr.pop(i)`.", "skip", ""),
        ("Why is `0.1 + 0.2 != 0.3` in Python?", "floating", ""),
        ("Bug in: `def add(a, b=[]): b.append(a); return b`?", "mutable default", ""),
        ("Why does `print('5' + 5)` fail in Python?", "TypeError", ""),
        ("Bug: `while True: input()` reads input but never breaks. Fix?", "break", ""),
        ("In JS, what does `[] == false` return and why?", "true", ""),
        ("Why is `Array(3).fill([])` a footgun?", "shared reference", ""),
        ("In SQL, why does `WHERE col = NULL` never match?", "IS NULL", ""),
    ]
    return [("code_debug", p, m, b) for p, m, b in items][:n]


def gen_code_synth(n: int) -> list[tuple[str, str, str, str]]:
    items = [
        ("Write a Python function that reverses a string.", "def,return", ""),
        ("One-line Python to flatten a nested list.", "for", ""),
        ("Python regex to validate an email address.", "re,@", ""),
        ("Bash one-liner to find files larger than 100MB.", "find,-size", ""),
        ("SQL query: top 5 customers by total spend in 2024.", "select,sum,group", ""),
        ("Python: read a CSV and compute column mean.", "csv,mean", ""),
        ("JavaScript debounce function.", "function,setTimeout", ""),
        ("Rust function that returns the nth Fibonacci number.", "fn", ""),
        ("Go: HTTP server on port 8080 that returns 'hello'.", "http,ListenAndServe", ""),
        ("Python: read JSON and pretty-print it.", "json,indent", ""),
        ("SQL: window function for rolling 7-day average.", "OVER,ROWS", ""),
        ("Python list comprehension for squares 1..20 (skip evens).", "[", ""),
        ("Bash: count unique words in a file.", "sort,uniq", ""),
        ("Python: merge two dicts preferring the second's values.", "dict,update", ""),
        ("Python typing: function that returns Optional[int].", "Optional", ""),
    ]
    return [("code_synth", p, m, b) for p, m, b in items][:n]


def gen_data_analysis(n: int) -> list[tuple[str, str, str, str]]:
    items = [
        ("Given sales [120,140,135,180,210,195,225], identify the trend and the % growth from start to end.", "87,upward,growth", ""),
        ("Numbers [4,7,2,8,3,5,9,1]. Mean and median?", "4.875,4.5", ""),
        ("Daily temperatures Mon-Sun: 22,24,21,28,30,27,25. Highest day?", "Friday,30", ""),
        ("Quarterly revenue (millions): Q1=$2.1 Q2=$2.4 Q3=$2.0 Q4=$2.8. Total + best quarter?", "9.3,Q4", ""),
        ("Sample scores: 78 82 90 65 88 75 92 80. Variance (population)?", "70", ""),
        ("Test scores: 90,85,92,88,79,95,72,88,91,85. Median, range, std dev?", "87,23", ""),
        ("Population data: A=1.2M B=0.8M C=1.5M D=0.9M. % of total in C?", "33", ""),
        ("Quarterly net income (M$): 5.2 6.1 5.8 6.4. Annual growth %?", "23", ""),
    ]
    return [("data_analysis", p, m, b) for p, m, b in items][:n]


def gen_reasoning_puzzle(n: int) -> list[tuple[str, str, str, str]]:
    items = [
        ("Three boxes labeled apples, oranges, both — all wrong. Pull one fruit from one box. Which box and which fruit gives certainty?", "both", ""),
        ("A bat and a ball cost $1.10. The bat costs $1 more than the ball. How much is the ball?", "0.05", "0.10"),
        ("If it takes 5 machines 5 minutes to make 5 widgets, how long for 100 machines to make 100 widgets?", "5", ""),
        ("In a lake, water lilies double every day. The lake is full in 48 days. Day when half-covered?", "47", ""),
        ("Three switches outside, three bulbs upstairs. Each switch matches one bulb. You can only check the bulbs once. How?", "heat,touch,warm", ""),
        ("You see a boat full of people but no person on the boat. How?", "married", ""),
        ("A man is looking at a portrait. Brothers and sisters I have none, but that man's father is my father's son. Who is in the portrait?", "son", ""),
        ("Two ropes burn unevenly but each takes exactly 1 hour. Measure 45 minutes.", "ends,both", ""),
        ("12 balls, one heavier or lighter. 3 weighings on a balance — find it.", "weigh", ""),
        ("Monty Hall: 3 doors, prize behind one. Pick one. Host opens another with no prize. Switch?", "yes,2/3", ""),
    ]
    return [("reasoning_puzzle", p, m, b) for p, m, b in items][:n]


def gen_domain_research(n: int) -> list[tuple[str, str, str, str]]:
    items = [
        ("Explain the photoelectric effect and why it earned Einstein the Nobel Prize.", "photon,quanta", ""),
        ("Compare TCP and UDP — when would you prefer UDP?", "reliable,connection,real-time", ""),
        ("What is the difference between mitosis and meiosis?", "haploid,diploid", ""),
        ("Outline the causes of the 2008 financial crisis.", "subprime,mortgage", ""),
        ("Explain RAFT consensus in 3 paragraphs.", "leader,log,election", ""),
        ("Difference between SQL and NoSQL — when each fits.", "schema,scale", ""),
        ("Explain CRISPR-Cas9 in plain English.", "gene,DNA,edit", ""),
        ("What is gradient descent? Why does momentum help?", "loss,minimum", ""),
        ("Explain the difference between gross margin and net margin.", "cost,operating", ""),
        ("Summarize the plot of Macbeth in 4 sentences.", "Macbeth,king,Lady,Banquo", ""),
    ]
    return [("domain_research", p, m, b) for p, m, b in items][:n]


def gen_tool_chain(n: int) -> list[tuple[str, str, str, str]]:
    items = [
        ("Compute compound interest on $1000 at 5% for 10 years, then convert the result to euros at $1=€0.92.", "1628,1498", ""),
        ("Find the area of a circle r=7m and tell me how many such circles fit (no overlap) in a 100m×100m field (approximate).", "153,65", ""),
        ("Convert 95°F to °C, then tell me what cooking task that's typical for.", "35", ""),
        ("Compute (8! / 4!) and tell me what permutation problem that solves.", "1680,permutations", ""),
        ("Sum integers 1 to 1000, then divide by 7 — give quotient and remainder.", "500500,71500", ""),
    ]
    return [("tool_chain", p, m, b) for p, m, b in items][:n]


def gen_meta(n: int) -> list[tuple[str, str, str, str]]:
    items = [
        ("What model are you running, and which backend?", "Gemma,Vulkan", ""),
        ("Are my prompts sent over the internet?", "device,local,no", "cloud"),
        ("How much RAM does this model need?", "GB", ""),
        ("Can you read files on my computer?", "files.search", "all"),
        ("Do you remember my previous conversations?", "memory,don't", ""),
        ("Why are some answers fast and others slow?", "tokens,length", ""),
    ]
    return [("meta", p, m, b) for p, m, b in items][:n]


def main() -> int:
    random.seed(42)
    # Build a 1000-question deck.
    deck: list[tuple[str, str, str, str]] = []
    deck += gen_math_chain(180)
    deck += gen_tool_call(180)
    deck += gen_multi_hop(120)
    deck += gen_code_debug(60)
    deck += gen_code_synth(120)
    deck += gen_data_analysis(60)
    deck += gen_reasoning_puzzle(80)
    deck += gen_domain_research(100)
    deck += gen_tool_chain(60)
    deck += gen_meta(40)
    random.shuffle(deck)
    # If short, pad with more math_chain
    while len(deck) < TARGET:
        deck += gen_math_chain(50)
    deck = deck[:TARGET]

    print(f"feeder: {len(deck)} questions queued, target={TARGET}", flush=True)
    sent = 0
    dup = 0
    for cat, prompt, must, ban in deck:
        # Throttle: don't let pending grow > 60
        while len(list(PENDING.glob("*.json"))) > 60:
            time.sleep(2)
        out = submit(cat, prompt, must, ban)
        if out.startswith("SUBMITTED"):
            sent += 1
        elif out.startswith("DUPLICATE"):
            dup += 1
        if (sent + dup) % 100 == 0:
            done = sum(1 for _ in ANSWERED.open()) if ANSWERED.exists() else 0
            print(f"  feeder: sent={sent} dup={dup} answered={done}", flush=True)
    print(f"feeder DONE: sent={sent} dup={dup}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
