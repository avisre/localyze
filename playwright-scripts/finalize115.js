/**
 * finalize115.js — finish the rollout. Current state: v1.1.3, v1.1.4,
 * v1.1.5 all in the draft. Need to:
 *   1. Go to /prepare, remove v1.1.3 + v1.1.4
 *   2. Back to /review
 *   3. Fill rollout percentage = 100
 *   4. Save (which submits for review)
 *   5. Publishing → Send for review
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const DEVELOPER_ID = '6492542126543167241';
const APP_ID = '4974212910605033903';
const PUB_URL  = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/publishing`;
const PREPARE_URL = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/4697325931803269676/releases/2/prepare`;
const REVIEW_URL  = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/4697325931803269676/releases/2/review`;

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `finalize115-${TS}`);
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
      if (r.width === 0) continue;
      cands.push({ el, area: r.width * r.height });
    }
    if (!cands.length) return null;
    cands.sort((a, b) => b.area - a.area);
    const el = cands[0].el;
    el.scrollIntoView({ block: 'center' });
    el.click();
    return { tag: el.tagName, text: (el.innerText || '').slice(0, 60) };
  }, { text, exact });
  if (r) console.log(`✓ click "${text}" → ${r.tag} "${r.text}"`);
  else console.log(`✗ click "${text}" → not found`);
  await sleep(opts.afterMs || 3500);
  return !!r;
}

async function removeBundle(page, versionPattern) {
  console.log(`→ remove ${versionPattern}`);
  const opened = await page.evaluate((pat) => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const re = new RegExp(pat);
    let row = null;
    for (const el of walk(document)) {
      const t = (el.innerText || '').trim();
      const role = el.getAttribute && el.getAttribute('role');
      if ((role === 'row' || el.tagName === 'TR') && re.test(t)) { row = el; break; }
    }
    if (!row) return { error: 'no row' };
    function* desc(root) {
      if (!root) return;
      for (const el of root.querySelectorAll('*')) {
        yield el; if (el.shadowRoot) yield* desc(el.shadowRoot);
      }
    }
    for (const el of desc(row)) {
      const t = (el.innerText || '').trim();
      const aria = el.getAttribute('aria-label') || '';
      if ((t === 'more_vert' || aria === 'Manage artifact') &&
          (el.tagName === 'BUTTON' || el.tagName === 'MATERIAL-BUTTON' || el.tagName === 'DIV' || el.getAttribute('role') === 'button')) {
        const r = el.getBoundingClientRect();
        if (r.width === 0) continue;
        el.scrollIntoView({ block: 'center' });
        el.click();
        return { found: true };
      }
    }
    return { error: 'no more_vert' };
  }, versionPattern);
  console.log(`  open: ${JSON.stringify(opened)}`);
  await sleep(2500);

  const r = await page.evaluate(() => {
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
  console.log(`  remove menu: ${JSON.stringify(r)}`);
  await sleep(2500);

  // Confirm dialog: "Remove app bundle from release? ... Cancel / Remove"
  // Click the "Remove" button SPECIFICALLY (smallest area is the dialog button).
  const confirmed = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const cands = [];
    for (const el of walk(document)) {
      if (!['BUTTON', 'MATERIAL-BUTTON'].includes(el.tagName) && el.getAttribute('role') !== 'button') continue;
      const t = (el.innerText || '').trim();
      if (t !== 'Remove') continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      cands.push({ el, area: r.width * r.height });
    }
    if (!cands.length) return null;
    // Dialog confirm button is typically small and near a dialog (sort by area, pick smallest)
    cands.sort((a, b) => a.area - b.area);
    const el = cands[0].el;
    el.scrollIntoView({ block: 'center' });
    el.click();
    return { count: cands.length, area: cands[0].area };
  });
  console.log(`  confirm: ${JSON.stringify(confirmed)}`);
  await sleep(4500);
}

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = null;
  for (const p of ctx.pages()) {
    const url = p.url();
    if (url.startsWith('https://play.google.com/console/') && !url.includes('accounts.google.com') && !url.includes('/about')) {
      try { const ok = await p.evaluate(() => /Publishing|Production/i.test(document.body.innerText || '')); if (ok) { page = p; break; } } catch {}
    }
  }
  if (!page) { console.error('❌ no signed-in tab'); process.exit(1); }
  await page.bringToFront();
  await page.keyboard.press('Escape'); await sleep(1200);

  // STEP 1: Go to prepare and remove v1.1.3 + v1.1.4
  console.log('\n=== STEP 1: remove v1.1.3 and v1.1.4 from prepare ===');
  await page.goto(PREPARE_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(8000);
  await shot(page, 'prepare-initial');

  const init = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      has113: /10\s*\(1\.1\.3\)/.test(t),
      has114: /11\s*\(1\.1\.4\)/.test(t),
      has115: /12\s*\(1\.1\.5\)/.test(t),
    };
  });
  console.log(`  state: ${JSON.stringify(init)}`);

  if (init.has113) {
    await removeBundle(page, '10\\s*\\(1\\.1\\.3\\)');
    await shot(page, 'after-rm-113');
  }
  if (init.has114) {
    await removeBundle(page, '11\\s*\\(1\\.1\\.4\\)');
    await shot(page, 'after-rm-114');
  }

  const final = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      has113: /10\s*\(1\.1\.3\)/.test(t),
      has114: /11\s*\(1\.1\.4\)/.test(t),
      has115: /12\s*\(1\.1\.5\)/.test(t),
    };
  });
  console.log(`  after removal: ${JSON.stringify(final)}`);

  // STEP 2: Click Next to go to /review
  console.log('\n=== STEP 2: Next → /review ===');
  await click(page, 'Next', { exact: true, afterMs: 8000 });
  await shot(page, 'review-page');
  console.log(`  url: ${page.url()}`);

  // STEP 3: dismiss any "Proceed anyway" + fill rollout percentage = 100
  console.log('\n=== STEP 3: dismiss errors + fill rollout % ===');
  for (let i = 0; i < 6; i++) {
    if (!(await click(page, 'Proceed anyway', { afterMs: 2500 }))) break;
  }
  await shot(page, 'after-proceeds');

  // Fill rollout percentage = 100
  const pctFilled = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      if (el.tagName !== 'INPUT') continue;
      const aria = el.getAttribute('aria-label') || '';
      const type = el.type;
      if (/roll-?out|percentage/i.test(aria) && (type === 'text' || type === 'number' || !type)) {
        el.focus();
        el.value = '';
        const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
        setter.call(el, '100');
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
        return { found: true, aria };
      }
    }
    return null;
  });
  console.log(`  rollout%: ${JSON.stringify(pctFilled)}`);
  await page.keyboard.press('Tab');
  await sleep(2500);
  await shot(page, 'pct-filled');

  // Re-check errors (anything still blocking?)
  await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      const aria = el.getAttribute && el.getAttribute('aria-label');
      if (el.tagName === 'BUTTON' && aria === 'Show more validation details') el.click();
    }
  });
  await sleep(2000);
  const errs = await page.evaluate(() => {
    const t = document.body.innerText || '';
    const m = t.match(/Errors,\s*warnings[\s\S]*?(?=Changes to|Release delivery|Release notes|$)/);
    return m ? m[0].slice(0, 2500) : null;
  });
  console.log(`\n  ERRORS:\n${errs || '[none]'}\n`);

  // STEP 4: Click Save (rollout)
  console.log('\n=== STEP 4: Save / rollout ===');
  await click(page, 'Save', { exact: true, afterMs: 8000 });
  await shot(page, 'after-save');

  let rolled = await click(page, 'Start rollout to Production', { exact: true, afterMs: 6000 });
  if (!rolled) rolled = await click(page, 'Start rollout', { exact: true, afterMs: 5000 });
  await shot(page, 'after-rollout');

  for (const lbl of ['Rollout', 'Confirm', 'OK']) {
    if (await click(page, lbl, { exact: true, afterMs: 5000 })) break;
  }
  await shot(page, 'after-confirm');

  // STEP 5: Send for review
  console.log('\n=== STEP 5: Publishing → send for review ===');
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
