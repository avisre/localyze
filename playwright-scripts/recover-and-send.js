/**
 * recover-and-send.js — current state:
 *   - Release 3 has v1.1.6 attached as DRAFT (name "1.1.6", notes filled)
 *   - Publishing has v1.0.3 + Countries IN REVIEW (sent by mistake)
 *   - Need to: pull v1.0.3 out of review, save release 3, send v1.1.6
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const DEVELOPER_ID = '6492542126543167241';
const APP_ID = '4974212910605033903';
const PUB_URL = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/publishing`;
const REL3_PREPARE = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/4697325931803269676/releases/3/prepare`;

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `recover-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
let stepN = 0;
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

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

async function clickDialog(page, label) {
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

  // STEP 1: Pull v1.0.3 out of review
  console.log('\n=== STEP 1: pull v1.0.3 out of review ===');
  await page.goto(PUB_URL, { waitUntil:'networkidle' });
  await sleep(8000);
  await shot(page, 'pub-initial');

  const inReview = await page.evaluate(() => /Changes in review/.test(document.body.innerText || ''));
  console.log(`  in-review: ${inReview}`);

  if (inReview) {
    await clickLargest(page, 'Remove changes', { exact: true, afterMs: 4000 });
    await shot(page, 'rm-dialog');
    const ok = await clickDialog(page, 'Remove changes');
    console.log(`  dialog confirm: ${ok}`);
    await sleep(8000);
    await shot(page, 'after-rm');
  }

  // Now there should be a "Changes not yet sent for review" section with v1.0.3 listed
  // Discard the v1.0.3 production change via more_vert
  console.log('\n=== STEP 1b: discard v1.0.3 from queue ===');
  await page.reload({ waitUntil:'networkidle' });
  await sleep(8000);
  await shot(page, 'after-reload');

  // Find more_vert next to the "Production / 4 (1.0.3)" row
  const mvOpened = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    // Find row containing "Production" and "4 (1.0.3)"
    let target = null;
    for (const el of walk(document)) {
      const t = (el.innerText || '').trim();
      if (/Production[\s\S]{0,200}4 \(1\.0\.3\)/.test(t) && t.length < 600) {
        target = el; break;
      }
    }
    if (!target) return null;
    function* desc(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* desc(el.shadowRoot); } }
    for (const el of desc(target)) {
      if (el.tagName !== 'BUTTON') continue;
      const aria = el.getAttribute('aria-label') || '';
      if (aria === 'More actions') {
        const r = el.getBoundingClientRect();
        if (r.width === 0) continue;
        el.click();
        return { found: true };
      }
    }
    return null;
  });
  console.log(`  more_vert: ${JSON.stringify(mvOpened)}`);
  await sleep(2500);
  await shot(page, 'mv-menu');

  // Look for Discard menu item
  const menuItems = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const items = [];
    for (const el of walk(document)) {
      const role = el.getAttribute && el.getAttribute('role');
      if (role !== 'menuitem' && role !== 'option') continue;
      const r = el.getBoundingClientRect();
      if (r.width > 0) items.push((el.innerText || '').trim());
    }
    return items;
  });
  console.log(`  menu: ${JSON.stringify(menuItems)}`);

  for (const m of menuItems) {
    if (/discard|delete|remove/i.test(m)) {
      console.log(`→ click menu: ${m}`);
      await clickLargest(page, m, { exact: true, afterMs: 3000 });
      // Dialog confirm
      const c = await clickDialog(page, 'Discard');
      if (!c) await clickDialog(page, 'Remove');
      await sleep(5000);
      break;
    }
  }
  await shot(page, 'after-mv-action');

  // STEP 2: open release 3, click Next → Save
  console.log('\n=== STEP 2: open release 3, Save ===');
  await page.goto(REL3_PREPARE, { waitUntil:'networkidle' });
  await sleep(8000);
  await shot(page, 'rel3-prepare');

  // Verify v1.1.6 attached
  const has116 = await page.evaluate(() => /13\s*\(1\.1\.6\)/.test(document.body.innerText || ''));
  console.log(`  v1.1.6 attached: ${has116}`);

  await clickLargest(page, 'Next', { exact: true, afterMs: 7000 });
  await shot(page, 'after-next');

  // Proceed anyway if any
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
  console.log(`url: ${page.url()}`);

  // STEP 3: publishing → Send v1.1.6 for review
  console.log('\n=== STEP 3: Send v1.1.6 for review ===');
  await page.goto(PUB_URL, { waitUntil:'networkidle' });
  await sleep(10000);
  await shot(page, 'pub-final');

  const state = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      blocked: /1 issue affects/.test(t),
      hasSendBtn: /Send \d+ changes? for review/.test(t),
      hasV116: /1\.1\.6/.test(t),
      hasV103: /1\.0\.3/.test(t),
    };
  });
  console.log(`state: ${JSON.stringify(state)}`);

  if (!state.hasV116) {
    console.error('❌ v1.1.6 still not in queue. Aborting.');
    process.exit(2);
  }

  if (state.hasSendBtn && !state.blocked) {
    const sendBtn = page.locator('button').filter({ hasText: /Send \d+ changes? for review/ }).first();
    await sendBtn.click({ timeout: 10000 });
    await sleep(6000);
    try {
      await page.getByRole('button', { name: 'Send changes for review', exact: true }).click({ timeout: 8000 });
      console.log('✓ Send changes for review confirmed');
    } catch (e) {
      console.log(`fallback click: ${e.message.slice(0, 80)}`);
      await page.evaluate(() => {
        function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
        for (const el of walk(document)) {
          if (!['BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) continue;
          if ((el.innerText || '').trim() === 'Send changes for review') { el.click(); return; }
        }
      });
    }
    await sleep(10000);
    await shot(page, 'sent');
  }

  // Final verify
  await page.reload({ waitUntil:'networkidle' });
  await sleep(10000);
  await shot(page, 'final');
  const final = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      hasInReview: /Changes in review/.test(t),
      hasSendBtn: /Send \d+ changes? for review/.test(t),
      hasV116: /1\.1\.6/.test(t),
      hasV103: /1\.0\.3/.test(t),
    };
  });
  console.log(`final: ${JSON.stringify(final)}`);
  if (final.hasInReview && final.hasV116 && !final.hasV103) {
    console.log(`\n✅✅ DONE — v1.1.6 in review, v1.0.3 removed!`);
  } else if (final.hasInReview && final.hasV116) {
    console.log(`\n⚠️  v1.1.6 in review BUT v1.0.3 also present`);
  } else {
    console.log(`\n⚠️  state unclear`);
  }
  await browser.close();
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
