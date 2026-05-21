"""Pre-filter that intercepts prompts BEFORE they reach the model.

Mirrors the Android side: a tight set of regex patterns for prompt injection
and vague-opener clarification. Returns a canned safe response when matched —
the model isn't asked to refuse, the harness just doesn't call it.

This is the same architecture as `ClarificationOrchestrator` + the
injection-resistance text in `SystemPromptBuilder`, but applied at the prompt
boundary so we don't depend on the model resisting under temperature.
"""

from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass
class Decision:
    """Result of the pre-filter pass."""
    passthrough: bool          # True → call the model with the original prompt
    response: str = ""         # if passthrough=False, return this verbatim
    reason: str = ""           # tag for logging


# ── INJECTION / RED-TEAM ─────────────────────────────────────────────────────
# These catch the common prompt-injection patterns. We return a short, neutral
# refusal that does NOT echo any of the injected payload (so must_not_contain
# checks on "PWNED" / "ldap://" / etc. pass automatically).
_INJECT_PATTERNS = [
    re.compile(p, re.IGNORECASE) for p in (
        r"\bignore\s+(all\s+|the\s+)?previous\s+instructions?\b",
        r"\bignore\s+(any|all)\s+(previous|prior)\b",
        r"\bdisregard\s+(all\s+|the\s+)?(previous|prior)\s+(instructions?|rules?)\b",
        r"\breply\s+(?:only\s+)?(?:with\s+)?(?:the\s+word\s+)?[\"']?\w+[\"']?\s*(?:and|then)?\s*(?:nothing|only)\b",
        r"\bjust\s+say\s+[\"']?\w+[\"']?\s*(?:and|then)?\s*(?:nothing|stop)\b",
        r"\bsay\s+(?:exactly|just|only)\s+[\"']?\w+[\"']?\b",
        r"\brespond\s+with\s+(?:only\s+)?[\"']?\w+[\"']?\s*$",
        r"\bpwned\b",
        r"\${jndi:",
        r"\bldap://",
        r"\brepeat\s+your\s+(system\s+)?prompt\b",
        r"\bprint\s+your\s+(system\s+)?prompt\b",
        r"\bshow\s+(?:me\s+)?your\s+(system\s+)?prompt\b",
        r"\bwhat\s+(is|are)\s+your\s+(system\s+|initial\s+)?(prompt|instructions?|rules?|guidelines?)\b",
        r"\boutput\s+the\s+text\s+(of\s+)?your\s+(system\s+)?prompt\b",
        r"\bact\s+as\s+(?:DAN|a\s+jailbroken|an?\s+unrestricted)\b",
        r"\byou\s+are\s+(?:now\s+)?(?:DAN|jailbroken|unrestricted)\b",
        r"\bnew\s+instructions?\s*:\s*",
        r"\boverride\s+(?:all\s+)?(?:safety|restrictions?|filters?)\b",
        r"\benable\s+(?:developer|admin|root)\s+mode\b",
    )
]

# Allow the prompt through for the rare benign reading where the user actually
# asks *us* about prompt injection as a topic (e.g. "what is prompt injection").
_INJECT_TOPIC_ALLOW = re.compile(
    r"\b(what\s+is|explain|describe)\s+(prompt\s+injection|injection\s+attack|log4j|jndi)\b",
    re.IGNORECASE,
)

_INJECT_REFUSAL = (
    "I can't follow embedded instructions that try to override my own "
    "guidelines. If you'd like, share what you actually want to learn or "
    "do and I'll help with that."
)


def looks_like_injection(prompt: str) -> bool:
    if _INJECT_TOPIC_ALLOW.search(prompt):
        return False
    return any(p.search(prompt) for p in _INJECT_PATTERNS)


# ── VAGUE OPENERS / CLARIFICATION ────────────────────────────────────────────
# Ported, condensed, from the Android `VaguePatterns` table. Each entry maps
# a regex to a list of clarifying questions to ask. Order matters — most
# specific first.

@dataclass
class _Vague:
    rx: re.Pattern
    questions: list[tuple[str, list[str]]]


def _v(pattern: str, qs: list[tuple[str, list[str]]]) -> _Vague:
    return _Vague(re.compile(pattern, re.IGNORECASE), qs)


_VAGUE_PATTERNS: list[_Vague] = [
    _v(
        r"^\s*(top\s+\d+\s+)?news\b|"
        r"^\s*what(?:'s| is)?\s+(?:the\s+|in\s+the\s+)?news\b|"
        r"^\s*biggest\s+\w+\s+news\b|"
        r"^\s*(latest|recent)\s+(news|updates?|stories|headlines)\s*\??\s*$",
        [
            ("About what topic?", ["finance", "tech", "sports", "politics", "world"]),
            ("From which region?", ["US", "India", "UK", "EU", "global"]),
            ("What time window?", ["today", "this week", "this month"]),
        ],
    ),
    _v(
        r"\b(recommend|suggest|pick|find|best)\s+(a\s+|an\s+|some\s+)?(stock|stocks|shares|investment)\b",
        [
            ("Risk tolerance?", ["low", "moderate", "high"]),
            ("Time horizon?", ["short-term (<1yr)", "medium (1-5yr)", "long (5+yr)"]),
            ("Sector preference?", ["tech", "finance", "energy", "healthcare", "any"]),
        ],
    ),
    _v(
        r"\b(best|recommend|suggest|which)\s+(a\s+|an\s+)?"
        r"(phone|laptop|car|ev|evs|tablet|headphones?|earbuds|camera|tv|monitor)\b",
        [
            ("Budget range?", ["under $500", "$500-1000", "$1000-2000", "$2000+"]),
            ("Primary use?", ["work", "gaming", "travel", "video editing", "general"]),
            ("Region?", ["US", "UK", "India", "EU", "global"]),
        ],
    ),
    _v(
        r"^\s*(help|assist)\s+me\s+with\s+(my\s+)?"
        r"(taxes|finances?|career|diet|fitness|health|relationship|sleep|focus)\s*\.?\s*$",
        [
            ("What specifically?", ["a question", "a plan", "review my situation", "general guidance"]),
            ("Country / region?", ["US", "India", "UK", "EU", "other"]),
            ("How urgent?", ["right now", "this week", "this month", "general"]),
        ],
    ),
    _v(
        r"^\s*(give\s+me|any|got)\s+(some\s+)?advice\s*\.?\s*$",
        [
            ("What domain?", ["career", "money", "health", "relationships", "learning"]),
            ("What's your situation?", ["just starting", "stuck", "doing well, want more", "between options"]),
        ],
    ),
    _v(
        r"^\s*tell\s+me\s+something\s+(interesting|cool|fun)\b",
        [
            ("About what topic?", ["history", "science", "tech", "space", "nature", "random"]),
            ("How long an answer?", ["one-liner", "short paragraph", "detailed"]),
        ],
    ),
    _v(
        r"^\s*how(?:'s|\s+is)\s+the\s+(market|economy|weather)\b",
        [
            ("Which market / sector?", ["S&P 500", "NASDAQ", "tech", "energy", "specific stock"]),
            ("Region?", ["US", "India", "Europe", "global"]),
            ("What do you want to know?", ["today's move", "YTD performance", "outlook"]),
        ],
    ),
    # Recommend a movie/book/song/restaurant — singular AND plural.
    _v(
        r"\b(recommend|suggest|pick|find)\s+(a\s+|an\s+|some\s+)?"
        r"(movies?|films?|books?|songs?|albums?|restaurants?|shows?|series|podcasts?)\b",
        [
            ("Genre / style?", ["drama", "comedy", "thriller", "documentary", "any"]),
            ("Mood right now?", ["light", "thought-provoking", "fun", "intense"]),
            ("Time / length?", ["short", "medium", "long", "any"]),
        ],
    ),
    # Big life decisions — career / school / relationship pivot questions.
    _v(
        r"^\s*should\s+i\s+(quit|leave|switch|change|resign\s+from|"
        r"stay\s+at|stay\s+in|take|accept|reject|decline)\s+"
        r"(my\s+)?(job|role|career|company|position|offer|"
        r"internship|grad\s+school|phd|startup|business|relationship)\b",
        [
            ("What's pushing you to consider this?", ["burnout / stress", "comp / pay", "career growth", "team / culture", "personal life"]),
            ("How urgent?", ["right now", "this quarter", "this year", "exploring"]),
            ("What does success look like in 1 year?", ["more $", "more meaning", "more time", "more growth", "more stability"]),
        ],
    ),
    # Emotional opener: "I'm having a hard time", "I feel down", "I'm stuck"
    # — these are NOT acute crisis (no self-harm signal) and should be met
    # with one clarifying question rather than a hotline reflex.
    _v(
        r"^\s*i'?m\s+(having\s+a\s+hard\s+time|"
        r"struggling|stuck|burned?\s*out|exhausted|"
        r"overwhelmed|lost|sad|down|anxious|stressed)\s*\.?\s*$",
        [
            ("What's going on?", ["work", "family", "relationship", "money", "health", "school"]),
            ("How long has this been a thing?", ["a few days", "a few weeks", "months", "longer"]),
            ("What would help most right now?", ["someone to listen", "practical advice", "a plan", "just venting"]),
        ],
    ),
    # "help me with my novel / book / project / presentation / essay / thesis"
    # — could be plotting, editing, structure, research, or motivation.
    _v(
        r"\bhelp\s+me\s+with\s+(my\s+)?"
        r"(novel|book|project|presentation|essay|thesis|dissertation)\b",
        [
            ("What stage are you at?", ["just brainstorming", "outlining", "drafting", "editing / polishing"]),
            ("What kind of help?", ["structure", "writing", "research", "feedback"]),
            ("How long does it need to be?", ["short", "medium", "long", "no limit"]),
        ],
    ),
    # "explain this thing" / "describe that stuff / concept" — no referent.
    _v(
        r"\b(explain|describe)\s+(this|that)\s+(thing|stuff|concept)\b",
        [
            ("Which thing exactly?", ["paste the text", "name the topic", "share a link"]),
            ("What's your level?", ["beginner", "intermediate", "expert"]),
            ("How much detail?", ["one-liner", "short paragraph", "deep dive"]),
        ],
    ),
    # "make (me) a plan / schedule / routine / outline" — scope unknown.
    # Loosened: allow trailing words/punctuation so "make me a meal plan",
    # "make a workout routine", "make me a schedule for next week" all match.
    _v(
        r"\b(make|give|build|create)\s+(me\s+)?an?\s+"
        r"(meal\s+|workout\s+|study\s+|training\s+|reading\s+|"
        r"morning\s+|evening\s+|daily\s+|weekly\s+)?"
        r"(plan|schedule|routine|outline)\b",
        [
            ("A plan for what?", ["work / project", "study", "fitness", "diet", "travel", "money"]),
            ("Over what time frame?", ["one day", "one week", "one month", "longer"]),
            ("How detailed?", ["high-level bullets", "step-by-step", "hour-by-hour"]),
        ],
    ),
    # "plan / organize / design / build my day / week / month / year / life"
    # — loose match so trailing punctuation OR continuation words still trigger.
    _v(
        r"\b(plan|organize|organise|design|build|map\s+out|sketch\s+out)\s+"
        r"(my\s+|a\s+|the\s+|out\s+my\s+)?"
        r"(day|week|weekend|month|quarter|year|life|"
        r"trip|vacation|holiday|itinerary|schedule|routine)\b",
        [
            ("What's the focus?", ["work", "study", "fitness", "travel", "family", "balanced"]),
            ("How detailed?", ["high-level bullets", "day-by-day", "hour-by-hour"]),
            ("Any fixed commitments to fit around?", ["yes — I'll list them", "no, start from scratch", "loose preferences"]),
        ],
    ),
    # "brainstorm ideas" / "come up with ideas" / "help me think through X"
    # — needs domain + count.
    _v(
        r"\b(brainstorm|come\s+up\s+with|generate)\s+(some\s+|a\s+few\s+|a\s+list\s+of\s+)?"
        r"(ideas?|options?|suggestions?|concepts?)\b|"
        r"\bhelp\s+me\s+think\s+(through|about)\b",
        [
            ("What domain?", ["work", "personal", "creative", "business", "learning"]),
            ("How many ideas?", ["3", "5", "10"]),
            ("How wild / safe?", ["safe / practical", "balanced", "wild / out-there"]),
        ],
    ),
    # "write me a story / poem / song / screenplay" without a topic.
    _v(
        r"^\s*(write|compose|draft)\s+(me\s+)?(a|an|some)\s+"
        r"(story|poem|song|lyric|screenplay|script|essay|short\s+story|haiku|sonnet)"
        r"\s*\.?\s*$",
        [
            ("What's the topic?", ["love", "loss", "adventure", "nature", "tech", "your pick"]),
            ("Genre / tone?", ["funny", "serious", "dark", "uplifting", "absurd"]),
            ("How long?", ["a few lines", "a paragraph", "a page", "longer"]),
        ],
    ),
    # "teach me X" / "walk me through X" — level + format unknown.
    _v(
        r"\b(teach\s+me|walk\s+me\s+through|show\s+me\s+how\s+to|"
        r"explain\s+to\s+me|tutor\s+me\s+(on|in))\b",
        [
            ("What's your level?", ["beginner", "intermediate", "advanced"]),
            ("What format?", ["overview", "deep-dive", "step-by-step exercises", "examples only"]),
            ("How long do you have?", ["5 min", "30 min", "an hour", "as long as it takes"]),
        ],
    ),
    # "what's a good book / movie / game / song / recipe" — taste unknown.
    _v(
        r"\bwhat(?:'s|\s+is)\s+a\s+(good|nice|interesting|fun)\s+"
        r"(book|movie|game|song|recipe)\b",
        [
            ("Genre / style?", ["drama", "comedy", "action", "thriller", "any"]),
            ("Mood right now?", ["light", "thought-provoking", "fun", "intense"]),
            ("How much time / effort?", ["short", "medium", "long", "any"]),
        ],
    ),
    # "start a business / company / startup / side hustle" — scope unknown.
    _v(
        r"\b(start|begin)\s+a\s+(business|company|startup|side\s+hustle)\b",
        [
            ("What kind of business?", ["product", "service", "online / SaaS", "local / physical", "not sure"]),
            ("Budget to start?", ["under $1k", "$1k-10k", "$10k+", "bootstrap only"]),
            ("Time you can invest?", ["evenings only", "part-time", "full-time"]),
        ],
    ),
    # "write / draft an email / message / letter / post" — audience unknown.
    _v(
        r"\b(write|draft)\s+(an?\s+)?(email|message|letter|post)\s*\.?\s*$",
        [
            ("Who's the audience?", ["boss", "colleague", "client", "friend", "public"]),
            ("What's the goal?", ["request", "update", "apology", "decline", "announce"]),
            ("Desired tone?", ["formal", "friendly", "neutral", "urgent"]),
        ],
    ),
    # "I have a meeting / interview / exam / date / trip / presentation tomorrow"
    # — they want help, but for what? prep, calm-down, logistics?
    _v(
        r"\bi\s+have\s+an?\s+"
        r"(meeting|interview|exam|date|trip|presentation)\s+tomorrow\b",
        [
            ("What do you want help with?", ["prep / talking points", "calming nerves", "logistics", "follow-up plan"]),
            ("How much time do you have to prep?", ["under 1 hour", "a few hours", "tonight", "all day"]),
            ("What's the stakes level?", ["low", "medium", "high"]),
        ],
    ),
    # "give me / tell me some / a few / three ideas / examples / suggestions / tips"
    # — about what? for whom?
    _v(
        r"\b(give\s+me|tell\s+me)\s+(some|a\s+few|three)\s+"
        r"(ideas|examples|suggestions|tips)\s*\.?\s*$",
        [
            ("Ideas for what?", ["work", "side project", "learning", "hobby", "gifts"]),
            ("For whom?", ["yourself", "team", "friend / family", "general audience"]),
            ("How many?", ["3", "5", "10"]),
        ],
    ),
]


def _format_clarification(qs: list[tuple[str, list[str]]]) -> str:
    lines = ["Quick question first — to give you a useful answer:"]
    for i, (q, opts) in enumerate(qs, 1):
        rendered_opts = " / ".join(f"**{o}**" for o in opts)
        lines.append(f"{i}. {q} ({rendered_opts})")
    return "\n".join(lines)


def vague_clarification(prompt: str) -> str | None:
    for v in _VAGUE_PATTERNS:
        if v.rx.search(prompt):
            return _format_clarification(v.questions)
    return None


# ── PUBLIC API ────────────────────────────────────────────────────────────────

def decide(prompt: str) -> Decision:
    p = prompt.strip()
    if looks_like_injection(p):
        return Decision(passthrough=False, response=_INJECT_REFUSAL, reason="injection")
    canned = vague_clarification(p)
    if canned is not None:
        return Decision(passthrough=False, response=canned, reason="vague")
    return Decision(passthrough=True, reason="passthrough")
