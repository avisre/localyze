# Localyze Feature Verification and Roadmap

Last updated: 2026-04-21

Localyze is the only user-facing brand name for this app. Previous visible names have been replaced in app strings, notifications, onboarding, chat, settings, exports, and the launcher assets. Internal package names such as `com.localassistant` are still unchanged because changing the Android package ID is a separate release migration decision.

## Verification Baseline

Device tested:

| Item | Value |
| --- | --- |
| Device | OnePlus NE2211 |
| Android | 16, API 36 |
| Serial | a5523839 |
| App id | com.localassistant |
| App version | 1.0, versionCode 1 |
| Min SDK | 28 |
| Target SDK | 35 |

Automated checks:

| Check | Status | Evidence |
| --- | --- | --- |
| Debug build | PASS | `./gradlew.bat assembleDebug` |
| Unit tests | PASS | `./gradlew.bat testDebugUnitTest` |
| Connected device instrumentation | PASS | `./gradlew.bat connectedDebugAndroidTest`, 61 tests on NE2211 |
| Install on phone | PASS | `adb install -r app/build/outputs/apk/debug/app-debug.apk` |
| Cold launch on phone | PASS | `adb shell am start -W -n com.localassistant/.MainActivity`, status OK, total time 799 ms |
| UI text dump | BLOCKED | Phone is PIN locked, so `uiautomator` only returned the keyguard hierarchy |

## Current Feature Status

| Feature | Status | What is verified | Release note |
| --- | --- | --- | --- |
| Localyze branding | PASS | Visible string scan found no previous public brand names in app source | Internal package/class names remain stable for Android compatibility |
| App logo | PASS | Launcher foreground, launcher background, notification icon, and in-app mark use the green Localyze-style mark | Exact PNG import can be added later if the vector approximation is not enough |
| One model only | PASS | `ModelRepository` exposes only Gemma 4 E4B for production download/load paths | This keeps the MVP simple and aligned with the privacy pitch |
| App install and launch | PASS | Debug APK installs and cold launches on the connected OnePlus | Full visual UI verification needs the phone unlocked |
| Chat persistence | PASS | Instrumentation tests create conversations, save messages, delete messages, update titles, and verify conversation isolation | This supports ChatGPT/Claude-style saved chats at the data layer |
| Conversation CRUD | PASS | Connected tests cover create, update, delete, search-adjacent data behavior, and cascade delete | More UI tests should cover rename, archive, pin, and bulk delete flows |
| Library screen | BUILD PASS | Code compiles with Library navigation and conversation list UI | Manual unlocked-device testing is still needed |
| Memory storage | PASS | Connected tests save, search, list, and delete memories through the repository | The MVP should expose memory controls clearly and keep memory opt-in |
| Model download state | PASS | Connected tests verify model file detection, delete behavior, storage checks, network type checks, and resumable download metadata | Real 3.65 GB download was not rerun in this pass |
| Download resume safety | PASS | Test now verifies a temp file plus saved URL metadata, matching production behavior | A random temp file alone is not treated as resumable |
| Voice input transcript path | BUILD PASS | Speech-to-text code compiles and routes transcripts into the text model path | Needs manual mic permission and speech-recognizer testing on an unlocked phone |
| Image input path | BUILD PASS | Image share/picker and image response paths compile | Needs manual picker/share testing on an unlocked phone |
| Attachments and local file memory | PARTIAL | Attachment memory repository exists and text/image summaries can be stored | PDF/text/image search with local embeddings should be a roadmap item, not MVP-critical |
| Email/SMS/calendar/alarms | PARTIAL | Intent/service code exists, but these flows depend on installed apps and permissions | Keep hidden or clearly gated until manual end-to-end tests pass |
| Web search | NOT MVP | The privacy-focused release should not depend on internet search | Leave disabled or gated behind a clear opt-in |
| Backups/export | BUILD PASS | Backup/export code compiles and chat export branding is Localyze | Manual backup, restore, and encrypted export tests are required |
| Billing/subscription | NOT READY | Settings show a premium row, but Play Billing is not implemented | Cannot charge `$79/year` in production until Play Billing is wired and tested |

## Android Compatibility Policy

The app should launch on every supported Android 9+ device, but the local Gemma 4 E4B model cannot honestly run on every Android phone. The model requires significant RAM and storage, and some devices will not have compatible acceleration.

Compatibility target:

| Area | Policy |
| --- | --- |
| Install | Support Android 9+ with optional camera and optional microphone hardware |
| Launch | App should open without crashing even if the device cannot run the model |
| Model chat | Enable only when RAM, storage, and model runtime checks pass |
| Unsupported devices | Show a clear local-only compatibility screen instead of crashing |
| Offline privacy | Keep normal chat and saved conversations local after setup |

Current compatibility changes:

| Change | Reason |
| --- | --- |
| Camera hardware marked optional | Prevents Play/device filtering for devices without cameras |
| Microphone hardware marked optional | Prevents filtering for devices without voice input hardware |
| Main activity marked resizable | Improves behavior on tablets, foldables, desktop mode, and multi-window |
| Native accelerator libraries marked optional | Lets the app install when OpenCL/DSP libraries are unavailable |

## MVP For A Privacy-Focused Play Release

Ship only the features that are reliable, explainable, and privacy-aligned:

1. On-device Gemma 4 E4B text chat.
2. Saved conversations with create, read, update title, delete, pin, archive, and search.
3. Local memory that is off by default, with review, edit, and delete controls.
4. Clear model setup flow with Wi-Fi warning, storage warning, RAM warning, and resume support.
5. Local data controls: delete all chats, delete memories, delete model, export conversations.
6. Voice input only if manual device tests pass; otherwise mark it beta or hide it.
7. Image input only if manual picker/share tests pass; otherwise mark it beta or hide it.
8. No internet search in the default product.
9. No auto-send email, SMS, calendar, or alarm actions in the MVP.
10. Play Billing annual subscription before any production paid release.

## Must Fix Before Production

| Priority | Item | Why |
| --- | --- | --- |
| P0 | Implement Google Play Billing for the `$79/year` subscription | The current subscription UI is not a real paywall |
| P0 | Add a privacy policy and Play Data Safety answers | Required for Play production, especially with microphone/media permissions |
| P0 | Decide whether agentic permissions ship | Contacts, calendar, notification listener, and exact alarm permissions raise review and trust risk |
| P0 | Add real model integrity verification | `SHA256_HASH` is currently empty |
| P0 | Run full unlocked-device UI smoke tests | Current automated data tests pass, but manual UI hierarchy was blocked by lock screen |
| P1 | Add Compose UI tests for Chat, Library, Settings, and onboarding | Protects the production UI from regressions |
| P1 | Add crash and timeout diagnostics | Needed for heavy on-device model failures |
| P1 | Test real model download, resume, load, and one prompt on a fresh install | Data tests do not prove a full 3.65 GB download on every network |
| P1 | Verify backup/restore round trip | Important for privacy-conscious users who own their data |
| P2 | Rename package only if needed | Package renaming affects Play identity and installed-app migration |

## Feature Roadmap

Near term:

1. Production chat CRUD UI: rename, pin, archive, folders/projects, favorites, bulk delete, and export.
2. Better search: search titles, messages, folders, favorites, and archived chats.
3. Memory center: opt-in toggle, saved memory list, edit/delete, and "what do you remember about me?"
4. Privacy center: one screen for model file, chats, memory, backups, permissions, and deletion.
5. Local backups: encrypted export and restore for chats, memories, and settings.
6. Voice command flow: mic input, transcript preview, edit before send, then pass transcript to Gemma.
7. Attachment memory: import text/PDF/images, summarize locally, and search later.

Later:

1. Tool approval center: every tool, permission status, risk level, and recent history.
2. Safe reply assistant: notification listener plus draft-only email/SMS replies.
3. Calendar and alarm drafts: never create or send without user approval.
4. Local embeddings for document search.
5. Performance dashboard: model load time, tokens/sec, RAM pressure, backend, and crash diagnostics.
6. Proactive reminders and task follow-ups, all permission-gated.
7. Optional web search as an explicit opt-in feature, not part of the privacy-first default.

## Manual Device Test Checklist

Run these on an unlocked phone before Play production:

1. Fresh install from debug/release build.
2. Launch and confirm Localyze logo/name are visible.
3. Complete model setup or verify unsupported-device state.
4. Start a new chat.
5. Send a text prompt and receive a Gemma 4 E4B response.
6. Close and reopen the app, then confirm the chat persists.
7. Rename, pin, archive, search, and delete a conversation.
8. Enable memory, save a memory, view it, edit it, delete it, and clear all memories.
9. Test voice input: grant microphone permission, speak, inspect transcript, send to model.
10. Test image input: pick/share an image and verify the app stores/sends it correctly.
11. Export conversations and inspect the generated file.
12. Restore from backup on a clean install.
13. Delete all chats and verify Library is empty.
14. Delete model and verify setup returns to model-needed state.
15. Rotate screen, use split-screen, and test small/large font settings.
