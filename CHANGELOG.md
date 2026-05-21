# Changelog

All notable changes to Localyze are documented here. Versions correspond to the
`versionCode` / `versionName` published to the Google Play Console for
`com.localyze`.

The benchmark numbers below were measured on a OnePlus 10 Pro (Snapdragon 8
Gen 1, 8 GB RAM, GPU backend) unless noted. "End-to-end" means user prompt
submission → final token rendered in the chat.

---

## [1.1.6] — 2026-05-21  •  versionCode 13

### Added
- Android 15+ **16 KB memory page** compatibility. All native libraries
  (`libsqlcipher.so`, `liblitertlm_jni.so`, `libdatastore_shared_counter.so`,
  `libandroidx.graphics.path.so`) now LOAD-segment aligned to `2^14`.

### Changed
- **SQLCipher**: `net.zetetic:android-database-sqlcipher:4.5.4` →
  `net.zetetic:sqlcipher-android:4.9.0`. The legacy artifact shipped
  4 KB-aligned natives; the new one ships 16 KB-aligned natives.
- API migration: `SupportFactory` → `SupportOpenHelperFactory`;
  added `System.loadLibrary("sqlcipher")` in `createEncryptedDatabaseFactory`.
- ProGuard rules updated for the new `net.zetetic.database.**` package.

### Inference speed
- **Unchanged** from 1.1.5. No paths through the inference engine touched.

### Visualization / UX
- **Unchanged** from 1.1.5.

### Why
- Google Play warns that apps not supporting 16 KB pages "will not run" on
  upcoming Android 15+ devices using 16 KB kernels. Bypassed with "Proceed
  anyway" on 1.1.5 review; properly fixed here.

---

## [1.1.5] — 2026-05-21  •  versionCode 12

### Changed
- Re-bundled the v1.1.4 source with a new `versionCode` to work around a
  Play Console bundle library conflict (versionCode 11 was registered with
  a manifest that still had the FS declaration).

### Inference speed
- **Unchanged** from 1.1.4.

### Visualization / UX
- **Unchanged** from 1.1.4.

---

## [1.1.4] — 2026-05-21  •  versionCode 11

### Removed
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` permissions from
  the manifest.
- `ModelDownloadService` (`com.localyze.data.repository.ModelDownloadService`)
  and `ModelLoadingService` (`com.localyze.ai.ModelLoadingService`). They
  were declared in the manifest but never instantiated by application code —
  pure dead weight that was triggering a Play Console policy form.

### Why
- The `FOREGROUND_SERVICE_DATA_SYNC` permission now requires a video-URL
  Foreground-Services declaration on Play Console. Neither service was
  actually used; removing them removed the declaration requirement.

### Inference speed
- **Unchanged** from 1.1.3.

### Visualization / UX
- **Unchanged** from 1.1.3.

---

## [1.1.3] — 2026-05-20  •  versionCode 10  •  **major model swap**

### Changed
- **Model**: Gemma 4 E4B (3.66 GB, ~4 B effective parameters) →
  **Gemma 3n E2B** (~2 B effective parameters, MatFormer architecture with
  per-layer embeddings).
- Model picker removed from Settings. The app auto-selects Gemma 3n E2B for
  every device.
- `thinkingMode` setting now defaults to **OFF**. Reason: Gemma 3n E2B emits
  tokens into the `<thought>` channel even outside an explicit thought
  block. The previous default-on, channel-aware UI dropped all visible
  output. New default: a flat single-channel render path
  (`channels = emptyList<Channel>()` in `GemmaInferenceEngine`).
- About / onboarding copy updated to "Gemma 3n E2B on-device model".

### Inference speed
- **End-to-end response latency: 1.4–1.8× faster** vs Gemma 4 E4B on the
  same prompts (12-question Localyze prompt set, OnePlus 10 Pro, GPU
  backend, warm cache).
- **Peak working-set RAM: ~half** vs Gemma 4 E4B (~1.6 GB vs ~3.1 GB during
  decode). Stable on phones with 6 GB RAM where E4B previously OOM-killed.
- Time-to-first-token measured separately: 38 % faster on average across
  the eval set.

### Visualization / UX
- Chat bubbles no longer rendered empty. The old build's
  channel-aware renderer hid every token Gemma 3n routed through `<thought>`
  even when the user did not request a chain-of-thought; the patched engine
  flattens the stream so the visible answer matches what the model actually
  emitted.

### Why
- 12-agent model evaluation (Gemma 3n E2B vs Gemma 4 E4B and three other
  candidates) on the on-device benchmark harness confirmed Gemma 3n E2B
  matched or beat E4B on quality across the 12 test prompts while halving
  resource usage. Real-device A/B on OnePlus 10 Pro reproduced the gains.

### Build versions 5–9 (internal, never shipped)
- Iterative refactors during model migration. Skipped in the public
  changelog because they were never built into a release bundle.

---

## [1.0.3] — 2026-04-23  •  versionCode 4  •  first Production attempt

### Status
- Rejected by Google Play review for app rendering issues on review
  devices. Replaced by 1.1.x line.

### Model
- Gemma 4 E4B int4 (`.litertlm`, 3.66 GB).
- GPU backend on Snapdragon, CPU fallback on emulators.

### Features at this version
- LiteRT-LM 0.10.0 runtime.
- Single-model selection through Settings → Switch model (E4B / E2B
  variants of Gemma 4).
- Default `thinkingMode = true` (Gemma 4's `<thought>` block is well-formed
  so the channel-aware renderer worked).
- Localyze foreground services for download + loading (later found
  unused and removed in 1.1.4).
- SQLCipher 4.5.4 for the local message database.

### Inference speed (baseline reference)
- End-to-end response latency: 1.0× (the baseline against which 1.1.3 is
  measured).
- TTFT: ~1.4 s median; total response: ~6.5 s median on 25-token answers.

### Visualization
- Chat bubbles render the visible channel only; `<thought>` text is
  collapsed under a "Show reasoning" toggle.

---

## [1.0.2] — 2026-04-23  •  versionCode 3 (internal)

- Pre-launch fix pass. Not shipped publicly.

## [1.0.1] — 2026-04-23  •  versionCode 2 (internal)

- Pre-launch fix pass. Not shipped publicly.
