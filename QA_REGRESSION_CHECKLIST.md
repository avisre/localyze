# QA Regression Checklist

Use this before a production candidate, especially after chat UI, model, storage, or settings changes.

## Chat Streaming

- [ ] Send a short text prompt and confirm the response renders normally.
- [ ] Send a long prompt and confirm newest streaming text stays visible while already at bottom.
- [ ] During streaming, drag upward and confirm the list does not fight the gesture.
- [ ] Confirm the `Scroll to bottom` button appears after scrolling away from the bottom.
- [ ] Tap `Scroll to bottom` and confirm following resumes.
- [ ] Test a long multiline answer and a long code-block answer.
- [ ] Open and close the keyboard during streaming; composer remains visible and usable.
- [ ] Send multiple back-to-back prompts; no clipping, overlap, or scroll reset.
- [ ] Stop generation while streaming, then send another prompt.
- [ ] After Stop generation, confirm the partial answer remains visible, the composer re-enables, and cancellation is not recorded as an error.
- [ ] Regenerate the last assistant response.
- [ ] Rotate the device during active streaming if rotation is supported for the build.

## Model And Onboarding

- [ ] Fresh install with no model shows onboarding/download state.
- [ ] Download progress updates without freezing.
- [ ] Cancel/retry download if network changes.
- [ ] If a retryable download error occurs, tap Retry and confirm a valid partial resumes instead of restarting at 0.
- [ ] If a temp download file exists with missing/mismatched metadata, confirm the app discards it before starting a clean download.
- [ ] Completed download produces the expected model file under app storage.
- [ ] Relaunch after download auto-loads the model and starts on chat.
- [ ] Delete model from Settings and confirm the app returns to a missing-model state.

## Conversation Management

- [ ] Create a new chat.
- [ ] Open conversation history/library.
- [ ] Rename a conversation.
- [ ] Delete a conversation.
- [ ] Search conversations.
- [ ] Pin/favorite/archive only where the controls are present and stable.
- [ ] Export a single conversation and selected conversations.

## Memory And Local Data

- [ ] Memory is off by default on a fresh install.
- [ ] With Memory off, the model should not be offered the memory tool.
- [ ] Enable Memory in Settings.
- [ ] Ask what the assistant remembers and verify saved-memory transparency text.
- [ ] Save a memory through the confirmation flow.
- [ ] Search/review saved memories.
- [ ] Edit a memory.
- [ ] Delete a memory.
- [ ] Clear all memories.
- [ ] Delete all chats and confirm local data behavior matches the UI copy.

## Attachments

- [ ] Share an image into the app from Android Photos/Files and confirm the assistant analyzes the image.
- [ ] Cold-start the app from an image share and confirm it waits for model load instead of returning a generic loading fallback.
- [ ] Attach an image with the in-app picker and confirm the assistant analyzes it.
- [ ] Confirm image analysis does not request broad photo-library permission when a specific picker/share URI is already granted.
- [ ] If image inference fails, confirm the UI shows an error and does not save an empty assistant response.
- [ ] Add a text file through the Android file picker.
- [ ] Confirm unsupported files are saved only as references and are not marketed as extracted.
- [ ] Search saved attachments.
- [ ] Delete an attachment.
- [ ] Confirm attachment metadata survives encrypted backup/restore.

## Backup And Restore

- [ ] Export encrypted backup with a valid passphrase.
- [ ] Reject too-short passphrases.
- [ ] Reject invalid backup text.
- [ ] Reject wrong passphrase on import.
- [ ] Import into a fresh install and verify conversations, messages, memories, tasks, attachments, and drafts.

## Subscription And Entitlement

- [ ] Confirm Play Console subscription product ID is `localyze_premium_yearly`, or build with `LOCALYZE_PREMIUM_SUBSCRIPTION_PRODUCT_ID` set to the active product ID.
- [ ] Confirm the Play Console subscription has an active base plan/offer and intended pricing.
- [ ] Install from an internal testing track with a licensed tester account.
- [ ] Open Settings and confirm `Localyze Premium` shows the localized Google Play price, not a hardcoded price.
- [ ] Tap `Localyze Premium` and complete the Google Play checkout flow.
- [ ] Confirm Premium state changes to active after purchase.
- [ ] Confirm completed purchases are acknowledged in Play Console / purchase state.
- [ ] Cancel the subscription in Google Play and confirm app entitlement updates after restore/query.
- [ ] Tap `Restore purchase` and confirm an active subscription is recovered.
- [ ] Tap `Manage subscription` and confirm it opens Google Play subscription management.
- [ ] Verify any locked feature fails gracefully if premium gating is added.

## Safety

- [ ] Verify any visible report/flag control opens a real flow.
- [ ] If no report/flag UI exists, do not advertise it in product copy.
- [ ] Confirm tool confirmations appear for high-risk actions such as memory save, clipboard write, task creation, calendar, contacts, and alarms.

## Release Gate

- [ ] `.\gradlew.bat :app:testDebugUnitTest`
- [ ] `.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest`
- [ ] Run connected tests on at least one physical ARM device.
- [ ] Run connected tests on at least one Google APIs emulator for the previous supported Android version.
- [ ] Run connected tests on at least one Google APIs emulator for the current target Android version.
- [ ] `.\gradlew.bat :app:assembleRelease`
- [ ] Confirm release signing credentials are configured.
- [ ] Confirm the signed release AAB is generated after signing credentials are present.
- [ ] Preserve the upload keystore and password source used for the accepted Play release so future updates can be signed with the same upload key.
- [ ] Upload only to the Play Console closed-testing track unless production rollout is explicitly approved.
- [ ] Install the release candidate and run the chat streaming checklist with the real downloaded model.
