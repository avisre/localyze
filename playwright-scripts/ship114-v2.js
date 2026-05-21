/**
 * ship114-v2.js — discard old v1.1.3 draft PROPERLY, create new release,
 * upload v1.1.4 AAB, fill name+notes, save→review→rollout.
 *
 * Key fixes vs ship114.js:
 *   - Confirm dialog button label is "Discard draft release" (not "Discard")
 *   - Wait for draft URL to change to /tracks/.../releases/<id>/edit before
 *     looking for file input
 *   - Also try clicking "Upload" button to force file input appearance
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
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `ship114v2-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
let stepN = 0;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function shot(page, label) {
  stepN++;
  const lbl = `${String(stepN).padStart(2, '0')}-${label}`;
  try { await page.screenshot({ path: path.join(OUT, `${lbl}.png`), fullPage: false }); } catch {}
  console.log(`📸 ${lbl}`);
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

  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  const page = await ctx.newPage();
  await page.setViewportSize({ width: 1400, height: 900 });

  // STEP 1: open the existing v1.1.3 draft and discard
  console.log('\n=== STEP 1: discard v1.1.3 draft ===');
  await page.goto(PROD_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(7000);

  // Click Releases tab
  await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      if ((el.tagName === 'TAB-BUTTON' || el.getAttribute('role') === 'tab') &&
          ((el.innerText || '').trim() === 'Releases' || el.getAttribute('aria-label') === 'Releases')) {
        el.click(); return;
      }
    }
  });
  await sleep(5000);
  await shot(page, 'releases-tab');

  await clickByText(page, 'Edit release', { afterMs: 7000 });
  await shot(page, 'draft-edit');
  console.log(`  url: ${page.url()}`);

  // Click "Discard draft release" link (top right)
  await clickByText(page, 'Discard draft release', { afterMs: 3000 });
  await shot(page, 'discard-dialog');

  // Confirm — the dialog has a button labeled exactly "Discard draft release"
  // We need to target the one INSIDE the dialog (small button), not the top
  // link we already clicked. Strategy: pick the SMALLEST matching element.
  const confirmed = await clickByText(page, 'Discard draft release', { afterMs: 8000 });
  console.log(`  confirmed: ${confirmed}`);
  await shot(page, 'after-discard');
  console.log(`  url after discard: ${page.url()}`);

  // STEP 2: navigate to Production track again and Create new release
  console.log('\n=== STEP 2: create new release ===');
  await page.goto(PROD_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(7000);
  await shot(page, 'production-after-discard');

  await clickByText(page, 'Create new release', { afterMs: 10000 });
  await shot(page, 'new-release-form');
  console.log(`  url: ${page.url()}`);

  // Wait for /releases/<id>/edit URL
  for (let i = 0; i < 30; i++) {
    if (/\/releases\/\d+\/edit/.test(page.url())) break;
    await sleep(2000);
  }
  console.log(`  release URL: ${page.url()}`);
  await shot(page, 'release-form-loaded');

  // STEP 3: Upload AAB
  console.log('\n=== STEP 3: upload AAB ===');
  let uploaded = false;
  for (let attempt = 0; attempt < 15 && !uploaded; attempt++) {
    const inputs = await page.$$('input[type="file"]');
    console.log(`  attempt ${attempt + 1}: file inputs found: ${inputs.length}`);
    for (const fi of inputs) {
      try {
        const accept = await fi.getAttribute('accept');
        console.log(`    accept=${accept}`);
        await fi.setInputFiles(AAB_PATH);
        console.log(`✓ uploaded AAB`);
        uploaded = true;
        break;
      } catch (e) { console.log(`    err: ${e.message.slice(0, 70)}`); }
    }
    if (!uploaded) await sleep(3000);
  }
  if (!uploaded) {
    console.error('❌ no file input found');
    await shot(page, 'no-input');
    process.exit(2);
  }
  await sleep(4000);
  await shot(page, 'after-upload');

  // Wait for processing
  console.log('→ wait for processing');
  for (let i = 0; i < 90; i++) {
    await sleep(5000);
    const ready = await page.evaluate(() => /1\.1\.4|\(11\)|Release name/i.test(document.body.innerText || ''));
    if (ready) { console.log(`  ready after ~${(i+1)*5}s`); break; }
    if (i % 6 === 0) console.log(`  ...${(i+1)*5}s elapsed`);
  }
  await shot(page, 'processed');

  // STEP 4: Fill name + notes
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
    target.focus(); target.scrollIntoView({block:'center'});
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(target, name);
    target.dispatchEvent(new Event('input', { bubbles: true }));
    target.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  }, RELEASE_NAME);
  await shot(page, 'name-filled');

  await page.evaluate((notes) => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    let target = null;
    for (const el of walk(document)) {
      if (el.getAttribute && el.getAttribute('contenteditable') === 'true' && el.getAttribute('role') === 'textbox') { target = el; break; }
    }
    if (!target) for (const el of walk(document)) if (el.tagName === 'TEXTAREA') { target = el; break; }
    if (!target) return false;
    target.focus(); target.scrollIntoView({block:'center'});
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
  await page.keyboard.press('Tab');
  await sleep(2500);
  await shot(page, 'notes-filled');

  // STEP 5: Next → Save → Review → Rollout
  console.log('\n=== STEP 5: progress to rollout ===');
  await clickByText(page, 'Next', { afterMs: 5500 });
  await shot(page, 'after-next');

  // Check for errors before Save
  const errCount = await page.evaluate(() => {
    const t = document.body.innerText || '';
    const m = t.match(/(\d+)\s+Error/);
    return m ? parseInt(m[1]) : 0;
  });
  console.log(`  errors detected on preview: ${errCount}`);
  await shot(page, 'preview-state');

  await clickByText(page, 'Save', { afterMs: 5500 });
  await shot(page, 'after-save');

  let reviewed = await clickByText(page, 'Review release', { afterMs: 6000 });
  if (!reviewed) reviewed = await clickByText(page, 'Review', { afterMs: 6000 });
  await shot(page, 'after-review');

  let rolled = await clickByText(page, 'Start rollout to Production', { afterMs: 5000 });
  if (!rolled) rolled = await clickByText(page, 'Start rollout', { afterMs: 5000 });
  await shot(page, 'after-rollout');

  for (const lbl of ['Rollout', 'Confirm', 'OK']) {
    if (await clickByText(page, lbl, { afterMs: 5000 })) break;
  }
  await shot(page, 'after-confirm');

  // STEP 6: Publishing → Send for review
  console.log('\n=== STEP 6: send for review ===');
  await page.goto(PUB_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(8000);
  await shot(page, 'publishing');

  const bt = await page.evaluate(() => document.body.innerText || '');
  const has114 = /1\.1\.4|\(11\)/.test(bt);
  const has103 = /1\.0\.3|\(4\)/.test(bt);
  console.log(`  has v1.1.4? ${has114}  has v1.0.3? ${has103}`);

  if (!has114 && has103) {
    console.error('❌ v1.1.4 NOT in queue. Aborting send.');
    process.exit(3);
  }

  let sent = false;
  for (const lbl of ['Send 1 change for review', 'Send 2 changes for review', 'Send 3 changes for review', 'Send for review']) {
    sent = await clickByText(page, lbl, { exact: false, afterMs: 5000 });
    if (sent) break;
  }
  await shot(page, 'send-clicked');

  for (const lbl of ['Send for review', 'Send', 'Confirm']) {
    if (await clickByText(page, lbl, { afterMs: 5000 })) break;
  }
  await shot(page, 'send-confirmed');

  console.log(`\n✅ DONE — v1.1.4 submitted`);
  console.log(`   Output: ${OUT}`);
  process.exit(0);
})().catch((e) => { console.error('❌', e.stack || e.message); process.exit(1); });
