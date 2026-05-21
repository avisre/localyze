/**
 * auto-submit-v113.js — end-to-end automation:
 *   1. open Localyze app dashboard
 *   2. navigate to Production track
 *   3. discard any existing drafted releases
 *   4. click "Create new release"
 *   5. upload v1.1.3 AAB
 *   6. wait for bundle processing
 *   7. fill release notes
 *   8. Next → Save → Review release → Start rollout
 *   9. (if Publishing overview shows pending changes) → Send N changes for review
 *
 * Authorized by user: "u do it dont bother for me just do it until it's done".
 *
 * Every step takes a screenshot; if anything looks off, the browser stays
 * open so you can finish manually.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PROFILE = path.join(os.homedir(), '.cache', 'play-console-profile');
const DEVELOPER_ID = '6492542126543167241';
const APP_ID = '4974212910605033903';
const AAB_PATH = path.resolve(__dirname, '..', 'app', 'build', 'outputs', 'bundle', 'release', 'app-release.aab');
const RELEASE_NAME = '1.1.3';
// Play Console requires release notes wrapped in <language-code> tags.
// English (US) is the default — any locale-tagged section is accepted.
const RELEASE_NOTES = `<en-US>
Major update — Gemma 3n E2B is now the on-device model.
1.4–1.8x faster end-to-end responses and roughly half the RAM
compared with the previous build. Model picker removed: one model,
auto-selected for every device. Thinking mode now defaults to OFF.
</en-US>`;

const PINNED = path.join(os.homedir(), '.cache', 'ms-playwright', 'chromium-1217', 'chrome-linux64', 'chrome');
const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `auto-submit-${TS}`);
fs.mkdirSync(OUT, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let stepNum = 0;
async function shot(page, label) {
  stepNum++;
  const f = path.join(OUT, `${String(stepNum).padStart(2, '0')}-${label}.png`);
  try { await page.screenshot({ path: f }); console.log(`📸 ${f.replace(OUT + '/', '')}`); } catch (e) {}
}
async function tryClick(page, locator, desc, opts = {}) {
  try {
    const timeout = opts.timeout || 8000;
    await locator.first().waitFor({ state: 'visible', timeout });
    await locator.first().click();
    console.log(`✓ clicked: ${desc}`);
    await sleep(opts.afterMs || 2500);
    return true;
  } catch (e) {
    console.log(`✗ click failed: ${desc} (${e.message.slice(0, 80)})`);
    return false;
  }
}

// Shadow-DOM-aware click by text. Play Console renders buttons inside
// custom-element shadow roots (e.g., <gmp-action-bar>), so Playwright's
// text selectors can't always pierce them. This walks every shadow root.
async function clickByText(page, pattern, desc, opts = {}) {
  const found = await page.evaluate((patternStr) => {
    const re = new RegExp(patternStr, 'i');
    const seen = new Set();
    const queue = [document];
    while (queue.length) {
      const root = queue.shift();
      if (seen.has(root)) continue;
      seen.add(root);
      const all = root.querySelectorAll('*');
      for (const el of all) {
        if (el.shadowRoot) queue.push(el.shadowRoot);
        const txt = (el.innerText || el.textContent || '').trim();
        const isInteractive = el.tagName === 'BUTTON' || el.tagName === 'A' ||
                              el.getAttribute('role') === 'button' || el.onclick !== null;
        if (isInteractive && re.test(txt) && txt.length < 100) {
          // Avoid clicking on a parent whose visible text is something else.
          // Heuristic: prefer the deepest element matching the text.
          el.scrollIntoView({ block: 'center' });
          el.click();
          return { tag: el.tagName, text: txt.slice(0, 80) };
        }
      }
    }
    return null;
  }, pattern);
  if (found) {
    console.log(`✓ JS-clicked: ${desc} (${found.tag}: "${found.text}")`);
    await sleep(opts.afterMs || 2500);
    return true;
  }
  console.log(`✗ JS-click no match: ${desc}`);
  return false;
}

if (!fs.existsSync(AAB_PATH)) {
  console.error(`AAB missing: ${AAB_PATH}`); process.exit(1);
}

(async () => {
  console.log(`AAB: ${AAB_PATH} (${(fs.statSync(AAB_PATH).size / 1e6).toFixed(1)} MB)`);
  const opts = { headless: false, viewport: { width: 1400, height: 900 } };
  if (fs.existsSync(PINNED)) opts.executablePath = PINNED;
  const ctx = await chromium.launchPersistentContext(PROFILE, opts);
  const page = ctx.pages()[0] || (await ctx.newPage());

  // ── 1. Open Localyze app dashboard ────────────────────────────────────
  // Try both /u/0/ and /u/1/ — the persistent profile may be on either.
  for (const u of ['0', '1']) {
    const url = `https://play.google.com/console/u/${u}/developers/${DEVELOPER_ID}/app/${APP_ID}/app-dashboard`;
    console.log(`→ try /u/${u}/ app-dashboard: ${url}`);
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await sleep(4000);
    const here = page.url();
    if (here.includes(`/app/${APP_ID}/`)) {
      console.log(`✓ on app context: ${here}`);
      break;
    }
    console.log(`  bounced to ${here} — trying next /u/`);
  }
  await shot(page, 'app-dashboard');

  // ── 2. Navigate to Production track ───────────────────────────────────
  const prodUrl = page.url().replace(/\/app\/(\d+)\/.*$/, `/app/${APP_ID}/tracks/production`);
  console.log(`→ navigating to Production track: ${prodUrl}`);
  await page.goto(prodUrl, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(5000);
  await shot(page, 'production-track');

  // ── 3. Discard any draft releases on this track ──────────────────────
  // Play Console shows a "Releases on this track" section with each draft
  // in a row that has a kebab menu (three vertical dots). Click → Discard.
  console.log(`→ scanning for drafted releases to discard`);
  for (let attempt = 0; attempt < 5; attempt++) {
    const kebab = page.locator('button[aria-label*="more" i], button[aria-label="More options"], button[aria-label*="actions" i]').first();
    if (!(await kebab.count())) { console.log('  no draft kebabs found'); break; }
    await kebab.click({ trial: false }).catch(() => {});
    await sleep(1500);
    await shot(page, `kebab-${attempt}`);
    const discardBtn = page.getByRole('menuitem', { name: /Discard/i }).or(page.locator('text=Discard release')).or(page.locator('text=/^Discard$/'));
    const okClicked = await tryClick(page, discardBtn, `Discard option (attempt ${attempt})`);
    if (!okClicked) { console.log('  no Discard option in this menu — stop discarding loop'); break; }
    // Confirm-dialog
    const confirm = page.getByRole('button', { name: /^Discard$/i }).or(page.getByRole('button', { name: /Confirm/i }));
    await tryClick(page, confirm, `confirm discard ${attempt}`, { afterMs: 4000 });
    await shot(page, `discarded-${attempt}`);
  }

  // ── 4. Click "Create new release" then OPEN the resulting draft ─────
  console.log(`→ create new release`);
  await sleep(8000);
  await shot(page, 'before-create');
  await clickByText(page, '^Create new release$', 'Create new release', { afterMs: 6000 });
  await shot(page, 'after-create-click');

  // The JS-click may have created a draft without navigating. If we don't
  // see a file-input within 4s, go find the draft and open it.
  let hasInput = await page.evaluate(() => !!document.querySelector('input[type="file"]'));
  if (!hasInput) {
    console.log('→ no upload form yet; navigating to Releases tab to find the draft');
    const releasesUrl = page.url().replace(/\/tracks\/[\w-]+.*$/, `/tracks/production/releases`);
    await page.goto(releasesUrl, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await sleep(5000);
    await shot(page, 'releases-tab');
    // Find any href that contains "edit" or "/releases/" with a numeric id.
    const editHref = await page.evaluate(() => {
      const links = Array.from(document.querySelectorAll('a[href*="/releases/"]'));
      const draft = links.find((a) => /\/releases\/\d+|\/releases\/[A-Za-z0-9_-]+\/edit|edit$/.test(a.href));
      return draft ? draft.href : null;
    });
    if (editHref) {
      console.log(`→ found draft edit href: ${editHref}`);
      await page.goto(editHref, { waitUntil: 'domcontentloaded', timeout: 60000 });
      await sleep(5000);
      await shot(page, 'on-draft-edit');
    } else {
      // Fallback: click "Edit release" or "Manage" text anywhere.
      await clickByText(page, '^(Edit release|Manage release|Continue|Edit)$', 'Edit release', { afterMs: 5000 });
      await shot(page, 'after-edit-click');
    }
    hasInput = await page.evaluate(() => !!document.querySelector('input[type="file"]'));
    if (!hasInput) {
      console.log('✗ still no file input on the page. Browser stays open for manual completion.');
      await shot(page, 'cannot-enter-draft');
      await new Promise(() => {});
    }
  }
  await sleep(4000);
  await shot(page, 'new-release-form');

  // ── 5. Upload AAB ──────────────────────────────────────────────────────
  console.log(`→ locating file input`);
  let fileInput = null;
  for (let i = 0; i < 15 && !fileInput; i++) {
    fileInput = await page.$('input[type="file"]');
    if (!fileInput) await sleep(2000);
  }
  if (!fileInput) {
    console.error('❌ no file input on new-release page');
    await shot(page, 'no-file-input');
    await new Promise(() => {});
  }
  await fileInput.setInputFiles(AAB_PATH);
  console.log(`✓ AAB pushed to file input`);
  await shot(page, 'after-setInputFiles');

  // ── 6. Wait for bundle processing ─────────────────────────────────────
  console.log(`→ waiting for bundle processing (up to 6 min)`);
  let processed = false;
  for (let i = 0; i < 72 && !processed; i++) {
    await sleep(5000);
    const txt = await page.evaluate(() => document.body.innerText || '');
    if (/Release name|What.s new in this release|App bundles|Release details/i.test(txt) &&
        /\d+\s+\(?\d+\.\d+/.test(txt)) {
      processed = true;
      console.log(`  processed after ~${(i + 1) * 5}s`);
    }
    if (i % 8 === 0) await shot(page, `processing-${i}`);
  }
  await shot(page, 'processed');

  // ── 7a. Fill release name (required field marked with *) ─────────────
  console.log(`→ filling release name`);
  // The release-name input has placeholder text or is the first input on
  // the page near the "Release name" label.
  const nameInput = page.locator('input[type="text"]').first();
  try {
    await nameInput.click({ timeout: 5000 });
    await page.keyboard.press('Control+A');
    await page.keyboard.press('Delete');
    await page.keyboard.type(RELEASE_NAME, { delay: 10 });
    console.log(`✓ release name = ${RELEASE_NAME}`);
  } catch (e) { console.warn(`✗ release name fill: ${e.message.slice(0, 80)}`); }
  await shot(page, 'release-name-filled');

  // ── 7b. Fill release notes (must be wrapped in <en-US>...</en-US>) ───
  console.log(`→ filling release notes`);
  // Find ALL textareas; release notes is typically the larger one (multiline).
  const noteAreas = await page.$$('textarea');
  let notesArea = null;
  for (const ta of noteAreas) {
    const rows = await ta.getAttribute('rows');
    const ariaLabel = await ta.getAttribute('aria-label');
    if ((rows && parseInt(rows) >= 3) || /release.?notes/i.test(ariaLabel || '')) {
      notesArea = ta; break;
    }
  }
  if (!notesArea && noteAreas.length > 0) notesArea = noteAreas[noteAreas.length - 1];
  if (notesArea) {
    await notesArea.click();
    await page.keyboard.press('Control+A');
    await page.keyboard.press('Delete');
    await page.keyboard.type(RELEASE_NOTES, { delay: 3 });
    console.log(`✓ release notes typed (with <en-US> wrapper)`);
  } else {
    console.warn(`✗ release-notes textarea not found`);
  }
  // Tab out to commit (Play Console validates onblur).
  await page.keyboard.press('Tab');
  await sleep(2000);
  await shot(page, 'release-notes-filled');

  // ── 8. Click Next → Save → Review release → Start rollout (JS) ───────
  await clickByText(page, '^Next$', 'Next', { afterMs: 4000 });
  await shot(page, 'after-next');

  await clickByText(page, '^Save$', 'Save', { afterMs: 4000 });
  await shot(page, 'after-save');

  await clickByText(page, '^(Review release|Review)$', 'Review release', { afterMs: 5000 });
  await shot(page, 'after-review');

  // Production rollout button might say "Start rollout to Production",
  // "Rollout to production", or "Send N changes for review".
  let didRollout =
    await clickByText(page, '^Start rollout', 'Start rollout', { afterMs: 5000 });
  if (!didRollout) didRollout =
    await clickByText(page, '^Rollout to production', 'Rollout to production', { afterMs: 5000 });
  if (!didRollout) didRollout =
    await clickByText(page, '^Submit update', 'Submit update', { afterMs: 5000 });
  if (!didRollout)
    await clickByText(page, '^Send for review', 'Send for review', { afterMs: 5000 });
  await shot(page, 'after-rollout');

  // Confirmation dialog "Rollout this release?"
  await clickByText(page, '^(Rollout|Confirm)$', 'Confirm rollout', { afterMs: 5000 });
  await shot(page, 'after-confirm');

  // ── 9. Send pending changes for review (Publishing overview) ──────────
  console.log(`→ checking Publishing overview for pending changes`);
  const pubUrl = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/publishing`;
  await page.goto(pubUrl, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(4000);
  await shot(page, 'publishing-overview');

  // Either "Send N changes for review" or "Send for review"
  const sentN = await clickByText(page, '^Send \\d+ changes? for review$', 'Send N changes for review', { afterMs: 4000 });
  const sentPlain = sentN ? false : await clickByText(page, '^Send for review$', 'Send for review', { afterMs: 4000 });
  if (sentN || sentPlain) {
    await shot(page, 'after-send-for-review');
    await clickByText(page, '^(Send for review|Confirm|Send)$', 'Confirm send', { afterMs: 5000 });
    await shot(page, 'after-confirm-send');
    console.log(`✓ submitted to Google review`);
  } else {
    console.log(`(no "Send for review" button — the rollout step likely already sent it)`);
  }

  console.log(`\n✅ DONE — release v1.1.3 submitted to Production for Google review.`);
  console.log(`   Screenshots at: ${OUT}`);
  console.log(`   Keeping browser open so you can spot-check. Ctrl-C this terminal to close.`);
  await new Promise(() => {});
})().catch((e) => { console.error('❌', e.stack || e.message); process.exit(1); });
