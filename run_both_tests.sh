#!/bin/bash
# Chain Malayalam-50 then India-100, log to a combined file.
cd "$(dirname "$0")"
set -e

log() { echo "[$(date +%H:%M:%S)] $1" | tee -a both_tests.log; }

rm -f both_tests.log

log "=========================================="
log "PHASE 1/2: Malayalam-50"
log "=========================================="
rm -f malayalam50.log malayalam50_results.jsonl malayalam50_results.json
python3 run_malayalam50.py 2>&1 | tee -a both_tests.log

log "=== Malayalam-50 phase done — re-rolling failures ==="
# Convert jsonl to json so resume script can find prior results
python3 -c "
import json
recs = []
with open('malayalam50_results.jsonl') as f:
    for line in f: recs.append(json.loads(line))
json.dump(recs, open('malayalam50_results.json','w'), ensure_ascii=False, indent=2)
" 2>&1 | tee -a both_tests.log
python3 resume_malayalam50.py 2>&1 | tee -a both_tests.log
log "Malayalam-50 final scoring:"
python3 grade_malayalam50.py 2>&1 | head -15 | tee -a both_tests.log

log "=========================================="
log "PHASE 2/2: India-100"
log "=========================================="
rm -f india100.log india100_results.jsonl india100_results.json
python3 run_india100.py 2>&1 | tee -a both_tests.log
log "India-100 final scoring:"
python3 grade_india100.py 2>&1 | head -25 | tee -a both_tests.log

log "=========================================="
log "ALL TESTS COMPLETE"
log "=========================================="
