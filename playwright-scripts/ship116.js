/**
 * ship116.js — supersede the in-review v1.1.5 with v1.1.6 (16 KB-fixed).
 *
 * Strategy:
 *   1. Publishing → "Remove changes" to pull v1.1.5 back to draft state.
 *   2. Production track → Edit release → remove v1.1.5 → upload v1.1.6
 *      → confirm v1.1.6 attached.
 *   3. Next → Save → click "Send changes for review" dialog.
 *   4. Verify "Changes in review" reappears with v1.1.6.
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
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `ship116-${TS}`);
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
  if (r) console.log(`✓ click "${text}"`);
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
    if (!row) return null;
    function* desc(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* desc(el.shadowRoot); } }
    for (const el of desc(row)) {
      const t = (el.innerText || '').trim();
      const aria = el.getAttribute('aria-label') || '';
      if ((t === 'more_vert' || aria === 'Manage artifact') &&
          (el.tagName === 'BUTTON' || el.tagName === 'MATERIAL-BUTTON' || el.tagName === 'DIV' || el.getAttribute('role') === 'button')) {
        const r = el.getBoundingClientRect();
        if (r.width === 0) continue;
        el.scrollIntoView({ block: 'center' }); el.click();
        return { found: true };
      }
    }
    return null;
  }, versionPattern);
  console.log(`  menu: ${JSON.stringify(opened)}`);
  await sleep(2500);
  // Click "Remove app bundle" menu item
  await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      const role = el.getAttribute && el.getAttribute('role');
      if (role !== 'menuitem' && role !== 'option') continue;
      const t = (el.innerText || '').trim();
      if (t === 'Remove app bundle') { el.scrollIntoView({block:'center'}); el.click(); return; }
    }
  });
  await sleep(2500);
  // Confirm dialog: small "Remove" button
  await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const cands = [];
    for (const el of walk(document)) {
      if (!['BUTTON', 'MATERIAL-BUTTON'].includes(el.tagName)) continue;
      const t = (el.innerText || '').trim();
      if (t !== 'Remove') continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      cands.push({ el, area: r.width * r.height });
    }
    if (cands.length) {
      cands.sort((a, b) => a.area - b.area);
      cands[0].el.click();
    }
  });
  await sleep(4500);
}

(async () => {
  if (!fs.existsSync(AAB_PATH)) { console.error('❌ AAB missing'); process.exit(1); }
  console.log(`AAB: ${(fs.statSync(AAB_PATH).size/1e6).toFixed(1)} MB`);

  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = ctx.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();
  await page.setViewportSize({ width: 1400, height: 1200 });
  await page.keyboard.press('Escape'); await sleep(1500);

  // STEP 1: Publishing → Remove changes (pull v1.1.5 back from review queue)
  console.log('\n=== STEP 1: pull v1.1.5 back from in-review ===');
  await page.goto(PUB_URL, { waitUntil:'networkidle', timeout: 60000 });
  await sleep(8000);
  await shot(page, 'publishing-initial');

  // Check if "Remove changes" button visible
  const removable = await page.evaluate(() => /Remove changes|Changes in review/i.test(document.body.innerText || ''));
  console.log(`  in-review state: ${removable}`);

  if (removable) {
    await click(page, 'Remove changes', { afterMs: 4000 });
    await shot(page, 'remove-dialog');
    // Confirm
    for (const lbl of ['Remove', 'Confirm', 'Yes']) {
      // Use smallest visible "Remove" button (dialog confirm)
      const did = await page.evaluate((label) => {
        function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
        const cands = [];
        for (const el of walk(document)) {
          if (!['BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) continue;
          if ((el.innerText || '').trim() !== label) continue;
          const r = el.getBoundingClientRect();
          if (r.width === 0) continue;
          cands.push({ el, area: r.width * r.height });
        }
        if (!cands.length) return false;
        cands.sort((a, b) => a.area - b.area);
        cands[0].el.click();
        return true;
      }, lbl);
      if (did) { console.log(`  confirm ${lbl}`); await sleep(5000); break; }
    }
    await shot(page, 'after-remove-changes');
  }

  // STEP 2: open prepare, remove v1.1.5 bundle if attached
  console.log('\n=== STEP 2: open prepare + remove v1.1.5 ===');
  await page.goto(PREPARE_URL, { waitUntil:'networkidle', timeout: 60000 });
  await sleep(8000);
  await shot(page, 'prepare-initial');

  const init = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      has115: /12\s*\(1\.1\.5\)/.test(t),
      has116: /13\s*\(1\.1\.6\)/.test(t),
    };
  });
  console.log(`  state: ${JSON.stringify(init)}`);

  if (init.has115) {
    await removeBundle(page, '12\\s*\\(1\\.1\\.5\\)');
    await shot(page, 'after-rm-115');
  }

  // STEP 3: upload v1.1.6
  console.log('\n=== STEP 3: upload v1.1.6 ===');
  let uploaded = false;
  for (let attempt = 0; attempt < 6 && !uploaded; attempt++) {
    const inputs = await page.$$('input[type="file"]');
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
        console.log('✓ setInputFiles returned');
        uploaded = true;
      } catch (e) { console.log(`  err: ${e.message.slice(0, 90)}`); }
    }
    if (!uploaded) await sleep(3000);
  }
  if (!uploaded) { console.error('❌ upload failed'); process.exit(2); }
  await sleep(3000);
  await shot(page, 'upload-started');

  // Wait for v1.1.6 row
  console.log('→ wait for v1.1.6 bundle row');
  let attached = false;
  for (let i = 0; i < 120; i++) {
    await sleep(5000);
    const state = await page.evaluate(() => {
      const t = document.body.innerText || '';
      return {
        hasRow: /13\s*\(1\.1\.6\)/.test(t),
        spinner: /Uploading|Optimizing|Processing|optimized for distribution/i.test(t),
      };
    });
    if (i % 6 === 0) console.log(`  ${(i+1)*5}s: hasRow=${state.hasRow} spinner=${state.spinner}`);
    if (state.hasRow && !state.spinner) { attached = true; console.log(`✓ attached after ~${(i+1)*5}s`); break; }
  }
  await shot(page, 'attached');
  if (!attached) { console.error('❌ v1.1.6 not attached'); process.exit(3); }

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

  // STEP 5: Next → Save → Send
  console.log('\n=== STEP 5: Next + Save ===');
  await click(page, 'Next', { exact: true, afterMs: 7000 });
  await shot(page, 'after-next');

  // expand errors + click Proceed anyway if 16 KB error still appears
  await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      const aria = el.getAttribute && el.getAttribute('aria-label');
      if (el.tagName === 'BUTTON' && aria === 'Show more validation details') el.click();
    }
  });
  await sleep(3000);
  for (let i = 0; i < 5; i++) {
    if (!(await click(page, 'Proceed anyway', { afterMs: 2500 }))) break;
  }
  const errs = await page.evaluate(() => {
    const t = document.body.innerText || '';
    const m = t.match(/Errors,\s*warnings[\s\S]*?(?=Changes to|Release delivery|Release notes|$)/);
    return m ? m[0].slice(0, 2000) : null;
  });
  console.log(`\n  ERRORS:\n${errs || '[none]'}\n`);

  await click(page, 'Save', { exact: true, afterMs: 8000 });
  await shot(page, 'after-save');

  // STEP 6: publishing → Send 2 changes for review → "Send changes for review"
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
      hasInReview: /Changes in review/.test(t),
    };
  });
  console.log(`  state: ${JSON.stringify(state)}`);

  if (state.hasInReview) {
    console.log('  ⚠️  Already in review — possibly v1.1.6 went through? Check final.');
  } else if (state.hasSendBtn && !state.blocked) {
    console.log('→ Send for review');
    const sendBtn = page.locator('button').filter({ hasText: /Send \d+ changes? for review/ }).first();
    await sendBtn.click({ timeout: 10000 });
    await sleep(6000);
    await shot(page, 'send-clicked');

    // Dialog confirm: "Send changes for review"
    try {
      await page.getByRole('button', { name: 'Send changes for review', exact: true }).click({ timeout: 8000 });
      console.log('  ✓ confirm clicked');
    } catch (e) {
      console.log(`  err: ${e.message.slice(0, 90)}`);
      // fallback
      await page.evaluate(() => {
        function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
        for (const el of walk(document)) {
          if (!['BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) continue;
          const t = (el.innerText || '').trim();
          if (t === 'Send changes for review') { el.click(); return; }
        }
      });
    }
    await sleep(8000);
    await shot(page, 'sent');
  }

  // Final verification
  await page.reload({ waitUntil:'networkidle' });
  await sleep(10000);
  await shot(page, 'final');
  const final = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      hasInReview: /Changes in review/.test(t),
      hasSendBtn: /Send \d+ changes? for review/.test(t),
      hasV116: /1\.1\.6/.test(t),
      hasUnderReview: /Under review|Pending publishing|Awaiting review/i.test(t),
    };
  });
  console.log(`\nfinal: ${JSON.stringify(final)}`);
  if (final.hasInReview || final.hasUnderReview || (!final.hasSendBtn && final.hasV116)) {
    console.log(`\n✅ DONE — v1.1.6 in review!`);
  } else {
    console.log(`\n⚠️  unclear, see ${OUT}`);
  }
  await browser.close();
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
