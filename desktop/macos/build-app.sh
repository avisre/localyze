#!/usr/bin/env bash
# ============================================================================
#  Localyze.ai — macOS build / sign / notarise / DMG pipeline
#
#  Run this on an Apple-Silicon Mac with Xcode 15+ and Swift 5.10+ installed.
#  We have no Mac in CI yet, so this script is the canonical source of truth
#  for how to produce a shippable Localyze.app on an actual machine.
#
#  ----------------------------------------------------------------------------
#  Required tooling
#  ----------------------------------------------------------------------------
#    - Xcode 15+ (for `xcrun notarytool`, codesign, hdiutil)
#    - Swift 5.10+ (toolchain that matches Package.swift's swift-tools-version)
#    - Optional: `create-dmg` (Homebrew: `brew install create-dmg`) for a
#      prettier installer; we fall back to `hdiutil` when it's missing.
#
#  ----------------------------------------------------------------------------
#  Environment variables
#  ----------------------------------------------------------------------------
#  Code-signing (optional — script will skip signing if these are unset):
#    APPLE_DEVELOPER_ID          e.g. "Developer ID Application: Localyze Inc. (ABCDE12345)"
#                                    Pass this exactly as `security find-identity -v` lists it.
#
#  Notarisation (optional — script will skip notarising if these are unset):
#    APPLE_API_KEY_ID            ASCII key ID from App Store Connect → Users and Access → Keys
#    APPLE_API_KEY_ISSUER        Issuer UUID from the same page
#    APPLE_API_KEY_FILE          Absolute path to the downloaded `.p8` private key
#
#  Output:
#    BUILD_DIR (default: ./build)            — swift-build output
#    DIST_DIR  (default: ./dist)             — final .app and .dmg
#
#  ----------------------------------------------------------------------------
#  What this script does, in order
#  ----------------------------------------------------------------------------
#    1) `swift build -c release --arch arm64 --arch x86_64`  (universal binary)
#    2) Stage a .app bundle:  Contents/MacOS/Localyze
#                             Contents/Info.plist
#                             Contents/Resources/AppIcon.icns (if present)
#                             Contents/Frameworks/             (mlx-swift dylibs)
#    3) (optional) codesign --options runtime --entitlements …
#    4) (optional) xcrun notarytool submit … --wait, then `stapler staple`
#    5) Build a DMG via `create-dmg` (preferred) or `hdiutil create`.
#  ----------------------------------------------------------------------------
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${BUILD_DIR:-$ROOT_DIR/build}"
DIST_DIR="${DIST_DIR:-$ROOT_DIR/dist}"
APP_NAME="Localyze"
APP_BUNDLE="$DIST_DIR/${APP_NAME}.app"
INFO_PLIST="$ROOT_DIR/Bundle/Info.plist"
ENTITLEMENTS="$ROOT_DIR/Bundle/Localyze.entitlements"

log() { printf '\033[1;34m[build-app]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[build-app]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------- preflight ----------
[[ "$(uname -s)" == "Darwin" ]] || die "This script only runs on macOS."
command -v swift   >/dev/null || die "Need 'swift' on PATH (install Xcode CLT)."
command -v codesign >/dev/null || die "Need 'codesign' (install Xcode CLT)."
[[ -f "$INFO_PLIST"   ]] || die "Missing $INFO_PLIST"
[[ -f "$ENTITLEMENTS" ]] || die "Missing $ENTITLEMENTS"

mkdir -p "$BUILD_DIR" "$DIST_DIR"

# ---------- 1. Universal swift build ----------
log "Building universal binary (arm64 + x86_64)…"
swift build \
    -c release \
    --arch arm64 --arch x86_64 \
    --package-path "$ROOT_DIR" \
    --build-path "$BUILD_DIR"

BIN_PATH="$BUILD_DIR/apple/Products/Release/$APP_NAME"
[[ -x "$BIN_PATH" ]] || die "Binary not found at $BIN_PATH"

# Verify both slices are present.
if ! lipo -info "$BIN_PATH" | grep -q "arm64.*x86_64\|x86_64.*arm64"; then
    log "WARNING: $BIN_PATH is not a universal binary. lipo says:"
    lipo -info "$BIN_PATH"
fi

# ---------- 2. Stage the .app ----------
log "Staging $APP_BUNDLE…"
rm -rf "$APP_BUNDLE"
mkdir -p "$APP_BUNDLE/Contents/MacOS"
mkdir -p "$APP_BUNDLE/Contents/Resources"
mkdir -p "$APP_BUNDLE/Contents/Frameworks"

cp "$BIN_PATH"     "$APP_BUNDLE/Contents/MacOS/$APP_NAME"
cp "$INFO_PLIST"   "$APP_BUNDLE/Contents/Info.plist"

# Icon: prefer a pre-built .icns at Bundle/AppIcon.icns, otherwise warn.
if [[ -f "$ROOT_DIR/Bundle/AppIcon.icns" ]]; then
    cp "$ROOT_DIR/Bundle/AppIcon.icns" "$APP_BUNDLE/Contents/Resources/AppIcon.icns"
else
    log "WARNING: no Bundle/AppIcon.icns — Finder will show a generic icon."
fi

# Copy mlx-swift's Metal shader libraries into Frameworks so dyld can find
# them under hardened runtime. swift-build leaves these next to the binary.
shopt -s nullglob
for metallib in "$BUILD_DIR/apple/Products/Release/"*.metallib; do
    cp "$metallib" "$APP_BUNDLE/Contents/Resources/"
done
for dylib in "$BUILD_DIR/apple/Products/Release/"*.dylib; do
    cp "$dylib" "$APP_BUNDLE/Contents/Frameworks/"
done
shopt -u nullglob

# ---------- 3. Codesign (optional) ----------
if [[ -n "${APPLE_DEVELOPER_ID:-}" ]]; then
    log "Codesigning with: $APPLE_DEVELOPER_ID"

    # Sign nested frameworks / dylibs first (inside-out).
    find "$APP_BUNDLE/Contents/Frameworks" -type f \( -name '*.dylib' -o -name '*.framework' \) \
        -exec codesign --force --timestamp --options runtime \
                       --sign "$APPLE_DEVELOPER_ID" {} \;

    # Sign the main executable + bundle with entitlements.
    codesign --force --deep --timestamp \
             --options runtime \
             --entitlements "$ENTITLEMENTS" \
             --sign "$APPLE_DEVELOPER_ID" \
             "$APP_BUNDLE"

    log "Verifying signature…"
    codesign --verify --deep --strict --verbose=2 "$APP_BUNDLE"
    spctl --assess --type execute --verbose=4 "$APP_BUNDLE" || \
        log "spctl note: Gatekeeper will still warn until notarisation completes."
else
    log "APPLE_DEVELOPER_ID not set — skipping codesign."
    log "  The resulting .app will need 'xattr -dr com.apple.quarantine' on first launch."
fi

# ---------- 4. Notarise (optional) ----------
if [[ -n "${APPLE_API_KEY_ID:-}" && -n "${APPLE_API_KEY_ISSUER:-}" && -n "${APPLE_API_KEY_FILE:-}" ]]; then
    log "Notarising $APP_BUNDLE …"
    ZIP_PATH="$DIST_DIR/${APP_NAME}-notarise.zip"
    ditto -c -k --keepParent "$APP_BUNDLE" "$ZIP_PATH"

    xcrun notarytool submit "$ZIP_PATH" \
        --key       "$APPLE_API_KEY_FILE" \
        --key-id    "$APPLE_API_KEY_ID" \
        --issuer    "$APPLE_API_KEY_ISSUER" \
        --wait

    log "Stapling notarisation ticket…"
    xcrun stapler staple "$APP_BUNDLE"
    xcrun stapler validate "$APP_BUNDLE"
    rm -f "$ZIP_PATH"
else
    log "APPLE_API_KEY_ID / _ISSUER / _FILE not all set — skipping notarisation."
fi

# ---------- 5. DMG ----------
DMG_PATH="$DIST_DIR/${APP_NAME}-1.0.dmg"
rm -f "$DMG_PATH"

if command -v create-dmg >/dev/null 2>&1; then
    log "Building DMG via create-dmg…"
    create-dmg \
        --volname "Localyze.ai" \
        --window-pos 200 120 --window-size 600 400 \
        --icon-size 96 \
        --icon "${APP_NAME}.app" 150 200 \
        --hide-extension "${APP_NAME}.app" \
        --app-drop-link 450 200 \
        --no-internet-enable \
        "$DMG_PATH" "$APP_BUNDLE"
else
    log "create-dmg missing; falling back to hdiutil."
    hdiutil create -volname "Localyze.ai" \
                   -srcfolder "$APP_BUNDLE" \
                   -ov -format UDZO \
                   "$DMG_PATH"
fi

# If we have signing creds, sign the DMG too (Gatekeeper checks both).
if [[ -n "${APPLE_DEVELOPER_ID:-}" ]]; then
    codesign --force --timestamp --sign "$APPLE_DEVELOPER_ID" "$DMG_PATH"
fi

# If we have notary creds, staple the DMG so offline first-launch works.
if [[ -n "${APPLE_API_KEY_ID:-}" && -n "${APPLE_API_KEY_FILE:-}" ]]; then
    xcrun notarytool submit "$DMG_PATH" \
        --key       "$APPLE_API_KEY_FILE" \
        --key-id    "$APPLE_API_KEY_ID" \
        --issuer    "$APPLE_API_KEY_ISSUER" \
        --wait
    xcrun stapler staple "$DMG_PATH"
fi

log "Done."
log "  App: $APP_BUNDLE"
log "  DMG: $DMG_PATH"
