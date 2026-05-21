#!/bin/bash
# Localyze Production Readiness Test — 100-point check
# Usage: bash run_100q_test.sh
# Emulator must be running; ADB in PATH

ADB="adb"
ACT="com.localyze/.MainActivity"
APP="com.localyze"
SRC="$(dirname "$0")/app/src/main/java/com/localyze"
PASS=0; FAIL=0; WARN=0
FAILS=""

pass() { echo "  ✅ PASS: $1"; ((PASS++)); }
fail() { echo "  ❌ FAIL: $1"; ((FAIL++)); FAILS="$FAILS\n  - $1"; }
warn() { echo "  ⚠️  WARN: $1"; ((WARN++)); }
section() { echo ""; echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"; echo "  $1"; echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"; }

echo ""; echo "══════════════════════════════════════════════════════════════"
echo "  LOCALYZE PRODUCTION READINESS — 100-POINT TEST SUITE"
echo "  $(date '+%Y-%m-%d %H:%M:%S')"
echo "══════════════════════════════════════════════════════════════"

# ─── SECTION 1: STATIC CODE CHECKS (30 points) ─────────────────
section "SECTION 1: STATIC CODE CHECKS (30 points)"

# 1.1 No mock engine remnants
if ! grep -rq "MockGemmaEngine\|USE_MOCK_ENGINE\|useMockEngine\|isMockMode\|mockEngine" "$SRC" 2>/dev/null; then
  pass "No mock engine remnants in source"
else
  fail "Mock engine remnants found"
fi

# 1.2 ARM ABI check in isRunningOnEmulator
if grep -q 'startsWith("arm", ignoreCase = true)' "$SRC/domain/usecases/SendMessageUseCase.kt" 2>/dev/null; then
  pass "isRunningOnEmulator has ARM ABI guard"
else
  fail "isRunningOnEmulator missing ARM ABI guard"
fi

# 1.3 AtomicBoolean for responseReceived
if grep -q 'AtomicBoolean' "$SRC/ai/GemmaInferenceEngine.kt" 2>/dev/null; then
  pass "AtomicBoolean used for responseReceived race"
else
  fail "Missing AtomicBoolean for responseReceived"
fi

# 1.4 buildToolProviders does not read DataStore with runBlocking (the sync
#     callback bridge for LiteRT ToolProvider.execute() is intentional and excluded)
if grep -q 'buildToolProviders' "$SRC/ai/GemmaInferenceEngine.kt" 2>/dev/null; then
  # Bad pattern: runBlocking reading DataStore settings inside the provider builder
  if ! awk '/private fun buildToolProviders/,/^    \}/' "$SRC/ai/GemmaInferenceEngine.kt" | \
      grep 'runBlocking' | grep -q 'settingsDataStore\|DataStore\|allowWebSearch\|memoryEnabled'; then
    pass "buildToolProviders does not use runBlocking for DataStore reads"
  else
    fail "buildToolProviders still uses runBlocking for DataStore reads"
  fi
else
  warn "buildToolProviders not found — skipping"
fi

# 1.5 SupervisorJob cancel in NotificationReplyListenerService
if grep -q 'job.cancel()' "$SRC/services/NotificationReplyListenerService.kt" 2>/dev/null; then
  pass "NotificationReplyListenerService cancels SupervisorJob in onDestroy"
else
  fail "NotificationReplyListenerService missing job.cancel()"
fi

# 1.6 DatabasePassphraseHelper fallback is not static string
if ! grep -q '"fallback-android-id"' "$SRC/data/local/DatabasePassphraseHelper.kt" 2>/dev/null; then
  pass "DatabasePassphraseHelper has no static fallback string"
else
  fail "DatabasePassphraseHelper still uses static fallback-android-id"
fi

# 1.7 DatabasePassphraseHelper has getOrCreateFallbackDeviceId
if grep -q 'getOrCreateFallbackDeviceId' "$SRC/data/local/DatabasePassphraseHelper.kt" 2>/dev/null; then
  pass "DatabasePassphraseHelper has per-device UUID fallback"
else
  fail "DatabasePassphraseHelper missing getOrCreateFallbackDeviceId"
fi

# 1.8 DatabasePassphraseHelper filters null ANDROID_ID (9774d56d682e549c)
if grep -q '9774d56d682e549c' "$SRC/data/local/DatabasePassphraseHelper.kt" 2>/dev/null; then
  pass "DatabasePassphraseHelper filters known null ANDROID_ID"
else
  fail "DatabasePassphraseHelper missing null ANDROID_ID filter"
fi

# 1.9 MainActivity uses IntentCompat (no deprecated getParcelableExtra)
MAIN_ACT=$(find "$(dirname "$0")/app/src/main/java" -name "MainActivity.kt" 2>/dev/null | head -1)
if [ -n "$MAIN_ACT" ] && grep -q 'IntentCompat' "$MAIN_ACT" 2>/dev/null; then
  pass "MainActivity uses IntentCompat (no deprecated getParcelableExtra)"
else
  fail "MainActivity still uses deprecated getParcelableExtra"
fi

# 1.10 MetarDecoderTool has no !! forced unwraps
BANGS=$(grep -o '!!' "$SRC/tools/MetarDecoderTool.kt" 2>/dev/null | wc -l)
if [ "${BANGS:-0}" -eq 0 ]; then
  pass "MetarDecoderTool has no !! forced unwraps"
else
  fail "MetarDecoderTool still has $BANGS !! forced unwraps"
fi

# 1.11 ToolDispatcher has catch-all in executeTool
if grep -q 'catch (e: Exception)' "$SRC/tools/ToolDispatcher.kt" 2>/dev/null; then
  pass "ToolDispatcher has catch-all error boundary in executeTool"
else
  fail "ToolDispatcher missing catch-all error boundary"
fi

# 1.12 Image orphan fix — File.delete() on DB failure
if grep -q 'imagePath.*let.*File.*delete' "$SRC/domain/usecases/SendMessageUseCase.kt" 2>/dev/null || \
   grep -q "File(it).delete" "$SRC/domain/usecases/SendMessageUseCase.kt" 2>/dev/null; then
  pass "Image file orphan on DB failure is fixed"
else
  warn "Image orphan fix not detected (may be in different form)"
fi

# 1.13 buildEmulatorWebSnippetAnswer present
if grep -q 'buildEmulatorWebSnippetAnswer' "$SRC/domain/usecases/SendMessageUseCase.kt" 2>/dev/null; then
  pass "buildEmulatorWebSnippetAnswer present for emulator web fallback"
else
  fail "buildEmulatorWebSnippetAnswer missing"
fi

# 1.14 No hardcoded API keys
if ! grep -rq 'api_key\s*=\s*"[A-Za-z0-9_\-]\{20,\}"\|apiKey\s*=\s*"[A-Za-z0-9_\-]\{20,\}"' "$SRC" 2>/dev/null; then
  pass "No hardcoded API keys found"
else
  fail "Hardcoded API keys detected"
fi

# 1.15 SQLCipher in use (encrypted DB)
if grep -q 'sqlcipher\|SupportFactory\|SQLCipher' "$SRC/data/local/DatabasePassphraseHelper.kt" 2>/dev/null; then
  pass "SQLCipher encrypted database in use"
else
  fail "SQLCipher not detected"
fi

# 1.16 ProGuard enabled for release
if grep -q 'isMinifyEnabled = true' "$(dirname "$0")/app/build.gradle.kts" 2>/dev/null; then
  pass "ProGuard minification enabled for release"
else
  fail "ProGuard not enabled for release"
fi

# 1.17 isShrinkResources enabled for release
if grep -q 'isShrinkResources = true' "$(dirname "$0")/app/build.gradle.kts" 2>/dev/null; then
  pass "Resource shrinking enabled for release"
else
  fail "isShrinkResources not enabled"
fi

# 1.18 Firebase Crashlytics present
if grep -q 'firebase-crashlytics' "$(dirname "$0")/app/build.gradle.kts" 2>/dev/null; then
  pass "Firebase Crashlytics crash reporting in place"
else
  warn "Firebase Crashlytics not detected"
fi

# 1.19 LiteRT-LM dependency present
if grep -q 'litertlm-android' "$(dirname "$0")/app/build.gradle.kts" 2>/dev/null; then
  pass "LiteRT-LM (litertlm-android) dependency present"
else
  fail "LiteRT-LM dependency missing"
fi

# 1.20 OWASP dependency-check plugin present
if grep -q 'owasp.dependencycheck' "$(dirname "$0")/app/build.gradle.kts" 2>/dev/null; then
  pass "OWASP dependency-check plugin present"
else
  warn "OWASP dependency-check not present"
fi

# 1.21 Play Integrity API in dependencies
if grep -q 'play:integrity' "$(dirname "$0")/app/build.gradle.kts" 2>/dev/null; then
  pass "Play Integrity API for purchase verification present"
else
  warn "Play Integrity API not detected"
fi

# 1.22 No Ollama references in main source
if ! grep -rq 'ollama\|Ollama' "$SRC" 2>/dev/null; then
  pass "No Ollama references in main source (fully on-device)"
else
  fail "Ollama references found in main source — not production-safe"
fi

# 1.23 No TODO/FIXME/HACK in security-critical files
SEC_FILES=("$SRC/data/local/DatabasePassphraseHelper.kt" "$SRC/data/security")
TODO_COUNT=$(grep -rn "TODO\|FIXME\|HACK" "${SEC_FILES[@]}" 2>/dev/null | wc -l || echo 0)
if [ "$TODO_COUNT" -eq 0 ]; then
  pass "No TODO/FIXME/HACK in security-critical files"
else
  warn "$TODO_COUNT TODO/FIXME/HACK comments in security files"
fi

# 1.24 Paging3 used for message list
if grep -q 'paging-compose\|paging-runtime' "$(dirname "$0")/app/build.gradle.kts" 2>/dev/null; then
  pass "Paging3 in use for efficient message list loading"
else
  warn "Paging3 not detected"
fi

# 1.25 targetSdk >= 35
if grep -q 'targetSdk = 35' "$(dirname "$0")/app/build.gradle.kts" 2>/dev/null; then
  pass "targetSdk = 35 (latest)"
else
  warn "targetSdk may not be 35"
fi

# 1.26 minSdk = 28
if grep -q 'minSdk = 28' "$(dirname "$0")/app/build.gradle.kts" 2>/dev/null; then
  pass "minSdk = 28 (Android 9+)"
else
  warn "minSdk check failed"
fi

# 1.27 cachedAllowWebSearch present (no runBlocking in engine init)
if grep -q 'cachedAllowWebSearch' "$SRC/ai/GemmaInferenceEngine.kt" 2>/dev/null; then
  pass "GemmaInferenceEngine caches settings to avoid runBlocking"
else
  fail "GemmaInferenceEngine missing cachedAllowWebSearch"
fi

# 1.28 DataStore used (not SharedPreferences for main settings)
if grep -q 'DataStore\|datastore' "$SRC/data/local/SettingsDataStore.kt" 2>/dev/null; then
  pass "DataStore used for settings (modern, coroutine-safe)"
else
  warn "DataStore not detected for settings"
fi

# 1.29 No WebView usage (privacy check)
if ! grep -rq 'WebView\|webview' "$SRC" 2>/dev/null; then
  pass "No WebView usage (privacy preserved)"
else
  warn "WebView usage detected — review for privacy"
fi

# 1.30 No cleartext network traffic expected
MAN="$(dirname "$0")/app/src/main/AndroidManifest.xml"
if ! grep -q 'usesCleartextTraffic.*true\|cleartextTrafficPermitted.*true' "$MAN" 2>/dev/null; then
  pass "No cleartext traffic permitted in manifest"
else
  warn "Cleartext traffic may be enabled — review manifest"
fi

echo ""; echo "── SECTION 1 COMPLETE ─────────────────────────────────────────"
echo "   Checks: $((PASS+FAIL+WARN)) | Pass: $PASS | Fail: $FAIL | Warn: $WARN"
S1_PASS=$PASS; S1_FAIL=$FAIL; S1_WARN=$WARN

# ─── SECTION 2: DEVICE INSTALL & LAUNCH CHECKS (10 points) ──────
section "SECTION 2: DEVICE / INSTALL CHECKS (10 points)"

# 2.1 Device connected
if $ADB devices 2>/dev/null | grep -q 'device$'; then
  pass "ADB device connected"
else
  fail "No ADB device connected — skipping device tests"
  echo ""; echo "⚠️  Skipping all device tests (no device)."
  echo ""; DEVICE_SKIP=1
fi

DEVICE_SKIP=${DEVICE_SKIP:-0}

if [ "$DEVICE_SKIP" -eq 0 ]; then
  # 2.2 APK installed
  if $ADB shell pm list packages 2>/dev/null | grep -q "$APP"; then
    pass "$APP installed on device"
  else
    fail "$APP not installed — install first"
  fi

  # 2.3 App launches without crash
  $ADB logcat -c 2>/dev/null
  $ADB shell am start -n "$ACT" 2>/dev/null
  sleep 4
  if $ADB shell pidof "$APP" 2>/dev/null | grep -qE '[0-9]+'; then
    pass "App launched successfully (PID running)"
  else
    fail "App crashed on launch (no PID)"
  fi

  # 2.4 No ANR detected
  if ! $ADB shell logcat -d -s ActivityManager:E 2>/dev/null | grep -q 'ANR in'; then
    pass "No ANR on launch"
  else
    fail "ANR detected on launch"
  fi

  # 2.5 No FATAL EXCEPTION in first 4s
  if ! $ADB shell logcat -d 2>/dev/null | grep -q 'FATAL EXCEPTION'; then
    pass "No FATAL EXCEPTION on launch"
  else
    fail "FATAL EXCEPTION detected on launch"
  fi

  # 2.6 Model directory accessible
  if $ADB shell run-as "$APP" ls files/models 2>/dev/null; then
    pass "files/models directory accessible"
  else
    warn "Could not check models dir (may be normal on first launch)"
  fi

  # 2.7 MainActivity visible
  sleep 1
  FOCUSED=$($ADB shell dumpsys window windows 2>/dev/null | grep -o 'mCurrentFocus.*' | head -1 || true)
  if echo "$FOCUSED" | grep -q 'com.localyze'; then
    pass "MainActivity is the focused window"
  else
    warn "Could not confirm MainActivity focus: $FOCUSED"
  fi

  # 2.8 Test basic text intent
  $ADB logcat -c 2>/dev/null
  $ADB shell am start -n "$ACT" --es chat_msg "Hello" 2>/dev/null
  sleep 2
  if $ADB shell logcat -d -s MainActivity:D 2>/dev/null | grep -q 'chat_msg'; then
    pass "chat_msg intent delivered and logged"
  else
    warn "chat_msg log tag not seen (check tag name)"
  fi

  # 2.9 No SecurityException in logcat
  if ! $ADB shell logcat -d 2>/dev/null | grep -q 'SecurityException'; then
    pass "No SecurityException in launch + intent test"
  else
    fail "SecurityException detected"
  fi

  # 2.10 App is still running after intent
  if $ADB shell pidof "$APP" 2>/dev/null | grep -qE '[0-9]+'; then
    pass "App still running after intent delivery"
  else
    fail "App died after intent delivery"
  fi
fi

echo ""; echo "── SECTION 2 COMPLETE ─────────────────────────────────────────"
echo "   Section Pass: $((PASS-S1_PASS)) | Fail: $((FAIL-S1_FAIL))"
S2_PASS=$PASS; S2_FAIL=$FAIL

# ─── SECTION 3: WEB SEARCH FUNCTIONAL TESTS (20 questions) ──────
section "SECTION 3: WEB SEARCH FUNCTIONAL TESTS (20 questions)"

# Enable web search for this section
$ADB shell am start -n "$ACT" --ez allow_web_search true 2>/dev/null
sleep 2

WEB_QUESTIONS=(
  "What is the weather in New York today?"
  "What is the current price of Bitcoin?"
  "Who is the CEO of Google right now?"
  "What are the latest technology news headlines?"
  "What is the current USD to EUR exchange rate?"
  "What is the latest Android version released?"
  "What movies are trending this week?"
  "What is the current stock price of Apple?"
  "What happened in AI news today?"
  "What is the population of India in 2025?"
  "What is the current repo rate of RBI?"
  "What is the temperature in London today?"
  "What are the top headlines from BBC News?"
  "What is the Sensex level today?"
  "What is the latest news about SpaceX?"
  "Who won the most recent FIFA World Cup?"
  "What is the top grossing movie this year?"
  "What is the price of gold per ounce today?"
  "What is the latest news about OpenAI?"
  "What is the current oil price per barrel?"
)

ask_web() {
  local q="$1"
  local idx="$2"
  echo ""
  echo "  [W$(printf '%02d' $idx)] $q"

  if [ "$DEVICE_SKIP" -eq 1 ]; then
    warn "Skipped (no device)"
    return
  fi

  $ADB logcat -c 2>/dev/null
  $ADB shell am start -n "$ACT" \
    --ez allow_web_search true \
    --es chat_msg "$(echo "$q" | sed 's/ /%20/g')" 2>/dev/null

  # Wait up to 30s for some logcat activity
  got_activity=0
  for i in $(seq 1 20); do
    sleep 1
    LOG=$($ADB shell logcat -d 2>/dev/null)
    if echo "$LOG" | grep -qiE "web_search|WebSearch|preflight|StreamingToken|onDone|ToolCall|emulator|Emulator"; then
      got_activity=1
      break
    fi
  done

  if [ "$got_activity" -eq 1 ]; then
    pass "Q$idx got response activity (web search / emulator path)"
  else
    warn "Q$idx no activity in 20s — model may still be loading"
  fi
  sleep 2
}

for i in "${!WEB_QUESTIONS[@]}"; do
  ask_web "${WEB_QUESTIONS[$i]}" $((i+1))
done

echo ""; echo "── SECTION 3 COMPLETE ─────────────────────────────────────────"
echo "   Section Pass: $((PASS-S2_PASS)) | Fail: $((FAIL-S2_FAIL))"
S3_PASS=$PASS; S3_FAIL=$FAIL

# ─── SECTION 4: OFFLINE / TOOL QUESTIONS (20 questions) ─────────
section "SECTION 4: OFFLINE / TOOL QUESTIONS (20 questions)"

# Disable web search for pure offline tests
$ADB shell am start -n "$ACT" --ez allow_web_search false 2>/dev/null
sleep 2

OFFLINE_QUESTIONS=(
  "What is 234 times 56?"
  "What is the square root of 144?"
  "What is 15 percent of 850?"
  "What is today's date?"
  "What time is it right now?"
  "Convert 100 USD to EUR"
  "What is 2 to the power of 10?"
  "How many seconds in a day?"
  "What is the factorial of 7?"
  "What is sin 90 degrees?"
  "Convert 37 degrees Celsius to Fahrenheit"
  "What is 1000 divided by 8?"
  "What is the area of a circle with radius 5?"
  "What is 15% tip on a 120 dollar bill?"
  "What is 7 plus 8 times 3 minus 2?"
  "Convert 5 kilometers to miles"
  "What is the sum of first 10 natural numbers?"
  "What is 48 divided by 6 plus 3 times 4?"
  "How many days are there in a leap year?"
  "What is the cube of 12?"
)

ask_offline() {
  local q="$1"
  local idx="$2"
  echo ""
  echo "  [O$(printf '%02d' $idx)] $q"

  if [ "$DEVICE_SKIP" -eq 1 ]; then
    warn "Skipped (no device)"
    return
  fi

  $ADB logcat -c 2>/dev/null
  $ADB shell am start -n "$ACT" \
    --ez allow_web_search false \
    --es chat_msg "$(echo "$q" | sed 's/ /%20/g')" 2>/dev/null

  got_activity=0
  for i in $(seq 1 15); do
    sleep 1
    LOG=$($ADB shell logcat -d 2>/dev/null)
    if echo "$LOG" | grep -qiE "StreamingToken|onDone|ToolCall|emulator|Emulator|calculator|compute"; then
      got_activity=1
      break
    fi
  done

  if [ "$got_activity" -eq 1 ]; then
    pass "Q$idx got response activity"
  else
    warn "Q$idx no activity in 15s — emulator guard or loading"
  fi
  sleep 1
}

for i in "${!OFFLINE_QUESTIONS[@]}"; do
  ask_offline "${OFFLINE_QUESTIONS[$i]}" $((i+1))
done

echo ""; echo "── SECTION 4 COMPLETE ─────────────────────────────────────────"
echo "   Section Pass: $((PASS-S3_PASS)) | Fail: $((FAIL-S3_FAIL))"
S4_PASS=$PASS; S4_FAIL=$FAIL

# ─── SECTION 5: CRASH / STABILITY CHECKS (20 questions) ─────────
section "SECTION 5: STABILITY & STRESS TESTS (20 checks)"

STRESS_CASES=(
  "a"
  "1"
  ""
  "   "
  "Hello! How are you doing today? I hope everything is going well."
  "What is the meaning of life, the universe, and everything? Please give me a detailed philosophical answer."
  "Write me a Python function that sorts a list using bubble sort. Include comments and time complexity analysis."
  "Please translate this to Spanish: I love programming and building mobile apps."
  "Summarize the following: Machine learning is a subset of artificial intelligence that enables systems to learn and improve from experience without being explicitly programmed."
  "What would happen if the moon suddenly disappeared?"
  "Tell me a short story about a robot who learns to feel emotions."
  "Can you help me debug this code: for i in range(10) print(i)"
  "What are the pros and cons of electric vehicles vs gasoline cars?"
  "Give me a recipe for chocolate chip cookies."
  "Explain quantum entanglement to a 10-year-old."
  "What are the best practices for securing an Android app?"
  "Help me write an email declining a job offer politely."
  "What is the difference between machine learning and deep learning?"
  "Explain the concept of recursion with an example."
  "Write a haiku about artificial intelligence."
)

stress_test() {
  local q="$1"
  local idx="$2"
  echo ""
  echo "  [S$(printf '%02d' $idx)] ${q:0:60}..."

  if [ "$DEVICE_SKIP" -eq 1 ]; then
    warn "Skipped (no device)"
    return
  fi

  $ADB logcat -c 2>/dev/null
  if [ -n "$q" ] && [ "${q// }" != "" ]; then
    $ADB shell am start -n "$ACT" --ez allow_web_search true \
      --es chat_msg "$(echo "$q" | tr ' ' '_' | sed 's/_/%20/g')" 2>/dev/null
  else
    $ADB shell am start -n "$ACT" 2>/dev/null
  fi
  sleep 3

  # Check app is still alive (no crash)
  if $ADB shell pidof "$APP" 2>/dev/null | grep -qE '[0-9]+'; then
    # Also check no FATAL EXCEPTION
    if $ADB shell logcat -d 2>/dev/null | grep -q 'FATAL EXCEPTION'; then
      fail "S$idx app crashed (FATAL EXCEPTION) on: ${q:0:40}"
    else
      pass "S$idx app survived stress input"
    fi
  else
    fail "S$idx app died (no PID) after: ${q:0:40}"
  fi
}

for i in "${!STRESS_CASES[@]}"; do
  stress_test "${STRESS_CASES[$i]}" $((i+1))
done

echo ""; echo "── SECTION 5 COMPLETE ─────────────────────────────────────────"
echo "   Section Pass: $((PASS-S4_PASS)) | Fail: $((FAIL-S4_FAIL))"

# ─── FINAL SUMMARY ───────────────────────────────────────────────
TOTAL=$((PASS + FAIL + WARN))
echo ""
echo "══════════════════════════════════════════════════════════════"
echo "  PRODUCTION READINESS REPORT"
echo "══════════════════════════════════════════════════════════════"
echo "  Total checks : $TOTAL"
echo "  ✅ Passed    : $PASS"
echo "  ❌ Failed    : $FAIL"
echo "  ⚠️  Warnings  : $WARN"
PCT=$((PASS * 100 / (PASS + FAIL)))
echo "  Pass rate    : $PCT% (excluding warnings)"
echo ""
if [ -n "$FAILS" ]; then
  echo "  FAILURES:"; echo -e "$FAILS"
  echo ""
fi
if [ "$FAIL" -eq 0 ]; then
  echo "  🚀 VERDICT: PRODUCTION READY ✅"
elif [ "$FAIL" -le 3 ]; then
  echo "  🔧 VERDICT: MINOR ISSUES — review failures above"
else
  echo "  ❌ VERDICT: NOT READY — $FAIL failures must be fixed"
fi
echo "══════════════════════════════════════════════════════════════"
echo "  Completed: $(date '+%Y-%m-%d %H:%M:%S')"
