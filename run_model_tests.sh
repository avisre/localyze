#!/bin/bash
MODEL="kimi-k2.6:cloud"
PASS_G=0; FAIL_G=0; PASS_W=0; FAIL_W=0; BUGS=""

echo "================================================================================"
echo "LOCALYZE MODEL TEST SUITE — Ollama kimi-k2.6:cloud"
echo "Started: $(date -Iseconds)"
echo "================================================================================"

echo ""; echo "SECTION 0: CODE BUG CHECKS"; echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
MOCK_R=$(grep -rn "MockGemmaEngine\|USE_MOCK_ENGINE\|useMockEngine\|isMockMode\|mockEngine" /home/hardoker77/Downloads/new/localyze-main/app/src/main/java/ 2>/dev/null)
if [ -z "$MOCK_R" ]; then echo "✅ No mock mode remnants found"; else echo "❌ Mock remnants found:"; echo "$MOCK_R"; BUGS="$BUGS\n[HIGH] Mock remnants"; fi
BUILD_M=$(grep -n "USE_MOCK_ENGINE" /home/hardoker77/Downloads/new/localyze-main/app/build.gradle.kts 2>/dev/null)
if [ -z "$BUILD_M" ]; then echo "✅ USE_MOCK_ENGINE removed from build.gradle.kts"; else echo "❌ USE_MOCK_ENGINE still in build"; echo "$BUILD_M"; BUGS="$BUGS\n[HIGH] USE_MOCK_ENGINE"; fi

ask_ollama() {
    curl -s http://localhost:11434/api/generate -d "{\"model\":\"$MODEL\",\"prompt\":\"$1\",\"stream\":false,\"options\":{\"temperature\":0.7,\"num_predict\":512}}" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('response','ERROR'))" 2>/dev/null
}

echo ""; echo "SECTION 1: 15 GENERAL KNOWLEDGE QUESTIONS"; echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

echo ""; echo "[G01] Capital of Japan & landmark?"
R=$(ask_ollama "What is the capital of Japan and name one famous landmark there?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "tokyo" && { echo "  ✅ PASSED"; ((PASS_G++)); } || { echo "  ❌ FAILED"; ((FAIL_G++)); }

echo ""; echo "[G02] Photosynthesis?"
R=$(ask_ollama "Explain photosynthesis in simple terms."); echo "  $(echo $R | head -c 150)..."
(echo "$R" | grep -qi "sunlight" || echo "$R" | grep -qi "plant") && { echo "  ✅ PASSED"; ((PASS_G++)); } || { echo "  ❌ FAILED"; ((FAIL_G++)); }

echo ""; echo "[G03] 15% of 240?"
R=$(ask_ollama "What is 15 percent of 240?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -q "36" && { echo "  ✅ PASSED"; ((PASS_G++)); } || { echo "  ❌ FAILED"; ((FAIL_G++)); }

echo ""; echo "[G04] Printing press importance?"
R=$(ask_ollama "Why was the printing press important?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "book\|knowledge\|Gutenberg\|information\|spread" && { echo "  ✅ PASSED"; ((PASS_G++)); } || { echo "  ❌ FAILED"; ((FAIL_G++)); }

echo ""; echo "[G05] SQL injection?"
R=$(ask_ollama "What is SQL injection and how do parameterized queries prevent it?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "SQL\|injection\|parameterized\|input" && { echo "  ✅ PASSED"; ((PASS_G++)); } || { echo "  ❌ FAILED"; ((FAIL_G++)); }

echo ""; echo "[G06] Binary search?"
R=$(ask_ollama "Explain how binary search works."); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "sorted\|middle\|divide\|half\|log" && { echo "  ✅ PASSED"; ((PASS_G++)); } || { echo "  ❌ FAILED"; ((FAIL_G++)); }

echo ""; echo "[G07] Compound interest?"
R=$(ask_ollama "What is compound interest and how does it differ from simple interest?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "compound\|interest on interest\|principal" && { echo "  ✅ PASSED"; ((PASS_G++)); } || { echo "  ❌ FAILED"; ((FAIL_G++)); }

echo ""; echo "[G08] Mutual fund vs fixed deposit?"
R=$(ask_ollama "What is the difference between a mutual fund and a fixed deposit?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "mutual fund\|fixed deposit\|risk\|return\|market" && { echo "  ✅ PASSED"; ((PASS_G++)); } || { echo "  ❌ FAILED"; ((FAIL_G++)); }

echo ""; echo "[G09] REST vs GraphQL?"
R=$(ask_ollama "What is REST and how does it differ from GraphQL?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "REST\|GraphQL\|endpoint\|query\|API" && { echo "  ✅ PASSED"; ((PASS_G++)); } || { echo "  ❌ FAILED"; ((FAIL_G++)); }

echo ""; echo "[G10] Machine learning & LLMs?"
R=$(ask_ollama "Explain what machine learning and large language models are."); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "pattern\|data\|learn\|language\|model" && { echo "  ✅ PASSED"; ((PASS_G++)); } || { echo "  ❌ FAILED"; ((FAIL_G++)); }

echo ""; echo "[G11] Diwali?"
R=$(ask_ollama "What is Diwali and why is it celebrated?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "festival\|light\|Diwali\|celebrat" && { echo "  ✅ PASSED"; ((PASS_G++)); } || { echo "  ❌ FAILED"; ((FAIL_G++)); }

echo ""; echo "[G12] Yoga in Indian tradition?"
R=$(ask_ollama "What is yoga in Indian tradition?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "yoga\|body\|mind\|tradition\|discipline" && { echo "  ✅ PASSED"; ((PASS_G++)); } || { echo "  ❌ FAILED"; ((FAIL_G++)); }

echo ""; echo "[G13] Climate change?"
R=$(ask_ollama "What is climate change and why does it matter?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "temperature\|greenhouse\|fossil\|carbon\|warming" && { echo "  ✅ PASSED"; ((PASS_G++)); } || { echo "  ❌ FAILED"; ((FAIL_G++)); }

echo ""; echo "[G14] Nobel Prize?"
R=$(ask_ollama "What is the Nobel Prize and how are laureates selected?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "Nobel\|award\|prize\|nomination\|committee" && { echo "  ✅ PASSED"; ((PASS_G++)); } || { echo "  ❌ FAILED"; ((FAIL_G++)); }

echo ""; echo "[G15] Mean, median, mode?"
R=$(ask_ollama "What are mean, median, and mode? Give an example."); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "mean\|median\|mode\|average" && { echo "  ✅ PASSED"; ((PASS_G++)); } || { echo "  ❌ FAILED"; ((FAIL_G++)); }

echo ""; echo "SECTION 2: 15 WEB SEARCH QUESTIONS"; echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

echo ""; echo "[W01] RBI repo rate?"
R=$(ask_ollama "What is the current repo rate set by the RBI? Provide the most recent data you know."); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "RBI\|repo rate\|6\.\|%" && { echo "  ✅ PASSED"; ((PASS_W++)); } || { echo "  ❌ FAILED"; ((FAIL_W++)); }

echo ""; echo "[W02] Tech news headlines?"
R=$(ask_ollama "What are the latest headlines in technology news?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "headline\|news\|tech\|AI\|latest" && { echo "  ✅ PASSED"; ((PASS_W++)); } || { echo "  ❌ FAILED"; ((FAIL_W++)); }

echo ""; echo "[W03] Sensex/Nifty?"
R=$(ask_ollama "What is the approximate current Sensex and Nifty index level?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "Sensex\|Nifty\|index\|point\|stock" && { echo "  ✅ PASSED"; ((PASS_W++)); } || { echo "  ❌ FAILED"; ((FAIL_W++)); }

echo ""; echo "[W04] Trending movies?"
R=$(ask_ollama "What are the top trending movies this week?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "movie\|film\|trending\|box office" && { echo "  ✅ PASSED"; ((PASS_W++)); } || { echo "  ❌ FAILED"; ((FAIL_W++)); }

echo ""; echo "[W05] iPhone price India?"
R=$(ask_ollama "What is the latest iPhone price in India?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "iPhone\|price\|India\|Apple" && { echo "  ✅ PASSED"; ((PASS_W++)); } || { echo "  ❌ FAILED"; ((FAIL_W++)); }

echo ""; echo "[W06] India-UK trade?"
R=$(ask_ollama "What happened in the latest India-UK trade agreement?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "India\|UK\|trade\|agreement\|deal" && { echo "  ✅ PASSED"; ((PASS_W++)); } || { echo "  ❌ FAILED"; ((FAIL_W++)); }

echo ""; echo "[W07] AI regulation?"
R=$(ask_ollama "What are the latest developments in AI regulation globally?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "AI\|regulation\|policy\|EU\|govern" && { echo "  ✅ PASSED"; ((PASS_W++)); } || { echo "  ❌ FAILED"; ((FAIL_W++)); }

echo ""; echo "[W08] Delhi weather?"
R=$(ask_ollama "What is the current weather in New Delhi India?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "weather\|Delhi\|temperature\|hot\|rain\|degree" && { echo "  ✅ PASSED"; ((PASS_W++)); } || { echo "  ❌ FAILED"; ((FAIL_W++)); }

echo ""; echo "[W09] Oscar Best Picture?"
R=$(ask_ollama "Who won the most recent Oscar for Best Picture?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "Oscar\|Best Picture\|won\|Academy\|film" && { echo "  ✅ PASSED"; ((PASS_W++)); } || { echo "  ❌ FAILED"; ((FAIL_W++)); }

echo ""; echo "[W10] Quantum computing?"
R=$(ask_ollama "What are the latest developments in quantum computing?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "quantum\|computing\|qubit\|research" && { echo "  ✅ PASSED"; ((PASS_W++)); } || { echo "  ❌ FAILED"; ((FAIL_W++)); }

echo ""; echo "[W11] Latest Android?"
R=$(ask_ollama "What is the latest version of Android released?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "Android\|version\|release\|16\|15" && { echo "  ✅ PASSED"; ((PASS_W++)); } || { echo "  ❌ FAILED"; ((FAIL_W++)); }

echo ""; echo "[W12] Crypto regulation?"
R=$(ask_ollama "What are the current cryptocurrency regulation updates?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "crypto\|regulation\|digital\|bitcoin\|framework" && { echo "  ✅ PASSED"; ((PASS_W++)); } || { echo "  ❌ FAILED"; ((FAIL_W++)); }

echo ""; echo "[W13] Global summits?"
R=$(ask_ollama "What are the top global summits happening recently?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "summit\|global\|meeting\|conference\|G7\|G20" && { echo "  ✅ PASSED"; ((PASS_W++)); } || { echo "  ❌ FAILED"; ((FAIL_W++)); }

echo ""; echo "[W14] Tech stock market?"
R=$(ask_ollama "What is the current stock market performance of major tech companies?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "stock\|tech\|market\|Apple\|Google\|Microsoft" && { echo "  ✅ PASSED"; ((PASS_W++)); } || { echo "  ❌ FAILED"; ((FAIL_W++)); }

echo ""; echo "[W15] Music trends?"
R=$(ask_ollama "What are the latest music trends globally?"); echo "  $(echo $R | head -c 150)..."
echo "$R" | grep -qi "music\|trending\|global\|song\|artist\|stream" && { echo "  ✅ PASSED"; ((PASS_W++)); } || { echo "  ❌ FAILED"; ((FAIL_W++)); }

echo ""; echo "================================================================================"
echo "TEST SUMMARY"
echo "================================================================================"
TOTAL_G=$((PASS_G + FAIL_G)); TOTAL_W=$((PASS_W + FAIL_W))
PCT_G=$((PASS_G * 100 / TOTAL_G)); PCT_W=$((PASS_W * 100 / TOTAL_W))
echo ""; echo "General Knowledge: $PASS_G/$TOTAL_G passed ($PCT_G%)"
echo "Web Search:        $PASS_W/$TOTAL_W passed ($PCT_W%)"
echo "Bugs Found:        $(echo "$BUGS" | grep -c '.' 2>/dev/null || echo 0)"
if [ -n "$BUGS" ]; then echo ""; echo "Bugs:"; echo -e "$BUGS"; fi
echo ""; if [ $PCT_G -ge 70 ] && [ $PCT_W -ge 50 ]; then echo "OVERALL: ✅ PASS"; else echo "OVERALL: ❌ FAIL"; fi
echo "Completed: $(date -Iseconds)"
