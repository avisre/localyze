/**
 * ship114-v7.js — fix the checkbox-clicking issue. In the
 * "Add app bundle from library" modal, the checkboxes are Material elements
 * that don't expose as input[type=checkbox]. Need to use [role=checkbox],
 * mat-checkbox, or click the leftmost cell of the row.
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
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `ship114v7-${TS}`);
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
  if (r) console.log(`✓ click "${text}" → ${r.tag} area=${Math.round(r.area)} "${r.text}"`);
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

  // Close any leftover modal
  await page.keyboard.press('Escape');
  await sleep(800);
  await page.keyboard.press('Escape');
  await sleep(1500);

  console.log('\n=== STEP 1: open draft ===');
  await page.goto(DRAFT_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(8000);
  await shot(page, 'draft');

  // STEP 2: Open Add from library modal
  console.log('\n=== STEP 2: Add from library ===');
  await click(page, 'Add from library', { afterMs: 5000 });
  await shot(page, 'lib-modal');

  // STEP 3: check the v1.1.4 checkbox using ROBUST selectors
  console.log('\n=== STEP 3: check v1.1.4 row ===');
  const picked = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    // Strategy: find row containing "1.1.4", then find the checkbox-like element
    let targetRow = null;
    for (const el of walk(document)) {
      const role = el.getAttribute && el.getAttribute('role');
      if (role !== 'row' && el.tagName !== 'TR') continue;
      const t = (el.innerText || '').trim();
      if (!/\b11\b/.test(t) || !/1\.1\.4/.test(t)) continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      targetRow = el;
      break;
    }
    if (!targetRow) return { error: 'no row found' };

    // Look for checkbox-like elements inside the row
    const checkSelectors = [
      'input[type="checkbox"]',
      '[role="checkbox"]',
      'mat-checkbox',
      'material-checkbox',
      'mwc-checkbox',
      'div.checkbox',
    ];
    for (const sel of checkSelectors) {
      const c = targetRow.querySelector(sel);
      if (c) {
        const cr = c.getBoundingClientRect();
        c.scrollIntoView({ block: 'center' });
        c.click();
        return { type: sel, rect: { x: Math.round(cr.x), y: Math.round(cr.y), w: Math.round(cr.width), h: Math.round(cr.height) } };
      }
    }
    // Also try the first <td> cell (typically holds checkbox)
    const firstCell = targetRow.querySelector('td:first-child, [role="cell"]:first-child');
    if (firstCell) {
      firstCell.click();
      return { type: 'first-cell', tag: firstCell.tagName };
    }
    // Last resort: click on the leftmost edge of the row
    const rr = targetRow.getBoundingClientRect();
    targetRow.scrollIntoView({ block: 'center' });
    // dispatch a mouse click event at the left edge
    const evt = new MouseEvent('click', {
      bubbles: true, cancelable: true,
      clientX: rr.x + 20, clientY: rr.y + rr.height / 2,
    });
    targetRow.dispatchEvent(evt);
    return { type: 'left-edge-click', rect: { x: Math.round(rr.x), y: Math.round(rr.y), w: Math.round(rr.width) } };
  });
  console.log(`  picked: ${JSON.stringify(picked)}`);
  await sleep(3000);
  await shot(page, 'after-check');

  // Verify the row is now checked (look for aria-checked=true or checked attribute)
  const checkedState = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      const role = el.getAttribute && el.getAttribute('role');
      if (role !== 'row' && el.tagName !== 'TR') continue;
      const t = (el.innerText || '').trim();
      if (!/1\.1\.4/.test(t)) continue;
      // look for any aria-checked descendant
      const checked = el.querySelectorAll('[aria-checked="true"], [aria-selected="true"], input:checked');
      return { hasChecked: checked.length, ariaSelected: el.getAttribute('aria-selected') };
    }
    return null;
  });
  console.log(`  checked state: ${JSON.stringify(checkedState)}`);

  // STEP 4: click Add to release (now should be enabled)
  await click(page, 'Add to release', { exact: true, afterMs: 6000 });
  await shot(page, 'after-add');

  // Verify v1.1.4 attached
  let attached = false;
  for (let i = 0; i < 15; i++) {
    await sleep(3000);
    const ok = await page.evaluate(() => /11\s*\(1\.1\.4\)/.test(document.body.innerText || ''));
    if (ok) { console.log(`  ✓ attached after ~${(i+1)*3}s`); attached = true; break; }
  }
  if (!attached) {
    console.error('❌ v1.1.4 still not attached');
    const dump = await page.evaluate(() => {
      const t = document.body.innerText || '';
      const m = t.match(/App bundles[\s\S]*?(?=Previous release|Release details|$)/);
      return m ? m[0].slice(0, 1500) : null;
    });
    console.log(dump);
    process.exit(2);
  }

  // STEP 5: remove v1.1.3 if still attached (we want only v1.1.4 in the release)
  const stillHas113 = await page.evaluate(() => /10\s*\(1\.1\.3\)/.test(document.body.innerText || ''));
  console.log(`  still has v1.1.3? ${stillHas113}`);
  if (stillHas113) {
    console.log('→ remove v1.1.3 via more_vert');
    // Find more_vert in v1.1.3 row
    const mvOpened = await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      // find all visible buttons with material icon "more_vert"
      for (const el of walk(document)) {
        if (el.tagName !== 'BUTTON' && el.tagName !== 'MATERIAL-BUTTON') continue;
        const txt = (el.innerText || '').trim();
        if (txt !== 'more_vert') continue;
        const r = el.getBoundingClientRect();
        if (r.width === 0) continue;
        // walk up to find the row
        let p = el;
        for (let i = 0; i < 10 && p; i++) {
          const pt = (p.innerText || '').trim();
          if (/10\s*\(1\.1\.3\)/.test(pt)) {
            el.scrollIntoView({block:'center'}); el.click();
            return { found: true };
          }
          p = p.parentElement;
        }
      }
      return null;
    });
    console.log(`  mv: ${JSON.stringify(mvOpened)}`);
    await sleep(3000);
    await shot(page, 'mv-open');

    const menuItems = await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      const items = [];
      for (const el of walk(document)) {
        const role = el.getAttribute && el.getAttribute('role');
        if (role === 'menuitem' || role === 'option') {
          const r = el.getBoundingClientRect();
          if (r.width > 0) items.push((el.innerText || '').trim());
        }
      }
      return items;
    });
    console.log(`  menu: ${JSON.stringify(menuItems)}`);
    for (const m of menuItems) {
      if (/remove|delete/i.test(m)) {
        await click(page, m, { exact: true, afterMs: 4000 });
        break;
      }
    }
    await shot(page, 'after-rm-113');
  }

  // STEP 6: name + notes
  console.log('\n=== STEP 6: name + notes ===');
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
          if (r.width > 0) { target = el; break; }
        }
      }
    }
    if (!target) return;
    target.focus();
    target.value = '';
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

  // STEP 7: Next, check errors, Save, Review, Rollout
  console.log('\n=== STEP 7: progress ===');
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
  const errs = await page.evaluate(() => {
    const t = document.body.innerText || '';
    const m = t.match(/Errors,\s*warnings[\s\S]*?(?=Changes to|Release delivery|Release notes|$)/);
    return m ? m[0].slice(0, 2500) : null;
  });
  console.log(`\n  ERRORS section:\n${errs || '[none]'}\n`);
  await shot(page, 'preview-errs');

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

  console.log('\n=== STEP 8: send for review ===');
  await page.goto(PUB_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(8000);
  await shot(page, 'publishing');

  const bt = await page.evaluate(() => document.body.innerText || '');
  const has114 = /1\.1\.4|\(11\)/.test(bt);
  const has103 = /1\.0\.3|\(4\)/.test(bt);
  console.log(`  has v1.1.4? ${has114}  has v1.0.3? ${has103}`);
  if (!has114) { console.error('❌ v1.1.4 NOT in queue'); process.exit(6); }

  for (const lbl of ['Send 1 change for review', 'Send 2 changes for review', 'Send 3 changes for review', 'Send for review']) {
    if (await click(page, lbl, { afterMs: 5000 })) break;
  }
  await shot(page, 'send-clicked');

  for (const lbl of ['Send for review', 'Send', 'Confirm']) {
    if (await click(page, lbl, { exact: true, afterMs: 5000 })) break;
  }
  await shot(page, 'sent');

  console.log(`\n✅ DONE — v1.1.4 submitted. Output: ${OUT}`);
  process.exit(0);
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
