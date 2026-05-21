# Localyze.ai Privacy Policy

_Last updated: 2026-05-20_

Localyze.ai is a privacy-first Android assistant. Chat runs entirely on your phone via an on-device LLM (Gemma 4 E4B). This policy explains what stays local, what touches the network, and how to control it.

## 1. On-device processing
All chat prompts, responses, and voice-to-text are processed locally by the Gemma 4 E4B model. **Your conversation content never leaves your phone** — we operate no chat servers.

## 2. Local storage
Chat history, settings, memories, and attachments live in a local Room database **encrypted at rest with SQLCipher**. Model weights stay in your app-private files directory. Nothing in this database is uploaded.

## 3. Audio
Voice input is captured and transcribed on-device. Raw audio is discarded after transcription and never transmitted.

## 4. Optional network activity
- **Model download** (~3.6 GB, one time) from HuggingFace via HTTPS.
- **Web search tool**: only when you enable it in Settings and the model invokes it. Queries go to a public search endpoint over HTTPS. Disabled by default.
- **Google Play Billing** for the optional "Localyze Premium" subscription, plus **Play Integrity** for purchase verification.
- **Firebase Crashlytics** (opt-out) and the Firebase Analytics dependency. The build currently uses a **placeholder Firebase project**, but the Crashlytics SDK is active and **may collect crash stack traces, device model, OS version, and a Firebase install ID** once a real project is wired. Disable in Settings → Privacy.

All traffic uses TLS 1.2+.

## 5. We do NOT collect
Advertising IDs, location, contacts, chat content, prompt/response telemetry, or third-party ad SDKs.

## 6. Retention & deletion
- **Clear chats**: Settings → Data → Clear all chats.
- **Delete model**: Settings → Storage → Remove model file.
- **Full wipe**: uninstall — Android removes all app-private data.

## 7. Children
Not directed at children under 13.

## 8. Contact
Questions or deletion requests: **report@localyze.ai**
Full policy: **[YOUR-WEBSITE]/privacy**
