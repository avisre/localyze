/**
 * play-console-health-check.js
 * Daily health-check script for Localyze Google Play Console account.
 *
 * Steps:
 * 1. Logs into Play Console using credentials from environment variables.
 * 2. Navigates to Account details and screenshots any warning banners.
 * 3. Navigates to Payments profile and checks payment-method verification.
 * 4. Navigates to the app's Closed testing page and screenshots track status.
 * 5. Navigates to Monetization → Products → Subscriptions and checks for
 *    localyze_premium_yearly.
 * 6. Saves all screenshots to output/playwright/health-check-{timestamp}/
 * 7. Outputs a JSON summary of findings.
 *
 * Usage:
 *   npm install
 *   node play-console-health-check.js
 */

require('dotenv').config();
const { firefox } = require('playwright');
const fs = require('fs');
const path = require('path');

// ---------------------------------------------------------------------------
// Configuration from environment
// ---------------------------------------------------------------------------
const EMAIL = process.env.PLAY_CONSOLE_EMAIL;
const PASSWORD = process.env.PLAY_CONSOLE_PASSWORD;
const DEVELOPER_ID = process.env.DEVELOPER_ID || '6492542126543167241';
const APP_ID = process.env.APP_ID || '4974212910605033903';

if (!EMAIL || !PASSWORD) {
  console.error('Error: PLAY_CONSOLE_EMAIL and PLAY_CONSOLE_PASSWORD must be set in .env');
  process.exit(1);
}

// ---------------------------------------------------------------------------
// Helper: create timestamped output directory
// ---------------------------------------------------------------------------
const TIMESTAMP = new Date().toISOString().replace(/[:.]/g, '-');
const OUT_DIR = path.resolve(__dirname, '..', 'output', 'playwright', `health-check-${TIMESTAMP}`);
fs.mkdirSync(OUT_DIR, { recursive: true });

// ---------------------------------------------------------------------------
// Helper: pause for manual 2FA entry
// ---------------------------------------------------------------------------
async function promptFor2FA(page) {
  console.log('\n>>> 2FA detected or additional verification required.');
  console.log('>>> Please complete the steps in the browser, then press ENTER here to continue...');
  await page.screenshot({ path: path.join(OUT_DIR, '02a-2fa-prompt.png'), fullPage: false });
  await new Promise((resolve) => {
    process.stdin.once('data', () => resolve());
  });
  // Give the SPA a moment to settle after manual interaction
  await page.waitForTimeout(5000);
}

// ---------------------------------------------------------------------------
// Helper: robust navigation with retries
// ---------------------------------------------------------------------------
async function gotoWithRetry(page, url, options = {}) {
  const maxRetries = 3;
  for (let i = 0; i < maxRetries; i++) {
    try {
      await page.goto(url, { waitUntil: 'networkidle', timeout: 60000, ...options });
      return;
    } catch (err) {
      console.warn(`Navigation attempt ${i + 1} failed: ${err.message}`);
      if (i === maxRetries - 1) throw err;
      await page.waitForTimeout(5000);
    }
  }
}

// ---------------------------------------------------------------------------
// Helper: wait for Play Console SPA to stabilise
// ---------------------------------------------------------------------------
async function waitForSpa(page, selector, timeout = 30000) {
  try {
    await page.waitForSelector(selector, { timeout });
    // Extra breathing room for React/Angular hydration
    await page.waitForTimeout(2000);
  } catch (e) {
    console.warn(`Selector not found within ${timeout}ms: ${selector}`);
  }
}

// ---------------------------------------------------------------------------
// Main automation flow
// ---------------------------------------------------------------------------
(async () => {
  const summary = {
    timestamp: new Date().toISOString(),
    developerId: DEVELOPER_ID,
    appId: APP_ID,
    accountWarnings: [],
    paymentStatus: null,
    closedTestingStatus: null,
    subscriptionFound: null,
    screenshots: [],
    errors: [],
  };

  const browser = await firefox.launch({ headless: false }); // visible for 2FA
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();

  try {
    // =====================================================================
    // 1. LOGIN
    // =====================================================================
    console.log('[1/5] Logging into Google Play Console...');
    await gotoWithRetry(page, 'https://play.google.com/console');

    // Email field
    await waitForSpa(page, 'input[type="email"]');
    await page.fill('input[type="email"]', EMAIL);
    await page.click('#identifierNext, button:has-text("Next"), [jsname="LgbsSe"]');
    await page.waitForTimeout(3000);

    // Password field
    await waitForSpa(page, 'input[type="password"]');
    await page.fill('input[type="password"]', PASSWORD);
    await page.click('#passwordNext, button:has-text("Next"), [jsname="LgbsSe"]');
    await page.waitForTimeout(5000);

    // Detect 2FA / additional verification
    const urlAfterLogin = page.url();
    if (urlAfterLogin.includes('myaccount.google.com') || urlAfterLogin.includes('signin/rejected') || urlAfterLogin.includes('challenge')) {
      await promptFor2FA(page);
    }

    // Wait for Play Console dashboard to load
    await waitForSpa(page, '[data-test-id="dashboard"], header, .gb_Ld, [role="main"]');
    const dashPath = path.join(OUT_DIR, '01-dashboard.png');
    await page.screenshot({ path: dashPath, fullPage: true });
    summary.screenshots.push('01-dashboard.png');
    console.log('    → Dashboard screenshot saved.');

    // =====================================================================
    // 2. ACCOUNT DETAILS — check for warning banners
    // =====================================================================
    console.log('[2/5] Navigating to Account details...');
    const accountUrl = `https://play.google.com/console/u/1/developers/${DEVELOPER_ID}/account-details`;
    await gotoWithRetry(page, accountUrl);
    await waitForSpa(page, 'body');
    await page.waitForTimeout(4000);

    const accountPath = path.join(OUT_DIR, '02-account-details.png');
    await page.screenshot({ path: accountPath, fullPage: true });
    summary.screenshots.push('02-account-details.png');

    // Look for warning / alert banners
    const warningSelectors = [
      '[data-test-id="warning-banner"]',
      '[data-test-id="alert-banner"]',
      '[role="alert"]',
      '.alert',
      '.warning',
      '[class*="banner"]',
      '[class*="warning"]',
      '[class*="alert"]',
    ];

    for (const sel of warningSelectors) {
      try {
        const banners = await page.$$(sel);
        for (const banner of banners) {
          const text = await banner.textContent();
          if (text && text.trim().length > 0) {
            summary.accountWarnings.push(text.trim().replace(/\s+/g, ' '));
          }
        }
      } catch (_) { /* ignore missing selectors */ }
    }

    // Also scan page text for known warning phrases
    const pageText = await page.textContent('body');
    const criticalPhrases = ['account termination', 'removal scheduled', 'payment method', 'verify', 'suspended'];
    for (const phrase of criticalPhrases) {
      if (pageText.toLowerCase().includes(phrase)) {
        summary.accountWarnings.push(`[Detected phrase in page text]: "${phrase}"`);
      }
    }

    console.log(`    → Warnings found: ${summary.accountWarnings.length}`);

    // =====================================================================
    // 3. PAYMENTS PROFILE — check payment method verification
    // =====================================================================
    console.log('[3/5] Navigating to Payments profile...');
    // Payments profile is typically under Account → Payments profile, but
    // the direct URL pattern varies. We navigate via the settings gear or
    // use the Google Payments Center link if exposed.
    const paymentsUrl = `https://play.google.com/console/u/1/developers/${DEVELOPER_ID}/payments`;
    await gotoWithRetry(page, paymentsUrl);
    await waitForSpa(page, 'body');
    await page.waitForTimeout(4000);

    const paymentsPath = path.join(OUT_DIR, '03-payments-profile.png');
    await page.screenshot({ path: paymentsPath, fullPage: true });
    summary.screenshots.push('03-payments-profile.png');

    // Try to infer verification status from visible text
    const paymentsText = await page.textContent('body');
    const paymentLower = paymentsText.toLowerCase();
    if (paymentLower.includes('verified') && !paymentLower.includes('not verified')) {
      summary.paymentStatus = 'verified';
    } else if (paymentLower.includes('not verified') || paymentLower.includes('unverified') || paymentLower.includes('verification required')) {
      summary.paymentStatus = 'unverified';
    } else if (paymentLower.includes('pending')) {
      summary.paymentStatus = 'pending';
    } else {
      summary.paymentStatus = 'unknown';
    }
    console.log(`    → Payment status: ${summary.paymentStatus}`);

    // =====================================================================
    // 4. CLOSED TESTING PAGE — screenshot track status
    // =====================================================================
    console.log('[4/5] Navigating to Closed testing...');
    const closedTestingUrl = `https://play.google.com/console/u/1/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks`;
    await gotoWithRetry(page, closedTestingUrl);
    await waitForSpa(page, 'body');
    await page.waitForTimeout(5000);

    // Look for "Closed testing" tab/link and click it if not already active
    const closedTabSelectors = [
      'text=Closed testing',
      '[data-test-id="closed-testing-tab"]',
      'a:has-text("Closed testing")',
      'button:has-text("Closed testing")',
    ];
    for (const sel of closedTabSelectors) {
      try {
        const el = await page.$(sel);
        if (el) {
          await el.click();
          await page.waitForTimeout(3000);
          break;
        }
      } catch (_) { /* ignore */ }
    }

    const closedPath = path.join(OUT_DIR, '04-closed-testing.png');
    await page.screenshot({ path: closedPath, fullPage: true });
    summary.screenshots.push('04-closed-testing.png');

    // Try to extract track status text
    const trackText = await page.textContent('body');
    summary.closedTestingStatus = {
      hasAlpha: trackText.toLowerCase().includes('alpha'),
      hasBeta: trackText.toLowerCase().includes('beta'),
      hasInternal: trackText.toLowerCase().includes('internal testing'),
      rawSnippet: trackText.substring(0, 500).replace(/\s+/g, ' '),
    };
    console.log('    → Closed-testing screenshot saved.');

    // =====================================================================
    // 5. MONETIZATION → PRODUCTS → SUBSCRIPTIONS
    // =====================================================================
    console.log('[5/5] Navigating to Monetization → Subscriptions...');
    const subsUrl = `https://play.google.com/console/u/1/developers/${DEVELOPER_ID}/app/${APP_ID}/monetize/subscriptions`;
    await gotoWithRetry(page, subsUrl);
    await waitForSpa(page, 'body');
    await page.waitForTimeout(5000);

    const subsPath = path.join(OUT_DIR, '05-subscriptions.png');
    await page.screenshot({ path: subsPath, fullPage: true });
    summary.screenshots.push('05-subscriptions.png');

    const subsText = await page.textContent('body');
    summary.subscriptionFound = subsText.includes('localyze_premium_yearly');
    console.log(`    → localyze_premium_yearly found: ${summary.subscriptionFound}`);

  } catch (err) {
    console.error('Automation error:', err.message);
    summary.errors.push(err.message);
    try {
      const errorShot = path.join(OUT_DIR, '99-error.png');
      await page.screenshot({ path: errorShot, fullPage: true });
      summary.screenshots.push('99-error.png');
    } catch (_) { /* ignore screenshot failure */ }
  } finally {
    await browser.close();
  }

  // ---------------------------------------------------------------------------
  // Write JSON summary
  // ---------------------------------------------------------------------------
  const summaryPath = path.join(OUT_DIR, 'summary.json');
  fs.writeFileSync(summaryPath, JSON.stringify(summary, null, 2));
  console.log(`\n✅ Health-check complete. Summary written to:\n   ${summaryPath}\n`);
  console.log(JSON.stringify(summary, null, 2));
})();
