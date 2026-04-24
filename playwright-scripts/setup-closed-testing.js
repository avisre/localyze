/**
 * setup-closed-testing.js
 * Automates closed-testing setup for the Localyze Android app on Google Play Console.
 *
 * Steps:
 * 1. Logs into Play Console (same flow as health-check).
 * 2. Navigates to Testing → Closed testing.
 * 3. Creates a new closed testing track if none exists.
 * 4. Uploads a tester list from a CSV file path provided via TESTER_CSV_PATH env var.
 * 5. Screenshots each step for verification.
 *
 * Prerequisites:
 *   - Account must be in good standing (run health-check first).
 *   - TESTER_CSV_PATH must point to a CSV with at least an "email" column
 *     or one email per line.
 *
 * Usage:
 *   npm install
 *   node setup-closed-testing.js
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
const TESTER_CSV_PATH = process.env.TESTER_CSV_PATH;

if (!EMAIL || !PASSWORD) {
  console.error('Error: PLAY_CONSOLE_EMAIL and PLAY_CONSOLE_PASSWORD must be set in .env');
  process.exit(1);
}

if (!TESTER_CSV_PATH) {
  console.error('Error: TESTER_CSV_PATH must be set in .env');
  process.exit(1);
}

if (!fs.existsSync(TESTER_CSV_PATH)) {
  console.error(`Error: Tester CSV not found at ${TESTER_CSV_PATH}`);
  process.exit(1);
}

// ---------------------------------------------------------------------------
// Helper: create timestamped output directory
// ---------------------------------------------------------------------------
const TIMESTAMP = new Date().toISOString().replace(/[:.]/g, '-');
const OUT_DIR = path.resolve(__dirname, '..', 'output', 'playwright', `setup-closed-testing-${TIMESTAMP}`);
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
    await page.waitForTimeout(2000);
  } catch (e) {
    console.warn(`Selector not found within ${timeout}ms: ${selector}`);
  }
}

// ---------------------------------------------------------------------------
// Helper: parse tester CSV
// ---------------------------------------------------------------------------
function parseTesterCsv(filePath) {
  const content = fs.readFileSync(filePath, 'utf-8').trim();
  const lines = content.split(/\r?\n/);
  if (lines.length === 0) return [];

  const firstLine = lines[0];
  const hasHeader = firstLine.toLowerCase().includes('email');

  if (hasHeader) {
    const headers = firstLine.split(',').map((h) => h.trim().toLowerCase());
    const emailIndex = headers.indexOf('email');
    if (emailIndex === -1) {
      // fallback: treat every non-header line as an email
      return lines.slice(1).filter((l) => l.includes('@'));
    }
    return lines
      .slice(1)
      .map((l) => l.split(',')[emailIndex]?.trim())
      .filter((e) => e && e.includes('@'));
  }

  // No header — assume one email per line
  return lines.filter((l) => l.includes('@'));
}

// ---------------------------------------------------------------------------
// Main automation flow
// ---------------------------------------------------------------------------
(async () => {
  const testers = parseTesterCsv(TESTER_CSV_PATH);
  console.log(`Parsed ${testers.length} tester emails from ${TESTER_CSV_PATH}`);

  const browser = await firefox.launch({ headless: false });
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();

  try {
    // =====================================================================
    // 1. LOGIN
    // =====================================================================
    console.log('[1/4] Logging into Google Play Console...');
    await gotoWithRetry(page, 'https://play.google.com/console');

    await waitForSpa(page, 'input[type="email"]');
    await page.fill('input[type="email"]', EMAIL);
    await page.click('#identifierNext, button:has-text("Next"), [jsname="LgbsSe"]');
    await page.waitForTimeout(3000);

    await waitForSpa(page, 'input[type="password"]');
    await page.fill('input[type="password"]', PASSWORD);
    await page.click('#passwordNext, button:has-text("Next"), [jsname="LgbsSe"]');
    await page.waitForTimeout(5000);

    const urlAfterLogin = page.url();
    if (urlAfterLogin.includes('myaccount.google.com') || urlAfterLogin.includes('challenge')) {
      await promptFor2FA(page);
    }

    await waitForSpa(page, '[data-test-id="dashboard"], header, .gb_Ld, [role="main"]');
    await page.screenshot({ path: path.join(OUT_DIR, '01-dashboard.png'), fullPage: true });
    console.log('    → Dashboard screenshot saved.');

    // =====================================================================
    // 2. NAVIGATE TO CLOSED TESTING
    // =====================================================================
    console.log('[2/4] Navigating to Testing → Closed testing...');
    const tracksUrl = `https://play.google.com/console/u/1/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks`;
    await gotoWithRetry(page, tracksUrl);
    await waitForSpa(page, 'body');
    await page.waitForTimeout(5000);

    // Click "Closed testing" tab if present
    const closedSelectors = [
      'text=Closed testing',
      '[data-test-id="closed-testing-tab"]',
      'a:has-text("Closed testing")',
      'button:has-text("Closed testing")',
    ];
    for (const sel of closedSelectors) {
      try {
        const el = await page.$(sel);
        if (el) {
          await el.click();
          await page.waitForTimeout(3000);
          break;
        }
      } catch (_) { /* ignore */ }
    }

    await page.screenshot({ path: path.join(OUT_DIR, '02-closed-testing-landing.png'), fullPage: true });
    console.log('    → Closed-testing landing screenshot saved.');

    // =====================================================================
    // 3. CREATE NEW TRACK IF NONE EXISTS
    // =====================================================================
    console.log('[3/4] Checking for existing closed-testing tracks...');
    const bodyText = await page.textContent('body');
    const hasExistingTrack = bodyText.toLowerCase().includes('manage track') || bodyText.toLowerCase().includes('testers');

    if (!hasExistingTrack) {
      console.log('    → No track found. Attempting to create one...');
      const createSelectors = [
        'text=Create track',
        'button:has-text("Create track")',
        'a:has-text("Create track")',
        '[data-test-id="create-track-button"]',
      ];
      for (const sel of createSelectors) {
        try {
          const el = await page.$(sel);
          if (el) {
            await el.click();
            await page.waitForTimeout(3000);
            break;
          }
        } catch (_) { /* ignore */ }
      }

      // Fill track name (e.g., "Closed Alpha")
      const nameSelectors = [
        'input[placeholder*="track" i]',
        'input[name*="trackName" i]',
        '[data-test-id="track-name-input"] input',
        'input[type="text"]',
      ];
      for (const sel of nameSelectors) {
        try {
          const input = await page.$(sel);
          if (input) {
            await input.fill('Closed Alpha');
            await page.waitForTimeout(1000);
            break;
          }
        } catch (_) { /* ignore */ }
      }

      // Click Create / Save
      const saveSelectors = [
        'button:has-text("Create")',
        'button:has-text("Save")',
        '[data-test-id="save-track-button"]',
      ];
      for (const sel of saveSelectors) {
        try {
          const btn = await page.$(sel);
          if (btn) {
            await btn.click();
            await page.waitForTimeout(4000);
            break;
          }
        } catch (_) { /* ignore */ }
      }

      await page.screenshot({ path: path.join(OUT_DIR, '03a-track-created.png'), fullPage: true });
      console.log('    → Track created screenshot saved.');
    } else {
      console.log('    → Existing track detected, skipping creation.');
      await page.screenshot({ path: path.join(OUT_DIR, '03-existing-track.png'), fullPage: true });
    }

    // =====================================================================
    // 4. UPLOAD TESTER LIST
    // =====================================================================
    console.log('[4/4] Uploading tester list...');
    // Navigate to the "Testers" tab inside the closed-testing track
    const testersTabSelectors = [
      'text=Testers',
      'a:has-text("Testers")',
      'button:has-text("Testers")',
      '[data-test-id="testers-tab"]',
    ];
    for (const sel of testersTabSelectors) {
      try {
        const el = await page.$(sel);
        if (el) {
          await el.click();
          await page.waitForTimeout(3000);
          break;
        }
      } catch (_) { /* ignore */ }
    }

    await page.screenshot({ path: path.join(OUT_DIR, '04a-testers-tab.png'), fullPage: true });

    // Look for "Upload CSV" or "Import testers" button
    const uploadSelectors = [
      'text=Upload CSV',
      'button:has-text("Upload CSV")',
      'text=Import testers',
      'button:has-text("Import testers")',
      'input[type="file"]',
    ];
    let uploaded = false;
    for (const sel of uploadSelectors) {
      try {
        const el = await page.$(sel);
        if (!el) continue;

        if (await el.evaluate((node) => node.tagName.toLowerCase() === 'input' && node.type === 'file')) {
          await el.setInputFiles(TESTER_CSV_PATH);
        } else {
          await el.click();
          await page.waitForTimeout(2000);
          const fileInput = await page.$('input[type="file"]');
          if (fileInput) await fileInput.setInputFiles(TESTER_CSV_PATH);
        }
        await page.waitForTimeout(3000);
        uploaded = true;
        break;
      } catch (err) {
        console.warn(`Upload attempt via "${sel}" failed: ${err.message}`);
      }
    }

    if (!uploaded) {
      console.warn('Could not find CSV upload widget. Falling back to manual email entry...');
      // Try to find an "Add testers" textarea or individual email inputs
      const addSelectors = [
        'textarea',
        'input[placeholder*="email" i]',
        '[data-test-id="add-testers-input"]',
      ];
      for (const sel of addSelectors) {
        try {
          const input = await page.$(sel);
          if (input) {
            // Paste comma-separated emails
            const csvEmails = testers.join(', ');
            await input.fill(csvEmails);
            await page.waitForTimeout(1000);
            // Look for a save / add button
            const addBtn = await page.$('button:has-text("Add"), button:has-text("Save"), [data-test-id="save-testers-button"]');
            if (addBtn) await addBtn.click();
            await page.waitForTimeout(3000);
            uploaded = true;
            break;
          }
        } catch (_) { /* ignore */ }
      }
    }

    await page.screenshot({ path: path.join(OUT_DIR, '04b-testers-uploaded.png'), fullPage: true });
    console.log(`    → Tester upload step completed (uploaded=${uploaded}).`);

  } catch (err) {
    console.error('Automation error:', err.message);
    try {
      await page.screenshot({ path: path.join(OUT_DIR, '99-error.png'), fullPage: true });
    } catch (_) { /* ignore */ }
  } finally {
    await browser.close();
  }

  console.log(`\n✅ Closed-testing setup complete. Screenshots saved to:\n   ${OUT_DIR}\n`);
})();
