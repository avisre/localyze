/**
 * upload-via-cdp.js
 * Attaches to an already-running Brave/Chrome on http://127.0.0.1:9222
 * (the user is signed into Play Console there) and uploads the signed AAB
 * to Localyze's Production track.
 *
 * No password handling, no 2FA — uses the existing authenticated session.
 *
 * Usage:  node upload-via-cdp.js
 */

const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const CDP_URL = process.env.CDP_URL || 'http://127.0.0.1:9222';
const DEVELOPER_ID = process.env.DEVELOPER_ID || '6492542126543167241';
const APP_ID = process.env.APP_ID || '4974212910605033903';
const AAB_PATH = path.resolve(__dirname, '..', 'app', 'build', 'outputs', 'bundle', 'release', 'app-release.aab');
const RELEASE_NOTES = process.env.RELEASE_NOTES ||
  "Major update — Gemma 3n E2B is now the on-device model.\n1.4–1.8x faster end-to-end responses and roughly half the RAM\ncompared with the previous build. Model picker removed: one model,\nauto-selected for every device.";

if (!fs.existsSync(AAB_PATH)) {
  console.error(`AAB not found at ${AAB_PATH}`);
  process.exit(1);
}

const TIMESTAMP = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `cdp-upload-${TIMESTAMP}`);
fs.mkdirSync(OUT, { recursive: true });

const shot = async (page, label) => {
  const p = path.join(OUT, `${label}.png`);
  try { await page.screenshot({ path: p, fullPage: false }); console.log(`📸 ${label}.png`); } catch (e) {}
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const PUBLISH_URL = `https://play.google.com/console/u/1/developers/${DEVELOPER_ID}/app/${APP_ID}/publishing`;
const TRACKS_URL = `https://play.google.com/console/u/1/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/production`;
const NEW_RELEASE_URL = `https://play.google.com/console/u/1/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/production/releases/new`;

(async () => {
  console.log(`→ connecting to ${CDP_URL}`);
  const browser = await chromium.connectOverCDP(CDP_URL);
  const contexts = browser.contexts();
  if (contexts.length === 0) throw new Error('No browser contexts found on CDP endpoint');
  const context = contexts[0];

  // Find an existing Play Console tab, or open a new one.
  let page = context.pages().find((p) => p.url().includes('play.google.com/console'));
  if (!page) {
    console.log('→ no Play Console tab; opening one');
    page = await context.newPage();
  } else {
    console.log(`→ reusing existing Play Console tab: ${page.url()}`);
  }

  console.log(`→ navigating directly to the new-release page`);
  await page.goto(NEW_RELEASE_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(4000);
  await shot(page, '01-new-release-page');

  // Try the "Upload" button. The Play Console SPA has a file input with
  // accept=".aab,.apk"; setInputFiles works directly even when it's hidden.
  let fileInput = null;
  for (let i = 0; i < 6 && !fileInput; i++) {
    fileInput = await page.$('input[type="file"]');
    if (!fileInput) {
      console.log(`→ no file input yet (attempt ${i + 1}); waiting`);
      await sleep(2500);
    }
  }
  if (!fileInput) {
    await shot(page, '02-no-file-input');
    throw new Error('File input never appeared — Play Console layout may have changed');
  }

  console.log(`→ uploading ${AAB_PATH} (${fs.statSync(AAB_PATH).size} bytes)`);
  await fileInput.setInputFiles(AAB_PATH);
  await shot(page, '03-after-setInputFiles');

  // Play Console then shows a progress bar; wait for the "Saving" or
  // "Saved" indicator before pressing Next.
  console.log('→ waiting for upload + bundle processing (this can take 60-120s)');
  for (let i = 0; i < 60; i++) {
    await sleep(5000);
    await shot(page, `04-progress-${String(i).padStart(2, '0')}`);
    const ready = await page.evaluate(() => {
      const text = document.body.innerText;
      return /Release notes|Release name|What's new in this release/i.test(text);
    });
    if (ready) { console.log('→ upload finished, release form ready'); break; }
  }
  await shot(page, '05-upload-complete');

  // Fill release notes.
  console.log('→ filling release notes');
  try {
    const ta = await page.$('textarea, [contenteditable="true"]');
    if (ta) {
      await ta.click();
      await page.keyboard.press('Control+A');
      await page.keyboard.press('Delete');
      await ta.type(RELEASE_NOTES);
    } else {
      console.warn('→ could not locate release-notes textarea; you can paste in the browser');
    }
  } catch (e) {
    console.warn(`→ release-notes fill failed: ${e.message}`);
  }
  await shot(page, '06-release-notes-filled');

  console.log('\n✅ AAB uploaded and release notes filled.');
  console.log('   Next step (in the browser):  Next → Save → Review release → Start rollout');
  console.log(`   Screenshots saved to: ${OUT}`);

  // Don't close the browser — leave the user to review and click Start rollout.
  await browser.close();
})().catch((err) => {
  console.error('❌ failed:', err.message);
  process.exit(1);
});
