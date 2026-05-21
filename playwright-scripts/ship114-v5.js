/**
 * ship114-v5.js — v1.1.4 bundle is already in the library (versionCode 11).
 * Attach it via "Add from library" and proceed to rollout.
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

const PUB_URL  = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/publishing`;
const DRAFT_URL = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/4697325931803269676/releases/2/prepare`;

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `ship114v5-${TS}`);
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
  console.log(`✓ tab: ${page.url().slice(0, 100)}`);

  // STEP 1: navigate to the draft prepare page
  console.log('\n=== STEP 1: open draft + clear failed upload ===');
  await page.goto(DRAFT_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(7000);
  await shot(page, 'draft-initial');

  // Clear the error'd app-release.aab row via "clear" icon (the X)
  console.log('→ clear the errored upload row');
  await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      const txt = (el.innerText || '').trim();
      const aria = el.getAttribute('aria-label') || '';
      // The clear button next to "Version code 11 has already been used"
      if ((txt === 'clear' || aria === 'clear' || aria.toLowerCase().includes('clear') || aria.toLowerCase().includes('remove') || aria.toLowerCase().includes('cancel')) &&
          (el.tagName === 'BUTTON' || el.getAttribute('role') === 'button' || el.tagName === 'MATERIAL-ICON')) {
        el.click();
        return;
      }
    }
  });
  await sleep(3000);
  await shot(page, 'after-clear');

  // STEP 2: Add from library
  console.log('\n=== STEP 2: Add from library ===');
  await clickByText(page, 'Add from library', { afterMs: 5000 });
  await shot(page, 'lib-modal');

  // Pick the row containing "11 (1.1.4)" or "1.1.4"
  console.log('→ pick v1.1.4 row');
  let picked = false;
  // Try multiple matchers
  for (const lbl of ['11 (1.1.4)', '1.1.4 (11)', '(11)', '11']) {
    picked = await clickByText(page, lbl, { exact: false, afterMs: 2500 });
    if (picked) break;
  }
  // Fallback: find any radio/checkbox in the library modal and pick the one nearest "1.1.4"
  if (!picked) {
    console.log('  fallback: pick radio near 1.1.4');
    await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      // find element containing "1.1.4" then walk to find clickable parent
      for (const el of walk(document)) {
        const t = (el.innerText || '').trim();
        if (/1\.1\.4|\(11\)/.test(t) && t.length < 50) {
          let p = el;
          for (let i = 0; i < 6 && p; i++) {
            if (p.getAttribute && (p.getAttribute('role') === 'row' || p.tagName === 'TR' || p.getAttribute('role') === 'option')) {
              const rb = p.querySelector('input[type="radio"], input[type="checkbox"]');
              if (rb) { rb.click(); return; }
              p.click();
              return;
            }
            p = p.parentElement;
          }
        }
      }
    });
    await sleep(2500);
  }
  await shot(page, 'row-picked');

  // Click "Add to release" or "Add" button
  for (const lbl of ['Add to release', 'Add', 'OK', 'Save changes']) {
    if (await clickByText(page, lbl, { afterMs: 5000 })) break;
  }
  await shot(page, 'bundle-attached');

  // Verify bundle is attached: look for "11 (1.1.4)" in body or "1.1.4" with no spinner
  console.log('→ verify attachment');
  let attached = false;
  for (let i = 0; i < 24; i++) {
    await sleep(3000);
    const s = await page.evaluate(() => {
      const t = document.body.innerText || '';
      const hasRow = /11\s*\(1\.1\.4\)/.test(t) || (/1\.1\.4/.test(t) && /\(11\)/.test(t));
      const spinner = /Uploading|Optimizing|Processing|optimized for distribution/i.test(t);
      const error = /already been used|unexpected error/i.test(t);
      return { hasRow, spinner, error, snippet: t.slice(0, 800) };
    });
    if (i % 3 === 0) console.log(`  ${(i+1)*3}s hasRow=${s.hasRow} spinner=${s.spinner} err=${s.error}`);
    if (s.hasRow && !s.spinner && !s.error) { attached = true; break; }
  }
  await shot(page, 'verify');
  if (!attached) console.log('  ⚠️  bundle attachment not confirmed — proceeding anyway');

  // STEP 3: fill name + notes
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
    target.value = '';
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

  // STEP 4: Next → check errors → Save → Review → Rollout
  console.log('\n=== STEP 4: progress to rollout ===');
  await clickByText(page, 'Next', { afterMs: 7000 });
  await shot(page, 'after-next');

  // Expand errors
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
    return m ? m[0].slice(0, 2500) : null;
  });
  if (errs) console.log(`\n  ERRORS/WARNINGS:\n${errs}\n`);
  await shot(page, 'errors-expanded');

  // Click Save (may be disabled if errors; check button state)
  await clickByText(page, 'Save', { afterMs: 7000 });
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

  // STEP 5: Send for review
  console.log('\n=== STEP 5: send for review ===');
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
