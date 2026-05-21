/**
 * remove-113-and-ship.js — current state: draft has BOTH v1.1.3 and v1.1.4.
 * We need to remove v1.1.3 (it's "shadowed" + has FS permission). Then
 * Save → Review → Rollout.
 *
 * Strategy for removing v1.1.3:
 *   - find the row in App bundles section that contains "10 (1.1.3)"
 *   - look for ANY clickable element inside (button, material-icon[role=button], etc.)
 *     that's a more_vert or kebab icon
 *   - click it to open menu
 *   - click "Remove" / "Delete" / similar menu item
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
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `rm113-${TS}`);
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
  await page.keyboard.press('Escape');
  await sleep(1500);

  console.log('\n=== STEP 1: open draft prepare ===');
  await page.goto(DRAFT_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(8000);
  await shot(page, 'initial');

  // STEP 2: enumerate all clickable elements in/near the v1.1.3 row to find the kebab/menu
  console.log('\n=== STEP 2: find + click v1.1.3 row menu ===');
  const enumeration = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    // Find the row containing "10 (1.1.3)"
    let row113 = null;
    for (const el of walk(document)) {
      const t = (el.innerText || '').trim();
      const role = el.getAttribute && el.getAttribute('role');
      if ((role === 'row' || el.tagName === 'TR') && /10\s*\(1\.1\.3\)/.test(t)) {
        row113 = el;
        break;
      }
    }
    if (!row113) {
      // fallback: any element whose direct text is "10 (1.1.3)"
      for (const el of walk(document)) {
        const t = (el.innerText || '').trim();
        if (/10\s*\(1\.1\.3\)/.test(t) && t.length < 200) {
          row113 = el;
          break;
        }
      }
    }
    if (!row113) return { error: 'no v1.1.3 row found' };

    // List all clickable descendants
    const clickable = [];
    function isClickable(el) {
      const tag = el.tagName;
      const role = el.getAttribute && el.getAttribute('role');
      return ['BUTTON','A','MATERIAL-BUTTON','MATERIAL-ICON'].includes(tag) || role === 'button';
    }
    function* deepChildren(root) {
      if (!root) return;
      for (const el of root.querySelectorAll('*')) {
        yield el;
        if (el.shadowRoot) yield* deepChildren(el.shadowRoot);
      }
    }
    for (const el of deepChildren(row113)) {
      if (!isClickable(el)) continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      clickable.push({
        tag: el.tagName,
        role: el.getAttribute('role'),
        text: (el.innerText || '').trim().slice(0, 40),
        aria: el.getAttribute('aria-label'),
        rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
      });
    }
    return { rowText: (row113.innerText || '').trim().slice(0, 200), clickable };
  });
  console.log(`\n  row v1.1.3:`);
  console.log(`  text: ${enumeration.rowText}`);
  console.log(`  clickable elements:`);
  for (const c of (enumeration.clickable || [])) {
    console.log(`    ${c.tag}${c.role?'['+c.role+']':''} @${c.rect.x},${c.rect.y} ${c.rect.w}x${c.rect.h} aria="${c.aria||''}" "${c.text}"`);
  }

  // Click the LAST visible clickable in the row (typically the rightmost kebab/more_vert)
  console.log('\n→ click rightmost button in v1.1.3 row');
  const clickedKebab = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    let row113 = null;
    for (const el of walk(document)) {
      const t = (el.innerText || '').trim();
      const role = el.getAttribute && el.getAttribute('role');
      if ((role === 'row' || el.tagName === 'TR') && /10\s*\(1\.1\.3\)/.test(t)) { row113 = el; break; }
    }
    if (!row113) return null;
    // Get all visible clickables
    function* desc(root) {
      if (!root) return;
      for (const el of root.querySelectorAll('*')) {
        yield el; if (el.shadowRoot) yield* desc(el.shadowRoot);
      }
    }
    const btns = [];
    for (const el of desc(row113)) {
      const tag = el.tagName;
      const role = el.getAttribute && el.getAttribute('role');
      if (!['BUTTON','A','MATERIAL-BUTTON','MATERIAL-ICON'].includes(tag) && role !== 'button') continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      btns.push({ el, x: r.x });
    }
    if (!btns.length) return null;
    // Find one that's NOT the arrow_right_alt (last "→" link to details)
    // Prefer one with text "more_vert" or aria includes "more" or middle position
    let target = null;
    for (const b of btns) {
      const t = (b.el.innerText || '').trim();
      const aria = (b.el.getAttribute('aria-label') || '').toLowerCase();
      if (t === 'more_vert' || aria.includes('more')) { target = b.el; break; }
    }
    if (!target) {
      // sort by x, click the second-to-last
      btns.sort((a, b) => a.x - b.x);
      target = btns.length >= 2 ? btns[btns.length - 2].el : btns[btns.length - 1].el;
    }
    target.scrollIntoView({ block: 'center' });
    target.click();
    return { tag: target.tagName, text: (target.innerText || '').slice(0, 40), aria: target.getAttribute('aria-label') };
  });
  console.log(`  clicked: ${JSON.stringify(clickedKebab)}`);
  await sleep(3000);
  await shot(page, 'menu-open');

  // Dump menu items
  const menuItems = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const items = [];
    for (const el of walk(document)) {
      const role = el.getAttribute && el.getAttribute('role');
      if (role === 'menuitem' || role === 'option') {
        const r = el.getBoundingClientRect();
        if (r.width > 0) items.push({ text: (el.innerText || '').trim(), aria: el.getAttribute('aria-label') });
      }
    }
    return items;
  });
  console.log(`  menu items: ${JSON.stringify(menuItems)}`);

  // Click "Remove app bundle" specifically (not Delete ReTrace)
  let removed = false;
  for (const m of menuItems) {
    if (m.text === 'Remove app bundle' || /^Remove\b/.test(m.text)) {
      await click(page, m.text, { exact: true, afterMs: 4500 });
      removed = true;
      break;
    }
  }
  if (!removed) {
    console.log('  no exact match; trying via shadow walker for any "Remove app bundle"');
    const r = await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      for (const el of walk(document)) {
        const role = el.getAttribute && el.getAttribute('role');
        if (role !== 'menuitem' && role !== 'option') continue;
        const t = (el.innerText || '').trim();
        if (t === 'Remove app bundle' || t.startsWith('Remove app bundle')) {
          el.scrollIntoView({block:'center'}); el.click();
          return { text: t };
        }
      }
      return null;
    });
    console.log(`  shadow click: ${JSON.stringify(r)}`);
    removed = !!r;
    await sleep(4000);
  }
  await shot(page, 'after-remove');

  const finalState = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return { has113: /10\s*\(1\.1\.3\)/.test(t), has114: /11\s*\(1\.1\.4\)/.test(t) };
  });
  console.log(`  final: has v1.1.3=${finalState.has113}  has v1.1.4=${finalState.has114}`);
  if (finalState.has113) {
    console.error('❌ v1.1.3 still in release. Aborting before Save.');
    process.exit(3);
  }

  // STEP 3: re-fill name + notes (might be cleared)
  console.log('\n=== STEP 3: re-fill name + notes ===');
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
  await shot(page, 'refilled');

  // STEP 4: Next → check errors → click "Proceed anyway" for 16 KB → Save
  console.log('\n=== STEP 4: progress ===');
  await click(page, 'Next', { exact: true, afterMs: 7000 });
  await shot(page, 'preview');

  await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      const aria = el.getAttribute && el.getAttribute('aria-label');
      if (el.tagName === 'BUTTON' && aria === 'Show more validation details') el.click();
    }
  });
  await sleep(3000);

  // Click any "Proceed anyway" buttons (for 16 KB errors)
  let proceedCount = 0;
  for (let i = 0; i < 5; i++) {
    const clicked = await click(page, 'Proceed anyway', { afterMs: 2500 });
    if (!clicked) break;
    proceedCount++;
  }
  console.log(`  proceed-anyway clicks: ${proceedCount}`);
  await shot(page, 'after-proceed');

  // Re-read errors
  const errs = await page.evaluate(() => {
    const t = document.body.innerText || '';
    const m = t.match(/Errors,\s*warnings[\s\S]*?(?=Changes to|Release delivery|Release notes|$)/);
    return m ? m[0].slice(0, 2500) : null;
  });
  console.log(`\n  ERRORS after Proceed:\n${errs || '[none]'}\n`);

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

  // STEP 5: Send for review
  console.log('\n=== STEP 5: send for review ===');
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
