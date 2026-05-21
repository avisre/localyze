# Localyze.ai — Play Console Data Safety Draft

_Last updated: 2026-05-20. Paste into the Play Console Data Safety form._

## Security practices
- **Encrypted in transit?** Yes (TLS 1.2+ for HuggingFace, web-search, Play Billing, Firebase).
- **Encrypted at rest?** Yes (chat DB via SQLCipher; model in app-private storage).
- **User can request deletion?** Yes — in-app "Clear all chats" / "Remove model"; uninstall clears all app-private data.
- **Independent security review?** No.

## Data collected & shared (per Play category)

### Personal info
Name, email, address, phone, race, sexual orientation, other: **Not collected.**

### Financial info
- **Purchase history**: Handled by Google Play Billing for subscription management. Not collected by us, not shared by our app. Encrypted in transit.
- Payment info, credit score: Not collected.

### Location
Approximate / precise: **Not collected.**

### Web browsing
**Not collected.** Web-search queries (when opted in) are forwarded to a public search endpoint and not stored.

### App activity
- **App interactions**: Collected via Firebase Analytics (lifecycle/screen events). Not shared. Optional. Purpose: Analytics.
- **In-app search history**: Not collected/stored.
- **User-generated content (chats, voice, memories)**: **Not collected** — fully on-device.
- Installed apps, other actions: Not collected.

### App info and performance
- **Crash logs**: Firebase Crashlytics (opt-out). Not shared. Encrypted in transit. Purpose: App functionality.
- **Diagnostics / other performance data**: Same as crash logs.

### Device or other IDs
- **Device or other IDs**: Firebase install ID for Crashlytics/Analytics. Not shared. Optional (disabled on opt-out). Purpose: Analytics, App functionality.

### Messages, Photos/videos, Audio, Files, Calendar, Contacts, Health, Fitness
**Not collected.** Voice is transcribed on-device and discarded.

## Third parties
- **Google Play Billing + Play Integrity** — subscriptions and verification.
- **Firebase Crashlytics + Analytics** — currently a **placeholder Firebase project**; once a real project is connected before publish, the disclosures above apply.
- **HuggingFace** — model file download only, no account, no PII.
