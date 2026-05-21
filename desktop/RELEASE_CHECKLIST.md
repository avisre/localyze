# Localyze Desktop — v1.0 Release Checklist

Single source of truth for shipping Localyze v1.0 on Windows, macOS, and Linux.
Owner placeholders (`@you`) mark items where one person must take responsibility;
status is one of **DONE**, **TODO**, or **N/A** (out-of-scope for v1.0).

Last reviewed: 2026-05-18. See [STATUS.md](STATUS.md) for what is implemented today
and [CLAUDE.md](CLAUDE.md) for the policies any future agent must respect.

---

## 1. Code complete

What is already done per [STATUS.md](STATUS.md). Anything here at **TODO** is a v1.0 blocker.

| Item | Owner | Status |
|---|---|---|
| `desktop/` tree exists alongside Android app | @you | DONE |
| Shared manifest schema + 10 example tiers ([shared/manifest.schema.json](shared/manifest.schema.json)) | @you | DONE |
| Shared `<viz>` block format ([shared/viz-schema.md](shared/viz-schema.md)) | @you | DONE |
| Shared ReAct loop spec ([shared/research-agent.md](shared/research-agent.md)) | @you | DONE |
| Windows: WinUI 3 + ONNX Runtime GenAI ([windows/Localyze/](windows/Localyze/)) | @you | DONE |
| macOS: SwiftUI + MLX (`mlx-swift`) ([macos/Sources/Localyze/](macos/Sources/Localyze/)) | @you | DONE |
| Linux: Qt 6 + QML + llama.cpp ([linux/src/](linux/src/)) | @you | DONE |
| Hardware probe on all three platforms | @you | DONE |
| Backend selector with NPU > dGPU > iGPU > CPU priority | @you | DONE |
| Resumable + sha256-verified model downloader on all three | @you | DONE |
| ReAct agent + six tools (calc / memory / files / run / system.info / web) on all three | @you | DONE |
| `<viz>` parser + native renderers on all three | @you | DONE |
| Settings store with web-search toggle (default OFF) on all three | @you | DONE |
| Windows chart renderer wired to CommunityToolkit Labs LineChart (currently textual) | @you | TODO |
| PDF export wired to `NSPrintOperation` / `PrintManager` / `QPrinter` | @you | TODO |
| Files RAG upgraded from substring scan to local embeddings | @you | TODO |

## 2. Build & packaging

One row per platform-format. AppImage is the must-ship Linux format; `.deb` / `.rpm` /
Flatpak are nice-to-have for v1.0.

| Item | Owner | Status |
|---|---|---|
| Linux: `Localyze.AppImage` reproducible build script (no sudo) | @you | TODO |
| Linux: `.deb` (Debian/Ubuntu) | @you | TODO |
| Linux: `.rpm` (Fedora/RHEL) | @you | N/A (v1.1) |
| Linux: Flatpak manifest on Flathub | @you | N/A (v1.1) |
| Linux: Snap on snapcraft.io | @you | N/A (v1.1) |
| Linux: AppImage embeds Qt 6 runtime + per-backend llama.cpp `.so` | @you | TODO |
| Windows: MSIX package built from `Localyze.csproj` | @you | TODO |
| Windows: ONNX Runtime GenAI native libs bundled per arch (x64 + ARM64) | @you | TODO |
| Windows: Side-loadable `.appinstaller` for non-Store users | @you | TODO |
| macOS: Universal `.app` bundle (arm64 + x86_64) | @you | TODO |
| macOS: `.dmg` installer with background image and `Applications` symlink | @you | TODO |
| All: deterministic version embedded in binary (`Localyze --version`) | @you | TODO |
| All: build artifacts produced by CI (GitHub Actions matrix) | @you | TODO |

## 3. Signing & distribution

Code-signing is non-negotiable on Windows and macOS — unsigned binaries get
SmartScreen / Gatekeeper warnings that destroy first-run UX.

| Item | Owner | Status |
|---|---|---|
| Apple Developer Program enrollment ($99/yr) | @you | TODO |
| Apple Developer ID Application certificate (for `.dmg` direct distribution) | @you | TODO |
| macOS notarization via `notarytool` + stapling | @you | TODO |
| Microsoft Authenticode (EV or OV) code-signing certificate | @you | TODO |
| Windows MSIX signed with the Authenticode cert | @you | TODO |
| Linux: detached `.AppImage.zsync` + GPG signature alongside each release | @you | TODO |
| Mac App Store submission (sandboxed build, separate target) | @you | N/A (v1.1) |
| Microsoft Store submission (Partner Center) | @you | N/A (v1.1) |
| GitHub Releases as primary distribution channel for v1.0 | @you | TODO |
| `localyze.ai` website serves direct-download links + auto-detect-OS button | @you | TODO |
| Mirror list (GitHub Releases + CDN + Hugging Face) in the manifest | @you | TODO |

## 4. Model file distribution

The ~4 GB GGUF (and 2.4 GB MLX bundle, and ~2 GB ONNX) cannot live in the
installer — they ship from a CDN after the hardware probe.

| Item | Owner | Status |
|---|---|---|
| Primary host: Hugging Face Hub (`localyze/gemma-4-e4b-it-desktop`) | @you | TODO |
| Secondary host: CloudFlare R2 bucket (free egress) | @you | TODO |
| Tertiary host: GitHub Release assets (≤2 GB each; split if needed) | @you | TODO |
| Per-tier SHA-256 baked into `manifest.json` ([shared/manifest.example.json](shared/manifest.example.json)) | @you | DONE (example) |
| Production `manifest.json` served from `manifest.localyze.ai/v1.0/manifest.json` | @you | TODO |
| Manifest itself signed (minisign or sigstore) — client verifies before download | @you | TODO |
| Per-platform artifacts produced: `gemma-4-e4b-it-q4_k_m.gguf` (Linux), `gemma-4-e4b-it-mlx-int4` (mac), `gemma-4-e4b-it-int4.onnx` (win) | @you | TODO |
| Resumable downloads via HTTP `Range:` headers — implemented on all 3 platforms | @you | DONE |
| Mirror failover: client tries primary, falls back to secondary on 4xx/5xx/timeout | @you | TODO |
| Bandwidth budget — estimate cost vs. expected first-day downloads | @you | TODO |

## 5. First-run UX

The 30 MB installer is intentionally lean — almost everything important happens
on first launch.

| Item | Owner | Status |
|---|---|---|
| Onboarding wizard: welcome → privacy notice → hardware probe → tier shown | @you | TODO |
| Hardware probe results displayed in human-readable form ("AMD RX 9070, ROCm Q4") | @you | TODO |
| Model download with progress bar, ETA, MB/s, pause/resume | @you | DONE (engine) / TODO (UI) |
| Disk-space precheck before download (≥ model_size_mb × 1.2) | @you | TODO |
| Network-fail error state: clear "retry / use a different mirror / cancel" | @you | TODO |
| Hardware-refuse path: explain what's missing ("needs ≥4 GB VRAM or NPU"), no silent downgrade | @you | TODO |
| Smoke-test prompt on first launch ("Say hello in three languages") — confirms model loaded | @you | TODO |
| `~/Localyze/` workspace folder created on first run, with sample artifact | @you | TODO |

## 6. Telemetry & privacy

**Opt-in only. OFF by default. Local-first is the whole point.**

| Item | Owner | Status |
|---|---|---|
| Telemetry toggle in Settings (default **OFF**) | @you | TODO |
| If enabled: only anonymous launch/version/OS/tier counters; never prompt content | @you | TODO |
| Telemetry endpoint over HTTPS, no third-party SDK | @you | TODO |
| 30-day retention policy on the server; aggregate-only after that | @you | TODO |
| Privacy policy page at `localyze.ai/privacy` listing every byte that may leave the device | @you | TODO |
| Existing [PRIVACY_POLICY.md](../PRIVACY_POLICY.md) updated to cover desktop | @you | TODO |
| Web search (already OFF by default) — confirmed across all 3 platforms | @you | DONE |

## 7. Crash reporting

Stay local-first: a crash dump that contains user prompts is a privacy disaster.

| Item | Owner | Status |
|---|---|---|
| Decision: homegrown opt-in upload, NOT Sentry / Crashlytics (avoid third-party PII) | @you | TODO |
| Crash dumps written locally to `~/Localyze/crashes/*.dmp` always | @you | TODO |
| "Send this crash to us?" dialog on next launch; OFF by default | @you | TODO |
| Crash dump scrubbed of prompt/response strings before upload | @you | TODO |
| Server-side: encrypted at rest, 90-day retention | @you | TODO |

## 8. Auto-update

Each platform uses its native update channel — never roll your own where the OS has one.

| Item | Owner | Status |
|---|---|---|
| macOS: Sparkle 2 (EdDSA-signed appcast at `updates.localyze.ai/mac/appcast.xml`) | @you | TODO |
| Windows MSIX: App Installer auto-update via `.appinstaller` URI | @you | TODO |
| Linux AppImage: `AppImageUpdate` + `.zsync` files (delta updates) | @you | TODO |
| Linux Flatpak: handled by Flathub (when shipped) | @you | N/A (v1.1) |
| Linux Snap: handled by snapcraft (when shipped) | @you | N/A (v1.1) |
| Update channel selector in Settings (stable / beta) — stable only for v1.0 | @you | TODO |
| Update never silently swaps the model — model updates are explicit, version-gated | @you | TODO |

## 9. Smoke-test matrix

Minimum hardware that must pass the 300-Q gauntlet before each release.

| Bucket | Required hardware | Owner | Status |
|---|---|---|---|
| macOS Apple Silicon (low) | M1 / 16 GB | @you | TODO |
| macOS Apple Silicon (high) | M3 Pro / 36 GB | @you | TODO |
| macOS Intel | Intel Mac w/ AMD dGPU (llama.cpp Metal path) | @you | TODO |
| Windows + NVIDIA | Win 11, RTX 3060 / 12 GB | @you | TODO |
| Windows + AMD | Win 11, RX 7600 / 8 GB (DirectML) | @you | TODO |
| Windows + Intel iGPU | Win 11, Intel Arc / Iris Xe | @you | TODO |
| Windows + NPU | Snapdragon X Elite or Lunar Lake | @you | TODO |
| Linux + NVIDIA | Ubuntu 22.04, RTX 3060 / 12 GB (CUDA build) | @you | TODO |
| Linux + AMD ROCm | Ubuntu 24.04 + RX 7900 / RX 9070 (HIP build) — primary dev box | @you | DONE (probe verified, inference pending model download) |
| Linux + Vulkan generic | Ubuntu 22.04, any modern GPU (Vulkan build) | @you | TODO |
| Linux + CPU-only | Ubuntu 24.04, no GPU, ≥16 GB RAM (Q4 fallback) | @you | DONE (probe + build verified) |

## 10. Quality bar

**A release ships only if it scores ≥ 95% on the 300-Q live gauntlet on every
smoke-test bucket above.** v5 hit 96.9% on Linux CPU — see
[STATUS.md § v5 quality milestone](STATUS.md#v5-quality-milestone-2026-05-18).

| Item | Owner | Status |
|---|---|---|
| 300-Q live gauntlet runs against the built artifact, not the source tree | @you | DONE (driver: `/tmp/qrepo/k300_driver*.py`) |
| Pass rate ≥ 95% per platform | @you | DONE on Linux CPU (96.9%); TODO on others |
| Every category at ≥ 90% (chat / clarify / writing / comparison / research / safety / edge / code / math / multilingual / redteam) | @you | DONE on Linux CPU; TODO on others |
| Regressions flagged automatically by `run_bank250.py` against previous release scores | @you | TODO |
| 1000-Q infrastructure (`tests/run_combined_1000.py`) as a periodic deeper sweep | @you | TODO (currently unit-test scale) |
| 50-Q deep-research bank ([tests/bank250.json](tests/bank250.json) subset) used to spot-check research mode | @you | DONE |

## 11. Marketing

| Item | Owner | Status |
|---|---|---|
| Top-level `README.md` with screenshots from each platform | @you | TODO |
| Per-platform screenshots: chat, deep research, an artifact (chart + editable table) | @you | TODO |
| Website at `localyze.ai` — download buttons, hardware-detect-OS, comparison table | @you | TODO |
| Comparison-vs-ChatGPT-locally table (privacy, offline, hardware-adaptive, native artifacts, cost) | @you | TODO |
| Launch blog post + demo video (60 s, no voiceover, just usage) | @you | TODO |
| Hacker News / Reddit / r/LocalLLaMA / Mastodon launch posts | @you | TODO |
| Press kit: logo SVG, screenshots @2x, one-paragraph pitch | @you | TODO |

---

## Release gate

A v1.0 release ships only when **every row above is DONE or N/A**. Anything still
TODO is a v1.0 blocker. Rows marked `N/A (v1.1)` are tracked for the next milestone.
