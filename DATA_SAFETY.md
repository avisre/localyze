# Google Play Data Safety Disclosure — Localyze

> This file maps every collected data type to the exact answers required by the Google Play Console Data Safety form.
> Last updated: 2026-05-07

---

## Data Types Collected

| Data type | Collected | Shared | Encrypted in transit | Required | Purpose |
|-----------|-----------|--------|---------------------|----------|---------|
| **Location** | No | — | — | — | — |
| **Name** | Yes (optional) | No | N/A | No | Contacts search tool reads contact names locally |
| **Email address** | Yes (optional) | No | N/A | No | Email draft tool uses system compose; no server upload |
| **Phone number** | Yes (optional) | No | N/A | No | SMS draft tool uses system compose; no server upload |
| **Contacts** | Yes (optional) | No | N/A | No | Contact search tool reads local contacts on-device |
| **Photos and videos** | Yes (optional) | No | N/A | No | User-selected images for vision analysis; stored locally |
| **Audio files** | Yes (optional) | No | N/A | No | Voice input recorded for transcription; stored locally |
| **Calendar events** | Yes (optional) | No | N/A | No | Calendar tool reads/writes local calendar data |
| **Notification content** | Yes (optional) | No | N/A | No | Notification reply-drafts feature reads notification text locally |
| **App interactions** | Yes | No | N/A | No | Crash reporting (Firebase Crashlytics, opt-out) |
| **In-app search history** | Yes | No | N/A | No | Web search queries sent to DuckDuckGo when enabled |
| **Purchase history** | Yes | No | Yes | No | Google Play Billing for subscription management |
| **Other app performance data** | Yes | No | Yes | No | Firebase Crashlytics crash logs (opt-out) |

---

## Play Console Form Answers

### Data Collection & Security

1. **Does your app collect or share any user data?** — Yes
2. **Is all user data encrypted in transit?** — Yes (TLS 1.2+ for all network endpoints)
3. **Does your app provide a way for users to request deletion of their data?** — Yes (in-app delete options + uninstall removes all app-private data)

### Data Types

#### Personal Info
- **Name** — Collected, not shared, not required, processed ephemerally (contact search)
- **Email address** — Collected, not shared, not required, processed ephemerally (email draft)
- **Phone number** — Collected, not shared, not required, processed ephemerally (SMS draft)

#### Contacts
- **Contacts** — Collected, not shared, not required, processed ephemerally (contact search tool)

#### Calendar
- **Calendar events** — Collected, not shared, not required, processed ephemerally (calendar tool)

#### Photos & Videos
- **Photos** — Collected, not shared, not required, processed ephemerally (vision mode, code workspace)
- **Videos** — Not collected

#### Audio Files
- **Voice or sound recordings** — Collected, not shared, not required, processed ephemerally (voice input)

#### App Activity
- **In-app search history** — Collected (web search queries to DuckDuckGo), not shared, not required
- **Installed apps** — Not collected

#### App Info & Performance
- **Crash logs** — Collected (Firebase Crashlytics), not shared, encrypted in transit, opt-out available
- **Diagnostics** — Collected (Firebase Crashlytics), not shared, encrypted in transit, opt-out available
- **Other app performance data** — Collected (Firebase Crashlytics), not shared, encrypted in transit, opt-out available

#### Device IDs
- **Device or other identifiers** — Not collected

#### Purchase History
- **Purchase history** — Collected (Google Play Billing), not shared, encrypted in transit, not required

---

## Third-Party SDKs

| SDK | Purpose | Data types | Privacy policy |
|-----|---------|-----------|--------------|
| Google Play Billing Library | Subscription purchases | Purchase history | https://policies.google.com/privacy |
| Firebase Crashlytics (optional, opt-out) | Crash reporting | Crash logs, diagnostics | https://policies.google.com/privacy |

---

## Notes for Play Console Reviewer

- Core AI inference (text, image, audio) runs entirely on-device using Google's Gemma 4 E4B model.
- No user data is sent to the app developer's own servers.
- Web search queries are sent to DuckDuckGo's public APIs only when the user explicitly enables the feature in Settings.
- Contact, calendar, and notification access are all optional and gated by Android runtime permissions.
- Notification content is read locally by a `NotificationListenerService` only when the reply-drafts feature is enabled.
- All chat history, memories, and attachments are stored in local Room database and files; no cloud sync.
