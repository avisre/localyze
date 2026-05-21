# Play Console Upload Checklist — Localyze.ai

Use this with version `1.0.5` (`versionCode 6`). Every item below has historically caused rejections; check each before Submit for Review.

---

## 1. Artefact

- **Upload:** `app/build/outputs/bundle/release/app-release.aab`
- **APK is not accepted** for new uploads — must be AAB.
- **App signing:** enroll in Play App Signing (Google holds the upload key, you keep the signing key). If prompted, accept.

## 2. Version

- versionCode `6` (up from `5`).
- versionName `1.0.5` (up from `1.0.4`).
- If previous rejection was on `5`, this build must be `6` or higher.

## 3. Removed in this build — what previously got it rejected

| Removed | Why |
|---|---|
| `NotificationReplyListenerService` manifest entry | `BIND_NOTIFICATION_LISTENER_SERVICE` is restricted to wearable / driving / accessibility / default SMS apps. AI reply-drafting does not qualify. Class file kept as dead code for later reinstatement after a Permission Declaration Form is approved. |

## 4. Restricted permissions — must declare in Play Console

Each permission below requires you to **fill the Permissions Declaration Form** in Play Console (Policy → App content → Sensitive app permissions).

| Permission | Justification text to paste |
|---|---|
| `READ_CONTACTS` | "User-invoked AI tool to look up a contact by name when drafting messages or scheduling calls. The model only reads contacts after the user explicitly asks (e.g. 'message John'). No contact data leaves the device." |
| `READ_CALENDAR` / `WRITE_CALENDAR` | "User-invoked AI tools to read upcoming events and add new events when the user asks (e.g. 'what's on my calendar this week', 'add a meeting on Friday at 3'). All access is on user request; no background sync." |
| `RECORD_AUDIO` | "Voice input. The user taps a microphone button to dictate a message. Audio is transcribed entirely on-device by the bundled speech-to-text model — nothing is uploaded." |
| `CAMERA` | "User-invoked image input. The user taps a camera icon to attach a photo to the AI chat for visual analysis. All vision inference runs on-device." |
| `SCHEDULE_EXACT_ALARM` | "Alarms set by the AI assistant on user request (e.g. 'remind me at 7am'). User can revoke in system Special App Access settings; the app degrades gracefully to inexact alarms when revoked." |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | "Two foreground services: model download (3.4 GB first-run download) and model loading (memory-mapping the model into the inference engine). Both are user-initiated and visible to the user in the notification shade while running." |

## 5. Data Safety form — paste from `PLAY_DATA_SAFETY.md`

- Chat content, audio, images, contacts, calendar entries → all marked **Processed on-device, not collected**.
- Crashlytics → optional crash reports (disclosed).
- Analytics → optional usage analytics (disclosed).
- Billing → handled by Google Play Billing (Google's own data flow, not yours).
- **All data is encrypted at rest** (SQLCipher) — check that box.
- **In-transit encryption** — yes, HuggingFace download is HTTPS.

## 6. Privacy policy

- **Required:** public URL pointing to the text in `PRIVACY_POLICY.md`.
- Host as GitHub Pages, Notion-public, Vercel, or any static-host. Plain HTTPS URL.
- Paste URL in Play Console → Policy → App content → Privacy policy.

## 7. Content rating questionnaire

- Audience: 13+.
- AI content: yes. Source: Google Gemma 4. Generates: text, on user prompt only.
- No violence, no sexual content, no gambling. (AI output is filtered by the model's built-in safety.)

## 8. App access

- **No login wall** — reviewers can use the app fully on first run.
- "Get Started" → "Choose Model" → "Download Model" — Google reviewer must be able to wait 3-5 min for download.
- **Add reviewer instruction** in Play Console → App content → App access:
  > "First run downloads a 3.4 GB AI model from HuggingFace. Please ensure a stable Wi-Fi connection. After download completes, chat is available immediately with no signup."

## 9. Generative AI Policy compliance

- ✓ AI label visible on every assistant bubble ("AI · Localyze").
- ✓ Report button (`Flag` icon) on every assistant bubble; opens email to `report@localyze.ai`.
- ✓ Model attribution in privacy policy ("Powered by Google's Gemma 4").
- ✓ No image/audio generation (text-only output).
- **Action item:** declare in Play Console → App content → Generative AI:
  - "User-generated prompt" → "Text generation"
  - Safety mechanism: "Built-in Gemma 4 safety filters + user reporting"

## 10. Pre-launch report (Play Console runs this automatically)

- Tests cold start on real devices.
- **Risk:** if the model download URL is blocked from Google's test farm, "Get Started" → "Download" may hang. The app handles this with a retry button; this should pass review, but watch the pre-launch report.

## 11. Crashlytics / Firebase

- Current `app/google-services.json` is a **placeholder** (project_id: `localyze-placeholder`). Crashlytics will silently fail at runtime — no rejection risk, but you also get no crash visibility.
- **Recommendation before publishing:** replace with a real Firebase project, OR strip Crashlytics from `app/build.gradle.kts`. Either is fine for Play approval.

## 12. Final pre-submit smoke

Run on the OnePlus 10 Pro (`adb -s a5523839`) one last time after upload artifact is built:
1. Uninstall any previous build.
2. Install the AAB-extracted APK with `bundletool` (or just install the release APK from `app/build/outputs/apk/release/`).
3. Cold start → "Get Started" → "Choose Gemma 4 E4B" → "Download Model" → wait → first chat message → confirm reply lands cleanly with no `<thought>` leak.

If all 12 items above are green, the upload should clear review on the first pass.
