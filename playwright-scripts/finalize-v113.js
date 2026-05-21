/**
 * finalize-v113.js — finish the Localyze v1.1.3 Production release.
 * Pre-state: AAB versionCode 10 / 1.1.3 already in bundle library; an
 * empty Draft exists on Production.
 *
 * Steps:
 *   1. Open Production track
 *   2. Find the draft's edit URL and navigate to it
 *   3. Click "Add from library" → modal → click bundle 1.1.3 → "Add to release"
 *   4. Fill Release name "1.1.3" + en-US notes
 *   5. Next → Save → Review release → Start rollout to Production → Confirm
 *   6. (if applicable) Send for review on Publishing overview
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PROFILE = path.join(os.homedir(), '.cache', 'play-console-profile');
const DEVELOPER_ID = '6492542126543167241';
const APP_ID = '4974212910605033903';
const PINNED = path.join(os.homedir(), '.cache', 'ms-playwright', 'chromium-1217', 'chrome-linux64', 'chrome');
const RELEASE_NAME = '1.1.3';
const RELEASE_NOTES = `<en-US>
Major update — Gemma 3n E2B is now the on-device model.
1.4–1.8x faster end-to-end responses and roughly half the RAM
compared with the previous build. Model picker removed: one model,
auto-selected for every device. Thinking mode now defaults to OFF.
</en-US>`;

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `finalize-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
let n = 0;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function shot(page, label) {
  n++;
  const f = path.join(OUT, `${String(n).padStart(2, '0')}-${label}.png`);
  try { await page.screenshot({ path: f }); console.log(`📸 ${path.basename(f)}`); } catch {}
}

async function jsClick(page, regex, desc, opts = {}) {
  const reSrc = (regex instanceof RegExp ? regex.source : regex);
  const found = await page.evaluate((reStr) => {
    const re = new RegExp(reStr);
    const seen = new Set();
    const q = [document];
    const matches = [];
    while (q.length) {
      const root = q.shift();
      if (seen.has(root)) continue;
      seen.add(root);
      for (const el of root.querySelectorAll('*')) {
        if (el.shadowRoot) q.push(el.shadowRoot);
        const txt = (el.innerText || el.textContent || '').trim();
        if (!txt || txt.length > 80) continue;
        if (!re.test(txt)) continue;
        const interactive = ['BUTTON', 'A'].includes(el.tagName) ||
                            el.getAttribute('role') === 'button' ||
                            el.getAttribute('role') === 'menuitem' ||
                            el.getAttribute('role') === 'option' ||
                            el.getAttribute('tabindex') === '0' ||
                            el.onclick !== null;
        if (interactive) matches.push(el);
      }
    }
    if (!matches.length) return null;
    matches.sort((a, b) => {
      const ra = a.getBoundingClientRect();
      const rb = b.getBoundingClientRect();
      return (ra.width * ra.height) - (rb.width * rb.height);
    });
    matches[0].scrollIntoView({ block: 'center' });
    matches[0].click();
    return { tag: matches[0].tagName, text: (matches[0].innerText || '').slice(0, 60) };
  }, reSrc);
  if (found) {
    console.log(`✓ click "${desc}" → ${found.tag}: "${found.text}"`);
    await sleep(opts.afterMs || 3500);
    return true;
  }
  console.log(`✗ "${desc}" — no match`);
  return false;
}

(async () => {
  const opts = { headless: false, viewport: { width: 1400, height: 900 } };
  if (fs.existsSync(PINNED)) opts.executablePath = PINNED;
  const ctx = await chromium.launchPersistentContext(PROFILE, opts);
  const page = ctx.pages()[0] || (await ctx.newPage());

  // 1. Go to "Latest releases and bundles" — that page reliably lists the
  //    draft with a direct link to it.
  const latestUrl = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/latest-releases-and-bundles`;
  console.log(`→ ${latestUrl}`);
  await page.goto(latestUrl, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(8000);
  await shot(page, 'latest-releases');

  console.log('→ scrape draft edit URL');
  let editUrl = await page.evaluate(() => {
    const links = Array.from(document.querySelectorAll('a[href*="releases/"]'));
    // Prefer URLs containing /tracks/production/releases/ — that's our target.
    const prodCands = links.filter((a) => /\/tracks\/production\/releases\/[^/]+/.test(a.href));
    if (prodCands.length) return prodCands[0].href;
    // Otherwise any release link.
    return links.length ? links[0].href : null;
  });
  console.log(`  found: ${editUrl}`);
  if (!editUrl) {
    const any = await page.evaluate(() =>
      Array.from(document.querySelectorAll('a[href*="releases/"]'))
        .map((a) => a.href).slice(0, 10));
    console.log(`  any release links: ${JSON.stringify(any)}`);
    console.error('❌ no draft edit link');
    await shot(page, 'no-edit-link');
    await new Promise(() => {});
  }
  const target = editUrl.endsWith('/edit') ? editUrl : `${editUrl}/edit`;
  console.log(`→ goto ${target}`);
  await page.goto(target, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(7000);
  await shot(page, 'draft-edit');

  console.log('→ Add from library');
  await jsClick(page, /^Add from library$/, 'Add from library', { afterMs: 5000 });
  await shot(page, 'lib-modal');

  console.log('→ pick 1.1.3 bundle');
  let picked = await jsClick(page, /^1\.1\.3$/, '1.1.3 bundle row', { afterMs: 2500 });
  if (!picked) picked = await jsClick(page, /1\.1\.3.*10|10.*1\.1\.3/, 'row with 10/1.1.3', { afterMs: 2500 });
  if (!picked) picked = await jsClick(page, /^10$/, 'versionCode 10', { afterMs: 2500 });
  await shot(page, 'bundle-picked');

  console.log('→ Add to release');
  await jsClick(page, /^(Add to release|Add|OK|Save changes)$/, 'Add to release', { afterMs: 5000 });
  await shot(page, 'bundle-added');

  console.log('→ release name');
  try {
    const inputs = await page.$$('input[type="text"]:visible, input[type="text"]:not([hidden])');
    const inp = inputs[0] || (await page.$('input[type="text"]'));
    if (inp) {
      await inp.click();
      await page.keyboard.press('Control+A');
      await page.keyboard.press('Delete');
      await page.keyboard.type(RELEASE_NAME, { delay: 20 });
    }
  } catch (e) { console.log(`  name err: ${e.message.slice(0, 70)}`); }
  await shot(page, 'name-filled');

  console.log('→ release notes');
  try {
    const tas = await page.$$('textarea');
    if (tas.length) {
      const ta = tas[tas.length - 1];
      await ta.click();
      await page.keyboard.press('Control+A');
      await page.keyboard.press('Delete');
      await page.keyboard.type(RELEASE_NOTES, { delay: 3 });
    }
  } catch (e) { console.log(`  notes err: ${e.message.slice(0, 70)}`); }
  await page.keyboard.press('Tab');
  await sleep(2500);
  await shot(page, 'notes-filled');

  await jsClick(page, /^Next$/, 'Next', { afterMs: 4500 });
  await shot(page, 'next');
  await jsClick(page, /^Save$/, 'Save', { afterMs: 4500 });
  await shot(page, 'save');
  await jsClick(page, /^Review release$/, 'Review release', { afterMs: 6000 });
  await shot(page, 'review');
  let rolled = await jsClick(page, /^Start rollout to Production$/, 'Start rollout to Production', { afterMs: 5000 });
  if (!rolled) rolled = await jsClick(page, /^Start rollout$/, 'Start rollout', { afterMs: 5000 });
  if (!rolled) rolled = await jsClick(page, /^Send \d+ changes? for review$/, 'Send N for review', { afterMs: 5000 });
  await shot(page, 'rollout');
  await jsClick(page, /^(Rollout|Confirm|Send)$/, 'Confirm dialog', { afterMs: 5000 });
  await shot(page, 'confirmed');

  console.log('→ Publishing overview check');
  const pubUrl = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/publishing`;
  await page.goto(pubUrl, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(6000);
  await shot(page, 'publishing');
  await jsClick(page, /^Send \d+ changes? for review$/, 'Send N for review', { afterMs: 5000 });
  await shot(page, 'send-for-review');
  await jsClick(page, /^(Send for review|Confirm|Send)$/, 'Confirm send', { afterMs: 5000 });
  await shot(page, 'confirm-send');

  console.log('\n✅ DONE — v1.1.3 submission attempted end-to-end.');
  console.log(`   Screenshots: ${OUT}`);
  await new Promise(() => {});
})().catch((e) => { console.error('❌', e.stack || e.message); process.exit(1); });
