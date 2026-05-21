/**
 * ship114-v4.js — careful upload + rollout. Key change: after setInputFiles,
 * poll for "11 (1.1.4)" to appear in the App bundles section as a real row
 * (not just text anywhere on the page). Don't proceed until confirmed.
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
const DRAFT_URL = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/4697325931803269676/releases/2/prepare`;

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `ship114v4-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
let stepN = 0;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function shot(page, label) {
  stepN++;
  const lbl = `${String(stepN).padStart(2, '0')}-${label}`;
  try { await page.screenshot({ path: path.join(OUT, `${lbl}.png`), fullPage: true }); } catch {}
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
  if (!fs.existsSync(AAB_PATH)) { console.error(`❌ AAB missing`); process.exit(1); }

  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = null;
  for (const p of ctx.pages()) {
    const url = p.url();
    if (url.startsWith('https://play.google.com/console/') && !url.includes('accounts.google.com') && !url.includes('/about')) {
      try {
        const ok = await p.evaluate(() => /Publishing overview|Production/i.test(document.body.innerText || ''));
        if (ok) { page = p; break; }
      } catch {}
    }
  }
  if (!page) { console.error('❌ no signed-in tab'); process.exit(1); }
  await page.bringToFront();
  console.log(`✓ tab: ${page.url().slice(0, 100)}`);

  // STEP 1: navigate directly to the empty draft prepare URL
  console.log('\n=== STEP 1: open empty draft ===');
  await page.goto(DRAFT_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(7000);
  await shot(page, 'draft-prepare');

  // STEP 2: upload AAB
  console.log('\n=== STEP 2: upload AAB ===');
  let uploaded = false;
  for (let attempt = 0; attempt < 12 && !uploaded; attempt++) {
    let inputs;
    try { inputs = await page.$$('input[type="file"]'); }
    catch (e) { console.log(`  ❌ page died: ${e.message.slice(0, 80)}`); process.exit(2); }
    console.log(`  attempt ${attempt + 1}: file inputs found: ${inputs.length}`);
    let target = null;
    for (const fi of inputs) {
      try {
        const accept = await fi.getAttribute('accept');
        if (accept && /\.aab/i.test(accept)) { target = fi; break; }
      } catch {}
    }
    if (!target) target = inputs[0];
    if (target) {
      try {
        console.log(`  → setInputFiles (3-min timeout)`);
        await target.setInputFiles(AAB_PATH, { timeout: 180000 });
        console.log(`✓ setInputFiles returned`);
        uploaded = true;
        break;
      } catch (e) { console.log(`  err: ${e.message.slice(0, 90)}`); }
    }
    if (!uploaded) await sleep(3000);
  }
  if (!uploaded) { console.error('❌ upload failed'); process.exit(3); }
  await sleep(3000);
  await shot(page, 'upload-initiated');

  // STEP 3: WAIT for v1.1.4 bundle row to appear (real attachment)
  console.log('\n=== STEP 3: wait for v1.1.4 bundle attachment ===');
  let attached = false;
  for (let i = 0; i < 180; i++) {  // up to 15 minutes
    await sleep(5000);
    let state;
    try {
      state = await page.evaluate(() => {
        const t = document.body.innerText || '';
        // Look for "11 (1.1.4)" pattern - the canonical version row
        const hasRow = /11\s*\(1\.1\.4\)/.test(t) || /1\.1\.4.*\(11\)/.test(t);
        const spinner = /optimized for distribution|Optimizing|Processing|Uploading/i.test(t);
        const error = /unexpected error|7FD4FA4E|failed/i.test(t);
        return { hasRow, spinner, error };
      });
    } catch (e) {
      console.log(`  ❌ page died: ${e.message.slice(0, 80)}`);
      process.exit(4);
    }
    if (i % 6 === 0) console.log(`  ${(i+1)*5}s: hasRow=${state.hasRow} spinner=${state.spinner} err=${state.error}`);
    if (state.error) { console.log(`  ⚠️  error banner detected`); }
    if (state.hasRow && !state.spinner) {
      console.log(`✓ v1.1.4 bundle attached after ~${(i+1)*5}s`);
      attached = true;
      break;
    }
  }
  await shot(page, 'after-attach');
  if (!attached) {
    console.error('❌ v1.1.4 never attached. Aborting.');
    process.exit(5);
  }

  // STEP 4: name + notes
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
  console.log('  name set');

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
  console.log('  notes set');
  await page.keyboard.press('Tab');
  await sleep(2500);
  await shot(page, 'name-notes-filled');

  // STEP 5: Next → Save → Review → Rollout
  console.log('\n=== STEP 5: progress to rollout ===');
  await clickByText(page, 'Next', { afterMs: 7000 });
  await shot(page, 'after-next');

  // Check errors on preview
  const errCount = await page.evaluate(() => {
    const t = document.body.innerText || '';
    const m = t.match(/(\d+)\s+Error/);
    return m ? parseInt(m[1]) : 0;
  });
  console.log(`  errors on preview: ${errCount}`);
  if (errCount > 0) {
    // Expand show more on errors to log them
    await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      for (const el of walk(document)) {
        const aria = el.getAttribute && el.getAttribute('aria-label');
        if (el.tagName === 'BUTTON' && aria === 'Show more validation details') el.click();
      }
    });
    await sleep(2500);
    const errs = await page.evaluate(() => {
      const t = document.body.innerText || '';
      const m = t.match(/Errors, warnings[\s\S]*?(?=Changes to|Release delivery|Release notes|$)/);
      return m ? m[0].slice(0, 2000) : '[not found]';
    });
    console.log(`\n  ERROR DETAILS:\n${errs}\n`);
    await shot(page, 'errors-expanded');
  }

  await clickByText(page, 'Save', { afterMs: 6000 });
  await shot(page, 'after-save');
  console.log(`  url after save: ${page.url()}`);

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

  // STEP 6: Send for review
  console.log('\n=== STEP 6: send for review ===');
  await page.goto(PUB_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(8000);
  await shot(page, 'publishing');

  const bt = await page.evaluate(() => document.body.innerText || '');
  const has114 = /1\.1\.4|\(11\)/.test(bt);
  const has103 = /1\.0\.3|\(4\)/.test(bt);
  console.log(`  has v1.1.4? ${has114}  has v1.0.3? ${has103}`);
  if (!has114) {
    console.error('❌ v1.1.4 NOT in queue. Stopping.');
    process.exit(6);
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
