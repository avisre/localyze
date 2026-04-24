/**
 * upload-aab.js
 * Uploads the signed release AAB to Google Play Console.
 *
 * Steps:
 * 1. Logs into Play Console using credentials from environment variables.
 * 2. Navigates to the app's Production track.
 * 3. Creates a new release.
 * 4. Uploads the AAB file.
 * 5. Saves screenshots for verification.
 *
 * Usage:
 *   node upload-aab.js
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
const AAB_PATH = path.resolve(__dirname, '..', 'app', 'build', 'outputs', 'bundle', 'release', 'app-release.aab');

const readline = require('readline');

async function promptInput(promptText) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  return new Promise((resolve) => {
    rl.question(promptText, (answer) => {
      rl.close();
      resolve(answer.trim());
    });
  });
}

if (!fs.existsSync(AAB_PATH)) {
  console.error(`Error: AAB file not found at ${AAB_PATH}`);
  process.exit(1);
}

// ---------------------------------------------------------------------------
// Helper: create timestamped output directory
// ---------------------------------------------------------------------------
const TIMESTAMP = new Date().toISOString().replace(/[:.]/g, '-');
const OUT_DIR = path.resolve(__dirname, '..', 'output', 'playwright', `upload-aab-${TIMESTAMP}`);
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
// Main automation flow
// ---------------------------------------------------------------------------
(async () => {
  let email = EMAIL;
  let password = PASSWORD;

  if (!email) {
    email = await promptInput('Enter Google Play Console email: ');
  }
  if (!password) {
    password = await promptInput('Enter Google Play Console password: ');
  }

  if (!email || !password) {
    console.error('Error: Both email and password are required.');
    process.exit(1);
  }

  const summary = {
    timestamp: new Date().toISOString(),
    developerId: DEVELOPER_ID,
    appId: APP_ID,
    aabPath: AAB_PATH,
    aabSize: fs.statSync(AAB_PATH).size,
    screenshots: [],
    errors: [],
    success: false,
  };

  const browser = await firefox.launch({ headless: false });
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();

  try {
    // =====================================================================
    // 1. LOGIN
    // =====================================================================
    console.log('[1/6] Logging into Google Play Console...');
    await gotoWithRetry(page, 'https://play.google.com/console');

    await waitForSpa(page, 'input[type="email"]');
    await page.fill('input[type="email"]', email);
    await page.click('#identifierNext, button:has-text("Next"), [jsname="LgbsSe"]');
    await page.waitForTimeout(3000);

    await waitForSpa(page, 'input[type="password"]');
    await page.fill('input[type="password"]', password);
    await page.click('#passwordNext, button:has-text("Next"), [jsname="LgbsSe"]');
    await page.waitForTimeout(5000);

    const urlAfterLogin = page.url();
    if (urlAfterLogin.includes('myaccount.google.com') || urlAfterLogin.includes('signin/rejected') || urlAfterLogin.includes('challenge')) {
      await promptFor2FA(page);
    }

    await waitForSpa(page, '[data-test-id="dashboard"], header, .gb_Ld, [role="main"]');
    const dashPath = path.join(OUT_DIR, '01-dashboard.png');
    await page.screenshot({ path: dashPath, fullPage: true });
    summary.screenshots.push('01-dashboard.png');
    console.log('    → Dashboard screenshot saved.');

    // =====================================================================
    // 2. Navigate to App
    // =====================================================================
    console.log('[2/6] Navigating to app...');
    const appUrl = `https://play.google.com/console/u/1/developers/${DEVELOPER_ID}/app/${APP_ID}/app-dashboard`;
    await gotoWithRetry(page, appUrl);
    await waitForSpa(page, 'body');
    await page.waitForTimeout(4000);

    const appPath = path.join(OUT_DIR, '02-app-dashboard.png');
    await page.screenshot({ path: appPath, fullPage: true });
    summary.screenshots.push('02-app-dashboard.png');
    console.log('    → App dashboard screenshot saved.');

    // =====================================================================
    // 3. Navigate to Production Track
    // =====================================================================
    console.log('[3/6] Navigating to Production track...');
    const productionUrl = `https://play.google.com/console/u/1/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/production`;
    await gotoWithRetry(page, productionUrl);
    await waitForSpa(page, 'body');
    await page.waitForTimeout(5000);

    const prodPath = path.join(OUT_DIR, '03-production-track.png');
    await page.screenshot({ path: prodPath, fullPage: true });
    summary.screenshots.push('03-production-track.png');
    console.log('    → Production track screenshot saved.');

    // =====================================================================
    // 4. Create New Release
    // =====================================================================
    console.log('[4/6] Creating new release...');
    const createReleaseSelectors = [
      'text=Create release',
      'text=Create new release',
      '[data-test-id="create-release-button"]',
      'button:has-text("Create release")',
      'button:has-text("Create new release")',
    ];

    let releaseClicked = false;
    for (const sel of createReleaseSelectors) {
      try {
        const el = await page.$(sel);
        if (el) {
          await el.click();
          releaseClicked = true;
          console.log(`    → Clicked "${sel}" to create release.`);
          break;
        }
      } catch (_) { /* ignore */ }
    }

    if (!releaseClicked) {
      throw new Error('Could not find "Create release" button');
    }

    await page.waitForTimeout(5000);
    const createPath = path.join(OUT_DIR, '04-create-release.png');
    await page.screenshot({ path: createPath, fullPage: true });
    summary.screenshots.push('04-create-release.png');

    // =====================================================================
    // 5. Upload AAB
    // =====================================================================
    console.log('[5/6] Uploading AAB file...');
    console.log(`    → File: ${AAB_PATH}`);
    console.log(`    → Size: ${(fs.statSync(AAB_PATH).size / 1024 / 1024).toFixed(2)} MB`);

    const uploadSelectors = [
      'input[type="file"]',
      '[data-test-id="app-bundle-upload"]',
      'input[accept=".aab,.apk"]',
      'input[name="bundleFile"]',
    ];

    let uploaded = false;
    for (const sel of uploadSelectors) {
      try {
        const input = await page.$(sel);
        if (input) {
          await input.setInputFiles(AAB_PATH);
          uploaded = true;
          console.log(`    → Upload initiated via selector: ${sel}`);
          break;
        }
      } catch (err) {
        console.warn(`    → Upload selector "${sel}" failed: ${err.message}`);
      }
    }

    if (!uploaded) {
      throw new Error('Could not find file upload input');
    }

    // Wait for upload to complete (look for success indicators)
    console.log('    → Waiting for upload to complete...');
    await page.waitForTimeout(30000); // Initial wait for upload to start

    const successSelectors = [
      'text=Upload successful',
      'text=Upload complete',
      'text=Ready to publish',
      'text=Review release',
      'text=Changes ready to review',
      '[data-test-id="save-button"]',
      'button:has-text("Save")',
      'button:has-text("Review release")',
    ];

    let uploadComplete = false;
    for (let attempt = 0; attempt < 30; attempt++) {
      for (const sel of successSelectors) {
        try {
          const el = await page.$(sel);
          if (el) {
            const text = await el.textContent();
            if (text && text.trim().length > 0) {
              console.log(`    → Upload status: "${text.trim()}"`);
              uploadComplete = true;
              break;
            }
          }
        } catch (_) { /* ignore */ }
      }
      if (uploadComplete) break;
      console.log(`    → Upload still in progress... (${attempt + 1}/30)`);
      await page.waitForTimeout(10000);
      const uploadPath = path.join(OUT_DIR, `05-upload-progress-${attempt + 1}.png`);
      await page.screenshot({ path: uploadPath, fullPage: true });
    }

    const uploadPath = path.join(OUT_DIR, '05-upload-complete.png');
    await page.screenshot({ path: uploadPath, fullPage: true });
    summary.screenshots.push('05-upload-complete.png');

    if (!uploadComplete) {
      throw new Error('Upload did not complete within timeout');
    }

    // =====================================================================
    // 6. Save and Review
    // =====================================================================
    console.log('[6/6] Saving release...');
    const saveSelectors = [
      'text=Save',
      '[data-test-id="save-button"]',
      'button:has-text("Save")',
      'text=Review release',
      'button:has-text("Review release")',
    ];

    let saved = false;
    for (const sel of saveSelectors) {
      try {
        const el = await page.$(sel);
        if (el) {
          await el.click();
          saved = true;
          console.log(`    → Clicked "${sel}" to save release.`);
          break;
        }
      } catch (_) { /* ignore */ }
    }

    if (!saved) {
      console.warn('    → Could not find Save/Review button, may need manual action');
    }

    await page.waitForTimeout(5000);
    const finalPath = path.join(OUT_DIR, '06-final-review.png');
    await page.screenshot({ path: finalPath, fullPage: true });
    summary.screenshots.push('06-final-review.png');

    summary.success = true;
    console.log('\n✅ AAB upload automation completed successfully!');

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
  console.log(`\n📄 Summary written to:\n   ${summaryPath}\n`);
  console.log(JSON.stringify(summary, null, 2));
})();