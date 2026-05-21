/**
 * ship114-final2.js — fixed: prefer VISIBLE candidates; click correct
 * Add-from-library button for app bundles (not expansion files).
 *
 * State assumed: v1.1.3 may or may not still be attached. v1.1.4 (11) is in
 * the bundle library.
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
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `ship114final2-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
let stepN = 0;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function shot(page, label) {
  stepN++;
  const lbl = `${String(stepN).padStart(2, '0')}-${label}`;
  try { await page.screenshot({ path: path.join(OUT, `${lbl}.png`), fullPage: true }); } catch {}
  console.log(`📸 ${lbl}`);
}

/** Click VISIBLE element. */
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
      if (r.width === 0 || r.height === 0) continue;  // ⬅️ filter invisible
      cands.push({ el, area: r.width * r.height });
    }
    if (!cands.length) return null;
    // Prefer the LARGEST visible (the main button, not a sub-icon)
    cands.sort((a, b) => b.area - a.area);
    const el = cands[0].el;
    el.scrollIntoView({ block: 'center' });
    el.click();
    return { tag: el.tagName, text: (el.innerText || '').slice(0, 60), area: cands[0].area };
  }, { text, exact });
  if (r) console.log(`✓ click "${text}" → ${r.tag} area=${r.area} "${r.text}"`);
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

  // Cancel any open dialog from previous run
  await page.keyboard.press('Escape');
  await sleep(1500);

  console.log('\n=== STEP 1: open draft ===');
  await page.goto(DRAFT_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(8000);
  await shot(page, 'draft');

  // Check current bundle state
  const initialState = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      has113: /10\s*\(1\.1\.3\)/.test(t),
      has114: /11\s*\(1\.1\.4\)/.test(t),
    };
  });
  console.log(`  initial: has v1.1.3? ${initialState.has113}  has v1.1.4? ${initialState.has114}`);

  // STEP 2: remove v1.1.3 if present (via more_vert menu, not the X icon)
  if (initialState.has113) {
    console.log('\n=== STEP 2: remove v1.1.3 ===');
    const mvClicked = await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      // Find ALL more_vert buttons
      const allMv = [];
      for (const el of walk(document)) {
        if (el.tagName === 'BUTTON' && el.getAttribute('aria-label') === 'More actions') {
          const r = el.getBoundingClientRect();
          if (r.width > 0 && r.height > 0) allMv.push(el);
        }
      }
      // Find the one in the row with "10 (1.1.3)"
      for (const mv of allMv) {
        let p = mv;
        for (let i = 0; i < 8 && p; i++) {
          const t = (p.innerText || '').trim();
          if (/10\s*\(1\.1\.3\)/.test(t)) {
            mv.scrollIntoView({ block: 'center' });
            mv.click();
            return { found: true };
          }
          p = p.parentElement;
        }
      }
      return null;
    });
    console.log(`  more_vert: ${JSON.stringify(mvClicked)}`);
    await sleep(2500);
    await shot(page, 'menu-open');

    // Click "Remove from release" or "Remove" or "Delete"
    let removed = false;
    for (const lbl of ['Remove from release', 'Remove', 'Delete']) {
      removed = await click(page, lbl, { exact: true, afterMs: 4000 });
      if (removed) break;
    }
    await shot(page, 'after-remove');
  } else {
    console.log('  v1.1.3 already not present, skipping remove');
  }

  // STEP 3: Add from library (VISIBLE button, in App bundles section)
  console.log('\n=== STEP 3: Add from library ===');
  await click(page, 'Add from library', { afterMs: 5000 });
  await shot(page, 'lib-modal');

  // Confirm we're in the App bundle library (not expansion file)
  const modalTitle = await page.evaluate(() => {
    const t = document.body.innerText || '';
    const m = t.match(/Add (?:expansion file|app bundle|bundle).*?from library/i);
    return m ? m[0] : null;
  });
  console.log(`  modal title: ${modalTitle}`);

  // Pick row with "11 (1.1.4)"
  console.log('→ pick v1.1.4 row');
  const picked = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    // Find rows containing "1.1.4" AND visible
    for (const el of walk(document)) {
      const role = el.getAttribute && el.getAttribute('role');
      const tag = el.tagName;
      if (role !== 'row' && tag !== 'TR') continue;
      const t = (el.innerText || '').trim();
      if (!/1\.1\.4/.test(t)) continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      // try radio first
      const rb = el.querySelector('input[type="radio"], input[type="checkbox"]');
      if (rb) { rb.scrollIntoView({block:'center'}); rb.click(); return { type:'radio', text:t.slice(0,100) }; }
      el.click();
      return { type:'row', text:t.slice(0,100) };
    }
    // Fallback: any element with text containing both 1.1.4 and 11
    for (const el of walk(document)) {
      const t = (el.innerText || '').trim();
      if (t.length > 4 && t.length < 200 && /\b11\b/.test(t) && /1\.1\.4/.test(t)) {
        const r = el.getBoundingClientRect();
        if (r.width > 0) {
          el.scrollIntoView({block:'center'});
          el.click();
          return { type:'fallback', text:t.slice(0,100) };
        }
      }
    }
    return null;
  });
  console.log(`  pick: ${JSON.stringify(picked)}`);
  await sleep(3000);
  await shot(page, 'row-picked');

  // Click Add to release / OK
  let added = false;
  for (const lbl of ['Add to release', 'Save changes', 'Add', 'OK']) {
    added = await click(page, lbl, { exact: true, afterMs: 5000 });
    if (added) break;
  }
  await shot(page, 'bundle-added');

  // Verify
  console.log('→ verify v1.1.4 attached');
  let attached = false;
  for (let i = 0; i < 15; i++) {
    await sleep(3000);
    const ok = await page.evaluate(() => /11\s*\(1\.1\.4\)/.test(document.body.innerText || ''));
    if (ok) { console.log(`  ✓ attached after ~${(i+1)*3}s`); attached = true; break; }
  }
  if (!attached) {
    console.error('❌ v1.1.4 not attached. Dumping state.');
    const dump = await page.evaluate(() => {
      const t = document.body.innerText || '';
      const m = t.match(/App bundles[\s\S]*?(?=Previous release|Release details|$)/);
      return m ? m[0].slice(0, 1200) : null;
    });
    console.log(dump);
    process.exit(2);
  }
  await shot(page, 'attached');

  // STEP 4: fill name + notes
  console.log('\n=== STEP 4: name + notes ===');
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
  await shot(page, 'name-notes');

  // STEP 5: Next
  console.log('\n=== STEP 5: Next → preview ===');
  await click(page, 'Next', { exact: true, afterMs: 7000 });
  await shot(page, 'after-next');

  // expand errors if any
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
    const m = t.match(/Errors,\s*warnings[\s\S]*?(?=Changes to|Release delivery|Release notes|$)/);
    return m ? m[0].slice(0, 2500) : null;
  });
  if (errs) console.log(`\n  ERRORS:\n${errs}\n`);
  await shot(page, 'preview');

  // Try to save
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

  // STEP 6: Publishing
  console.log('\n=== STEP 6: Send for review ===');
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
