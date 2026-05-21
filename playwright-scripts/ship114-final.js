/**
 * ship114-final.js — v1.1.4 IS in the bundle library. v1.1.3 is currently
 * attached to the draft. Remove v1.1.3, add v1.1.4 from library, fill, save,
 * review, rollout.
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
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `ship114final-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
let stepN = 0;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function shot(page, label) {
  stepN++;
  const lbl = `${String(stepN).padStart(2, '0')}-${label}`;
  try { await page.screenshot({ path: path.join(OUT, `${lbl}.png`), fullPage: true }); } catch {}
  console.log(`📸 ${lbl}`);
}

/** Click by text — exact=false by default (covers material-icon prefixes). */
async function click(page, text, opts = {}) {
  const exact = !!opts.exact;
  const r = await page.evaluate(({ text, exact }) => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const cands = [];
    for (const el of walk(document)) {
      const txt = (el.innerText || '').trim();
      const aria = el.getAttribute('aria-label') || '';
      const match = exact ? (txt === text || aria === text)
                          : (txt.includes(text) || aria.includes(text));
      if (!match) continue;
      if (!['BUTTON','A','MATERIAL-BUTTON'].includes(el.tagName) && el.getAttribute('role') !== 'button') continue;
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

  console.log('\n=== STEP 1: open draft ===');
  await page.goto(DRAFT_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(7000);
  await shot(page, 'draft-initial');

  // STEP 2: remove v1.1.3 from draft
  console.log('\n=== STEP 2: remove v1.1.3 from draft ===');
  // Click more_vert next to the v1.1.3 bundle row
  const menuClick = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    // Find the row containing "10 (1.1.3)" then its more_vert button
    for (const el of walk(document)) {
      const t = (el.innerText || '').trim();
      if (/10\s*\(1\.1\.3\)/.test(t) && t.length < 120) {
        let row = el;
        for (let i = 0; i < 8 && row; i++) {
          const btn = row.querySelector && row.querySelector('button[aria-label="More actions"], button[aria-label*="more"], material-icon[aria-label*="More"]');
          if (btn) { btn.click(); return { found: true, tag: btn.tagName }; }
          row = row.parentElement;
        }
      }
    }
    return null;
  });
  console.log(`  more_vert click: ${JSON.stringify(menuClick)}`);
  await sleep(2500);
  await shot(page, 'menu-open');

  // Now click "Remove" or "Delete" in the menu
  let removed = await click(page, 'Remove', { afterMs: 4000 });
  if (!removed) removed = await click(page, 'Delete', { afterMs: 4000 });
  await shot(page, 'after-remove');

  // STEP 3: Add from library
  console.log('\n=== STEP 3: Add from library ===');
  await click(page, 'Add from library', { afterMs: 5000 });
  await shot(page, 'lib-modal');

  // In the modal, pick row containing 1.1.4 (11)
  console.log('→ pick v1.1.4 row in modal');
  const picked = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    // Look for a row that contains BOTH "11" and "1.1.4"
    for (const el of walk(document)) {
      const t = (el.innerText || '').trim();
      if (t.length > 5 && t.length < 300 && /\b11\b/.test(t) && /1\.1\.4/.test(t)) {
        // Walk up to find a clickable row
        let row = el;
        for (let i = 0; i < 6 && row; i++) {
          // Click the row itself (often the entire row is clickable)
          const rb = row.querySelector && row.querySelector('input[type="radio"], input[type="checkbox"]');
          if (rb) { rb.scrollIntoView({block:'center'}); rb.click(); return { type: 'radio' }; }
          if (row.getAttribute && row.getAttribute('role') === 'row') {
            row.click(); return { type: 'row-click' };
          }
          row = row.parentElement;
        }
        // Last resort: click el itself
        el.click(); return { type: 'el-click', text: t.slice(0, 80) };
      }
    }
    return null;
  });
  console.log(`  pick result: ${JSON.stringify(picked)}`);
  await sleep(3000);
  await shot(page, 'row-picked');

  // Click "Add to release" or "Add" in modal
  for (const lbl of ['Add to release', 'Save changes', 'Add', 'OK']) {
    if (await click(page, lbl, { exact: true, afterMs: 5000 })) break;
  }
  await shot(page, 'bundle-added');

  // Verify "11 (1.1.4)" now shown in App bundles section
  console.log('→ verify bundle attached');
  let attached = false;
  for (let i = 0; i < 20; i++) {
    await sleep(3000);
    const ok = await page.evaluate(() => {
      const t = document.body.innerText || '';
      return /11\s*\(1\.1\.4\)/.test(t);
    });
    if (ok) { console.log(`  ✓ attached after ~${(i+1)*3}s`); attached = true; break; }
  }
  await shot(page, 'verify');

  if (!attached) {
    console.error('❌ v1.1.4 still not shown — dumping App bundles section');
    const dump = await page.evaluate(() => {
      const t = document.body.innerText || '';
      const m = t.match(/App bundles[\s\S]*?(?=Previous release|Release details|$)/);
      return m ? m[0].slice(0, 1500) : null;
    });
    console.log(dump);
    process.exit(2);
  }

  // STEP 4: fill name + notes (in case Play cleared them)
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

  // STEP 5: Next → Save → Review → Rollout
  console.log('\n=== STEP 5: progress to rollout ===');
  await click(page, 'Next', { exact: true, afterMs: 7000 });
  await shot(page, 'after-next');

  // Inspect errors before saving
  const preview = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      const aria = el.getAttribute && el.getAttribute('aria-label');
      if (el.tagName === 'BUTTON' && aria === 'Show more validation details') el.click();
    }
    return null;
  });
  await sleep(3000);
  const errs = await page.evaluate(() => {
    const t = document.body.innerText || '';
    const m = t.match(/Errors,\s*warnings[\s\S]*?(?=Changes to|Release delivery|Release notes|$)/);
    return m ? m[0].slice(0, 2500) : null;
  });
  if (errs) console.log(`\n  ERRORS/WARNINGS BLOCK:\n${errs}\n`);
  await shot(page, 'preview-errors');

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

  // STEP 6: send for review
  console.log('\n=== STEP 6: send for review ===');
  await page.goto(PUB_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(8000);
  await shot(page, 'publishing');
  const bt = await page.evaluate(() => document.body.innerText || '');
  const has114 = /1\.1\.4|\(11\)/.test(bt);
  const has103 = /1\.0\.3|\(4\)/.test(bt);
  console.log(`  has v1.1.4? ${has114}  has v1.0.3? ${has103}`);
  if (!has114) { console.error('❌ v1.1.4 NOT in queue'); process.exit(6); }

  let sent = false;
  for (const lbl of ['Send 1 change for review', 'Send 2 changes for review', 'Send 3 changes for review', 'Send for review']) {
    sent = await click(page, lbl, { afterMs: 5000 });
    if (sent) break;
  }
  await shot(page, 'send-clicked');

  for (const lbl of ['Send for review', 'Send', 'Confirm']) {
    if (await click(page, lbl, { exact: true, afterMs: 5000 })) break;
  }
  await shot(page, 'send-confirmed');

  console.log(`\n✅ DONE — v1.1.4 submitted. Output: ${OUT}`);
  process.exit(0);
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
