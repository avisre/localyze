/**
 * ship115.js — upload v1.1.5 (versionCode 12), remove v1.1.3 and v1.1.4
 * from the draft, then Save → Review → Rollout. The "Remove app bundle"
 * menu item lives behind the more_vert button (aria="Manage artifact").
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const DEVELOPER_ID = '6492542126543167241';
const APP_ID = '4974212910605033903';
const RELEASE_NAME = '1.1.5';
const RELEASE_NOTES = `<en-US>
Major update — Gemma 3n E2B is now the on-device model.
1.4–1.8x faster end-to-end responses and roughly half the RAM
compared with the previous build. Model picker removed: one model,
auto-selected for every device. Thinking mode now defaults to OFF.
</en-US>`;
const AAB_PATH = path.resolve(__dirname, '..', 'app', 'build', 'outputs', 'bundle', 'release', 'app-release.aab');

const PUB_URL  = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/publishing`;
const DRAFT_URL = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/4697325931803269676/releases/2/prepare`;

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `ship115-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
let stepN = 0;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function shot(page, label) {
  stepN++;
  const lbl = `${String(stepN).padStart(2, '0')}-${label}`;
  try { await page.screenshot({ path: path.join(OUT, `${lbl}.png`), fullPage: true }); } catch {}
  console.log(`📸 ${lbl}`);
}

async function click(page, text, opts = {}) {
  const exact = !!opts.exact;
  const r = await page.evaluate(({ text, exact }) => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const cands = [];
    for (const el of walk(document)) {
      const txt = (el.innerText || '').trim();
      const aria = el.getAttribute('aria-label') || '';
      const match = exact ? (txt === text || aria === text) : (txt.includes(text) || aria.includes(text));
      if (!match) continue;
      if (!['BUTTON','A','MATERIAL-BUTTON'].includes(el.tagName) && el.getAttribute('role') !== 'button') continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0 || r.height === 0) continue;
      cands.push({ el, area: r.width * r.height });
    }
    if (!cands.length) return null;
    cands.sort((a, b) => b.area - a.area);
    const el = cands[0].el;
    el.scrollIntoView({ block: 'center' });
    el.click();
    return { tag: el.tagName, text: (el.innerText || '').slice(0, 60), area: cands[0].area };
  }, { text, exact });
  if (r) console.log(`✓ click "${text}" → ${r.tag} area=${Math.round(r.area)}`);
  else console.log(`✗ click "${text}" → not found`);
  await sleep(opts.afterMs || 3500);
  return !!r;
}

/** Remove a specific bundle (e.g. "10 (1.1.3)") via more_vert menu. */
async function removeBundle(page, versionPattern) {
  console.log(`→ remove bundle matching ${versionPattern}`);
  // Click the more_vert button in the row
  const opened = await page.evaluate((pat) => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const re = new RegExp(pat);
    // Find the row containing the version
    let row = null;
    for (const el of walk(document)) {
      const t = (el.innerText || '').trim();
      const role = el.getAttribute && el.getAttribute('role');
      if ((role === 'row' || el.tagName === 'TR') && re.test(t)) { row = el; break; }
    }
    if (!row) return { error: 'no row' };
    // Find a clickable that's a "more_vert" / "Manage artifact"
    function* desc(root) {
      if (!root) return;
      for (const el of root.querySelectorAll('*')) {
        yield el; if (el.shadowRoot) yield* desc(el.shadowRoot);
      }
    }
    for (const el of desc(row)) {
      const t = (el.innerText || '').trim();
      const aria = el.getAttribute('aria-label') || '';
      const role = el.getAttribute('role');
      if ((t === 'more_vert' || aria === 'Manage artifact') &&
          (el.tagName === 'BUTTON' || el.tagName === 'MATERIAL-BUTTON' || el.tagName === 'DIV' || role === 'button')) {
        const r = el.getBoundingClientRect();
        if (r.width === 0) continue;
        el.scrollIntoView({ block: 'center' });
        el.click();
        return { found: true, aria, tag: el.tagName };
      }
    }
    return { error: 'no more_vert' };
  }, versionPattern);
  console.log(`  open menu: ${JSON.stringify(opened)}`);
  await sleep(2500);

  // Click "Remove app bundle"
  const removed = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      const role = el.getAttribute && el.getAttribute('role');
      if (role !== 'menuitem' && role !== 'option') continue;
      const t = (el.innerText || '').trim();
      if (t === 'Remove app bundle' || t.startsWith('Remove app bundle')) {
        el.scrollIntoView({ block: 'center' });
        el.click();
        return { text: t };
      }
    }
    return null;
  });
  console.log(`  remove menu: ${JSON.stringify(removed)}`);
  await sleep(4500);
}

(async () => {
  if (!fs.existsSync(AAB_PATH)) { console.error('❌ AAB missing'); process.exit(1); }
  console.log(`AAB: ${(fs.statSync(AAB_PATH).size/1e6).toFixed(1)} MB`);

  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = null;
  for (const p of ctx.pages()) {
    const url = p.url();
    if (url.startsWith('https://play.google.com/console/') && !url.includes('accounts.google.com') && !url.includes('/about')) {
      try {
        const ok = await p.evaluate(() => /Publishing|Production/i.test(document.body.innerText || ''));
        if (ok) { page = p; break; }
      } catch {}
    }
  }
  if (!page) { console.error('❌ no signed-in tab'); process.exit(1); }
  await page.bringToFront();
  await page.keyboard.press('Escape');
  await sleep(1200);

  // STEP 1: open draft
  console.log('\n=== STEP 1: open draft ===');
  await page.goto(DRAFT_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(8000);
  await shot(page, 'draft');

  // Initial state check
  const init = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      has113: /10\s*\(1\.1\.3\)/.test(t),
      has114: /11\s*\(1\.1\.4\)/.test(t),
      has115: /12\s*\(1\.1\.5\)/.test(t),
    };
  });
  console.log(`  initial: ${JSON.stringify(init)}`);

  // STEP 2: Remove v1.1.3 (if present)
  if (init.has113) {
    console.log('\n=== STEP 2: remove v1.1.3 ===');
    await removeBundle(page, '10\\s*\\(1\\.1\\.3\\)');
    await shot(page, 'after-rm-113');
  }

  // STEP 3: Remove v1.1.4 (if present)
  if (init.has114) {
    console.log('\n=== STEP 3: remove v1.1.4 ===');
    await removeBundle(page, '11\\s*\\(1\\.1\\.4\\)');
    await shot(page, 'after-rm-114');
  }

  // STEP 4: Upload v1.1.5
  console.log('\n=== STEP 4: upload v1.1.5 ===');
  let uploaded = false;
  for (let attempt = 0; attempt < 8 && !uploaded; attempt++) {
    let inputs;
    try { inputs = await page.$$('input[type="file"]'); }
    catch (e) { console.log(`  page died: ${e.message.slice(0, 80)}`); process.exit(2); }
    let target = null;
    for (const fi of inputs) {
      try {
        const accept = await fi.getAttribute('accept');
        if (accept && /\.aab/i.test(accept)) { target = fi; break; }
      } catch {}
    }
    if (target) {
      try {
        console.log(`  attempt ${attempt+1}: setInputFiles (3-min timeout)`);
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
  await shot(page, 'upload-started');

  // Wait for v1.1.5 bundle to appear in the draft (real attachment)
  console.log('→ wait for v1.1.5 bundle row');
  let v115Attached = false;
  for (let i = 0; i < 180; i++) {
    await sleep(5000);
    let state;
    try {
      state = await page.evaluate(() => {
        const t = document.body.innerText || '';
        return {
          hasRow: /12\s*\(1\.1\.5\)/.test(t),
          spinner: /Uploading|Optimizing|Processing|optimized for distribution/i.test(t),
          error: /already been used|unexpected error/i.test(t),
        };
      });
    } catch (e) { console.log(`  page died: ${e.message.slice(0, 80)}`); process.exit(4); }
    if (i % 6 === 0) console.log(`  ${(i+1)*5}s: hasRow=${state.hasRow} spinner=${state.spinner} err=${state.error}`);
    if (state.error) {
      console.log(`  ⚠️  error banner detected`);
      const eb = await page.evaluate(() => {
        const t = document.body.innerText || '';
        const m = t.match(/(Version code|already been used)[^.]*\./);
        return m ? m[0] : null;
      });
      console.log(`  error: ${eb}`);
    }
    if (state.hasRow && !state.spinner) { v115Attached = true; console.log(`✓ v1.1.5 attached after ~${(i+1)*5}s`); break; }
  }
  await shot(page, 'v115-attached');
  if (!v115Attached) { console.error('❌ v1.1.5 never attached'); process.exit(5); }

  // STEP 5: name + notes
  console.log('\n=== STEP 5: name + notes ===');
  await page.evaluate((name) => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    let target = null;
    for (const el of walk(document)) {
      if (el.tagName !== 'INPUT' || (el.type && el.type !== 'text')) continue;
      const aria = el.getAttribute('aria-label') || '';
      if (/release name/i.test(aria)) { target = el; break; }
    }
    if (!target) return;
    target.focus(); target.value = '';
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(target, name);
    target.dispatchEvent(new Event('input', { bubbles: true }));
    target.dispatchEvent(new Event('change', { bubbles: true }));
  }, RELEASE_NAME);

  await page.evaluate((notes) => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    let target = null;
    for (const el of walk(document)) {
      if (el.getAttribute && el.getAttribute('contenteditable') === 'true' && el.getAttribute('role') === 'textbox') { target = el; break; }
    }
    if (!target) for (const el of walk(document)) if (el.tagName === 'TEXTAREA') { target = el; break; }
    if (!target) return;
    target.focus();
    if (target.tagName === 'TEXTAREA') {
      const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
      setter.call(target, notes);
    } else {
      target.innerText = notes;
    }
    target.dispatchEvent(new Event('input', { bubbles: true }));
    target.dispatchEvent(new Event('change', { bubbles: true }));
  }, RELEASE_NOTES);
  await page.keyboard.press('Tab');
  await sleep(2500);
  await shot(page, 'filled');

  // STEP 6: Next → Proceed anyway → Save → Review → Rollout
  console.log('\n=== STEP 6: progress ===');
  await click(page, 'Next', { exact: true, afterMs: 7000 });
  await shot(page, 'after-next');

  await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      const aria = el.getAttribute && el.getAttribute('aria-label');
      if (el.tagName === 'BUTTON' && aria === 'Show more validation details') el.click();
    }
  });
  await sleep(3000);

  // Click any "Proceed anyway" buttons
  let proceedCount = 0;
  for (let i = 0; i < 6; i++) {
    if (await click(page, 'Proceed anyway', { afterMs: 2500 })) proceedCount++;
    else break;
  }
  console.log(`  proceed-anyway clicks: ${proceedCount}`);
  await shot(page, 'after-proceed');

  const errs = await page.evaluate(() => {
    const t = document.body.innerText || '';
    const m = t.match(/Errors,\s*warnings[\s\S]*?(?=Changes to|Release delivery|Release notes|$)/);
    return m ? m[0].slice(0, 2500) : null;
  });
  console.log(`\n  ERRORS:\n${errs || '[none]'}\n`);

  await click(page, 'Save', { exact: true, afterMs: 7000 });
  await shot(page, 'after-save');
  console.log(`  url: ${page.url()}`);

  let reviewed = await click(page, 'Review release', { exact: true, afterMs: 7000 });
  if (!reviewed) reviewed = await click(page, 'Review', { exact: true, afterMs: 6000 });
  await shot(page, 'after-review');

  let rolled = await click(page, 'Start rollout to Production', { exact: true, afterMs: 5000 });
  if (!rolled) rolled = await click(page, 'Start rollout', { exact: true, afterMs: 5000 });
  await shot(page, 'after-rollout');

  for (const lbl of ['Rollout', 'Confirm', 'OK']) {
    if (await click(page, lbl, { exact: true, afterMs: 5000 })) break;
  }
  await shot(page, 'after-confirm');

  // STEP 7: send for review
  console.log('\n=== STEP 7: send for review ===');
  await page.goto(PUB_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(8000);
  await shot(page, 'publishing');

  const bt = await page.evaluate(() => document.body.innerText || '');
  const has115 = /1\.1\.5|\(12\)/.test(bt);
  const has103 = /1\.0\.3|\(4\)/.test(bt);
  console.log(`  has v1.1.5? ${has115}  has v1.0.3? ${has103}`);
  if (!has115) { console.error('❌ v1.1.5 NOT in queue'); process.exit(6); }

  for (const lbl of ['Send 1 change for review', 'Send 2 changes for review', 'Send 3 changes for review', 'Send for review']) {
    if (await click(page, lbl, { afterMs: 5000 })) break;
  }
  await shot(page, 'send-clicked');

  for (const lbl of ['Send for review', 'Send', 'Confirm']) {
    if (await click(page, lbl, { exact: true, afterMs: 5000 })) break;
  }
  await shot(page, 'sent');

  console.log(`\n✅ DONE — v1.1.5 submitted. Output: ${OUT}`);
  process.exit(0);
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
