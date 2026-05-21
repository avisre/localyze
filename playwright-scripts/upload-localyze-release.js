/**
 * upload-localyze-release.js
 *
 * One-command upload of the signed Localyze (com.localyze) release AAB to
 * Play Console's Production track, using the persistent logged-in Chromium
 * profile that the stock-market-game repo's
 * start_chrome_for_play_console.sh established. No CDP juggling, no
 * password handling, no 2FA prompts after the first sign-in there.
 *
 * Run:
 *   node playwright-scripts/upload-localyze-release.js
 *
 * If Play Console asks for a fresh sign-in (cookies expired), do it once in
 * the headed Chromium window the script pops up — future runs are silent.
 *
 * The script intentionally STOPS before clicking "Save" / "Review release"
 * / "Start rollout" so you confirm the version + rollout % yourself with the
 * page in front of you. Screenshots saved per-step for verification.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const os = require('os');

// ── config ────────────────────────────────────────────────────────────────
const PROFILE = process.env.PLAY_PROFILE_DIR
  || path.join(os.homedir(), '.cache', 'play-console-profile');
const DEVELOPER_ID = process.env.DEVELOPER_ID || '6492542126543167241';
const APP_ID = process.env.APP_ID || '4974212910605033903';
const AAB_PATH = process.env.AAB_PATH
  || path.resolve(__dirname, '..', 'app', 'build', 'outputs', 'bundle', 'release', 'app-release.aab');
const RELEASE_NOTES = process.env.RELEASE_NOTES || `Major update — Gemma 3n E2B is now the on-device model.
1.4–1.8x faster end-to-end responses and roughly half the RAM
compared with the previous build. Model picker removed: one model,
auto-selected for every device.`;

const NEW_RELEASE_URL = `https://play.google.com/console/u/1/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/production/releases/new`;
const PROD_TRACK_URL  = `https://play.google.com/console/u/1/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/production`;

if (!fs.existsSync(AAB_PATH)) {
  console.error(`❌ AAB not found at ${AAB_PATH}`);
  console.error('   Run ./gradlew :app:bundleRelease first.');
  process.exit(1);
}

// Prefer the same Chromium binary the stock-market-game script uses; fall back
// to whatever Playwright picks if that exact build isn't installed.
const PINNED_CHROMIUM = path.join(os.homedir(), '.cache', 'ms-playwright',
  'chromium-1217', 'chrome-linux64', 'chrome');

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `localyze-upload-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
const shot = async (page, label) => {
  try {
    await page.screenshot({ path: path.join(OUT, `${label}.png`), fullPage: false });
    console.log(`  📸 ${label}.png`);
  } catch (_) {}
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  console.log(`→ persistent profile: ${PROFILE}`);
  console.log(`→ AAB: ${AAB_PATH} (${(fs.statSync(AAB_PATH).size / 1e6).toFixed(1)} MB)`);
  fs.mkdirSync(PROFILE, { recursive: true });

  const launchOpts = {
    headless: false,
    viewport: { width: 1400, height: 900 },
    args: [
      '--no-first-run',
      '--no-default-browser-check',
      '--disable-blink-features=AutomationControlled',
    ],
  };
  if (fs.existsSync(PINNED_CHROMIUM)) {
    launchOpts.executablePath = PINNED_CHROMIUM;
    console.log(`→ using pinned Chromium: ${PINNED_CHROMIUM}`);
  } else {
    console.log('→ pinned Chromium not present, using Playwright default');
  }

  const ctx = await chromium.launchPersistentContext(PROFILE, launchOpts);
  const page = ctx.pages()[0] || (await ctx.newPage());

  // 1. Land on the new-release page directly. If still signed-out, Play
  //    Console will redirect us to accounts.google.com — we wait for the
  //    user to finish 2FA, then proceed.
  console.log(`→ navigating to: ${NEW_RELEASE_URL}`);
  await page.goto(NEW_RELEASE_URL, { waitUntil: 'domcontentloaded', timeout: 90000 });
  await sleep(4000);
  await shot(page, '01-initial');

  // 2. Wait up to 10 min for ANY release-form page. We no longer require a
  //    specific URL pattern — Play Console deep-links bounce to the account
  //    home if a track isn't set up. Instead the user clicks their way to
  //    the new-release page they want (Closed testing OR Production OR
  //    Internal) and the script latches on by detecting form text.
  console.log('→ waiting for the new-release form to appear');
  console.log('  In the Chromium window: navigate to the track you want');
  console.log('  (e.g. Localyze → Release → Closed testing → Manage track → Create new release)');
  console.log('  The script polls every 3s and uploads as soon as it sees the form.');
  const deadline = Date.now() + 10 * 60 * 1000;
  let lastUrl = '';
  while (Date.now() < deadline) {
    const url = page.url();
    if (url !== lastUrl) { console.log(`  url=${url.slice(0, 110)}`); lastUrl = url; }
    if (url.includes('play.google.com/console')) {
      // Strict: only latch when an actual .aab/.apk file input is present
      // AND we're on a track-release URL (not Publishing overview or
      // Policy center, both of which mention "Release notes" in passing).
      const ready = await page.evaluate(() => {
        const inputs = Array.from(document.querySelectorAll('input[type="file"]'));
        const hasAab = inputs.some((i) => /\.aab|\.apk|application\/octet/i.test(i.accept || ''));
        const onReleaseUrl = /\/tracks\/[\w-]+\/releases\/(new|edit|create)/.test(location.pathname)
                           || /\/tracks\/[\w-]+$/.test(location.pathname);
        return hasAab && onReleaseUrl;
      });
      if (ready) { console.log(`→ release form detected on ${url.slice(0, 100)}`); break; }
    }
    await sleep(3000);
  }
  await shot(page, '02-form-visible');

  // 3. Find the file <input>. Play Console renders a hidden one once the
  //    "Add from library" / "Upload" controls have hydrated.
  console.log('→ locating file input');
  let fileInput = null;
  for (let i = 0; i < 15 && !fileInput; i++) {
    fileInput = await page.$('input[type="file"][accept*=".aab"], input[type="file"]');
    if (!fileInput) {
      console.log(`  (attempt ${i + 1}/15) input not yet present, waiting`);
      await sleep(2500);
    }
  }
  if (!fileInput) {
    await shot(page, '03-no-file-input');
    console.error('❌ no <input type="file"> appeared. The Play Console UI may need a manual click first.');
    console.error('   The Chromium window stays open so you can finish manually.');
    await new Promise(() => {});
  }

  // 4. Push the AAB through the input — works even when the input is hidden.
  console.log(`→ uploading AAB`);
  await fileInput.setInputFiles(AAB_PATH);
  await shot(page, '03-after-setInputFiles');

  // 5. Wait for the bundle to be processed (Play Console shows a spinner
  //    + then a release-name field once it's parsed the AAB).
  console.log('→ waiting for bundle processing (up to 6 min)');
  let processed = false;
  for (let i = 0; i < 72; i++) {
    await sleep(5000);
    const txt = await page.evaluate(() => document.body.innerText || '');
    if (/Release name|What.s new in this release|Release notes/i.test(txt)) {
      processed = true;
      console.log(`  release form populated after ~${(i + 1) * 5}s`);
      break;
    }
    if (i % 6 === 0) await shot(page, `04-processing-${String(i).padStart(2, '0')}`);
  }
  await shot(page, '05-processed');
  if (!processed) {
    console.warn('⚠️  bundle did not finish processing in 6 min. Check the browser; the file may still be uploading.');
  }

  // 6. Fill release notes. Play Console's notes textarea is a
  //    contenteditable [role="textbox"] inside a <gmpx-release-notes-editor>.
  console.log('→ filling release notes');
  try {
    const editor = await page.$('[contenteditable="true"][role="textbox"]')
                 || await page.$('textarea[name*="releaseNotes" i]')
                 || await page.$('textarea');
    if (editor) {
      await editor.click();
      await page.keyboard.press('Control+A');
      await page.keyboard.press('Delete');
      await page.keyboard.type(RELEASE_NOTES, { delay: 5 });
      await shot(page, '06-release-notes-filled');
    } else {
      console.warn('  release-notes box not found; please paste in browser');
    }
  } catch (e) {
    console.warn(`  release-notes fill failed: ${e.message}`);
  }

  // 7. Stop. The user clicks Save / Review release / Start rollout.
  console.log('');
  console.log('✅ AAB uploaded and release notes filled.');
  console.log('   IN THE BROWSER WINDOW, click:  Next → Save → Review release → Start rollout');
  console.log(`   Screenshots: ${OUT}`);
  console.log('');
  console.log('Leaving Chromium open. Press Ctrl-C in this terminal when you are done.');

  // Keep alive so the persistent context (and the Chromium window) stays up.
  await new Promise(() => {});
})().catch((err) => {
  console.error('❌ failed:', err.stack || err.message);
  process.exit(1);
});
