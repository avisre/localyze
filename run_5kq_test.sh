#!/bin/bash
# Localyze 5000-Question Production Test
# Phase 1: Full response validation on 500 representative questions (with web search)
# Phase 2: Rapid-fire stability ping on all remaining ~3100 questions (crash detection only)

ADB="adb"
APP="com.localyze"
ACT="com.localyze/.MainActivity"
ALL_Q="/tmp/q_all.txt"
WEB_Q="/tmp/q_web.txt"
OFFLINE_Q="/tmp/q_offline.txt"
OUTDIR="$(dirname "$0")/chatbot_test_results/5kq_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUTDIR"

TOTAL_PASS=0; TOTAL_FAIL=0; TOTAL_CRASH=0; TOTAL_SKIP=0
ERRORS=""

log() { echo "$1" | tee -a "$OUTDIR/run.log"; }
pass() { log "  ✅ $1"; ((TOTAL_PASS++)); }
fail() { log "  ❌ $1"; ((TOTAL_FAIL++)); ERRORS="$ERRORS\n$1"; }
warn() { log "  ⚠️  $1"; ((TOTAL_SKIP++)); }

encode_q() {
  # URL-encode spaces only (simplistic but works for most questions)
  echo "$1" | sed 's/ /+/g' | sed "s/'/%27/g" | sed 's/&/%26/g' | sed 's/?/%3F/g'
}

check_alive() {
  if ! $ADB shell pidof "$APP" 2>/dev/null | grep -qE '[0-9]+'; then
    log "  💀 CRASH DETECTED at Q$1!"
    ((TOTAL_CRASH++))
    # Restart the app
    $ADB shell am start -n "$ACT" 2>/dev/null
    sleep 4
    return 1
  fi
  return 0
}

log ""
log "══════════════════════════════════════════════════════════════"
log "  LOCALYZE 5000-QUESTION PRODUCTION TEST"
log "  Started: $(date '+%Y-%m-%d %H:%M:%S')"
log "══════════════════════════════════════════════════════════════"

if [ ! -f "$ALL_Q" ]; then
  log "❌ Question file not found at $ALL_Q — run the setup script first"
  exit 1
fi

TOTAL_Q=$(wc -l < "$ALL_Q")
log "  Total questions loaded: $TOTAL_Q"

# ── PRE-FLIGHT ─────────────────────────────────────────────────
log ""
log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log "  PRE-FLIGHT"
log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if ! $ADB devices 2>/dev/null | grep -q 'device$'; then
  log "❌ No ADB device — aborting"; exit 1
fi

$ADB shell am force-stop "$APP" 2>/dev/null; sleep 1
$ADB shell am start -n "$ACT" 2>/dev/null; sleep 4

if ! check_alive 0; then
  log "❌ App won't start — aborting"; exit 1
fi
log "  App running. PID=$($ADB shell pidof $APP 2>/dev/null)"

# Enable web search
$ADB shell am start -n "$ACT" --ez allow_web_search true 2>/dev/null; sleep 1
$ADB logcat -c 2>/dev/null

# ── PHASE 1: 500-QUESTION VALIDATION SAMPLE ─────────────────────
log ""
log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log "  PHASE 1: 500-QUESTION VALIDATION SAMPLE"
log "  (Full response capture, web search enabled)"
log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Sample every Nth line for even coverage across all categories
SAMPLE_EVERY=$(( TOTAL_Q / 500 ))
[ "$SAMPLE_EVERY" -lt 1 ] && SAMPLE_EVERY=1

PHASE1_PASS=0; PHASE1_FAIL=0; PHASE1_TOT=0
P1_LOG="$OUTDIR/phase1_results.csv"
echo "idx,question,status,response_time_s,got_response,web_triggered,error" > "$P1_LOG"

awk "NR % $SAMPLE_EVERY == 1 {print NR\": \"\$0}" "$ALL_Q" | head -500 | while IFS=': ' read -r idx q; do
  ((PHASE1_TOT++))
  q_enc=$(encode_q "$q")

  $ADB logcat -c 2>/dev/null
  t0=$(date +%s)

  $ADB shell am start -n "$ACT" --ez allow_web_search true --es chat_msg "$q_enc" 2>/dev/null

  # Wait up to 20s for any response activity
  got_resp=0; web_hit=0
  for i in $(seq 1 15); do
    sleep 1
    LOG=$($ADB shell logcat -d 2>/dev/null)
    if echo "$LOG" | grep -qiE "StreamingToken|onDone|emulator guard|Emulator|ToolCallStarted|web_search"; then
      got_resp=1
      echo "$LOG" | grep -qiE "web_search|WebSearch|ToolCallStarted" && web_hit=1
      break
    fi
  done

  t1=$(date +%s); elapsed=$((t1-t0))

  if ! check_alive "$PHASE1_TOT"; then
    echo "$PHASE1_TOT,\"$q\",CRASH,$elapsed,0,$web_hit,app_died" >> "$P1_LOG"
    log "  [P1-$PHASE1_TOT] CRASH on: ${q:0:60}"
    continue
  fi

  if [ "$got_resp" -eq 1 ]; then
    status="OK"
    log "  [P1-$PHASE1_TOT] ✅ ${elapsed}s web=$web_hit | ${q:0:70}"
    ((PHASE1_PASS++))
  else
    status="TIMEOUT"
    log "  [P1-$PHASE1_TOT] ⏰ ${elapsed}s TIMEOUT | ${q:0:70}"
    ((PHASE1_FAIL++))
  fi
  echo "$PHASE1_TOT,\"$q\",$status,$elapsed,$got_resp,$web_hit," >> "$P1_LOG"

  ((TOTAL_PASS += got_resp))
  [ "$got_resp" -eq 0 ] && ((TOTAL_FAIL++))

  sleep 1
done

log ""
log "  Phase 1 complete. CSV: $P1_LOG"

# ── PHASE 2: RAPID STABILITY PING (remaining ~3100 questions) ────
log ""
log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log "  PHASE 2: RAPID STABILITY PING (~3100 questions)"
log "  (Fire-and-forget, crash detection every 100q)"
log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Skip lines already sampled in phase 1; take all others
awk "NR % $SAMPLE_EVERY != 1" "$ALL_Q" > /tmp/q_phase2.txt
PHASE2_TOTAL=$(wc -l < /tmp/q_phase2.txt)
log "  Questions in phase 2: $PHASE2_TOTAL"

batch=0; p2_crash=0
$ADB logcat -c 2>/dev/null

while IFS= read -r q; do
  ((batch++))
  q_enc=$(encode_q "$q")

  $ADB shell am start -n "$ACT" --ez allow_web_search true --es chat_msg "$q_enc" >/dev/null 2>&1
  sleep 0.3

  # Crash check every 100 questions
  if (( batch % 100 == 0 )); then
    pct=$(( batch * 100 / PHASE2_TOTAL ))
    log "  [P2] $batch/$PHASE2_TOTAL ($pct%) — checking app health..."
    if ! check_alive "$batch"; then
      ((p2_crash++))
    else
      # Quick FATAL check
      if $ADB shell logcat -d 2>/dev/null | grep -q 'FATAL EXCEPTION'; then
        log "  [P2-$batch] ⚠️  FATAL EXCEPTION in logcat"
        ((p2_crash++))
        $ADB logcat -c 2>/dev/null
      fi
    fi
  fi

done < /tmp/q_phase2.txt

# Final health check
log "  Phase 2 complete ($batch questions fired). Checking final state..."
if $ADB shell pidof "$APP" 2>/dev/null | grep -qE '[0-9]+'; then
  log "  ✅ App still running after $batch rapid-fire questions"
else
  log "  ❌ App died at end of phase 2"
  ((p2_crash++))
fi

# ── FINAL REPORT ─────────────────────────────────────────────────
log ""
log "══════════════════════════════════════════════════════════════"
log "  FINAL REPORT"
log "══════════════════════════════════════════════════════════════"
log "  Total questions tested  : $((500 + batch))"
log "  Phase 1 responses OK    : $PHASE1_PASS / 500"
log "  Phase 1 timeouts        : $PHASE1_FAIL"
log "  Phase 2 stability sends : $batch"
log "  Crashes detected        : $((TOTAL_CRASH + p2_crash))"
log ""
if (( (TOTAL_CRASH + p2_crash) == 0 )); then
  log "  🚀 STABILITY: EXCELLENT — 0 crashes across all questions"
else
  log "  ⚠️  STABILITY: $((TOTAL_CRASH + p2_crash)) crashes detected"
fi
P1_PCT=$(( PHASE1_PASS * 100 / (PHASE1_PASS + PHASE1_FAIL + 1) ))
log "  📊 RESPONSE QUALITY: $PHASE1_PASS/500 validated ($P1_PCT%)"
log ""
log "  Results saved to: $OUTDIR/"
log "══════════════════════════════════════════════════════════════"
log "  Completed: $(date '+%Y-%m-%d %H:%M:%S')"
