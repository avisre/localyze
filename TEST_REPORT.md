# Localyze QA Test Report

Date: 2026-04-23
Device used: OnePlus NE2211, device id `a5523839`, debug build

## Summary

The chat streaming-scroll bug was reproduced on the connected device, fixed in Compose, covered with unit and instrumentation tests, and smoke-tested again with the downloaded Gemma model. I also used the wider QA pass to fix a privacy-facing memory opt-in issue, harden long-generation timeout behavior, isolate instrumentation tests from the real downloaded model, and add an encrypted backup round-trip test.

In a follow-up pass, conversation management issues were found and fixed: the create-conversation flow had a race condition that always returned an invalid ID, the conversation list showed identical icons for every capability mode, and the Settings screen contained dead code from an earlier refactor. Each fix is covered by new unit tests.

In the 2026-04-23 image-analysis pass, the attached-device failure was reproduced and fixed. The app now accepts shared/picked image URIs without requesting unnecessary broad photo-library permission, waits for the Gemma engine to finish loading before starting image inference, sends PNG image bytes through LiteRT-LM's Conversation API, and surfaces image errors without saving empty assistant messages.

In the 2026-04-23 clean reinstall pass, `com.localyze` and `com.localyze.test` were uninstalled and the patched debug APKs were installed fresh on the attached phone. Fresh onboarding correctly detected that the model was missing, but this pass also found production-facing encoding artifacts in visible UI strings and two model-download resume bugs. Those are fixed and covered with new tests. After the model finished downloading on-device, the final patched app loaded Gemma on GPU, answered text prompts, streamed long output correctly, analyzed an image, and handled Stop generation as a normal cancellation instead of a false error.

In the 2026-04-23 Play Billing pass, the Settings subscription placeholders were replaced with a real Google Play Billing integration using Billing Library `8.3.0`. The app now queries the Play subscription product, launches Google Play checkout, restores active purchases, acknowledges completed subscription purchases, opens the Play subscription management page, and exposes premium entitlement state from active Play purchases. The configured product ID is `localyze_premium_yearly` unless overridden with Gradle property `LOCALYZE_PREMIUM_SUBSCRIPTION_PRODUCT_ID`.

## Main Bug: Streaming Scroll

Root cause:

- The old `LazyColumn` effect auto-scrolled to the streaming assistant item index on every token.
- Because the streaming assistant bubble grows downward, scrolling to the item index aligned the top of the long response instead of keeping its bottom/latest text visible.
- The forced animation also fought user drag gestures while streaming, so manual scrolling felt locked until generation completed.

Fix:

- Added a bottom-anchor item after all messages/tool/status/streaming content.
- Follow the bottom anchor only while the user is already at the bottom.
- Pause auto-follow as soon as user input scrolls away from the bottom.
- Resume follow when the user returns to the bottom or taps the `Scroll to bottom` button.
- Added stable LazyColumn keys for persisted messages, active tools, streaming content, and the anchor.
- Added IME padding for the composer so keyboard open/close does not hide the input area.

## Other Issues Fixed

- Long local generations timed out after 60 seconds. Raised the generation timeout to 180 seconds to avoid cutting off production-length model answers.
- Memory was labeled as opt-in, but the setting only expanded/collapsed the memory management UI. Added a real `memoryEnabled` setting that defaults off, hides the memory tool from the native model until enabled, and prevents prompt memory injection in mock/context-window paths while disabled.
- Instrumentation tests touched target app model storage and could remove the downloaded model. Updated data-layer instrumentation tests to use isolated temp model directories and in-memory Room databases.
- Existing inference policy could over-steer the model toward web-search refusal behavior. Kept thinking off by default, kept web search hidden unless explicitly enabled, and added policy regression coverage for stable general-knowledge answers.

### Image Analysis Fixes

- **Shared image intents could be dropped by a permission detour.** `ChatScreen` requested `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` even for an Android `content://` URI delivered through `ACTION_SEND`, where the sender grants temporary read access for that URI. The app could show a system permission surface and consume the share without retrying. Removed the broad media permission request for shared/picked image analysis.
- **Cold-start image requests used fallback instead of the model.** `generateResponseWithImage()` ran while model initialization was still in progress, saw `engine == null`, and produced the generic "model is loading" fallback instead of waiting. Added a 180-second engine-ready wait shared by text/image inference before deciding fallback/error behavior.
- **The wrong LiteRT-LM image API path was tested and rejected.** The lower-level `Session.generateContentStream(InputData.Image(...))` path expects a preprocessed image tensor and failed with `Image must be preprocessed before being used in SessionAdvanced`. The final fix follows Google's AI Edge Gallery pattern: `Conversation.sendMessageAsync(Contents.of(Content.ImageBytes(pngBytes), Content.Text(prompt)))`.
- **Image errors could look like silent success.** `SendMessageUseCase.sendMessageWithImage()` previously continued after `InferenceToken.Error`, saved an empty assistant message, and emitted `Completed`. It now stops on image errors and avoids empty assistant turns.
- **Image generation had weaker ViewModel safeguards than text.** `ChatViewModel.doSendImageMessage()` now uses the same long-generation timeout and explicit error handling style as text messages.

### Conversation Management Fixes

- **ConversationsViewModel.createConversation race condition.** The function launched a coroutine to create the conversation but returned a local variable immediately, so callers always received `-1L`. Changed the function to `suspend` so it returns the real conversation ID (or `-1L` only on actual failure).
- **ConversationItem capability mode icons broken.** Every conversation row showed the same `ChatBubbleOutline` icon regardless of mode. The old code rendered an invisible emoji text plus a static icon. Replaced it with a `when` expression that selects the correct Material icon for each mode (`Visibility`, `Edit`, `Lightbulb`, `Terminal`, `Assessment`, `Email`).
- **SettingsScreen.kt dead code.** An old `Column`-based UI block (~160 lines) was left behind wrapped in `if (false)` during a refactor to `SettingsReferenceContent`. Removed the dead block to reduce file size and prevent confusion.

### Clean Reinstall / Model Download Fixes

- **Visible mojibake in production UI.** Several emoji strings had been checked in as corrupted UTF-8, so onboarding feature cards, conversation headers, permission labels, voice-message text, debug title, and exported chat role labels could render as garbled text. Replaced visible runtime strings with ASCII-safe Unicode escapes or Material icons.
- **Retry download deleted resumable progress.** The error UI said progress was saved for resume, but `OnboardingViewModel.retryDownload()` deleted partial model files before restarting. Retry now resumes any valid preserved partial file.
- **Stale temp model files were not discarded safely.** If a temp file existed without matching saved URL/progress metadata, a fresh download could overwrite from byte 0 without first deleting/truncating the stale file. Added a tested start policy: valid partials are kept; invalid/stale partials are deleted before a fresh start.
- **Unit testing the onboarding retry path pulled in Java 21 LiteRT classes on JDK 17.** Added a small `ModelInitializer` abstraction so `OnboardingViewModel` can be tested without loading the heavyweight LiteRT engine class.
- **Stop generation was logged as an unexpected error.** The UI stopped correctly, but cancelling the generation coroutine flowed into the generic error handler and recorded a false error. Cancellation is now handled as a normal terminal state for text, image, audio, and regenerate flows.

### Play Billing Fixes

- **Subscription rows were placeholders.** `Localyze Premium`, `Restore purchase`, and `Manage subscription` no longer open About. They now call real Google Play Billing purchase/restore/manage flows.
- **Hardcoded subscription pricing was removed.** The Settings screen now shows the localized price returned by Google Play. This avoids mismatches with Play's user-facing billing UI.
- **Premium entitlement recovery was added.** Active subscriptions are resolved from `BillingClient.queryPurchasesAsync()` so purchases made outside the app or restored from Google Play are reflected in-app.
- **Purchase acknowledgement was added.** Completed subscription purchases are acknowledged through `BillingClient.acknowledgePurchase()` to avoid Play refund/revocation due to unacknowledged purchases.
- **Manifest billing metadata is present.** The merged debug manifest includes `com.android.vending.BILLING` and `com.google.android.play.billingclient.version=8.3.0`.

## Automated Tests Added

- `ChatScrollPolicyTest`
  - Bottom detection for empty lists, no-scroll lists, visible anchor, hidden anchor, and far-below-viewport anchor.
- `ChatMessageListScrollInstrumentationTest`
  - Incremental streaming updates keep the bottom anchor visible when following output.
  - User scroll-up during streaming pauses auto-follow and shows a resume button.
- `ChatTimeoutPolicyTest`
  - Guards the long-generation timeout so it does not regress below 180 seconds.
- `InferencePolicyRegressionTest`
  - Guards web-search gating, thinking defaults, and memory tool visibility behind opt-in.
- `BackupRepositoryInstrumentationTest`
  - Encrypted backup/export round trip for conversations, messages, memories, tasks, attachments, and reply drafts.
- `ConversationsViewModelTest` (26 tests)
  - Initial state, load conversations, create conversation (with/without custom title), update conversation, delete conversation, toggle pin/favorite/archive, filters (active/favorites/archived/all), search query, bulk archive/delete, clear all conversations, pinned/unpinned getters, folder filter, export single/selected, dialog state management, error clearing, and error propagation.
- `ChatViewModelTest`
  - Added image-message regressions for successful image response flow and image-error handling so image failures stop streaming and remain visible to the user.
  - Added stop-generation coverage that verifies cancellation preserves partial output, clears streaming state, and does not record an error.
- `OnboardingDownloadRetryTest`
  - Verifies retrying a model download resumes a valid partial and does not delete the temp file first.
- `DownloadStartPolicyTest`
  - Verifies stale temp-file handling for valid resume metadata, missing metadata, fresh-download requests, and absent temp files.
- `PremiumEntitlementPolicyTest`
  - Verifies active subscription entitlement, pending purchase state, unrelated purchases, and purchased-over-pending precedence.

## Device Verification

- Reproduced the old chat bug with a long photosynthesis prompt. The UI stayed at the top of the streaming assistant bubble and did not keep the newest tokens visible.
- Installed the patched debug app and tested a long streamed response on-device.
- Confirmed manual scroll during streaming is no longer yanked back to the bottom; the `Scroll to bottom` affordance appears.
- Confirmed tapping `Scroll to bottom` resumes following the newest content.
- Confirmed keyboard open during a long conversation leaves the composer reachable.
- Confirmed generation completed after the timeout increase.
- Deleted/redownloaded the model during this pass. Final model file on device:
  - `files/models/gemma-4-E4B-it.litertlm`
  - size: `3654467584` bytes
- Relaunched the final patched app over existing data. The app starts on chat, detects the model as downloaded, and reports model load state `Loaded`.
- Reproduced the image-analysis bug on the OnePlus device with a test image containing a red square, a blue circle, and `QA42`.
- Verified the fixed debug build through an `ACTION_SEND` image share using a real Android media `content://` URI.
- Confirmed the assistant responded correctly: it identified the red square, blue circle, and `QA42` text.
- Confirmed the model file remained installed after patch installation.
- Clean-uninstalled and reinstalled `com.localyze` and `com.localyze.test` on the OnePlus device.
- Confirmed fresh install starts in onboarding because `files/models/gemma-4-E4B-it.litertlm` is absent.
- Confirmed onboarding progresses to `Ready to Download` and checks storage/Wi-Fi correctly.
- Confirmed UTF-8 UI rendering for the onboarding feature icons after the mojibake fix.
- Started an in-app E4B model redownload on validated Wi-Fi. The first network attempts aborted, the UI showed a retryable error, and partial files were preserved.
- Verified the patched downloader discards an invalid stale temp file and saves valid resume metadata during the next attempt.
- Completed the fresh reinstall model download after the retry/resume fixes. Final model file on device:
  - `files/models/gemma-4-E4B-it.litertlm`
  - size: `3654467584` bytes
  - LiteRT cache files present for audio adapter, audio encoder, vision adapter, shader/program cache, and mldrift program cache.
- Confirmed the patched app auto-loads the downloaded model after launch: `Model auto-init complete, state=Loaded`, backend `gpu`.
- Verified a real-model short prompt after download: the app returned `READY42`.
- Verified a real-model long prompt after download: the photosynthesis response streamed to the bottom/latest content, completed, and the composer re-enabled.
- Verified real-model image analysis after download through an Android media `content://` URI. The assistant correctly identified the red square, blue circle, and `QA42` text in the test image.
- Verified Stop generation on the real device after the final patch. The partial answer remained visible, the composer re-enabled, and logcat showed normal cancellation (`Message generation cancelled`) without the previous false `Unexpected error during message generation`.

## Commands Run

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Result: passed.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
```

Result: passed.

After the image-analysis fix:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleDebugAndroidTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Result: unit tests passed; debug APK and androidTest APK assembled; patched debug APK installed on the attached OnePlus device. Manual image-share verification passed with the real downloaded Gemma model.

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w -e class com.localyze.ChatMessageListScrollInstrumentationTest com.localyze.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.localyze.BackupRepositoryInstrumentationTest com.localyze.test/androidx.test.runner.AndroidJUnitRunner
```

Result: focused chat scroll instrumentation passed: `OK (2 tests)`. Backup instrumentation test passed: `OK (1 test)`.

Earlier in this QA pass, after isolating the model/data tests:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Result: passed 63 connected tests on the OnePlus device. Note: on this setup, Gradle connected-test cleanup can uninstall the target package and remove app data, including the 3.6 GB model. Prefer a QA/emulator device for the full connected suite.

Release build check:

```powershell
.\gradlew.bat :app:assembleRelease
```

Result: Kotlin/Java release compilation reached packaging, then failed because release signing is not configured: `SigningConfig "release" is missing required property "storePassword"`.

Clean reinstall / model-download pass:

```powershell
adb uninstall com.localyze
adb uninstall com.localyze.test
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w com.localyze.test/androidx.test.runner.AndroidJUnitRunner
.\gradlew.bat :app:assembleRelease
```

Result: unit tests passed; debug/test APKs built and installed; connected instrumentation passed `OK (68 tests)`; release packaging failed because release signing is missing `storePassword`.

Final post-download patch and smoke pass:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.localyze.ChatViewModelTest
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat :app:assembleRelease
```

Result: focused ChatViewModel tests passed; full unit suite passed; debug APK built and installed over existing data; model file was preserved and auto-loaded on GPU; release packaging still failed only because release signing is missing `storePassword`.

Play Billing pass:

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest --tests com.localyze.PremiumEntitlementPolicyTest
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
.\gradlew.bat :app:assembleRelease
```

Result: Billing code compiled; premium entitlement tests passed; full unit suite passed; debug APK and androidTest APK were produced. Device install was blocked because `adb devices` showed no attached devices after ADB restarted. Release packaging still failed because release signing is missing `storePassword`.

Closed-testing device matrix pass:

```powershell
.\gradlew.bat :app:installDebug :app:installDebugAndroidTest
adb -s a5523839 shell am instrument -w com.localyze.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5556 install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w com.localyze.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5558 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5558 install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s emulator-5558 shell am instrument -w com.localyze.test/androidx.test.runner.AndroidJUnitRunner
.\gradlew.bat :app:bundleRelease
```

Result: latest debug APK and androidTest APK installed on the attached OnePlus NE2211 physical phone running Android 16 / API 36 / arm64-v8a, a Pixel 8a API 35 x86_64 emulator, and a Pixel 6 API 34 x86_64 emulator. Connected instrumentation passed `OK (68 tests)` on all three device targets. Release AAB generation still reached `:app:signReleaseBundle` and failed because release signing passwords are missing.

Closed-testing release artifact pass:

```powershell
.\.local-release-secrets.ps1
.\gradlew.bat :app:bundleRelease
```

Result: generated a fresh ignored upload key at `localyze-upload.keystore` without overwriting the existing `localyze-release.keystore`, configured Gradle to accept `LOCALYZE_KEYSTORE_FILE`, and produced a signed closed-testing bundle at `app\build\outputs\bundle\release\app-release.aab`. Upload key alias is `localyze`; SHA-256 certificate fingerprint is `37:09:9D:2A:95:76:57:DA:FE:91:C5:AF:3C:08:00:22:3D:F6:63:56:C5:12:8B:2A:B3:A6:6B:BA:06:BB:19:77`.

## UI / UX Issues Found (Not Fixed)

- **Safety report placeholder in Settings.** The "Report / Flag response" row under "Support & Safety" opens the About screen instead of a real reporting flow. No report/flag UI exists in the chat message action menu either.
- The report/flag placeholder should either be wired to a real flow, hidden behind a feature flag, or removed before a production candidate ships, so users are not misled.

## Features Covered

- Chat send flow.
- Long streaming answer follow behavior.
- User manual scrolling during streaming.
- Resume-to-bottom behavior.
- Keyboard open with long answer.
- Model download, installed-file detection, and auto-load state.
- Conversation data CRUD through repository/instrumentation coverage.
- Conversation management UI: create, rename, delete, search, pin/favorite/archive, export, folder filter, bulk actions.
- Memory CRUD through existing repository coverage.
- Memory opt-in default and tool visibility through new regression coverage.
- Encrypted backup/export/import through new instrumentation coverage.
- Attachment persistence as backup data. Real file picker import still needs manual verification.
- Input validation (XSS, length, sanitization).
- Tool confirmation classification for high-risk actions.
- Google Play Billing client integration: product query, purchase launch, restore/query purchases, active entitlement resolution, purchase acknowledgement, and subscription management deep link.

## Remaining Manual Testing

- Closed-testing upload to Play Console from a normal signed-in browser, or via a Google Play Developer API service-account upload path.
- Google Play Billing end-to-end purchase test from an internal testing track. This cannot be fully tested from a sideloaded debug APK.
- Confirm Play Console has an active subscription product with ID `localyze_premium_yearly`, or build with `-PLOCALYZE_PREMIUM_SUBSCRIPTION_PRODUCT_ID=<your_product_id>`.
- Confirm Play Console base plan/offer pricing matches the intended Premium offer.
- Confirm tester account can purchase, cancel, restore, and manage the subscription from the Play Store.
- Full rotation/config-change pass during active streaming.
- Regenerate with the real model across several prompt lengths.
- Real Android file-picker attachment import for text, image, and unsupported file types.
- Backup restore into a separate fresh install, not only in-memory database.
- Release signing and Play Store production build validation.

## Risks

- The memory opt-in change is privacy-favorable but changes behavior for existing users: saved memories remain stored, but the assistant will not use the memory tool unless the user enables Memory in Settings.
- The native model loader logs a transient missing-native-method message before the LiteRT library loads successfully. The final launch smoke showed model state `Loaded`, but this should stay on the release-watch list.
- Full connected instrumentation can wipe the installed app/model on the current device setup. Run it on a disposable QA device/emulator unless you are ready to reinstall and redownload the model.
- Play Billing purchase verification is client-side only in this build. For stronger fraud resistance and cross-device entitlement sync, add a backend with Google Play Developer API verification and Real-time Developer Notifications.
- Report/flag UI rows in Settings are non-functional. If a production candidate is cut before these are implemented, the row should be hidden or labeled as coming soon.
- The signed AAB now exists for closed testing, but browser automation to Play Console is blocked by Google sign-in security. Upload must be completed in a normal signed-in browser session or with a configured Play Developer API service account.
- The new `localyze-upload.keystore` must be retained for future updates if this AAB is accepted by Play App Signing.

## 2026-04-23 Play Console Compliance Follow-up

- Play Console rejected the earlier closed-testing bundle because the app still declared camera access. `android.permission.CAMERA` and the stale camera-settings entry were removed from the release.
- Play Console then required a foreground-service declaration for release notification code. The `FOREGROUND_SERVICE_DATA_SYNC` release path and related service registrations were removed from the manifest.
- Play Console later flagged broad photo-library access because the app still declared `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` even though image input already used Android picker/share flows. Those broad media permissions were removed from the manifest.
- A new signed bundle was built and uploaded as `4 (1.0.3)`. The older `3 (1.0.2)` artifact was removed from the draft to avoid the shadowed-version error.
- The current release review state shows **no blocking errors** for the uploaded bundle. The remaining Play warning is the non-blocking deobfuscation-file warning.
- Publishing overview still contains app-content changes that should be reviewed carefully before sending to Google, including privacy policy, Data safety, ads declaration, content rating, and target-audience items.
- A privacy policy draft matching the codebase was added at `PRIVACY_POLICY.md`, but Play still needs a real public URL before this can be treated as fully ready for store review.

### Latest release build

- Version code: `4`
- Version name: `1.0.3`
- Artifact: `app/build/outputs/bundle/release/app-release.aab`

### Latest release command

```powershell
& { . .\.local-release-secrets.ps1; .\gradlew.bat :app:testDebugUnitTest :app:bundleRelease }
```

Result: passed, producing the signed `1.0.3` AAB used for the current closed-testing draft.
