#!/usr/bin/env bash
# =============================================================================
# build-appimage.sh -- Package Localyze.ai (Linux/Qt6) into a single AppImage
# =============================================================================
#
# Produces: desktop/linux/packaging/Localyze-x86_64.AppImage
#
# What this script does, in order:
#   1. Sanity-check the compiled binary at desktop/linux/build/Localyze.
#   2. Stage an AppDir tree (FHS-ish) with the binary, llama.cpp/ggml shared
#      libraries (Vulkan + CPU + base), .desktop file, and PNG icon.
#   3. Download linuxdeploy + the Qt plugin into tools/ on first run.
#   4. Invoke linuxdeploy to copy Qt6 libs, scan QML imports, and emit the
#      single-file AppImage.
#
# It is safe to re-run -- existing tools/ downloads, AppDir, and output are
# overwritten only where necessary. Pass `--clean` to wipe and start fresh.
#
# This script does NOT compile the app; build it first with CMake / Ninja.
# =============================================================================

set -euo pipefail

# -----------------------------------------------------------------------------
# Paths -- resolve everything relative to this script's location so the script
# can be invoked from any CWD.
# -----------------------------------------------------------------------------
PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LINUX_DIR="$(cd "${PKG_DIR}/.." && pwd)"
BUILD_DIR="${LINUX_DIR}/build"
BIN_DIR="${BUILD_DIR}/bin"
APPDIR="${PKG_DIR}/AppDir"
TOOLS_DIR="${PKG_DIR}/tools"
OUTPUT_DIR="${PKG_DIR}"

# Qt SDK location (matches the rest of the desktop tooling).
QT_PREFIX="${QT_PREFIX:-/home/hardoker77/Downloads/new/qt-local/usr}"
QT_LIB_DIR="${QT_PREFIX}/lib/x86_64-linux-gnu"
QT_QML_DIR="${QT_LIB_DIR}/qt6/qml"
QT_PLUGIN_DIR="${QT_LIB_DIR}/qt6/plugins"
QT_BIN_DIR="${QT_PREFIX}/bin"
QMAKE="${QT_BIN_DIR}/qmake6"

APP_NAME="Localyze"
APP_BINARY="${BUILD_DIR}/${APP_NAME}"
OUT_APPIMAGE="${OUTPUT_DIR}/${APP_NAME}-x86_64.AppImage"

# Pinned linuxdeploy artefacts (continuous channel, last-known-good filenames).
LD_URL="https://github.com/linuxdeploy/linuxdeploy/releases/download/continuous/linuxdeploy-x86_64.AppImage"
LDQT_URL="https://github.com/linuxdeploy/linuxdeploy-plugin-qt/releases/download/continuous/linuxdeploy-plugin-qt-x86_64.AppImage"

# -----------------------------------------------------------------------------
# Helpers
# -----------------------------------------------------------------------------
log()  { printf '\033[1;32m[appimage]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[appimage]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[appimage] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

have() { command -v "$1" >/dev/null 2>&1; }

# -----------------------------------------------------------------------------
# Optional --clean flag
# -----------------------------------------------------------------------------
if [[ "${1:-}" == "--clean" ]]; then
    log "Cleaning AppDir and previous AppImage."
    rm -rf "${APPDIR}"
    rm -f  "${OUT_APPIMAGE}"
fi

# -----------------------------------------------------------------------------
# Step 1: validate inputs.
# -----------------------------------------------------------------------------
log "Validating build artefacts."
[[ -x "${APP_BINARY}" ]]    || die "Binary not found or not executable: ${APP_BINARY}. Build first."
[[ -d "${QT_LIB_DIR}" ]]    || die "Qt lib dir missing: ${QT_LIB_DIR}. Set QT_PREFIX env var."
[[ -d "${QT_QML_DIR}" ]]    || die "Qt qml dir missing: ${QT_QML_DIR}."
[[ -x "${QMAKE}" ]]         || die "qmake6 missing at ${QMAKE}; linuxdeploy-plugin-qt requires it."

# Required tools for downloading / patching.
for tool in curl file find install patchelf; do
    have "${tool}" || warn "Missing '${tool}' on PATH (linuxdeploy may still bundle its own)."
done

# -----------------------------------------------------------------------------
# Step 2: stage AppDir.
#
# AppDir layout:
#   AppDir/
#     AppRun                    (symlink installed by linuxdeploy)
#     usr/
#       bin/Localyze            (main binary)
#       lib/libggml*.so*        (llama.cpp shared libs)
#       lib/libllama*.so*
#       share/applications/Localyze.desktop
#       share/icons/hicolor/256x256/apps/localyze.png
#     Localyze.desktop          (top-level copy for AppImage runtime)
#     localyze.png
# -----------------------------------------------------------------------------
log "Staging AppDir at ${APPDIR}."
rm -rf "${APPDIR}"
mkdir -p "${APPDIR}/usr/bin"
mkdir -p "${APPDIR}/usr/lib"
mkdir -p "${APPDIR}/usr/share/applications"
mkdir -p "${APPDIR}/usr/share/icons/hicolor/256x256/apps"
mkdir -p "${APPDIR}/usr/share/metainfo"

# 2a. main binary.
install -m 0755 "${APP_BINARY}" "${APPDIR}/usr/bin/${APP_NAME}"

# 2b. llama.cpp + ggml shared libraries (Vulkan backend + CPU fallback).
#     Copy preserving symlinks so libfoo.so -> libfoo.so.0 -> libfoo.so.0.x.y
#     chains stay intact (the loader resolves SONAMEs, not full paths).
log "Bundling llama.cpp / ggml shared libraries."
if [[ -d "${BIN_DIR}" ]]; then
    # cp -a preserves symlinks; we copy every libggml*, libllama* artefact.
    shopt -s nullglob
    for lib in "${BIN_DIR}"/libggml*.so* "${BIN_DIR}"/libllama*.so*; do
        cp -a "${lib}" "${APPDIR}/usr/lib/"
    done
    shopt -u nullglob
else
    warn "build/bin not found -- llama.cpp libs may already be in default ldpath."
fi

# 2c. desktop entry + icon (both at AppDir root AND under share/).
log "Installing .desktop entry and icon."
install -m 0644 "${PKG_DIR}/Localyze.desktop" \
                "${APPDIR}/usr/share/applications/Localyze.desktop"
install -m 0644 "${PKG_DIR}/Localyze.desktop" \
                "${APPDIR}/Localyze.desktop"

# Rasterise the SVG to a 256x256 PNG. Prefer rsvg-convert / inkscape /
# ImageMagick; if none are available, fall back to embedding a tiny
# pre-baked PNG so the AppImage still validates.
ICON_SRC="${PKG_DIR}/localyze.svg"
ICON_PNG="${APPDIR}/usr/share/icons/hicolor/256x256/apps/localyze.png"
if   have rsvg-convert; then
    rsvg-convert -w 256 -h 256 -o "${ICON_PNG}" "${ICON_SRC}"
elif have inkscape; then
    inkscape -w 256 -h 256 "${ICON_SRC}" -o "${ICON_PNG}" >/dev/null 2>&1
elif have convert; then
    convert -background none -resize 256x256 "${ICON_SRC}" "${ICON_PNG}"
elif have magick; then
    magick -background none -resize 256x256 "${ICON_SRC}" "${ICON_PNG}"
else
    warn "No SVG rasteriser found (rsvg-convert/inkscape/convert). Writing a 1x1 placeholder PNG."
    # 1x1 transparent PNG, base64-encoded. Good enough for AppImage tooling.
    base64 -d > "${ICON_PNG}" <<'EOF'
iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNgAAIAAAUAAarVyFEAAAAASUVORK5CYII=
EOF
fi
# linuxdeploy also expects an icon at the AppDir root with the basename
# matching the .desktop's Icon= field.
cp -f "${ICON_PNG}" "${APPDIR}/localyze.png"
# And copy the SVG alongside for desktop integration on Wayland/HiDPI systems.
mkdir -p "${APPDIR}/usr/share/icons/hicolor/scalable/apps"
install -m 0644 "${ICON_SRC}" "${APPDIR}/usr/share/icons/hicolor/scalable/apps/localyze.svg"

# -----------------------------------------------------------------------------
# Step 3: download linuxdeploy + Qt plugin (idempotent).
# -----------------------------------------------------------------------------
mkdir -p "${TOOLS_DIR}"
LD="${TOOLS_DIR}/linuxdeploy-x86_64.AppImage"
LDQT="${TOOLS_DIR}/linuxdeploy-plugin-qt-x86_64.AppImage"

fetch() {
    local url="$1" out="$2"
    if [[ -s "${out}" ]]; then
        log "Already have $(basename "${out}")."
        return 0
    fi
    log "Downloading $(basename "${out}")."
    curl --fail --location --progress-bar -o "${out}.part" "${url}"
    chmod +x "${out}.part"
    mv "${out}.part" "${out}"
}

fetch "${LD_URL}"   "${LD}"
fetch "${LDQT_URL}" "${LDQT}"

# linuxdeploy is itself an AppImage. On systems without FUSE we must
# extract it. Detect & fall back automatically.
run_appimage() {
    local image="$1"; shift
    if "${image}" "$@" 2>/tmp/ld_test.$$ ; then
        rm -f /tmp/ld_test.$$
        return 0
    fi
    if grep -qiE 'fuse|libfuse' /tmp/ld_test.$$ 2>/dev/null; then
        warn "FUSE not available -- extracting AppImage and re-running."
        local extract_root="${TOOLS_DIR}/$(basename "${image}").extracted"
        if [[ ! -d "${extract_root}" ]]; then
            ( cd "${TOOLS_DIR}" && "${image}" --appimage-extract >/dev/null )
            mv "${TOOLS_DIR}/squashfs-root" "${extract_root}"
        fi
        "${extract_root}/AppRun" "$@"
    else
        cat /tmp/ld_test.$$ >&2
        rm -f /tmp/ld_test.$$
        return 1
    fi
}

# -----------------------------------------------------------------------------
# Step 4: invoke linuxdeploy.
#
# Environment variables consumed by linuxdeploy-plugin-qt:
#   QMAKE              -- path to qmake6 (so the plugin learns Qt prefix/version)
#   QML_SOURCES_PATHS  -- where to scan for QML files for import discovery
#   EXTRA_QT_MODULES   -- extra modules to force-bundle (waylandclient, etc.)
#   EXTRA_QT_PLUGINS   -- extra Qt plugins to force-bundle
#   LD_LIBRARY_PATH    -- so linuxdeploy can resolve our bundled .so files
# -----------------------------------------------------------------------------
log "Running linuxdeploy with the Qt plugin."

export QMAKE
export QML_SOURCES_PATHS="${LINUX_DIR}/qml"
# Force-bundle modules that QML import-scanning may miss (Wayland session,
# XCB platform integration, virtual keyboard, dialogs backends, etc.).
export EXTRA_QT_MODULES="waylandclient;waylandcompositor;xcbqpa;widgets;quickcontrols2;quickdialogs2;charts;network;quick;qml;core;gui;dbus;svg"
# Platform + style plugins linuxdeploy may not auto-detect.
export EXTRA_QT_PLUGINS="wayland-decoration-client;wayland-graphics-integration-client;wayland-shell-integration;platforms/libqxcb;platforms/libqwayland-generic;platformthemes/libqgtk3;iconengines/libqsvgicon;imageformats/libqsvg"
# So linuxdeploy can `dlopen` our bundled libs while patching the binary.
export LD_LIBRARY_PATH="${APPDIR}/usr/lib:${QT_LIB_DIR}:${LD_LIBRARY_PATH:-}"
# Tell the bundled binary at runtime where to find QML imports.
export QML_IMPORT_PATH="${QT_QML_DIR}"
export QML2_IMPORT_PATH="${QT_QML_DIR}"
# Sign nothing; produce type-2 AppImage (default).
unset APPIMAGE_EXTRACT_AND_RUN

# Build linuxdeploy CLI args. --library entries explicitly attach our
# llama.cpp shared libs so patchelf rewrites their RPATH too.
LD_ARGS=(
    --appdir   "${APPDIR}"
    --executable "${APPDIR}/usr/bin/${APP_NAME}"
    --desktop-file "${APPDIR}/usr/share/applications/Localyze.desktop"
    --icon-file  "${APPDIR}/localyze.png"
    --plugin   qt
    --output   appimage
)
shopt -s nullglob
for lib in "${APPDIR}/usr/lib"/libggml*.so* "${APPDIR}/usr/lib"/libllama*.so*; do
    # Skip symlinks; linuxdeploy follows them automatically.
    [[ -L "${lib}" ]] && continue
    LD_ARGS+=(--library "${lib}")
done
shopt -u nullglob

(
    cd "${OUTPUT_DIR}"
    # OUTPUT controls the final AppImage filename.
    OUTPUT="${APP_NAME}-x86_64.AppImage" \
    LINUXDEPLOY_PLUGIN_QT_PATH="${LDQT}" \
        run_appimage "${LD}" "${LD_ARGS[@]}"
)

# -----------------------------------------------------------------------------
# Done.
# -----------------------------------------------------------------------------
if [[ -f "${OUT_APPIMAGE}" ]]; then
    chmod +x "${OUT_APPIMAGE}"
    log "Built: ${OUT_APPIMAGE}"
    log "Size:  $(du -h "${OUT_APPIMAGE}" | awk '{print $1}')"
    log "Run with: ./$(basename "${OUT_APPIMAGE}")"
else
    die "linuxdeploy did not produce ${OUT_APPIMAGE}."
fi
