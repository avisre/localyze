/**
 * new-release-116.js — discard release 2 (v1.1.5), create fresh release with
 * v1.1.6 attached via Add-from-library, save + send for review.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const DEVELOPER_ID = '6492542126543167241';
const APP_ID = '4974212910605033903';
const PROD_URL = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/production`;
const PUB_URL = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/publishing`;
const PREPARE_URL = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/4697325931803269676/releases/2/prepare`;
const DETAILS_URL = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/4697325931803269676/releases/2/details`;

const RELEASE_NAME = '1.1.6';
const RELEASE_NOTES = `<en-US>
Major update — Gemma 3n E2B is now the on-device model.
1.4–1.8x faster end-to-end responses and roughly half the RAM
compared with the previous build. Model picker removed: one model,
auto-selected for every device. Thinking mode now defaults to OFF.
Native libs upgraded for Android 16 KB memory page compatibility.
</en-US>`;

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `new116-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
let stepN = 0;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function shot(page, label) {
  stepN++;
  try { await page.screenshot({ path: path.join(OUT, `${String(stepN).padStart(2,'0')}-${label}.png`), fullPage: true }); } catch {}
  console.log(`📸 ${label}`);
}

async function clickLargest(page, text, opts = {}) {
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
      if (r.width === 0) continue;
      cands.push({ el, area: r.width * r.height });
    }
    if (!cands.length) return null;
    cands.sort((a, b) => b.area - a.area);
    cands[0].el.scrollIntoView({block:'center'});
    cands[0].el.click();
    return { tag: cands[0].el.tagName, text: (cands[0].el.innerText || '').slice(0, 60) };
  }, { text, exact });
  if (r) console.log(`✓ click "${text}"`);
  else console.log(`✗ click "${text}" → not found`);
  await sleep(opts.afterMs || 3500);
  return !!r;
}

async function clickInDialog(page, label) {
  return await page.evaluate((lbl) => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      const role = el.getAttribute && el.getAttribute('role');
      if (role !== 'dialog' && role !== 'alertdialog') continue;
      const btns = el.querySelectorAll('button, material-button');
      for (const b of btns) {
        if ((b.innerText || '').trim() === lbl) { b.click(); return true; }
      }
    }
    return false;
  }, label);
}

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = ctx.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();
  await page.setViewportSize({ width: 1400, height: 1200 });
  await page.keyboard.press('Escape'); await sleep(1500);

  // STEP 1: go to /details, click "Discard release" if available
  console.log('\n=== STEP 1: discard release 2 (v1.1.5) ===');
  await page.goto(DETAILS_URL, { waitUntil:'networkidle', timeout: 60000 });
  await sleep(8000);
  await shot(page, 'details');

  // Check if there's a Discard release link/button (not grayed)
  const canDiscard = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      if (!['BUTTON','A','MATERIAL-BUTTON'].includes(el.tagName)) continue;
      const t = (el.innerText || '').trim();
      if (t !== 'Discard release') continue;
      const cls = el.className || '';
      const disabled = el.disabled || el.hasAttribute('disabled') || el.getAttribute('aria-disabled') === 'true' || /disabled|grayed/i.test(cls);
      const r = el.getBoundingClientRect();
      return { exists: true, disabled, classes: cls.slice(0, 100) };
    }
    return { exists: false };
  });
  console.log(`  discard btn: ${JSON.stringify(canDiscard)}`);

  if (canDiscard.exists && !canDiscard.disabled) {
    await clickLargest(page, 'Discard release', { exact: true, afterMs: 4000 });
    await shot(page, 'discard-dialog');
    // Confirm in dialog
    const ok = await clickInDialog(page, 'Discard release');
    if (!ok) await clickInDialog(page, 'Discard');
    await sleep(7000);
    await shot(page, 'after-discard');
  } else {
    console.log('  Discard release not available; will try via prepare URL');
    // Force-navigate to prepare and see if discardable from there
    await page.goto(PREPARE_URL, { waitUntil:'networkidle' });
    await sleep(7000);
    const has = await page.evaluate(() => /Discard draft release|Discard release/.test(document.body.innerText || ''));
    console.log(`  prepare has discard? ${has}`);
    if (has) {
      // Try both
      let did = await clickLargest(page, 'Discard draft release', { exact: true, afterMs: 4000 });
      if (!did) did = await clickLargest(page, 'Discard release', { exact: true, afterMs: 4000 });
      // confirm
      const c1 = await clickInDialog(page, 'Discard draft release');
      if (!c1) await clickInDialog(page, 'Discard release');
      await sleep(7000);
      await shot(page, 'after-discard');
    }
  }

  // STEP 2: navigate to Production track + Create new release
  console.log('\n=== STEP 2: Create new release ===');
  await page.goto(PROD_URL, { waitUntil:'networkidle' });
  await sleep(7000);
  await shot(page, 'production');

  await clickLargest(page, 'Create new release', { exact: true, afterMs: 10000 });
  await shot(page, 'new-release');
  console.log(`  url: ${page.url()}`);

  // STEP 3: Add v1.1.6 from library
  console.log('\n=== STEP 3: Add v1.1.6 from library ===');
  await clickLargest(page, 'Add from library', { afterMs: 5000 });
  await shot(page, 'lib-modal');

  // Pick v1.1.6 row (versionCode 13)
  const picked = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      const role = el.getAttribute && el.getAttribute('role');
      if (role !== 'row' && el.tagName !== 'TR') continue;
      const t = (el.innerText || '').trim();
      if (!/1\.1\.6/.test(t) || !/\b13\b/.test(t)) continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      // Click checkbox
      const cb = el.querySelector('input[type="checkbox"], [role="checkbox"]');
      if (cb) { cb.click(); return { type: 'checkbox', text: t.slice(0, 80) }; }
      el.click();
      return { type: 'row', text: t.slice(0, 80) };
    }
    return null;
  });
  console.log(`  picked: ${JSON.stringify(picked)}`);
  await sleep(3000);
  await shot(page, 'picked');

  // Click "Add to release"
  await clickLargest(page, 'Add to release', { exact: true, afterMs: 6000 });
  await shot(page, 'added');

  // Verify
  let attached = false;
  for (let i = 0; i < 15; i++) {
    await sleep(3000);
    const ok = await page.evaluate(() => /13\s*\(1\.1\.6\)/.test(document.body.innerText || ''));
    if (ok) { attached = true; console.log(`✓ v1.1.6 attached after ~${(i+1)*3}s`); break; }
  }
  if (!attached) { console.error('❌ not attached'); process.exit(2); }

  // STEP 4: name + notes
  console.log('\n=== STEP 4: name + notes ===');
  await page.evaluate((name) => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      if (el.tagName !== 'INPUT' || (el.type && el.type !== 'text')) continue;
      const aria = el.getAttribute('aria-label') || '';
      if (/release name/i.test(aria)) {
        el.focus(); el.value = '';
        const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
        setter.call(el, name);
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
        return;
      }
    }
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
    } else { target.innerText = notes; }
    target.dispatchEvent(new Event('input', { bubbles: true }));
    target.dispatchEvent(new Event('change', { bubbles: true }));
  }, RELEASE_NOTES);
  await page.keyboard.press('Tab');
  await sleep(2500);
  await shot(page, 'filled');

  // STEP 5: Next → Proceed anyway × N → Save
  await clickLargest(page, 'Next', { exact: true, afterMs: 7000 });
  await shot(page, 'after-next');

  await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      const aria = el.getAttribute && el.getAttribute('aria-label');
      if (el.tagName === 'BUTTON' && aria === 'Show more validation details') el.click();
    }
  });
  await sleep(2500);
  for (let i = 0; i < 5; i++) {
    if (!(await clickLargest(page, 'Proceed anyway', { afterMs: 2500 }))) break;
  }
  const errs = await page.evaluate(() => {
    const t = document.body.innerText || '';
    const m = t.match(/Errors,\s*warnings[\s\S]*?(?=Changes to|Release delivery|Release notes|$)/);
    return m ? m[0].slice(0, 1500) : null;
  });
  console.log(`\nERRORS:\n${errs || '[none]'}\n`);

  await clickLargest(page, 'Save', { exact: true, afterMs: 8000 });
  await shot(page, 'saved');

  // STEP 6: send for review
  console.log('\n=== STEP 6: send for review ===');
  await page.goto(PUB_URL, { waitUntil:'networkidle' });
  await sleep(10000);
  await shot(page, 'publishing');

  const state = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      blocked: /1 issue affects/.test(t),
      hasSendBtn: /Send \d+ changes? for review/.test(t),
      hasV116: /1\.1\.6/.test(t),
    };
  });
  console.log(`state: ${JSON.stringify(state)}`);

  if (state.hasSendBtn && !state.blocked) {
    const sendBtn = page.locator('button').filter({ hasText: /Send \d+ changes? for review/ }).first();
    await sendBtn.click({ timeout: 10000 });
    await sleep(6000);
    try {
      await page.getByRole('button', { name: 'Send changes for review', exact: true }).click({ timeout: 8000 });
      console.log('✓ Send confirmed');
    } catch (e) { console.log(`fallback: ${e.message.slice(0, 80)}`); }
    await sleep(8000);
    await shot(page, 'sent');
  }

  await page.reload({ waitUntil:'networkidle' });
  await sleep(10000);
  await shot(page, 'final');
  const final = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      hasInReview: /Changes in review/.test(t),
      hasSendBtn: /Send \d+ changes? for review/.test(t),
      hasV116: /1\.1\.6/.test(t),
    };
  });
  console.log(`final: ${JSON.stringify(final)}`);
  if (final.hasInReview || (!final.hasSendBtn && final.hasV116)) {
    console.log(`\n✅ DONE — v1.1.6 in review!`);
  } else {
    console.log(`\n⚠️  unclear`);
  }
  await browser.close();
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
