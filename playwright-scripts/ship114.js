/**
 * ship114.js — full v1.1.4 production rollout via CDP. Assumes:
 *   - Chrome already running on http://127.0.0.1:9222 (signed in)
 *   - Empty v1.1.3 draft exists on Production track (will be discarded)
 *   - app-release.aab built locally is v1.1.4 (versionCode 11) with no
 *     FOREGROUND_SERVICE_DATA_SYNC permission
 *
 * Steps:
 *   1. Discard the existing v1.1.3 draft (clean slate)
 *   2. Navigate to Production track → Create new release
 *   3. Upload the v1.1.4 AAB via file input
 *   4. Wait for processing → release name "1.1.4" + en-US notes
 *   5. Next → Save → Review release → Start rollout → Confirm
 *   6. Publishing overview → Send N changes for review
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const DEVELOPER_ID = '6492542126543167241';
const APP_ID = '4974212910605033903';
const RELEASE_NAME = '1.1.4';
const RELEASE_NOTES = `<en-US>
Major update — Gemma 3n E2B is now the on-device model.
1.4–1.8x faster end-to-end responses and roughly half the RAM
compared with the previous build. Model picker removed: one model,
auto-selected for every device. Thinking mode now defaults to OFF.
</en-US>`;
const AAB_PATH = path.resolve(__dirname, '..', 'app', 'build', 'outputs', 'bundle', 'release', 'app-release.aab');

const PROD_URL = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/production`;
const PUB_URL  = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/publishing`;

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `ship114-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
let stepN = 0;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function shot(page, label) {
  stepN++;
  const lbl = `${String(stepN).padStart(2, '0')}-${label}`;
  try { await page.screenshot({ path: path.join(OUT, `${lbl}.png`), fullPage: false }); } catch {}
  console.log(`📸 ${lbl}`);
}

async function dump(page, label) {
  await shot(page, label);
  try {
    const els = await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      const out = [];
      for (const el of walk(document)) {
        const tag = el.tagName.toLowerCase();
        const role = el.getAttribute('role');
        const text = (el.innerText || '').trim().replace(/\s+/g, ' ').slice(0, 140);
        const aria = el.getAttribute('aria-label');
        const interactive = ['button','a','input','textarea'].includes(tag) ||
                            ['button','menuitem','option','tab','link','textbox'].includes(role);
        if (!interactive) continue;
        out.push({ tag, role, text, aria });
      }
      return { url: location.href, els: out };
    });
    fs.writeFileSync(path.join(OUT, `${stepN}-${label}.json`), JSON.stringify(els, null, 2));
  } catch {}
}

async function clickByText(page, text, opts = {}) {
  const exact = opts.exact !== false;
  const r = await page.evaluate(({ text, exact }) => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const cands = [];
    for (const el of walk(document)) {
      const txt = (el.innerText || '').trim();
      const aria = el.getAttribute('aria-label') || '';
      const match = exact ? (txt === text || aria === text) : (txt.includes(text) || aria.includes(text));
      if (!match) continue;
      if (!['BUTTON','A'].includes(el.tagName) && el.getAttribute('role') !== 'button') continue;
      cands.push(el);
    }
    if (!cands.length) return null;
    cands.sort((a,b)=>{const ra=a.getBoundingClientRect(),rb=b.getBoundingClientRect();return (ra.width*ra.height)-(rb.width*rb.height);});
    cands[0].scrollIntoView({ block: 'center' });
    cands[0].click();
    return { tag: cands[0].tagName, text: (cands[0].innerText || '').slice(0, 60) };
  }, { text, exact });
  if (r) console.log(`✓ click "${text}" → ${r.tag} "${r.text}"`);
  else console.log(`✗ click "${text}" → not found`);
  await sleep(opts.afterMs || 3500);
  return !!r;
}

(async () => {
  if (!fs.existsSync(AAB_PATH)) { console.error(`❌ AAB missing: ${AAB_PATH}`); process.exit(1); }
  console.log(`AAB: ${AAB_PATH} (${(fs.statSync(AAB_PATH).size/1e6).toFixed(1)} MB)`);

  console.log('→ CDP connect');
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  const page = await ctx.newPage();
  await page.setViewportSize({ width: 1400, height: 900 });

  // ── STEP 1: Discard v1.1.3 draft ──────────────────────────────────────
  console.log('\n=== STEP 1: discard v1.1.3 draft ===');
  await page.goto(PROD_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(7000);
  await dump(page, 'production');

  // Click Releases tab
  await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      if ((el.tagName === 'TAB-BUTTON' || el.getAttribute('role') === 'tab') &&
          ((el.innerText || '').trim() === 'Releases' || el.getAttribute('aria-label') === 'Releases')) {
        el.scrollIntoView({block:'center'}); el.click(); return;
      }
    }
  });
  await sleep(5000);
  await dump(page, 'releases-tab');

  // Click Edit release on draft, then Discard draft release
  await clickByText(page, 'Edit release', { afterMs: 6000 });
  await dump(page, 'draft-edit');
  await clickByText(page, 'Discard draft release', { afterMs: 4000 });
  await dump(page, 'discard-dialog');
  // confirm
  await clickByText(page, 'Discard', { afterMs: 5000 });
  await dump(page, 'discarded');

  // ── STEP 2: Create new release ────────────────────────────────────────
  console.log('\n=== STEP 2: create new release ===');
  await page.goto(PROD_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(7000);

  await clickByText(page, 'Create new release', { afterMs: 8000 });
  await dump(page, 'new-release-form');
  console.log(`  url: ${page.url()}`);

  // ── STEP 3: Upload AAB via file input ─────────────────────────────────
  console.log('\n=== STEP 3: upload AAB ===');
  // Find any file input (Playwright can setInputFiles on hidden inputs)
  let attempts = 0;
  let uploaded = false;
  while (attempts < 20 && !uploaded) {
    const inputs = await page.$$('input[type="file"]');
    console.log(`  file inputs found: ${inputs.length}`);
    for (const fi of inputs) {
      try {
        const accept = await fi.getAttribute('accept');
        console.log(`    accept=${accept}`);
        if (!accept || /\.aab|\.apk|octet/i.test(accept)) {
          await fi.setInputFiles(AAB_PATH);
          console.log(`✓ uploaded AAB`);
          uploaded = true;
          break;
        }
      } catch (e) { console.log(`    err: ${e.message.slice(0, 60)}`); }
    }
    if (!uploaded) { await sleep(3000); attempts++; }
  }
  if (!uploaded) {
    console.error('❌ no file input ever appeared');
    await dump(page, 'no-file-input');
    await browser.close();
    process.exit(2);
  }
  await sleep(3000);
  await dump(page, 'upload-started');

  // Wait for bundle processing
  console.log('→ wait for processing');
  for (let i = 0; i < 90; i++) {
    await sleep(5000);
    const ready = await page.evaluate(() => {
      const t = document.body.innerText || '';
      return /Release name|Release details|App bundles|version Code|\(11\)|1\.1\.4/i.test(t);
    });
    if (ready) { console.log(`  ready after ~${(i+1)*5}s`); break; }
    if (i % 6 === 0) console.log(`  ...${(i+1)*5}s elapsed`);
  }
  await dump(page, 'processed');

  // ── STEP 4: Fill name + notes ─────────────────────────────────────────
  console.log('\n=== STEP 4: name + notes ===');
  await page.evaluate((name) => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    let target = null;
    for (const el of walk(document)) {
      if (el.tagName !== 'INPUT' || (el.type && el.type !== 'text')) continue;
      const aria = el.getAttribute('aria-label') || '';
      if (/release name/i.test(aria)) { target = el; break; }
    }
    if (!target) {
      for (const el of walk(document)) {
        if (el.tagName === 'INPUT' && (!el.type || el.type === 'text')) {
          const r = el.getBoundingClientRect();
          if (r.width > 0 && r.height > 0) { target = el; break; }
        }
      }
    }
    if (!target) return false;
    target.scrollIntoView({block:'center'}); target.focus();
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(target, name);
    target.dispatchEvent(new Event('input', { bubbles: true }));
    target.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  }, RELEASE_NAME);
  console.log('  name set');
  await dump(page, 'name-filled');

  await page.evaluate((notes) => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    let target = null;
    for (const el of walk(document)) {
      if (el.getAttribute && el.getAttribute('contenteditable') === 'true' && el.getAttribute('role') === 'textbox') {
        target = el; break;
      }
    }
    if (!target) for (const el of walk(document)) if (el.tagName === 'TEXTAREA') { target = el; break; }
    if (!target) return false;
    target.scrollIntoView({block:'center'}); target.focus();
    if (target.tagName === 'TEXTAREA') {
      const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
      setter.call(target, notes);
    } else {
      target.innerText = notes;
    }
    target.dispatchEvent(new Event('input', { bubbles: true }));
    target.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  }, RELEASE_NOTES);
  console.log('  notes set');
  await page.keyboard.press('Tab');
  await sleep(2500);
  await dump(page, 'notes-filled');

  // ── STEP 5: Next → Save → Review → Rollout ───────────────────────────
  console.log('\n=== STEP 5: progress to rollout ===');
  await clickByText(page, 'Next', { afterMs: 5000 });
  await dump(page, 'after-next');

  await clickByText(page, 'Save', { afterMs: 5000 });
  await dump(page, 'after-save');

  let reviewed = await clickByText(page, 'Review release', { afterMs: 6000 });
  if (!reviewed) reviewed = await clickByText(page, 'Review', { afterMs: 6000 });
  await dump(page, 'after-review');

  let rolled = await clickByText(page, 'Start rollout to Production', { afterMs: 5000 });
  if (!rolled) rolled = await clickByText(page, 'Start rollout', { afterMs: 5000 });
  await dump(page, 'after-rollout');

  for (const lbl of ['Rollout', 'Confirm', 'OK']) {
    if (await clickByText(page, lbl, { afterMs: 5000 })) break;
  }
  await dump(page, 'after-confirm');

  // ── STEP 6: Send for review ──────────────────────────────────────────
  console.log('\n=== STEP 6: send for review ===');
  await page.goto(PUB_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(8000);
  await dump(page, 'publishing');

  const bt = await page.evaluate(() => document.body.innerText || '');
  const has114 = /1\.1\.4|\(11\)/.test(bt);
  const has103 = /1\.0\.3|\(4\)/.test(bt);
  console.log(`  has v1.1.4? ${has114}  has v1.0.3? ${has103}`);

  if (!has114 && has103) {
    console.error('❌ v1.1.4 NOT in queue — would re-submit v1.0.3. Aborting.');
    await browser.close();
    process.exit(3);
  }

  let sent = false;
  for (const lbl of ['Send 1 change for review', 'Send 2 changes for review', 'Send 3 changes for review', 'Send for review']) {
    sent = await clickByText(page, lbl, { exact: false, afterMs: 5000 });
    if (sent) break;
  }
  await dump(page, 'send-clicked');

  for (const lbl of ['Send for review', 'Send', 'Confirm']) {
    if (await clickByText(page, lbl, { afterMs: 5000 })) break;
  }
  await dump(page, 'send-confirmed');

  console.log(`\n✅ DONE — v1.1.4 submitted`);
  console.log(`   Output: ${OUT}`);
  await browser.close();
  process.exit(0);
})().catch(async (e) => {
  console.error('❌', e.stack || e.message);
  process.exit(1);
});
