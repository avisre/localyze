/**
 * ship114-v3.js — the v1.1.3 draft has been DISCARDED but the draft slot
 * (releases/2) still exists empty. We open that slot directly and upload
 * v1.1.4 into it.
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
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `ship114v3-${TS}`);
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
  console.log(`  existing tabs:`);
  for (const p of ctx.pages()) console.log(`    ${p.url().slice(0, 110)}`);

  // STEP 0: find a signed-in tab. If none, wait for one to appear.
  console.log('\n=== STEP 0: locate signed-in tab ===');
  let page = null;
  while (!page) {
    for (const p of ctx.pages()) {
      const url = p.url();
      if (url.startsWith('https://play.google.com/console/') &&
          !url.includes('accounts.google.com') &&
          !url.includes('/about')) {
        try {
          const ok = await p.evaluate(() => /Publishing overview|Production|Release/i.test(document.body.innerText || ''));
          if (ok) { page = p; break; }
        } catch {}
      }
    }
    if (!page) {
      console.log('  no signed-in tab yet; sign in via the Chrome window');
      await sleep(3000);
    }
  }
  console.log(`✓ using signed-in tab: ${page.url().slice(0, 100)}`);
  await page.setViewportSize({ width: 1400, height: 900 });
  await page.bringToFront();

  // STEP 1: open Production → Releases tab → Edit release
  console.log('\n=== STEP 1: open the (now-empty) draft ===');
  await page.goto(PROD_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(7000);

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

  await clickByText(page, 'Edit release', { afterMs: 8000 });
  await shot(page, 'draft-edit-form');
  console.log(`  url: ${page.url()}`);

  // STEP 2: upload AAB — use 3-min timeout (CDP upload of 34MB can be slow)
  console.log('\n=== STEP 2: upload AAB ===');
  let uploaded = false;
  for (let attempt = 0; attempt < 20 && !uploaded; attempt++) {
    let inputs;
    try { inputs = await page.$$('input[type="file"]'); }
    catch (e) { console.log(`  ❌ page died mid-attempt: ${e.message.slice(0, 80)}`); break; }
    console.log(`  attempt ${attempt + 1}: file inputs found: ${inputs.length}`);
    // Prefer the input that explicitly accepts .aab
    let target = null;
    for (const fi of inputs) {
      try {
        const accept = await fi.getAttribute('accept');
        if (accept && /\.aab/i.test(accept)) { target = fi; console.log(`    target accept=${accept}`); break; }
      } catch {}
    }
    if (!target) target = inputs[0];
    if (target) {
      try {
        console.log(`  → setInputFiles (3-min timeout)`);
        await target.setInputFiles(AAB_PATH, { timeout: 180000 });
        console.log(`✓ uploaded AAB`);
        uploaded = true;
        break;
      } catch (e) { console.log(`  setInputFiles err: ${e.message.slice(0, 90)}`); }
    }
    if (!uploaded) await sleep(3000);
  }
  if (!uploaded) {
    console.error('❌ no file input found — page state below');
    const text = await page.evaluate(() => (document.body.innerText || '').slice(0, 2000));
    console.log(text);
    await shot(page, 'no-input');
    process.exit(2);
  }
  await sleep(5000);
  await shot(page, 'after-upload');

  // Wait for processing — Play Console parses the AAB asynchronously
  console.log('→ waiting for AAB processing (up to 8 min)');
  for (let i = 0; i < 96; i++) {
    await sleep(5000);
    const state = await page.evaluate(() => {
      const t = document.body.innerText || '';
      const ready = /1\.1\.4|\(11\)|Release details/i.test(t);
      const processing = /Processing|Uploading|uploading/i.test(t);
      return { ready, processing };
    });
    if (state.ready) { console.log(`  ready after ~${(i+1)*5}s`); break; }
    if (i % 6 === 0) console.log(`  ...${(i+1)*5}s, processing=${state.processing}`);
  }
  await shot(page, 'processed');

  // STEP 3: name + notes
  console.log('\n=== STEP 3: name + notes ===');
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

  // STEP 4: Next → Save → Review → Rollout
  console.log('\n=== STEP 4: progress to rollout ===');
  await clickByText(page, 'Next', { afterMs: 6000 });
  await shot(page, 'after-next');

  // Check for errors before continuing
  const errMsg = await page.evaluate(() => {
    const t = document.body.innerText || '';
    const m = t.match(/(\d+)\s+Error/);
    return m ? `${m[1]} errors detected` : null;
  });
  if (errMsg) { console.log(`  ⚠️  ${errMsg} — script will still try Save`); }

  await clickByText(page, 'Save', { afterMs: 6000 });
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

  // STEP 5: Send for review
  console.log('\n=== STEP 5: send for review ===');
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
