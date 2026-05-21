# Localyze.ai - Linux AppImage Packaging

This folder contains the tooling to bundle the compiled Localyze.ai binary
into a single, self-contained `Localyze-x86_64.AppImage` that any modern
Linux user can download and double-click to run - no installation, no
package manager, no terminal required.

## Files

| File                  | Purpose                                                          |
| --------------------- | ---------------------------------------------------------------- |
| `build-appimage.sh`   | The packaging script. Stages an AppDir and invokes linuxdeploy.  |
| `Localyze.desktop`    | XDG desktop entry baked into the AppImage.                       |
| `localyze.svg`        | Scalable application icon (green tile with white "L").           |
| `tools/`              | Auto-downloaded `linuxdeploy` binaries (created on first run).   |
| `AppDir/`             | Staging tree assembled by the script (created on first run).     |
| `Localyze-x86_64.AppImage` | Final packaged app (produced by the script).                |

## Building the AppImage

### 1. Build the desktop binary first

```bash
cd desktop/linux
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
```

This produces `desktop/linux/build/Localyze` and the
`build/bin/libggml-*.so*` / `build/bin/libllama*.so*` runtime libraries.

### 2. Run the packaging script

```bash
cd desktop/linux/packaging
./build-appimage.sh
```

On first run the script downloads `linuxdeploy` and
`linuxdeploy-plugin-qt` (~50 MB total) into `tools/`. Subsequent runs
re-use those.

Pass `--clean` to wipe the staged `AppDir/` and previous AppImage:

```bash
./build-appimage.sh --clean
```

### Environment overrides

| Variable      | Default                                            | Meaning                          |
| ------------- | -------------------------------------------------- | -------------------------------- |
| `QT_PREFIX`   | `/home/hardoker77/Downloads/new/qt-local/usr`      | Root of the Qt6 install.         |

## What the AppImage contains

- `Localyze` executable (statically linked against project libs).
- Qt6 runtime: `Quick`, `QuickControls2`, `Charts`, `Network`, `Widgets`,
  `WaylandClient`, `XcbQpa`, `Svg`, `DBus` (plus their transitive deps).
- Qt platform plugins: `xcb`, `wayland-generic`, GTK3 platform theme,
  SVG icon engine.
- QML modules: `QtQuick`, `QtQuick.Controls`, `QtQuick.Layouts`,
  `QtQuick.Window`, `QtQuick.Dialogs`, `Qt.labs.platform`, `QtCharts`.
- llama.cpp + ggml shared libraries built with the Vulkan backend
  (`libllama.so*`, `libggml.so*`, `libggml-vulkan.so*`, `libggml-cpu.so*`,
  `libggml-base.so*`).
- `.desktop` file and 256x256 PNG icon for desktop integration.

The model file (Gemma 4 E4B) is **not** bundled - it is downloaded on
first launch into the user's XDG data directory, matching the Android
build's behaviour.

## How a user runs it

1. Download `Localyze-x86_64.AppImage`.
2. Mark it executable (most file managers do this via right-click ->
   Properties -> Permissions, or run `chmod +x Localyze-x86_64.AppImage`).
3. Double-click it.

The AppImage is fully self-contained; the only host requirement is a
recent enough glibc (>= 2.31, i.e. Ubuntu 20.04 / Debian 11 / Fedora 33+)
and a working Vulkan ICD (`mesa-vulkan-drivers`, `nvidia-vulkan-icd`,
`amdvlk`, etc., which ship by default on most desktop distros).

### Optional desktop integration

Tools like
[AppImageLauncher](https://github.com/TheAssassin/AppImageLauncher) will
register the `.desktop` entry and icon system-wide automatically. Without
it, the AppImage still runs - it just won't show up in the application
menu.

## Troubleshooting

- **"FUSE: failed to mount"** - the script auto-detects this and falls
  back to extract-and-run mode. To run the AppImage itself on such a
  host, invoke it with `--appimage-extract-and-run`.
- **Blank window on Wayland** - export `QT_QPA_PLATFORM=xcb` and re-run.
- **No GPU acceleration** - confirm Vulkan works on the host with
  `vulkaninfo --summary`. Localyze refuses to fall back to a CPU-only
  backend by design.
