/**
 * edit-release.js — click "Edit release details" on /details page to enter
 * the editable /prepare page, then upload v1.1.6.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const AAB = path.resolve(__dirname, '..', 'app', 'build', 'outputs', 'bundle', 'release', 'app-release.aab');
const DEVELOPER_ID = '6492542126543167241';
const APP_ID = '4974212910605033903';
const DETAILS_URL = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/4697325931803269676/releases/2/details`;
const PUB_URL = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/publishing`;

const RELEASE_NAME = '1.1.6';
const RELEASE_NOTES = `<en-US>
Major update — Gemma 3n E2B is now the on-device model.
1.4–1.8x faster end-to-end responses and roughly half the RAM
compared with the previous build. Model picker removed: one model,
auto-selected for every device. Thinking mode now defaults to OFF.
Native libs upgraded for Android 16 KB memory page compatibility.
</en-US>`;

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `edit-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
let stepN = 0;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function shot(page, label) {
  stepN++;
  try { await page.screenshot({ path: path.join(OUT, `${String(stepN).padStart(2,'0')}-${label}.png`), fullPage: true }); } catch {}
  console.log(`📸 ${label}`);
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
  if (r) console.log(`✓ click "${text}"`);
  else console.log(`✗ click "${text}" → not found`);
  await sleep(opts.afterMs || 3500);
  return !!r;
}

(async () => {
  console.log(`AAB: ${(fs.statSync(AAB).size/1e6).toFixed(1)} MB`);
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = ctx.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();
  await page.setViewportSize({ width: 1400, height: 1200 });

  // STEP 1: go to /details, click "Edit release details"
  console.log('\n=== STEP 1: enter edit mode ===');
  await page.goto(DETAILS_URL, { waitUntil:'networkidle', timeout: 60000 });
  await sleep(8000);
  await shot(page, 'details');

  await click(page, 'Edit release details', { exact: true, afterMs: 8000 });
  await shot(page, 'after-edit-click');
  console.log(`url: ${page.url()}`);

  // STEP 2: remove v1.1.5
  const has115 = await page.evaluate(() => /12\s*\(1\.1\.5\)/.test(document.body.innerText || ''));
  if (has115) {
    console.log('→ remove v1.1.5');
    await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      const re = /12\s*\(1\.1\.5\)/;
      let row = null;
      for (const el of walk(document)) {
        const t = (el.innerText || '').trim();
        const role = el.getAttribute && el.getAttribute('role');
        if ((role === 'row' || el.tagName === 'TR') && re.test(t)) { row = el; break; }
      }
      if (!row) return;
      function* desc(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* desc(el.shadowRoot); } }
      for (const el of desc(row)) {
        const aria = el.getAttribute('aria-label') || '';
        if (aria === 'Manage artifact') { el.click(); return; }
      }
    });
    await sleep(2500);
    await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      for (const el of walk(document)) {
        const role = el.getAttribute && el.getAttribute('role');
        if (role !== 'menuitem' && role !== 'option') continue;
        if ((el.innerText || '').trim() === 'Remove app bundle') { el.click(); return; }
      }
    });
    await sleep(2500);
    // Dialog confirm (find Remove button INSIDE dialog)
    await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      for (const el of walk(document)) {
        const role = el.getAttribute && el.getAttribute('role');
        if (role !== 'dialog' && role !== 'alertdialog') continue;
        const btns = el.querySelectorAll('button, material-button');
        for (const b of btns) {
          if ((b.innerText || '').trim() === 'Remove') { b.click(); return; }
        }
      }
    });
    await sleep(6000);
    await shot(page, 'after-rm-115');
  }

  // STEP 3: upload v1.1.6
  console.log('\n=== STEP 3: upload v1.1.6 ===');
  let uploaded = false;
  for (let i = 0; i < 8 && !uploaded; i++) {
    const inputs = await page.$$('input[type="file"]');
    console.log(`  attempt ${i+1}: ${inputs.length} file inputs`);
    for (const fi of inputs) {
      try {
        const accept = await fi.getAttribute('accept');
        if (accept && /\.aab/i.test(accept)) {
          console.log('  → setInputFiles');
          await fi.setInputFiles(AAB, { timeout: 180000 });
          console.log('✓ setInputFiles returned');
          uploaded = true;
          break;
        }
      } catch (e) { console.log(`  err: ${e.message.slice(0, 90)}`); }
    }
    if (!uploaded) await sleep(4000);
  }
  if (!uploaded) { console.error('❌ upload failed'); process.exit(2); }
  await sleep(5000);
  await shot(page, 'upload-started');

  // Wait for v1.1.6 row
  console.log('→ wait for v1.1.6 row');
  let attached = false;
  for (let i = 0; i < 144; i++) {
    await sleep(5000);
    const s = await page.evaluate(() => {
      const t = document.body.innerText || '';
      return {
        hasRow: /13\s*\(1\.1\.6\)/.test(t),
        spinner: /Uploading|Optimizing|Processing|optimized for distribution/i.test(t),
      };
    });
    if (i % 6 === 0) console.log(`  ${(i+1)*5}s: hasRow=${s.hasRow} spinner=${s.spinner}`);
    if (s.hasRow && !s.spinner) { attached = true; console.log(`✓ attached after ~${(i+1)*5}s`); break; }
  }
  await shot(page, 'attached');
  if (!attached) { console.error('❌ not attached'); process.exit(3); }

  // STEP 4: name + notes
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

  // STEP 5: Next + Proceed anyway + Save
  await click(page, 'Next', { exact: true, afterMs: 7000 });
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
    if (!(await click(page, 'Proceed anyway', { afterMs: 2500 }))) break;
  }
  const errs = await page.evaluate(() => {
    const t = document.body.innerText || '';
    const m = t.match(/Errors,\s*warnings[\s\S]*?(?=Changes to|Release delivery|Release notes|$)/);
    return m ? m[0].slice(0, 1500) : null;
  });
  console.log(`\nERRORS:\n${errs || '[none]'}\n`);

  await click(page, 'Save', { exact: true, afterMs: 8000 });
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
      console.log('✓ Send changes for review confirmed');
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
