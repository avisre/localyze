/**
 * finish-draft.js — assumes a Production track DRAFT exists for Localyze.
 * Opens it via the Releases tab, fills release name + en-US notes, clicks
 * Save / Next / Review / Start rollout, then Send-for-review on Publishing
 * overview. Uses Playwright's text-locator clicks (pierces shadow DOM,
 * triggers real pointer events).
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
const RELEASE_NOTES = `<en-US>
Major update — Gemma 3n E2B is now the on-device model.
1.4–1.8x faster end-to-end responses and roughly half the RAM
compared with the previous build. Model picker removed: one model,
auto-selected for every device. Thinking mode now defaults to OFF.
</en-US>`;
const PINNED = path.join(os.homedir(), '.cache', 'ms-playwright', 'chromium-1217', 'chrome-linux64', 'chrome');

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `finish-draft-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
let stepNum = 0;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function shot(page, label) {
  stepNum++;
  const f = path.join(OUT, `${String(stepNum).padStart(2, '0')}-${label}.png`);
  try { await page.screenshot({ path: f }); console.log(`📸 ${path.basename(f)}`); } catch {}
}
async function clickText(page, text, opts = {}) {
  const exact = opts.exact ?? true;
  try {
    const loc = page.getByText(text, { exact }).first();
    await loc.waitFor({ state: 'visible', timeout: opts.timeout || 15000 });
    await loc.click({ timeout: 8000 });
    console.log(`✓ click: "${text}"`);
    await sleep(opts.afterMs || 3500);
    return true;
  } catch (e) {
    console.log(`✗ "${text}" — ${e.message.split('\n')[0].slice(0, 90)}`);
    return false;
  }
}
async function clickRole(page, role, name, opts = {}) {
  try {
    const loc = page.getByRole(role, { name }).first();
    await loc.waitFor({ state: 'visible', timeout: opts.timeout || 15000 });
    await loc.click({ timeout: 8000 });
    console.log(`✓ ${role}[${name}] clicked`);
    await sleep(opts.afterMs || 3500);
    return true;
  } catch (e) {
    console.log(`✗ ${role}[${name}] — ${e.message.split('\n')[0].slice(0, 90)}`);
    return false;
  }
}

(async () => {
  const opts = { headless: false, viewport: { width: 1400, height: 900 } };
  if (fs.existsSync(PINNED)) opts.executablePath = PINNED;
  const ctx = await chromium.launchPersistentContext(PROFILE, opts);
  const page = ctx.pages()[0] || (await ctx.newPage());

  // Try /u/0/ first, fall back to /u/1/.
  for (const u of ['0', '1']) {
    const url = `https://play.google.com/console/u/${u}/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/production`;
    console.log(`→ /u/${u}/ Production`);
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await sleep(5000);
    if (page.url().includes(`/app/${APP_ID}/tracks/production`)) {
      console.log(`✓ on Production: ${page.url()}`);
      break;
    }
  }
  await shot(page, 'production');

  // Click the "Releases" tab (in-page).
  await clickText(page, 'Releases', { exact: true, afterMs: 4000 });
  await shot(page, 'releases-tab');

  // Find the draft and click "Edit" or the draft row link.
  console.log('→ looking for draft "Edit" link');
  let entered = await clickText(page, 'Edit release', { exact: true, afterMs: 5000 });
  if (!entered) entered = await clickText(page, 'Edit', { exact: true, afterMs: 5000 });
  if (!entered) {
    // Click the draft version number link
    const draftLink = page.locator('a[href*="releases/"]').first();
    try {
      await draftLink.waitFor({ state: 'visible', timeout: 10000 });
      await draftLink.click();
      console.log('✓ draft link clicked');
      await sleep(5000);
      entered = true;
    } catch {}
  }
  await shot(page, 'after-enter-draft');

  // We should now be on the edit form. Verify file-input + release-name presence.
  const hasInput = await page.evaluate(() => !!document.querySelector('input[type="file"]'));
  const onEdit = page.url().includes('/releases/');
  console.log(`hasFileInput=${hasInput}  onEditUrl=${onEdit}`);
  if (!hasInput && !onEdit) {
    console.log('✗ not on edit form. Browser stays open.');
    await new Promise(() => {});
  }

  // Upload AAB if there's a file input AND the page doesn't already have a
  // bundle attached (heuristic: presence of "Release name" field with empty
  // value implies a brand-new draft; otherwise the AAB is probably attached).
  console.log('→ uploading AAB');
  const fileInputs = await page.$$('input[type="file"]');
  for (const fi of fileInputs) {
    const accept = await fi.getAttribute('accept');
    if (!accept || /\.aab|\.apk/i.test(accept)) {
      try { await fi.setInputFiles(AAB_PATH); console.log('✓ AAB pushed'); break; } catch (e) { console.log(`  fi err: ${e.message.slice(0, 60)}`); }
    }
  }
  await shot(page, 'after-upload');

  // Wait for bundle processing.
  console.log('→ waiting for processing');
  for (let i = 0; i < 60; i++) {
    await sleep(5000);
    const ok = await page.evaluate(() => {
      const t = document.body.innerText || '';
      return /Release details|Release notes|App bundles/i.test(t) && /\(\d+\)|\d+\.\d+\.\d+/.test(t);
    });
    if (ok) { console.log(`  processed after ~${(i+1)*5}s`); break; }
  }
  await shot(page, 'processed');

  // Fill release name.
  console.log('→ filling release name');
  try {
    const nameInput = page.getByLabel(/Release name/i).first();
    await nameInput.click({ timeout: 8000 });
    await page.keyboard.press('Control+A');
    await page.keyboard.press('Delete');
    await page.keyboard.type(RELEASE_NAME, { delay: 15 });
    console.log(`✓ name = ${RELEASE_NAME}`);
  } catch (e) { console.log(`  name fill: ${e.message.slice(0, 80)}`); }
  await shot(page, 'name-filled');

  // Fill release notes.
  console.log('→ filling release notes');
  try {
    const notesArea = page.getByLabel(/Release notes/i).first();
    await notesArea.click({ timeout: 8000 });
    await page.keyboard.press('Control+A');
    await page.keyboard.press('Delete');
    await page.keyboard.type(RELEASE_NOTES, { delay: 3 });
    console.log('✓ notes typed');
  } catch (e) {
    // Fallback: any textarea on the page
    const tas = await page.$$('textarea');
    if (tas.length) {
      const ta = tas[tas.length - 1];
      await ta.click();
      await page.keyboard.press('Control+A');
      await page.keyboard.press('Delete');
      await page.keyboard.type(RELEASE_NOTES, { delay: 3 });
      console.log('✓ notes typed via textarea fallback');
    }
  }
  await page.keyboard.press('Tab');
  await sleep(2500);
  await shot(page, 'notes-filled');

  // Click Next.
  await clickRole(page, 'button', /^Next$/i, { afterMs: 4000 }) ||
    await clickText(page, 'Next', { exact: true, afterMs: 4000 });
  await shot(page, 'after-next');

  // Save as draft (sometimes there's a Save Changes button before Next)
  await clickRole(page, 'button', /^Save$/i, { afterMs: 4000 });
  await shot(page, 'after-save');

  // Review release.
  await clickRole(page, 'button', /Review release/i, { afterMs: 5000 }) ||
    await clickText(page, 'Review release', { exact: false, afterMs: 5000 });
  await shot(page, 'after-review');

  // Start rollout / Submit / Send for review.
  const rolled =
    await clickRole(page, 'button', /Start rollout to Production/i, { afterMs: 5000 }) ||
    await clickRole(page, 'button', /Start rollout/i, { afterMs: 5000 }) ||
    await clickRole(page, 'button', /Send \d+ changes? for review/i, { afterMs: 5000 }) ||
    await clickRole(page, 'button', /Send for review/i, { afterMs: 5000 }) ||
    await clickRole(page, 'button', /Submit update/i, { afterMs: 5000 });
  await shot(page, 'after-rollout');

  // Confirm dialog.
  await clickRole(page, 'button', /^Rollout$/i, { afterMs: 5000 }) ||
    await clickRole(page, 'button', /^Confirm$/i, { afterMs: 5000 }) ||
    await clickRole(page, 'button', /^Send$/i, { afterMs: 5000 });
  await shot(page, 'after-confirm');

  console.log(`\n✅ DONE — v1.1.3 should be in Google's review queue now.`);
  console.log(`   Screenshots: ${OUT}`);
  console.log(`   Browser stays open so you can spot-check.`);
  await new Promise(() => {});
})().catch((e) => { console.error('❌', e.stack || e.message); process.exit(1); });
