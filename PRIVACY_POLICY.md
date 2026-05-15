# Localyze Privacy Policy

Last updated: April 23, 2026

Localyze is a privacy-first Android app that provides an on-device AI assistant powered by Google's Gemma 4 E4B model.

## Summary

- Core text, image, and audio inference runs locally on your device.
- Your chats, memories, tasks, attachments, and model files are stored locally on your device unless you export or back them up yourself.
- We do not sell your personal data.
- We do not run advertising SDKs in the app.
- We do not use Stripe. Subscription billing is handled by Google Play.

## Data the app may access

Depending on the features you choose to use, Localyze may access:

- Microphone audio for voice input
- Photos or files that you explicitly select with Android system pickers or share into the app
- Contacts for the contact-search tool
- Calendar data for reading or creating calendar events
- Notification content if you enable the notification reply-drafts feature (the app reads notification text to suggest quick replies)
- Exact alarms if you enable alarm or reminder features
- Local app data such as chats, memories, tasks, attachments, reply drafts, backups, and model files

## How data is handled

### 1. On-device assistant processing

Localyze is designed so that assistant inference happens locally on your device. Your chat prompts, generated responses, selected images, and audio inputs are processed on-device by the local model for the app's core assistant features.

### 2. Notification content access

If you enable the notification reply-drafts feature, the app uses a `NotificationListenerService` to read the text content of incoming notifications. This is used only to generate draft replies and stays entirely on your device. You can disable this feature at any time in Settings, and the listener service will stop collecting notification content.

### 3. Local storage

The app stores data locally on your device to provide its features. This can include:

- Conversations and messages
- Saved memories and tasks
- Attachment metadata
- Downloaded model files and related caches
- Local backups or exports that you choose to create

### 3. Optional network activity

Although the assistant itself is designed for on-device use, some app features use the network:

- Model download: the local model file is downloaded from Hugging Face when you choose to install it
- Optional web search: if you enable web search in Settings, search queries are sent to DuckDuckGo's Instant Answer API to fetch results
- Google Play Billing: subscription purchase, restore, entitlement, and management flows communicate with Google Play

## Data sharing

We do not sell your personal data.

We do not share your chats, memories, contacts, calendar data, selected images, or audio recordings with the app developer's own servers for analytics, advertising, or profiling.

Data may be sent to third-party services only when needed for features you choose to use, such as:

- Hugging Face for model download
- DuckDuckGo for optional web search queries
- Google Play for billing and purchase management

## Permissions

Localyze may request permissions only for features you use:

- `RECORD_AUDIO` for voice input
- `READ_CONTACTS` for contact search
- `READ_CALENDAR` and `WRITE_CALENDAR` for calendar features
- `POST_NOTIFICATIONS` for notification features on supported Android versions
- `SCHEDULE_EXACT_ALARM` for alarm and reminder features

Photo and file selection uses Android system picker/share flows where possible.

## Your choices

You can:

- Decline optional permissions
- Keep memory disabled
- Keep web search disabled
- Delete the local model from Settings
- Delete chats and memories from within the app
- Uninstall the app to remove app-private local data stored by Android

## Children

Localyze is intended for adults and is not designed for children.

## Security

We aim to minimize data access and keep core assistant processing on-device. No method of storage or transmission is perfectly secure, but the app is designed to reduce unnecessary data transfer.

## Contact

For privacy questions about Localyze, use the developer contact details listed on the app's Google Play listing.
