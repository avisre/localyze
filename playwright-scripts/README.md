# Localyze Play Console Automation

Playwright-based automation scripts for monitoring and navigating the Google Play Console for the **Localyze** Android app (`com.localyze`).

---

## Prerequisites

- **Node.js** ≥ 18
- **npm** or **yarn**
- **Firefox** installed (scripts use the Firefox browser channel)

---

## Setup

1. **Install dependencies**

   ```bash
   cd playwright-scripts
   npm install
   ```

   This installs `@playwright/test` and `dotenv`, and downloads the Firefox browser binary.

2. **Configure environment variables**

   ```bash
   cp .env.example .env
   ```

   Edit `.env` and fill in your credentials:

   ```env
   PLAY_CONSOLE_EMAIL=your-email@gmail.com
   PLAY_CONSOLE_PASSWORD=your-password
   DEVELOPER_ID=6492542126543167241
   APP_ID=4974212910605033903
   TESTER_CSV_PATH=./testers.csv
   ```

   > **Security note:** `.env` is listed in `.gitignore` by default. Never commit real credentials.

3. **Prepare tester CSV (for closed-testing setup)**

   Create a CSV file at the path specified by `TESTER_CSV_PATH`.

   **Option A — header row:**
   ```csv
   email
   alice@example.com
   bob@example.com
   ```

   **Option B — plain list (no header):**
   ```csv
   alice@example.com
   bob@example.com
   ```

---

## Scripts

### 1. Daily Health Check

`play-console-health-check.js` logs into Play Console and captures the current state of critical areas.

**What it does:**
1. Logs in (pausing for 2FA if required).
2. Navigates to **Account details** and screenshots any warning banners.
3. Navigates to **Payments profile** and infers payment-method verification status.
4. Navigates to the app's **Closed testing** page and screenshots track status.
5. Navigates to **Monetization → Products → Subscriptions** and checks whether `localyze_premium_yearly` exists.
6. Saves all screenshots to `output/playwright/health-check-{timestamp}/`.
7. Writes a JSON summary (`summary.json`) with findings.

**Run:**
```bash
node play-console-health-check.js
```

**Output example:**
```
output/playwright/health-check-2026-04-23T11-30-00-000Z/
├── 01-dashboard.png
├── 02-account-details.png
├── 03-payments-profile.png
├── 04-closed-testing.png
├── 05-subscriptions.png
└── summary.json
```

---

### 2. Closed-Testing Setup

`setup-closed-testing.js` automates the creation of a closed-testing track and uploads testers.

> **Important:** Only run this **after** the account health issues (e.g., payment verification warnings) are resolved, or Play Console may block track creation.

**What it does:**
1. Logs in (pausing for 2FA if required).
2. Navigates to **Testing → Closed testing**.
3. Creates a new track named "Closed Alpha" if none is detected.
4. Uploads / enters the tester list from the CSV file.
5. Screenshots each step for manual verification.

**Run:**
```bash
node setup-closed-testing.js
```

**Output example:**
```
output/playwright/setup-closed-testing-2026-04-23T11-30-00-000Z/
├── 01-dashboard.png
├── 02-closed-testing-landing.png
├── 03a-track-created.png   (or 03-existing-track.png)
├── 04a-testers-tab.png
├── 04b-testers-uploaded.png
└── (99-error.png on failure)
```

---

## Handling 2FA

Both scripts launch Firefox in **headed mode** (`headless: false`) so you can interact with the browser.

If Google prompts for 2FA (SMS, Authenticator, or security key), the script will:
- Print a message in the terminal.
- Take a screenshot of the current page.
- **Pause** and wait for you to press `ENTER` in the terminal after completing the challenge.

---

## Troubleshooting

| Issue | Solution |
|---|---|
| `TimeoutError` on navigation | Play Console is a slow SPA. The scripts already use generous waits and retries. Increase `page.waitForTimeout` values if your connection is slow. |
| Selector not found | Google updates the Play Console UI frequently. Inspect the page in Firefox DevTools and update the selector arrays in the script. |
| `browserType.launch: Executable doesn't exist` | Run `npx playwright install firefox` to download the browser binary. |
| CSV upload not working | The fallback path pastes emails into a textarea. Ensure the CSV path in `.env` is correct and readable. |

---

## Project Context

- **App package:** `com.localyze`
- **Developer account ID:** `6492542126543167241`
- **App ID:** `4974212910605033903`
- **Critical warning:** Account termination scheduled for **May 20, 2026** due to unverified merchant payment method. Run the health-check script daily to monitor resolution.

---

## License

MIT — Localyze Dev Team
