# Build & release guide — Localyze.ai

This document covers everything you need to produce a Play Store-ready APK or
AAB: signing, ProGuard, lint, Crashlytics, and the version bump checklist.

## Quick reference

| Command | What it does |
|---|---|
| `./gradlew :app:assembleDebug` | Debug APK at `app/build/outputs/apk/debug/app-debug.apk` |
| `./gradlew :app:testDebugUnitTest` | All 188+ unit tests |
| `./gradlew :app:lintDebug` | Lint check (HTML/XML report in `app/build/reports/`) |
| `./gradlew :app:assembleRelease` | Minified, ProGuarded release APK (needs signing config) |
| `./gradlew :app:bundleRelease` | Signed AAB for Play Store upload |
| `./gradlew :app:connectedDebugAndroidTest` | Instrumentation tests on a connected device |

## Signing

The release `signingConfig` reads three values, in this priority order:

1. Environment variables: `LOCALYZE_KEYSTORE_PASSWORD`, `LOCALYZE_KEY_PASSWORD`, `LOCALYZE_KEYSTORE_FILE`
2. Gradle properties: same names, set in `~/.gradle/gradle.properties` or `local.properties`
3. Fallback keystore path: `../localyze-release.keystore` (sibling of the project root)

The key alias is hard-coded as `localyze`.

### Local dev: one-time setup

```bash
keytool -genkey -v \
  -keystore ../localyze-release.keystore \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias localyze
```

Then add to `~/.gradle/gradle.properties` (NOT committed):

```properties
LOCALYZE_KEYSTORE_PASSWORD=<store-password>
LOCALYZE_KEY_PASSWORD=<key-password>
LOCALYZE_KEYSTORE_FILE=/absolute/path/to/localyze-release.keystore
```

### CI: GitHub Actions example

Store the keystore as a base64-encoded GitHub Secret and decode at build time:

```yaml
- name: Decode keystore
  env:
    KEYSTORE_BASE64: ${{ secrets.LOCALYZE_KEYSTORE_BASE64 }}
  run: echo "$KEYSTORE_BASE64" | base64 -d > localyze-release.keystore
- name: Build release AAB
  env:
    LOCALYZE_KEYSTORE_PASSWORD: ${{ secrets.LOCALYZE_KEYSTORE_PASSWORD }}
    LOCALYZE_KEY_PASSWORD: ${{ secrets.LOCALYZE_KEY_PASSWORD }}
    LOCALYZE_KEYSTORE_FILE: ${{ github.workspace }}/localyze-release.keystore
  run: ./gradlew :app:bundleRelease
```

**Never** commit the keystore or the passwords — both are in `.gitignore`. If
they leak, rotate the upload key via Play Console (Setup → App signing).

## ProGuard / R8

Minification is on for `release` builds. Keep-rules live in
[app/proguard-rules.pro](app/proguard-rules.pro). The current rules cover:

- App models (`com.localyze.domain/data/ai/tools.**`)
- Hilt + Dagger generated classes
- Room entities + DAOs (the `_Impl` classes are looked up by name)
- OkHttp internal platform classes + TLS providers
- JSoup (reflective HTML parsing)
- WorkManager Worker subclasses (instantiated reflectively)
- DataStore preferences
- Billing client
- LiteRT-LM native bridge
- SQLCipher
- Firebase Crashlytics + line-number attributes
- Paging3
- kotlinx.serialization (`$$serializer` companions)
- Enum `values()` / `valueOf()`

If you add a new dependency that uses reflection, JSON serialization, or
service-loader-style class lookup, add a `-keep` rule and verify the release
APK still works (`assembleRelease` then sideload + smoke test).

## Lint

`lint { ... }` block is configured in [app/build.gradle.kts](app/build.gradle.kts):

- `abortOnError = true` — release build fails on hard errors
- `warningsAsErrors = false` — warnings are reported but don't fail
- `baseline = lint-baseline.xml` — generate once with
  `./gradlew :app:updateLintBaseline`, commit the file. New issues introduced
  after the baseline still fail
- HTML + XML reports under `app/build/reports/`

## Crashlytics opt-in

The app respects the user's `allowCrashReporting` toggle (default: enabled).
[LocalyzeApp.kt](app/src/main/java/com/localyze/LocalyzeApp.kt) observes the
preference flow and calls `setCrashlyticsCollectionEnabled(...)` whenever it
changes — flipping the Settings toggle now takes effect immediately, not just
on next launch. `CrashReportingManager.applyCollectionPreference(enabled)` is
exposed for tests / future explicit triggering.

For Play Store **Data Safety** declarations:

- Diagnostic data: crash stack traces, OS version, device model — **collected
  by default, opt-out available**
- No chat content, no contacts, no images, no audio, no model prompts are
  ever attached to a Crashlytics report (verify in `CrashReportingManager`)
- All custom keys / values are sanitized to ASCII alphanumerics +
  underscores, max 64 chars (key) and 128 chars (value)

## Logging hygiene

All `Log.d` and `Log.v` calls in app code go through
[AppLog](app/src/main/java/com/localyze/utils/AppLog.kt), which gates them on
`BuildConfig.DEBUG`. In release builds the body is no-op — debug tracing
(prompts, model paths, inference parameters) does not reach logcat.

Use `AppLog.d()` / `AppLog.v()` for all new debug logs. Reserve `Log.w` /
`Log.e` (or `AppLog.w` / `AppLog.e`) for events you want in production
crash diagnostics.

## Privacy URL placeholders

Two strings are placeholders pending real URLs:

- `R.string.privacy_policy_url` → `https://localyze.ai/privacy`
- `R.string.terms_of_service_url` → `https://localyze.ai/terms`

Update both in [app/src/main/res/values/strings.xml](app/src/main/res/values/strings.xml)
before Play Store submission and confirm the URLs return a 200 with the
matching policy.

## Release checklist

- [ ] Bump `versionCode` and `versionName` in `app/build.gradle.kts`
- [ ] Update `CHANGELOG.md` with user-facing changes
- [ ] `./gradlew :app:testDebugUnitTest` → 0 failures
- [ ] `./gradlew :app:lintRelease` → 0 errors
- [ ] `./gradlew :app:assembleRelease` builds + APK installs + smoke test
  (chat, code workspace, library, settings)
- [ ] Sideload release APK; verify Crashlytics opt-out toggle works
- [ ] `./gradlew :app:bundleRelease` for Play Store upload
- [ ] Privacy policy + terms URLs return 200
- [ ] Play Console Data Safety form filled in (audio, contacts, calendar,
  diagnostic, chat content all marked appropriately)
