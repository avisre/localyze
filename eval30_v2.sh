#!/bin/bash
set -e
ADB="/home/hardoker77/Downloads/new/localyze-main/.codex-tools/android-sdk/platform-tools/adb"
ACT="com.localyze/.MainActivity"
OUTDIR="chatbot_test_results/eval30"
mkdir -p "$OUTDIR"
ts=$(date +%Y%m%d_%H%M%S)
JSON="$OUTDIR/eval30_${ts}.json"
MD="$OUTDIR/eval30_${ts}.md"
echo '{"results":[' > "$JSON"
first=1

KQ=(
  "What is the capital of France?"
  "What is 2 plus 2?"
  "Who wrote Romeo and Juliet?"
  "What is the chemical symbol for water?"
  "How many planets are in our solar system?"
  "What is the speed of light approximately?"
  "Who painted the Mona Lisa?"
  "What is the largest ocean on Earth?"
  "What year did World War II end?"
  "What is the smallest prime number?"
  "Who invented the telephone?"
  "What is the capital of Japan?"
  "How many continents are there?"
  "What gas do plants absorb from the atmosphere?"
  "What is the freezing point of water in Celsius?"
)
WQ=(
  "What is the current weather in New York City?"
  "What is the price of Bitcoin right now?"
  "Who is the current CEO of Google?"
  "What was the score of the latest FIFA World Cup final?"
  "What movies are playing in theaters this week?"
  "What is the current population of India?"
  "What is the latest news about SpaceX?"
  "What is the stock price of Apple today?"
  "Who won the most recent Nobel Prize in Literature?"
  "What is the current exchange rate USD to EUR?"
  "What are the top trending topics on Twitter right now?"
  "What is the latest version of Android released?"
  "Who is the current president of the United States?"
  "What is the current GDP growth rate of China?"
  "What are today's headlines from BBC News?"
)

ask_question() {
  local q="$1"
  local idx="$2"
  local cat="$3"

  echo ""
  echo "[$cat] Q$idx: $q"

  # Start fresh conversation
  $ADB shell am start -n "$ACT" >/dev/null 2>&1
  sleep 3
  $ADB shell input tap 1280 280 >/dev/null 2>&1
  sleep 1

  $ADB shell logcat -c
  t0=$(date +%s)

  # Send question
  $ADB shell am start -n "$ACT" -a android.intent.action.SEND --es chat_msg "$q" >/dev/null 2>&1

  # Wait for onDone
  wait_done=0
  for i in $(seq 1 25); do
    sleep 1
    if $ADB shell logcat -d -s GemmaInference:D 2>/dev/null | grep -q "onDone callback received"; then
      wait_done=$i
      break
    fi
  done

  sleep 1

  # Extract response text
  $ADB shell uiautomator dump /sdcard/eval.xml >/dev/null 2>&1
   resp=$($ADB shell cat /sdcard/eval.xml 2>/dev/null | grep -o 'text="[^"]*"' | grep -v 'Localyze\|Chat\|Code\|Library\|Settings\|On-device\|Message\|just now\|Hello\|Report / Flag\|Backups\|Restore\|Subscription\|Backup text\|Restore from\|Restore your\|Restore purchase\|Recover from\|Manage subscription\|Billing is\|No active\|Unavailable' | grep -v 'text=""' | tail -1 | sed 's/text="//;s/"$//' || true)

  t1=$(date +%s)
  total=$((t1 - t0))

  tool="No"
  if $ADB shell logcat -d 2>/dev/null | grep -qiE "web_search|duckduckgo|bing|wikipedia"; then
    tool="Yes"
  fi

  ok="FAIL"
  if [ -n "$resp" ] && [ ${#resp} -gt 5 ]; then
    ok="OK"
  fi

  echo "  -> $ok | ${total}s | tool=$tool | ${resp:0:120}"

  if [ "$first" -eq 1 ]; then first=0; else echo "," >> "$JSON"; fi
  q_esc=$(echo "$q" | sed 's/"/\\"/g')
  r_esc=$(echo "$resp" | sed 's/"/\\"/g' | tr -d '\n\r' | head -c 500)
  cat >> "$JSON" <<EOF
  {"category":"$cat","index":$idx,"question":"$q_esc","response_time_sec":$total,"response_text":"$r_esc","tool_triggered":"$tool","status":"$ok"}
EOF
}

echo "=== KNOWLEDGE (15) ==="
for i in "${!KQ[@]}"; do ask_question "${KQ[$i]}" $((i+1)) "KNOWLEDGE"; done
echo ""
echo "=== WEB SEARCH (15) ==="
for i in "${!WQ[@]}"; do ask_question "${WQ[$i]}" $((i+1)) "WEB_SEARCH"; done

echo ']}' >> "$JSON"

ok_count=$(grep -c '"status":"OK"' "$JSON" || echo 0)
total_count=$(grep -c '"category"' "$JSON" || echo 0)

cat > "$MD" <<EOF
# Localyze 30-Question Evaluation Report
**Date:** $(date '+%Y-%m-%d %H:%M:%S')
**Device:** OnePlus NE2211 | Android 16 | Gemma 4 E4B GPU

## Summary
- Total questions: $total_count
- Successful responses: $ok_count/$total_count

EOF

echo "" >> "$MD"
echo "| # | Category | Time | Tool | Status | Question | Response |" >> "$MD"
echo "|---|----------|------|------|--------|----------|----------|" >> "$MD"

python3 -c "
import json
with open('$JSON') as f:
    data = json.load(f)
for r in data['results']:
    q = r['question'][:45].replace('|', '\\\\|')
    a = r['response_text'][:60].replace('|', '\\\\|').replace(chr(10), ' ')
    print(f\"| {r['index']:02d} | {r['category']} | {r['response_time_sec']}s | {r['tool_triggered']} | {r['status']} | {q} | {a} |\")" >> "$MD"

echo ""
echo "=== DONE ==="
echo "JSON: $JSON"
echo "MD:   $MD"
echo "Success: $ok_count / $total_count"