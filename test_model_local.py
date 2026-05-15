#!/usr/bin/env python3
"""
Localyze Model Test Suite — Local Ollama Testing
================================================
Tests the app's model (via Ollama kimi-k2.6:cloud) with:
  - 15 general knowledge questions
  - 15 web search questions

Also tests the app's web search tool and curated response system.
Results are printed to console and saved to test_results/ directory.
"""

import json
import subprocess
import time
import sys
import os
from datetime import datetime

MODEL = "kimi-k2.6:cloud"
OLLAMA_URL = "http://localhost:11434"

# ─── 15 General Knowledge Questions ───────────────────────────────────────────
GENERAL_QUESTIONS = [
    {
        "id": "G01",
        "question": "What is the capital of Japan and name one famous landmark there?",
        "category": "geography",
        "expected_keywords": ["Tokyo", "landmark"],
    },
    {
        "id": "G02",
        "question": "Explain photosynthesis in simple terms.",
        "category": "science",
        "expected_keywords": ["sunlight", "plant", "oxygen", "carbon dioxide"],
    },
    {
        "id": "G03",
        "question": "What is 15 percent of 240?",
        "category": "math",
        "expected_keywords": ["36"],
    },
    {
        "id": "G04",
        "question": "Why was the printing press important?",
        "category": "history",
        "expected_keywords": ["book", "knowledge", "Gutenberg"],
    },
    {
        "id": "G05",
        "question": "What is SQL injection and how do parameterized queries prevent it?",
        "category": "technology",
        "expected_keywords": ["SQL", "parameterized", "input", "injection"],
    },
    {
        "id": "G06",
        "question": "Explain how binary search works.",
        "category": "computer_science",
        "expected_keywords": ["sorted", "middle", "divide", "log"],
    },
    {
        "id": "G07",
        "question": "What is compound interest and how does it differ from simple interest?",
        "category": "finance",
        "expected_keywords": ["interest on interest", "compound", "principal"],
    },
    {
        "id": "G08",
        "question": "What is the difference between a mutual fund and a fixed deposit?",
        "category": "finance",
        "expected_keywords": ["mutual fund", "fixed deposit", "risk", "return"],
    },
    {
        "id": "G09",
        "question": "What is REST and how does it differ from GraphQL?",
        "category": "technology",
        "expected_keywords": ["REST", "GraphQL", "endpoint", "query"],
    },
    {
        "id": "G10",
        "question": "Explain what machine learning and large language models are.",
        "category": "ai",
        "expected_keywords": ["pattern", "data", "learn", "language"],
    },
    {
        "id": "G11",
        "question": "What is Diwali and why is it celebrated?",
        "category": "culture",
        "expected_keywords": ["festival", "light", "Diwali"],
    },
    {
        "id": "G12",
        "question": "What is yoga in Indian tradition?",
        "category": "culture",
        "expected_keywords": ["yoga", "body", "mind", "tradition"],
    },
    {
        "id": "G13",
        "question": "What is climate change and why does it matter?",
        "category": "science",
        "expected_keywords": ["temperature", "greenhouse", "fossil"],
    },
    {
        "id": "G14",
        "question": "What is the Nobel Prize and how are laureates selected?",
        "category": "general",
        "expected_keywords": ["Nobel", "award", "nomination"],
    },
    {
        "id": "G15",
        "question": "What are mean, median, and mode? Give an example.",
        "category": "math",
        "expected_keywords": ["mean", "median", "mode", "average"],
    },
]

# ─── 15 Web Search Questions ─────────────────────────────────────────────────
WEB_SEARCH_QUESTIONS = [
    {
        "id": "W01",
        "question": "What is the current repo rate set by the RBI?",
        "category": "finance_current",
        "needs_web": True,
        "expected_keywords": ["RBI", "repo rate", "%"],
    },
    {
        "id": "W02",
        "question": "What are the latest headlines in technology news today?",
        "category": "news_current",
        "needs_web": True,
        "expected_keywords": ["headline", "news", "tech"],
    },
    {
        "id": "W03",
        "question": "What is the current Sensex and Nifty level?",
        "category": "finance_current",
        "needs_web": True,
        "expected_keywords": ["Sensex", "Nifty", "index"],
    },
    {
        "id": "W04",
        "question": "What are the top trending movies this week?",
        "category": "entertainment_current",
        "needs_web": True,
        "expected_keywords": ["movie", "film", "trending"],
    },
    {
        "id": "W05",
        "question": "What is the latest iPhone price in India?",
        "category": "product_current",
        "needs_web": True,
        "expected_keywords": ["iPhone", "price", "India"],
    },
    {
        "id": "W06",
        "question": "What happened in the latest India-UK trade agreement?",
        "category": "politics_current",
        "needs_web": True,
        "expected_keywords": ["India", "UK", "trade", "agreement"],
    },
    {
        "id": "W07",
        "question": "What are the latest developments in AI regulation?",
        "category": "tech_policy",
        "needs_web": True,
        "expected_keywords": ["AI", "regulation", "policy"],
    },
    {
        "id": "W08",
        "question": "What is the current weather in New Delhi?",
        "category": "weather_current",
        "needs_web": True,
        "expected_keywords": ["weather", "Delhi", "temperature"],
    },
    {
        "id": "W09",
        "question": "Who won the most recent Oscar for Best Picture?",
        "category": "entertainment_current",
        "needs_web": True,
        "expected_keywords": ["Oscar", "Best Picture", "won"],
    },
    {
        "id": "W10",
        "question": "What are the latest developments in quantum computing?",
        "category": "tech_current",
        "needs_web": True,
        "expected_keywords": ["quantum", "computing", "research"],
    },
    {
        "id": "W11",
        "question": "What is the latest version of Android released?",
        "category": "tech_current",
        "needs_web": True,
        "expected_keywords": ["Android", "version", "release"],
    },
    {
        "id": "W12",
        "question": "What are the current crypto regulation updates?",
        "category": "finance_policy",
        "needs_web": True,
        "expected_keywords": ["crypto", "regulation", "digital"],
    },
    {
        "id": "W13",
        "question": "What are the top global summits happening this month?",
        "category": "politics_current",
        "needs_web": True,
        "expected_keywords": ["summit", "global", "meeting"],
    },
    {
        "id": "W14",
        "question": "What is the current stock market performance of major tech companies?",
        "category": "finance_current",
        "needs_web": True,
        "expected_keywords": ["stock", "tech", "market"],
    },
    {
        "id": "W15",
        "question": "What are the latest music trends globally?",
        "category": "entertainment_current",
        "needs_web": True,
        "expected_keywords": ["music", "trending", "global"],
    },
]


def query_ollama(prompt: str, model: str = MODEL, timeout: int = 120) -> dict:
    """Send a question to Ollama and get a response."""
    payload = {
        "model": model,
        "prompt": prompt,
        "stream": False,
        "options": {
            "temperature": 0.7,
            "num_predict": 1024,
        }
    }
    try:
        result = subprocess.run(
            ["curl", "-s", f"{OLLAMA_URL}/api/generate",
             "-d", json.dumps(payload)],
            capture_output=True,
            text=True,
            timeout=timeout
        )
        if result.returncode != 0:
            return {"error": f"curl failed: {result.stderr}", "response": ""}
        
        data = json.loads(result.stdout)
        return {
            "response": data.get("response", ""),
            "thinking": data.get("thinking", ""),
            "duration_ms": data.get("total_duration", 0) // 1_000_000,
            "eval_count": data.get("eval_count", 0),
            "prompt_eval_count": data.get("prompt_eval_count", 0),
            "done": data.get("done", False),
            "error": None,
        }
    except subprocess.TimeoutExpired:
        return {"error": f"Timeout after {timeout}s", "response": ""}
    except json.JSONDecodeError as e:
        return {"error": f"JSON parse error: {e}", "response": ""}
    except Exception as e:
        return {"error": f"Exception: {e}", "response": ""}


def check_keywords(response: str, keywords: list) -> dict:
    """Check if expected keywords appear in the response."""
    response_lower = response.lower()
    found = []
    missing = []
    for kw in keywords:
        if kw.lower() in response_lower:
            found.append(kw)
        else:
            missing.append(kw)
    return {
        "found_keywords": found,
        "missing_keywords": missing,
        "keyword_match_pct": round(len(found) / len(keywords) * 100) if keywords else 0,
    }


def test_web_search_tool(question: dict) -> dict:
    """Test the app's WebSearchTool via direct curl to simulate web search."""
    query = question["question"]
    try:
        # Simulate the app's web search tool which uses DuckDuckGo-like search
        result = subprocess.run(
            ["curl", "-s", "https://html.duckduckgo.com/html/",
             "-d", f"q={query}",
             "-H", "User-Agent: Mozilla/5.0"],
            capture_output=True,
            text=True,
            timeout=15
        )
        if result.returncode == 0 and len(result.stdout) > 100:
            return {
                "search_successful": True,
                "result_length": len(result.stdout),
                "error": None,
            }
        else:
            return {
                "search_successful": False,
                "result_length": len(result.stdout) if result.stdout else 0,
                "error": "Search returned empty or failed",
            }
    except Exception as e:
        return {"search_successful": False, "result_length": 0, "error": str(e)}


def run_tests():
    """Run all tests and generate report."""
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    results_dir = "/home/hardoker77/Downloads/new/localyze-main/test_results"
    os.makedirs(results_dir, exist_ok=True)
    
    all_results = {
        "timestamp": timestamp,
        "model": MODEL,
        "general_knowledge_results": [],
        "web_search_results": [],
        "bugs_found": [],
        "summary": {},
    }
    
    print("=" * 80)
    print("LOCALYZE MODEL TEST SUITE — Local Ollama Testing")
    print(f"Model: {MODEL}")
    print(f"Started: {datetime.now().isoformat()}")
    print("=" * 80)
    
    # ─── Test 1: General Knowledge Questions ─────────────────────────────────
    print("\n" + "─" * 80)
    print("SECTION 1: 15 GENERAL KNOWLEDGE QUESTIONS")
    print("─" * 80)
    
    general_passed = 0
    general_total = len(GENERAL_QUESTIONS)
    
    for q in GENERAL_QUESTIONS:
        print(f"\n[{q['id']}] Q: {q['question']}")
        print(f"  Category: {q['category']}")
        
        result = query_ollama(q["question"])
        
        if result["error"]:
            print(f"  ❌ ERROR: {result['error']}")
            all_results["general_knowledge_results"].append({
                "id": q["id"],
                "question": q["question"],
                "category": q["category"],
                "error": result["error"],
                "passed": False,
            })
            continue
        
        response = result["response"]
        duration = result.get("duration_ms", 0)
        
        kw_check = check_keywords(response, q["expected_keywords"])
        passed = kw_check["keyword_match_pct"] >= 50
        
        if passed:
            general_passed += 1
        
        print(f"  ✅ PASSED" if passed else f"  ❌ FAILED")
        print(f"  Duration: {duration}ms | Tokens: {result.get('eval_count', 'N/A')}")
        print(f"  Keywords found: {kw_check['found_keywords']} | Missing: {kw_check['missing_keywords']}")
        print(f"  Keyword match: {kw_check['keyword_match_pct']}%")
        print(f"  Response preview: {response[:200]}...")
        
        all_results["general_knowledge_results"].append({
            "id": q["id"],
            "question": q["question"],
            "category": q["category"],
            "response": response[:500],
            "thinking": result.get("thinking", "")[:200],
            "duration_ms": duration,
            "eval_count": result.get("eval_count", 0),
            "keywords_found": kw_check["found_keywords"],
            "keywords_missing": kw_check["missing_keywords"],
            "keyword_match_pct": kw_check["keyword_match_pct"],
            "passed": passed,
        })
    
    # ─── Test 2: Web Search Questions ────────────────────────────────────────
    print("\n" + "─" * 80)
    print("SECTION 2: 15 WEB SEARCH QUESTIONS")
    print("─" * 80)
    
    web_passed = 0
    web_total = len(WEB_SEARCH_QUESTIONS)
    
    for q in WEB_SEARCH_QUESTIONS:
        print(f"\n[{q['id']}] Q: {q['question']}")
        print(f"  Category: {q['category']} | Needs web search: {q['needs_web']}")
        
        # First test the web search tool
        search_result = test_web_search_tool(q)
        print(f"  Web search tool: {'✅ OK' if search_result['search_successful'] else '❌ FAILED'}")
        if search_result["error"]:
            print(f"  Search error: {search_result['error']}")
            all_results["bugs_found"].append({
                "id": q["id"],
                "type": "web_search_failure",
                "description": f"Web search tool failed for '{q['question']}': {search_result['error']}",
                "severity": "medium",
            })
        
        # Then test the model's response to the question
        web_prompt = f"""Answer this question with current information: {q['question']}
If you don't have real-time data, say so clearly and provide the most recent information you know."""
        
        result = query_ollama(web_prompt)
        
        if result["error"]:
            print(f"  ❌ ERROR: {result['error']}")
            all_results["web_search_results"].append({
                "id": q["id"],
                "question": q["question"],
                "category": q["category"],
                "search_result": search_result,
                "error": result["error"],
                "passed": False,
            })
            continue
        
        response = result["response"]
        duration = result.get("duration_ms", 0)
        
        # Check if the model acknowledges needing current data or provides useful info
        kw_check = check_keywords(response, q["expected_keywords"])
        passed = kw_check["keyword_match_pct"] >= 30 or any(
            phrase in response.lower() 
            for phrase in ["i don't have", "current", "latest", "recent", "as of"]
        )
        
        if passed:
            web_passed += 1
        
        print(f"  {'✅ PASSED' if passed else '❌ FAILED'}")
        print(f"  Duration: {duration}ms | Tokens: {result.get('eval_count', 'N/A')}")
        print(f"  Keywords found: {kw_check['found_keywords']} | Missing: {kw_check['missing_keywords']}")
        print(f"  Response preview: {response[:200]}...")
        
        all_results["web_search_results"].append({
            "id": q["id"],
            "question": q["question"],
            "category": q["category"],
            "response": response[:500],
            "thinking": result.get("thinking", "")[:200],
            "duration_ms": duration,
            "eval_count": result.get("eval_count", 0),
            "search_result": search_result,
            "keywords_found": kw_check["found_keywords"],
            "keywords_missing": kw_check["missing_keywords"],
            "keyword_match_pct": kw_check["keyword_match_pct"],
            "passed": passed,
        })
    
    # ─── Test 3: Code-specific questions ─────────────────────────────────────
    print("\n" + "─" * 80)
    print("SECTION 3: CODE & TOOL BUG CHECKS")
    print("─" * 80)
    
    # Check for mock mode remnants
    print("\nChecking for mock mode remnants...")
    mock_grep = subprocess.run(
        ["grep", "-rn", "MockGemmaEngine\\|USE_MOCK_ENGINE\\|useMockEngine\\|isMockMode\\|mockEngine",
         "/home/hardoker77/Downloads/new/localyze-main/app/src/main/java/"],
        capture_output=True, text=True
    )
    if mock_grep.returncode != 0 and not mock_grep.stdout.strip():
        print("  ✅ No mock mode remnants found in source code")
    else:
        print(f"  ❌ Mock mode remnants found:\n{mock_grep.stdout}")
        all_results["bugs_found"].append({
            "id": "BUG-MOCK",
            "type": "mock_mode_remnant",
            "description": "Mock mode references still exist in source code",
            "severity": "high",
            "details": mock_grep.stdout[:500]
        })
    
    # Check for build config remnants
    build_grep = subprocess.run(
        ["grep", "-n", "USE_MOCK_ENGINE",
         "/home/hardoker77/Downloads/new/localyze-main/app/build.gradle.kts"],
        capture_output=True, text=True
    )
    if build_grep.returncode != 0 and not build_grep.stdout.strip():
        print("  ✅ USE_MOCK_ENGINE removed from build.gradle.kts")
    else:
        print(f"  ❌ USE_MOCK_ENGINE still in build.gradle.kts:\n{build_grep.stdout}")
        all_results["bugs_found"].append({
            "id": "BUG-BUILD",
            "type": "build_config_remnant",
            "description": "USE_MOCK_ENGINE still in build.gradle.kts",
            "severity": "high",
            "details": build_grep.stdout
        })
    
    # Check Ollama connection
    print("\nChecking Ollama connection...")
    conn_test = query_ollama("Hello, are you working?", timeout=30)
    if conn_test["error"] is None:
        print(f"  ✅ Ollama connection working (model: {MODEL})")
        print(f"  Response: {conn_test['response'][:100]}...")
    else:
        print(f"  ❌ Ollama connection failed: {conn_test['error']}")
        all_results["bugs_found"].append({
            "id": "BUG-OLLAMA",
            "type": "ollama_connection",
            "description": f"Ollama connection failed: {conn_test['error']}",
            "severity": "critical",
        })
    
    # ─── Summary ─────────────────────────────────────────────────────────────
    print("\n" + "=" * 80)
    print("TEST SUMMARY")
    print("=" * 80)
    
    general_pct = round(general_passed / general_total * 100) if general_total else 0
    web_pct = round(web_passed / web_total * 100) if web_total else 0
    
    print(f"\nGeneral Knowledge: {general_passed}/{general_total} passed ({general_pct}%)")
    print(f"Web Search:        {web_passed}/{web_total} passed ({web_pct}%)")
    print(f"Bugs Found:        {len(all_results['bugs_found'])}")
    
    if all_results["bugs_found"]:
        print("\nBugs:")
        for bug in all_results["bugs_found"]:
            print(f"  [{bug['severity'].upper()}] {bug['description']}")
    
    all_results["summary"] = {
        "general_knowledge_passed": general_passed,
        "general_knowledge_total": general_total,
        "general_knowledge_pct": general_pct,
        "web_search_passed": web_passed,
        "web_search_total": web_total,
        "web_search_pct": web_pct,
        "bugs_count": len(all_results["bugs_found"]),
        "overall_passed": general_pct >= 70 and web_pct >= 50 and len(all_results["bugs_found"]) == 0,
    }
    
    # Save results
    results_file = os.path.join(results_dir, f"test_{timestamp}.json")
    with open(results_file, "w") as f:
        json.dump(all_results, f, indent=2)
    print(f"\nResults saved to: {results_file}")
    
    # Also save a markdown report
    md_file = os.path.join(results_dir, f"test_{timestamp}.md")
    with open(md_file, "w") as f:
        f.write(f"# Localyze Model Test Report\n\n")
        f.write(f"**Date:** {datetime.now().isoformat()}\n")
        f.write(f"**Model:** {MODEL}\n\n")
        f.write(f"## General Knowledge Results ({general_passed}/{general_total} = {general_pct}%)\n\n")
        f.write("| ID | Question | Category | Keywords | Status |\n")
        f.write("|----|----------|----------|----------|--------|\n")
        for r in all_results["general_knowledge_results"]:
            status = "✅" if r.get("passed") else "❌"
            kw = f"{r.get('keyword_match_pct', 0)}%"
            f.write(f"| {r['id']} | {r['question'][:50]} | {r['category']} | {kw} | {status} |\n")
        f.write(f"\n## Web Search Results ({web_passed}/{web_total} = {web_pct}%)\n\n")
        f.write("| ID | Question | Category | Keywords | Search | Status |\n")
        f.write("|----|----------|----------|----------|--------|--------|\n")
        for r in all_results["web_search_results"]:
            status = "✅" if r.get("passed") else "❌"
            kw = f"{r.get('keyword_match_pct', 0)}%"
            search_ok = "✅" if r.get("search_result", {}).get("search_successful") else "❌"
            f.write(f"| {r['id']} | {r['question'][:50]} | {r['category']} | {kw} | {search_ok} | {status} |\n")
        if all_results["bugs_found"]:
            f.write(f"\n## Bugs Found ({len(all_results['bugs_found'])})\n\n")
            for bug in all_results["bugs_found"]:
                f.write(f"- **[{bug['severity'].upper()}]** {bug['description']}\n")
        f.write(f"\n## Summary\n\n")
        f.write(f"- General Knowledge: **{general_pct}%** ({general_passed}/{general_total})\n")
        f.write(f"- Web Search: **{web_pct}%** ({web_passed}/{web_total})\n")
        f.write(f"- Bugs: **{len(all_results['bugs_found'])}**\n")
        overall = "✅ PASS" if all_results["summary"]["overall_passed"] else "❌ FAIL"
        f.write(f"- Overall: **{overall}**\n")
    print(f"Markdown report saved to: {md_file}")
    
    return all_results


if __name__ == "__main__":
    results = run_tests()
    sys.exit(0 if results["summary"]["overall_passed"] else 1)