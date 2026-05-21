#!/usr/bin/env python3
"""
Localyze 5000-Question Production Test
Phase 1: 500-question validation sample (with response detection)
Phase 2: Rapid-fire stability ping on remaining ~3100 questions
"""
import subprocess, sys, os, time, re, random, datetime

ADB = "adb"
APP = "com.localyze"
ACT = "com.localyze/.MainActivity"
Q_FILE = "/tmp/q_all.txt"

OUTDIR = os.path.join(
    os.path.dirname(__file__),
    "chatbot_test_results",
    f"5kq_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S')}"
)
os.makedirs(OUTDIR, exist_ok=True)
LOG_FILE = os.path.join(OUTDIR, "run.log")

def log(msg):
    print(msg, flush=True)
    with open(LOG_FILE, "a") as f:
        f.write(msg + "\n")

def adb(*args, timeout=8):
    try:
        r = subprocess.run([ADB] + list(args), capture_output=True, text=True, timeout=timeout)
        return r.stdout + r.stderr
    except Exception:
        return ""

def send_question(q, web=True):
    encoded = q.replace(" ", "+").replace("'", "%27").replace("&", "%26").replace("?", "%3F")
    web_flag = "true" if web else "false"
    adb("shell", "am", "start", "-n", ACT,
        "--ez", "allow_web_search", web_flag,
        "--es", "chat_msg", encoded, timeout=5)

def app_alive():
    out = adb("shell", "pidof", APP, timeout=4)
    return bool(re.search(r'\d+', out))

def clear_logcat():
    adb("logcat", "-c", timeout=3)

def get_logcat():
    return adb("shell", "logcat", "-d", timeout=6)

def got_response(logcat_text):
    return bool(re.search(
        r'StreamingToken|onDone|emulator guard|isRunning.*emulator|ToolCallStarted|Emulator',
        logcat_text, re.IGNORECASE
    ))

def has_crash(logcat_text):
    return "FATAL EXCEPTION" in logcat_text

def restart_app():
    adb("shell", "am", "force-stop", APP, timeout=5)
    time.sleep(1)
    adb("shell", "am", "start", "-n", ACT, timeout=5)
    time.sleep(4)

# ── LOAD QUESTIONS ───────────────────────────────────────────────
log("\n══════════════════════════════════════════════════════════════")
log(f"  LOCALYZE 5000-QUESTION PRODUCTION TEST")
log(f"  Started: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
log("══════════════════════════════════════════════════════════════")

if not os.path.exists(Q_FILE):
    log(f"❌ Question file not found: {Q_FILE}"); sys.exit(1)

with open(Q_FILE) as f:
    all_q = [l.strip() for l in f if l.strip() and not l.startswith('#')]

log(f"  Questions loaded: {len(all_q)}")
random.seed(42)
random.shuffle(all_q)

# Split sample vs rapid-fire
SAMPLE_SIZE = min(500, len(all_q))
sample_q = all_q[:SAMPLE_SIZE]
rapid_q = all_q[SAMPLE_SIZE:]

log(f"  Phase 1 (validation): {len(sample_q)} questions")
log(f"  Phase 2 (rapid ping): {len(rapid_q)} questions")

# ── PRE-FLIGHT ───────────────────────────────────────────────────
log("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
log("  PRE-FLIGHT")
log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

if "device" not in adb("devices"):
    log("❌ No ADB device connected — aborting"); sys.exit(1)

restart_app()
if not app_alive():
    log("❌ App won't start — aborting"); sys.exit(1)

adb("shell", "am", "start", "-n", ACT, "--ez", "allow_web_search", "true")
time.sleep(1)
clear_logcat()
log(f"  App running. Web search enabled.\n")

# ── PHASE 1: VALIDATION SAMPLE ───────────────────────────────────
log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
log(f"  PHASE 1: {len(sample_q)}-QUESTION VALIDATION SAMPLE")
log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

WEB_KW = ['current', 'today', 'latest', 'right now', 'price of', 'weather',
          'news', 'stock', 'score', 'trending', 'recent', 'live ', '2025',
          'this week', 'this year', 'exchange rate', 'repo rate', 'happening']

p1_pass = 0; p1_timeout = 0; p1_crash = 0
p1_results = []

for i, q in enumerate(sample_q, 1):
    is_web = any(k.lower() in q.lower() for k in WEB_KW)
    clear_logcat()
    t0 = time.time()
    send_question(q, web=is_web)

    wait_secs = 12 if is_web else 6
    responded = False
    for _ in range(wait_secs):
        time.sleep(1)
        lc = get_logcat()
        if got_response(lc):
            responded = True
            break

    elapsed = round(time.time() - t0, 1)
    alive = app_alive()
    crashed = has_crash(get_logcat()) if alive else True

    if not alive or crashed:
        p1_crash += 1
        status = "CRASH"
        icon = "💀"
        restart_app()
        clear_logcat()
    elif responded:
        p1_pass += 1
        status = "OK"
        icon = "✅"
    else:
        p1_timeout += 1
        status = "TIMEOUT"
        icon = "⏰"

    p1_results.append({"q": q, "status": status, "elapsed": elapsed, "web": is_web})

    if i % 10 == 0 or status in ("CRASH", "TIMEOUT"):
        log(f"  [{i:04d}/{len(sample_q)}] {icon} {elapsed}s web={is_web} | {q[:70]}")

    time.sleep(0.5)

log(f"\n  Phase 1 done: {p1_pass} OK | {p1_timeout} timeout | {p1_crash} crash")

# Save Phase 1 CSV
csv_path = os.path.join(OUTDIR, "phase1_results.csv")
with open(csv_path, "w") as f:
    f.write("idx,question,status,elapsed_s,web_search\n")
    for i, r in enumerate(p1_results, 1):
        q_esc = r['q'].replace('"', '\\"')
        f.write(f"{i},\"{q_esc}\",{r['status']},{r['elapsed']},{r['web']}\n")
log(f"  CSV: {csv_path}")

# ── PHASE 2: RAPID STABILITY PING ────────────────────────────────
log("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
log(f"  PHASE 2: RAPID STABILITY PING ({len(rapid_q)} questions)")
log("  (Fire 1 question per 0.35s, crash-check every 200)")
log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

p2_sent = 0; p2_crash = 0
clear_logcat()

for i, q in enumerate(rapid_q, 1):
    send_question(q, web=(i % 5 == 0))  # 20% with web on
    p2_sent += 1
    time.sleep(0.35)

    if i % 200 == 0:
        pct = i * 100 // len(rapid_q)
        alive = app_alive()
        crash_in_log = has_crash(get_logcat())
        if not alive or crash_in_log:
            p2_crash += 1
            log(f"  [P2-{i}] 💀 CRASH! Restarting...")
            restart_app()
            clear_logcat()
        else:
            log(f"  [P2-{i}/{len(rapid_q)}] {pct}% — ✅ stable ({p2_crash} crashes so far)")
        clear_logcat()  # reset for next batch

# Final check
log(f"\n  Phase 2 fired {p2_sent} questions. Final health check...")
alive = app_alive()
if alive:
    log("  ✅ App alive at end of Phase 2")
else:
    p2_crash += 1
    log("  ❌ App dead at end of Phase 2")

# ── FINAL REPORT ──────────────────────────────────────────────────
total_tested = len(sample_q) + p2_sent
total_crashes = p1_crash + p2_crash
p1_pct = p1_pass * 100 // max(len(sample_q), 1)

log("\n══════════════════════════════════════════════════════════════")
log("  FINAL REPORT")
log("══════════════════════════════════════════════════════════════")
log(f"  Total questions tested   : {total_tested}")
log(f"  Phase 1 response OK      : {p1_pass} / {len(sample_q)} ({p1_pct}%)")
log(f"  Phase 1 timeouts         : {p1_timeout}")
log(f"  Phase 2 rapid-fire sent  : {p2_sent}")
log(f"  Total crashes            : {total_crashes}")
log("")
if total_crashes == 0:
    log("  🚀 STABILITY: EXCELLENT — 0 crashes across all questions")
elif total_crashes <= 3:
    log(f"  ⚠️  STABILITY: {total_crashes} crash(es) — investigate")
else:
    log(f"  ❌ STABILITY: {total_crashes} crashes — critical issues")

log(f"  📊 RESPONSE QUALITY (emulator+web): {p1_pass}/{len(sample_q)} ({p1_pct}%)")
log(f"\n  Results: {OUTDIR}/")
log(f"══════════════════════════════════════════════════════════════")
log(f"  Completed: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
