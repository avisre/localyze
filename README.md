# Localyze

On-device AI assistant for Android. Runs Gemma 3n E2B locally with LiteRT-LM —
no data leaves the phone.

- **Package**: `com.localyze`
- **Min SDK**: 28 (Android 9)
- **Target SDK**: 35 (Android 15)
- **Model**: Gemma 3n E2B int4 LiteRT-LM (~2 B effective params, MatFormer)
- **Runtime**: LiteRT-LM 0.10.0 (GPU backend on Snapdragon, NPU on Hexagon
  HTP where available, CPU fallback)
- **Encryption at rest**: SQLCipher 4.9.0 (16 KB page-aligned)

---

## Build

Prerequisites: JDK 17, Android SDK with platform 35 and build-tools 35.0.0.

```bash
# Local debug build
./gradlew :app:assembleDebug

# Signed release bundle (requires signing config; see BUILD.md)
./gradlew :app:bundleRelease
```

See [`BUILD.md`](BUILD.md) for signing setup and emulator/device workflows.

## Privacy & data safety

- All inference runs on-device. No prompts, model outputs, or chat history
  are uploaded.
- Local Room database is encrypted with SQLCipher; passphrase is per-install
  and derived from `ANDROID_ID` + signing-cert fingerprint, wrapped with
  AES-GCM.
- See [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) and
  [`PLAY_DATA_SAFETY.md`](PLAY_DATA_SAFETY.md) for the user-facing
  declarations.

## Versioning & releases

This repo is tagged per Play Console release. See [`CHANGELOG.md`](CHANGELOG.md)
for the full history including model swaps, inference-speed deltas, and
visualization changes per version.

Quick history:

| Version | versionCode | Date       | Key change                                     |
| ------- | ----------- | ---------- | ---------------------------------------------- |
| 1.1.6   | 13          | 2026-05-21 | 16 KB native page alignment (SQLCipher 4.9.0)  |
| 1.1.5   | 12          | 2026-05-21 | Bundle-library re-roll of 1.1.4                |
| 1.1.4   | 11          | 2026-05-21 | Drop unused `FOREGROUND_SERVICE_DATA_SYNC`     |
| 1.1.3   | 10          | 2026-05-20 | **Gemma 3n E2B** (1.4–1.8× faster, ½ RAM)      |
| 1.0.3   | 4           | 2026-04-23 | First Production submission (Gemma 4 E4B)      |

## Layout

```
app/                      Android app module
  src/main/java/com/localyze/
    ai/                   Inference engine, prompts, branding
    data/                 Repos, local DB, model file mgmt
    domain/               Use-cases, clarification flow, models
    ui/                   Compose screens + viewmodels
    services/             Foreground / background services
    tools/                Function-call tools (currency, weather, ...)
scripts/                  Dev tooling (Chrome launcher for Play Console)
.github/                  CI / issue templates
```
