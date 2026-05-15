#!/usr/bin/env bash
# Start a Playwright-cached Chromium with the existing play-console-profile.
# If Chrome is already on :9222 (because you started it from the stock market
# project), this exits silently and the request_upload_key_reset.py will
# attach to that one.
set -euo pipefail

CHROME="${HOME}/.cache/ms-playwright/chromium-1217/chrome-linux64/chrome"
PROFILE="${HOME}/.cache/play-console-profile"
PORT=9222

if [ ! -x "${CHROME}" ]; then
  echo "Chromium binary not found at: ${CHROME}"
  exit 1
fi

mkdir -p "${PROFILE}"

if ss -ltn 2>/dev/null | grep -q ":${PORT} "; then
  echo "Chrome already listening on :${PORT} — reusing it."
  exit 0
fi

echo "Launching Chromium with CDP on :${PORT}"
"${CHROME}" \
  --remote-debugging-port="${PORT}" \
  --user-data-dir="${PROFILE}" \
  --no-first-run \
  --no-default-browser-check \
  https://play.google.com/console \
  >/tmp/play-console-chrome.log 2>&1 &

echo "PID: $!"
echo
echo "Next:"
echo "  1. If prompted, sign into the Google account that owns Localyze."
echo "  2. Navigate to the Localyze app -> Setup -> App integrity -> App signing."
echo "  3. Run: python3 scripts/request_upload_key_reset.py"
