#!/usr/bin/env bash
# After /tmp/localyze_model/gemma-4-E4B-it.litertlm finishes downloading,
# push it to the emulator's app data dir, force-stop the app, relaunch,
# and verify the chat surface is reachable.
set -euo pipefail
DEVICE="emulator-5554"
PKG="com.localyze"
SRC=/tmp/localyze_model/gemma-4-E4B-it.litertlm
DEST=files/models/gemma-4-E4B-it.litertlm

size=$(stat -c %s "$SRC")
echo "[1/6] Source size: $(numfmt --to=iec $size)"

echo "[2/6] Force-stopping app + clearing partial download state"
adb -s "$DEVICE" shell am force-stop "$PKG"
adb -s "$DEVICE" shell run-as "$PKG" rm -f "files/models/model_download.tmp" 2>/dev/null || true
adb -s "$DEVICE" shell run-as "$PKG" mkdir -p files/models 2>/dev/null || true

echo "[3/6] Pushing model into app data dir (run-as cat write)"
# Stream file via adb shell run-as because root push to app's private dir is blocked.
cat "$SRC" | adb -s "$DEVICE" shell "run-as $PKG sh -c 'cat > $DEST'"
pushed=$(adb -s "$DEVICE" shell run-as "$PKG" stat -c %s "$DEST")
echo "      pushed: $pushed bytes (expected $size)"
[ "$pushed" = "$size" ] || { echo "SIZE MISMATCH"; exit 1; }

echo "[4/6] Launching app"
adb -s "$DEVICE" shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 6

echo "[5/6] Inspecting visible UI"
adb -s "$DEVICE" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb -s "$DEVICE" shell cat /sdcard/ui.xml | grep -oE 'text="[^"]+"' | grep -v '^text=""$' | head -20

echo "[6/6] Done. Hand off to test scripts."
