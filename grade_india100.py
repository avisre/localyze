#!/usr/bin/env python3
"""Grade india100_results.json with multilingual keyword matching.

Each topic has a set of accept-keywords spanning Latin transliteration plus
each of the 10 native scripts where the proper noun is well known. A response
passes if it contains ANY accept-keyword for its topic AND does NOT contain
a clearly-wrong answer keyword.
"""

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RESULTS = json.loads((ROOT / "india100_results.json").read_text())

# Each list = accept-keywords for that topic. Match is case-insensitive,
# Unicode-aware. Multiple variants per language are fine.
TOPIC_ACCEPT = {
    1: [  # Capital = New Delhi
        "delhi", "newdelhi", "new delhi",
        "दिल्ली", "ਦਿੱਲੀ", "દિલ્હી", "দিল্লি",
        "டெல்லி", "ఢిల్లీ", "ദെൽഹി", "ദൽഹി", "ദൽഹി", "ഡൽഹി",
        "ദെൽഹി", "ന്യൂഡൽഹി", "ദെല്ഹി", "دہلی", "ڈلهی",
        "दिल्ली", "ದಿಲ್ಲಿ", "ದೆಹಲಿ",
    ],
    2: [  # Gandhi — accept any biographical signal
        "1869", "1948", "porbandar",
        "independence", "non-violence", "nonviolence", "ahimsa",
        "satyagraha", "swaraj", "civil disobedience", "bapu", "lawyer",
        "social", "political", "leader", "freedom",
        "स्वतंत्रता", "अहिंसा", "महात्मा", "नेता", "आंदोलन", "स्वतंत्र",
        "சுதந்திரம்", "அஹிம்சை", "மகாத்மா", "தலைவர்", "இயக்கம்",
        "స్వాతంత్ర్యం", "అహింస", "నాయకుడు", "ఉద్యమం",
        "স্বাধীনতা", "অহিংসা", "নেতা", "আন্দোলন",
        "स्वातंत्र्य", "अहिंसा", "नेता", "चळवळ",
        "સ્વાતંત્ર્ય", "અહિંસા", "નેતા", "આંદોલન", "સામાજિક", "રાજકીય",
        "ಸ್ವಾತಂತ್ರ್ಯ", "ಅಹಿಂಸೆ", "ನಾಯಕ", "ಚಳುವಳಿ",
        "സ്വാതന്ത്ര്യം", "അഹിംസ", "നേതാവ്",
        "ਆਜ਼ਾਦੀ", "ਅਹਿੰਸਾ", "ਆਗੂ", "ਅੰਦੋਲਨ",
        "آزادی", "عدم تشدد", "رہنما", "تحریک",
    ],
    3: [  # Taj Mahal location = Agra
        "agra", "uttar pradesh",
        "आगरा", "आग्रा", "ஆக்ரா", "ఆగ్రా", "আগ্রা", "આગ્રા", "ಆಗ್ರಾ",
        "ആഗ്ര", "ਆਗਰਾ", "ਅਗਰਾ", "آگرہ", "آگره",
    ],
    4: [  # National sport — accept hockey/kabaddi/cricket OR "no official"
        "hockey", "kabaddi", "cricket", "no official", "not officially",
        "हॉकी", "ஹாக்கி", "கிரிக்கெட்", "హాకీ", "క్రికెట్", "హోకీ",
        "হকি", "ক্রিকেট", "હોકી", "ક્રિકેટ", "ಹಾಕಿ", "ಕ್ರಿಕೆಟ್",
        "ഹോക്കി", "ക്രിക്കറ്റ്", "കബഡി", "ਹਾਕੀ", "ਹੌਕੀ", "ਕ੍ਰਿਕਟ", "ہاکی", "کرکٹ",
        "ఏదీ లేదు", "ಘೋಷಿಸಲಾಗಿಲ್ಲ", "अधिकृत", "अधिकृतपणे",
        "ഔദ്യോഗിക", "ഔദ്യോഗികമായി",
    ],
    5: [  # Holi
        "color", "colour", "spring", "hindu", "festival", "phagun", "phalgun",
        "रंग", "होली", "फाल्गुन", "वसंत",
        "ஹோலி", "வசந்த", "ரங்க", "வண்ண",
        "హోళి", "హోలీ",
        "হোলি", "রং",
        "होळी", "रंग",
        "હોળી", "રંગ",
        "ಹೋಳಿ", "ಬಣ್ಣ",
        "ഹോളി", "നിറം",
        "ਹੋਲੀ", "ਰੰਗ",
        "ہولی", "رنگ", "رنگوں",
        "fagua",
    ],
    6: [  # Rupee symbol
        "₹", "rupee", "rupiya", "rupaya",
        "रुपया", "ரூபாய்", "రూపాయి", "টাকা", "টাকার", "રૂપિયો",
        "ರೂಪಾಯಿ", "രൂപ", "ਰੁਪਏ", "روپیہ", "روپے",
    ],
    7: [  # Sachin Tendulkar
        "cricket", "batsman", "batter", "1989", "2013", "mumbai",
        "क्रिकेट", "கிரிக்கெட்", "క్రికెట్", "ক্রিকেট", "ક્રિકેટ",
        "ಕ್ರಿಕೆಟ್", "ക്രിക്കറ്റ്", "ਕ੍ਰਿਕਟ", "کرکٹ",
    ],
    8: [  # Ganga origin
        "gangotri", "himalaya", "himalayan", "uttarakhand", "bhagirathi",
        "गंगोत्री", "हिमालय", "ஹிமாலயம்", "హిమాలయ", "হিমালয়",
        "हिमालय", "હિમાલય", "ಹಿಮಾಲಯ", "ഹിമാലയ", "ਹਿਮਾਲਿਆ",
        "ہمالیہ", "गंगोत्री", "ഗംഗോത്രി", "ਗੰਗੋਤਰੀ",
    ],
    9: [  # 28 states
        "28", "twenty-eight", "twenty eight", "२८", "২৮", "੨੮", "૨૮",
        "೨೮", "౨౮", "൨൮", "٢٨", "۲۸", "இருபத்தெட்டு",
    ],
    10: [  # Diwali
        "rama", "ram", "ravana", "ayodhya", "lakshmi", "light", "diya",
        "dipawali", "deepavali", "lamps", "victory", "darkness",
        "राम", "अयोध्या", "लक्ष्मी", "दीप", "रोशनी",
        "ராமர்", "ராம", "ராமாயண", "லட்சுமி", "தீபம்", "வெளிச்சம்", "ஒளி",
        "రాముడు", "రామ", "లక్ష్మి", "దీపం", "వెలుగు", "అంధకార",
        "রাম", "লক্ষ্মী", "প্রদীপ", "আলো", "অন্ধকার",
        "राम", "लक्ष्मी", "दीप", "प्रकाश",
        "રામ", "લક્ષ્મી", "દીપ", "પ્રકાશ",
        "ರಾಮ", "ಲಕ್ಷ್ಮಿ", "ದೀಪ", "ಬೆಳಕು",
        "രാമൻ", "രാമ", "ലക്ഷ്മി", "വിളക്ക്", "വെളിച്ച",
        "ਰਾਮ", "ਲਕਸ਼ਮੀ", "ਦੀਵਾ", "ਰੋਸ਼ਨੀ",
        "رام", "لکشمی", "چراغ", "روشنی", "اندھیر",
    ],
}

# Strong wrong-answer markers (if present, mark as wrong even if accept matches)
TOPIC_REJECT = {
    1: ["mumbai", "kolkata", "chennai", "bangalore"],
    3: ["delhi", "mumbai", "jaipur"],
    4: [],
    9: ["29", "30", "27", "26"],
}


def grade_one(rec):
    topic = rec["topic_idx"]
    text = rec["response"].lower()
    accepts = [k.lower() for k in TOPIC_ACCEPT[topic]]
    rejects = [k.lower() for k in TOPIC_REJECT.get(topic, [])]
    has_accept = any(k in text for k in accepts)
    # only flag reject if it appears OUTSIDE the accept context (rough check)
    has_reject = any(k in text for k in rejects)
    # for topic 1, reject only counts if "Delhi" not also in response
    if topic == 1 and has_accept:
        has_reject = False
    if topic == 9:
        # literal "28" — Unicode-script digits or "28" not adjacent to other digits
        has_accept = bool(re.search(
            r"(?<!\d)28(?!\d)|२८|২৮|੨੮|૨૮|೨೮|౨౮|൨൮|٢٨|۲۸",
            rec["response"]
        ))
    if has_reject:
        return "WRONG"
    if has_accept:
        return "OK"
    return "MISS"


# Score
results = {}
for r in RESULTS:
    grade = grade_one(r)
    r["grade"] = grade
    results.setdefault(r["lang"], []).append((r["topic_idx"], grade, r))

# Aggregate
print(f"\n{'='*72}")
print(f"INDIA-100 SCORECARD — Localyze on Gemma 4 E4B (CPU emulator)")
print(f"{'='*72}")
print(f"{'Language':14s} {'OK':>3s} {'MISS':>5s} {'WRONG':>6s} {'Score':>6s}  Per-topic")
print("-" * 72)
totals = {"OK": 0, "MISS": 0, "WRONG": 0}
for lang in ["hindi","tamil","telugu","bengali","marathi","gujarati","kannada","malayalam","punjabi","urdu"]:
    rows = sorted(results[lang], key=lambda t: t[0])
    cnt = {"OK": 0, "MISS": 0, "WRONG": 0}
    per = []
    for topic, g, _ in rows:
        cnt[g] += 1
        per.append("✓" if g == "OK" else ("✗" if g == "WRONG" else "·"))
    pct = 100 * cnt["OK"] / 10
    print(f"{lang:14s} {cnt['OK']:>3d} {cnt['MISS']:>5d} {cnt['WRONG']:>6d} {pct:>5.0f}%  {''.join(per)}")
    for k in totals: totals[k] += cnt[k]
print("-" * 72)
total_pct = 100 * totals["OK"] / 100
print(f"{'TOTAL':14s} {totals['OK']:>3d} {totals['MISS']:>5d} {totals['WRONG']:>6d} {total_pct:>5.0f}%")
print()
print(f"Topics: 1=Capital 2=Gandhi 3=TajMahal 4=NationalSport 5=Holi")
print(f"        6=RupeeSymbol 7=Tendulkar 8=GangaOrigin 9=Numstates 10=Diwali")
print(f"Legend: ✓=correct  ·=missing/unrecognized  ✗=wrong-answer detected")

# Save graded results
out = ROOT / "india100_graded.json"
out.write_text(json.dumps(RESULTS, ensure_ascii=False, indent=2))
print(f"\nGraded data saved to {out.name}")
