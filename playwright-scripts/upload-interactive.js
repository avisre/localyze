/**
 * upload-interactive.js — Launches a headed Chromium window with a persistent
 * profile, opens Play Console. The user logs in manually in that window (once).
 * Once logged-in cookies are seen, the script navigates to the new-release
 * page, uploads the AAB, fills release notes, and stops. The user does the
 * final "Start rollout" click in the visible window.
 *
 * Profile is kept at: ~/.cache/localyze-play-profile (so future runs skip
 * login). Password is never typed by the script — the user logs in directly.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PROFILE_DIR = process.env.PLAY_PROFILE_DIR || path.join(os.homedir(), '.cache', 'localyze-play-profile');
const DEVELOPER_ID = process.env.DEVELOPER_ID || '6492542126543167241';
const APP_ID = process.env.APP_ID || '4974212910605033903';
const AAB_PATH = path.resolve(__dirname, '..', 'app', 'build', 'outputs', 'bundle', 'release', 'app-release.aab');
const RELEASE_NOTES = process.env.RELEASE_NOTES ||
  "Major update — Gemma 3n E2B is now the on-device model.\n1.4–1.8x faster end-to-end responses and roughly half the RAM\ncompared with the previous build. Model picker removed: one model,\nauto-selected for every device.";

const PUBLISH_URL = `https://play.google.com/console/u/1/developers/${DEVELOPER_ID}/app/${APP_ID}/publishing`;
const NEW_RELEASE_URL = `https://play.google.com/console/u/1/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/production/releases/new`;

if (!fs.existsSync(AAB_PATH)) { console.error(`AAB missing: ${AAB_PATH}`); process.exit(1); }

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `interactive-upload-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
const shot = async (page, label) => { try { await page.screenshot({ path: path.join(OUT, `${label}.png`), fullPage: false }); console.log(`📸 ${label}.png`); } catch (e) {} };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  console.log(`→ launching Chromium (profile at ${PROFILE_DIR})`);
  fs.mkdirSync(PROFILE_DIR, { recursive: true });
  const context = await chromium.launchPersistentContext(PROFILE_DIR, {
    headless: false,
    viewport: { width: 1400, height: 900 },
    args: ['--no-first-run', '--no-default-browser-check']
  });
  const page = context.pages()[0] || (await context.newPage());

  console.log(`→ navigating to Play Console`);
  await page.goto(PUBLISH_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(3000);
  await shot(page, '01-after-nav');

  // Wait for the user to log in (if needed). We detect login by URL: once
  // we're on play.google.com/console/u/<n>/developers/... (not accounts.google.com),
  // we proceed.
  console.log('→ waiting up to 5 min for login (do it in the browser window if prompted)');
  const deadline = Date.now() + 5 * 60 * 1000;
  while (Date.now() < deadline) {
    const url = page.url();
    if (url.startsWith('https://play.google.com/console') && !url.includes('accounts.google.com')) {
      const onLogin = await page.evaluate(() => /Sign in to Play Console/i.test(document.body.innerText || ''));
      if (!onLogin) { console.log(`→ logged in (at ${url.slice(0, 80)}...)`); break; }
    }
    await sleep(3000);
  }
  await shot(page, '02-logged-in');

  console.log(`→ jumping to new-release page`);
  await page.goto(NEW_RELEASE_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(5000);
  await shot(page, '03-new-release');

  // The Play Console new-release page renders the file input lazily.
  let fileInput = null;
  for (let i = 0; i < 12 && !fileInput; i++) {
    fileInput = await page.$('input[type="file"]');
    if (!fileInput) { await sleep(2500); }
  }
  if (!fileInput) {
    await shot(page, '03b-no-file-input');
    console.error('❌ no <input type="file"> on the page. Snapshot saved.');
    console.error('   The Play Console UI may be different in your locale, or the page may need an additional click first.');
    console.error('   Leaving the browser open so you can finish manually.');
    return;
  }

  console.log(`→ uploading ${path.basename(AAB_PATH)} (${(fs.statSync(AAB_PATH).size / 1e6).toFixed(1)} MB)`);
  await fileInput.setInputFiles(AAB_PATH);
  await shot(page, '04-after-setInputFiles');

  console.log('→ waiting for Play Console to process the bundle (up to 5 min)');
  for (let i = 0; i < 60; i++) {
    await sleep(5000);
    const ready = await page.evaluate(() => /Release notes|Release name|What.s new in this release/i.test(document.body.innerText || ''));
    if (ready) { console.log(`→ release form ready after ~${i * 5}s`); break; }
    if (i % 4 === 0) await shot(page, `05-processing-${String(i).padStart(2, '0')}`);
  }
  await shot(page, '06-form-ready');

  console.log('→ filling release notes');
  try {
    const ta = await page.$('textarea, [contenteditable="true"][role="textbox"]');
    if (ta) {
      await ta.click();
      await page.keyboard.press('Control+A');
      await page.keyboard.press('Delete');
      await page.keyboard.type(RELEASE_NOTES);
    } else {
      console.warn('→ release-notes box not found; please paste in browser');
    }
  } catch (e) { console.warn(`→ release-notes fill: ${e.message}`); }
  await shot(page, '07-release-notes');

  console.log('\n✅ AAB uploaded. Release notes filled.');
  console.log('   In the browser window, click: Next → Save → Review release → Start rollout');
  console.log(`   Screenshots: ${OUT}`);
  console.log('\nLeaving the browser open. Press Ctrl-C in this terminal once you have clicked through the final dialogs.');

  // Keep the script alive so the browser stays open
  await new Promise(() => {});
})().catch((err) => { console.error('❌', err.stack || err.message); process.exit(1); });
