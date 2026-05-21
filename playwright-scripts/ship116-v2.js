/**
 * ship116-v2.js — correct dialog labels:
 *   - Publishing "Remove changes" dialog → confirm with "Remove changes" (not "Remove")
 *   - Bundle row remove → "Remove app bundle" menu → "Remove" dialog confirm
 *
 * Flow:
 *   1. Publishing → Remove changes (× 2: button + dialog confirm "Remove changes")
 *   2. Prepare → remove leftover bundle row → upload v1.1.6
 *   3. Wait for v1.1.6 row → name + notes
 *   4. Next → Proceed anyway → Save
 *   5. Publishing → Send 2 changes → "Send changes for review"
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const DEVELOPER_ID = '6492542126543167241';
const APP_ID = '4974212910605033903';
const AAB_PATH = path.resolve(__dirname, '..', 'app', 'build', 'outputs', 'bundle', 'release', 'app-release.aab');
const PUB_URL  = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/publishing`;
const PREPARE_URL = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/4697325931803269676/releases/2/prepare`;

const RELEASE_NAME = '1.1.6';
const RELEASE_NOTES = `<en-US>
Major update — Gemma 3n E2B is now the on-device model.
1.4–1.8x faster end-to-end responses and roughly half the RAM
compared with the previous build. Model picker removed: one model,
auto-selected for every device. Thinking mode now defaults to OFF.
Native libs upgraded for Android 16 KB memory page compatibility.
</en-US>`;

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `ship116v2-${TS}`);
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

async function clickSmallest(page, label) {
  return await page.evaluate((lbl) => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const cands = [];
    for (const el of walk(document)) {
      if (!['BUTTON', 'MATERIAL-BUTTON'].includes(el.tagName)) continue;
      if ((el.innerText || '').trim() !== lbl) continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      cands.push({ el, area: r.width * r.height });
    }
    if (!cands.length) return false;
    cands.sort((a, b) => a.area - b.area);
    cands[0].el.scrollIntoView({block:'center'});
    cands[0].el.click();
    return true;
  }, label);
}

(async () => {
  console.log(`AAB: ${(fs.statSync(AAB_PATH).size/1e6).toFixed(1)} MB`);
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = ctx.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();
  await page.setViewportSize({ width: 1400, height: 1200 });
  await page.keyboard.press('Escape'); await sleep(1500);

  // ── STEP 1: Publishing → Remove changes → confirm "Remove changes" ─────
  console.log('\n=== STEP 1: pull v1.1.5 from in-review ===');
  await page.goto(PUB_URL, { waitUntil: 'networkidle', timeout: 60000 });
  await sleep(8000);
  await shot(page, 'pub');

  const inReview = await page.evaluate(() => /Changes in review/.test(document.body.innerText || ''));
  console.log(`  in-review: ${inReview}`);

  if (inReview) {
    await click(page, 'Remove changes', { afterMs: 4000 });
    await shot(page, 'remove-dialog');
    // Dialog confirm — find the button INSIDE a [role=dialog] (Material modal)
    const ok = await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      // Find a dialog/modal container
      for (const el of walk(document)) {
        const role = el.getAttribute && el.getAttribute('role');
        if (role !== 'dialog' && role !== 'alertdialog') continue;
        const r = el.getBoundingClientRect();
        if (r.width === 0) continue;
        // Find the "Remove changes" button inside this dialog
        const btns = el.querySelectorAll('button, material-button');
        for (const b of btns) {
          if ((b.innerText || '').trim() === 'Remove changes') {
            b.scrollIntoView({block:'center'}); b.click(); return { dialog: true };
          }
        }
      }
      // Fallback: pick the button with class containing "primary" or "filled" or color blue
      for (const el of walk(document)) {
        if (!['BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) continue;
        if ((el.innerText || '').trim() !== 'Remove changes') continue;
        const cls = el.className || '';
        // Material primary action button has "unelevated" or "filled" class
        if (/unelevated|filled|primary|mdc-button--unelevated/.test(cls)) {
          el.click(); return { primary: true, cls };
        }
      }
      return false;
    });
    console.log(`  confirm Remove changes: ${JSON.stringify(ok)}`);
    await sleep(8000);
    await shot(page, 'after-remove');

    // Verify
    const stillInReview = await page.evaluate(() => /Changes in review/.test(document.body.innerText || ''));
    console.log(`  still in-review? ${stillInReview}`);
    if (stillInReview) { console.error('❌ failed to pull from review'); process.exit(2); }
  }

  // ── STEP 2: Prepare → remove leftover bundle → upload v1.1.6 ───────────
  console.log('\n=== STEP 2: prepare ===');
  await page.goto(PREPARE_URL, { waitUntil: 'networkidle' });
  await sleep(8000);
  await shot(page, 'prepare');

  // Remove any of the old version rows
  for (const [v, pat] of [['1.1.5', '12\\s*\\(1\\.1\\.5\\)'], ['1.1.3', '10\\s*\\(1\\.1\\.3\\)'], ['1.1.4', '11\\s*\\(1\\.1\\.4\\)']]) {
    const has = await page.evaluate((p) => new RegExp(p).test(document.body.innerText || ''), pat);
    if (!has) continue;
    console.log(`→ remove v${v}`);
    // Open menu
    await page.evaluate((p) => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      const re = new RegExp(p);
      let row = null;
      for (const el of walk(document)) {
        const t = (el.innerText || '').trim();
        const role = el.getAttribute && el.getAttribute('role');
        if ((role === 'row' || el.tagName === 'TR') && re.test(t)) { row = el; break; }
      }
      if (!row) return;
      function* desc(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* desc(el.shadowRoot); } }
      for (const el of desc(row)) {
        const t = (el.innerText || '').trim();
        const aria = el.getAttribute('aria-label') || '';
        if ((t === 'more_vert' || aria === 'Manage artifact')) {
          const r = el.getBoundingClientRect();
          if (r.width === 0) continue;
          el.click(); return;
        }
      }
    }, pat);
    await sleep(2500);
    // Click "Remove app bundle" menu item
    await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      for (const el of walk(document)) {
        const role = el.getAttribute && el.getAttribute('role');
        if (role !== 'menuitem' && role !== 'option') continue;
        if ((el.innerText || '').trim() === 'Remove app bundle') { el.click(); return; }
      }
    });
    await sleep(2500);
    // Dialog confirm: "Remove app bundle from release?" → "Remove"
    await clickSmallest(page, 'Remove');
    await sleep(5000);
  }
  await shot(page, 'cleaned');

  // ── STEP 3: upload v1.1.6 ──────────────────────────────────────────────
  console.log('\n=== STEP 3: upload v1.1.6 ===');
  let uploaded = false;
  for (let attempt = 0; attempt < 8 && !uploaded; attempt++) {
    const inputs = await page.$$('input[type="file"]');
    console.log(`  attempt ${attempt+1}: ${inputs.length} file inputs`);
    let target = null;
    for (const fi of inputs) {
      try {
        const accept = await fi.getAttribute('accept');
        if (accept && /\.aab/i.test(accept)) { target = fi; break; }
      } catch {}
    }
    if (target) {
      try {
        console.log('  → setInputFiles (3-min)');
        await target.setInputFiles(AAB_PATH, { timeout: 180000 });
        console.log('✓ setInputFiles returned');
        uploaded = true;
      } catch (e) { console.log(`  err: ${e.message.slice(0, 90)}`); }
    }
    if (!uploaded) await sleep(4000);
  }
  if (!uploaded) { console.error('❌ upload failed'); process.exit(3); }
  await sleep(5000);
  await shot(page, 'upload-started');

  // Wait for v1.1.6 row to appear
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
  if (!attached) { console.error('❌ v1.1.6 not attached'); process.exit(4); }

  // ── STEP 4: name + notes ───────────────────────────────────────────────
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

  // ── STEP 5: Next → Proceed anyway × N → Save ──────────────────────────
  console.log('\n=== STEP 5: Next + Save ===');
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
  console.log(`\n  ERRORS:\n${errs || '[none]'}\n`);

  await click(page, 'Save', { exact: true, afterMs: 8000 });
  await shot(page, 'saved');

  // ── STEP 6: send for review ──────────────────────────────────────────
  console.log('\n=== STEP 6: send for review ===');
  await page.goto(PUB_URL, { waitUntil: 'networkidle' });
  await sleep(10000);
  await shot(page, 'publishing');

  const finalCheck = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      blocked: /1 issue affects/.test(t),
      hasSendBtn: /Send \d+ changes? for review/.test(t),
      hasV116: /1\.1\.6/.test(t),
    };
  });
  console.log(`  state: ${JSON.stringify(finalCheck)}`);

  if (finalCheck.hasSendBtn && !finalCheck.blocked) {
    const sendBtn = page.locator('button').filter({ hasText: /Send \d+ changes? for review/ }).first();
    await sendBtn.click({ timeout: 10000 });
    await sleep(6000);
    try {
      await page.getByRole('button', { name: 'Send changes for review', exact: true }).click({ timeout: 8000 });
      console.log('  ✓ confirmed Send changes for review');
    } catch (e) {
      console.log(`  fallback: ${e.message.slice(0, 80)}`);
      await page.evaluate(() => {
        function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
        for (const el of walk(document)) {
          if (!['BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) continue;
          if ((el.innerText || '').trim() === 'Send changes for review') { el.click(); return; }
        }
      });
    }
    await sleep(8000);
    await shot(page, 'sent');
  }

  // Final verify
  await page.reload({ waitUntil: 'networkidle' });
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
  console.log(`\nfinal: ${JSON.stringify(final)}`);
  if (final.hasInReview || (!final.hasSendBtn && final.hasV116)) {
    console.log(`\n✅ DONE — v1.1.6 in review!`);
  } else {
    console.log(`\n⚠️  unclear, see ${OUT}`);
  }
  await browser.close();
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
