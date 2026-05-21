/**
 * proceed-and-save.js — current state: draft has only v1.1.5; we're on
 * /review page; need to click "Proceed anyway" on the 16 KB error,
 * Save (now enabled), then Start rollout → Confirm.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const DEVELOPER_ID = '6492542126543167241';
const APP_ID = '4974212910605033903';
const PUB_URL  = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/publishing`;
const REVIEW_URL = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/4697325931803269676/releases/2/review`;

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `proceed-${TS}`);
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
  if (r) console.log(`✓ click "${text}" → ${r.tag}`);
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
      try { const ok = await p.evaluate(() => /Publishing|Production/i.test(document.body.innerText || '')); if (ok) { page = p; break; } } catch {}
    }
  }
  if (!page) { console.error('❌ no tab'); process.exit(1); }
  await page.bringToFront();

  console.log('\n=== STEP 1: go to /review ===');
  await page.goto(REVIEW_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(8000);
  await shot(page, 'review-initial');

  // STEP 2: expand errors first
  console.log('\n=== STEP 2: expand errors ===');
  await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      const aria = el.getAttribute && el.getAttribute('aria-label');
      if (el.tagName === 'BUTTON' && aria === 'Show more validation details') el.click();
    }
  });
  await sleep(3000);
  await shot(page, 'errors-expanded');

  // STEP 3: click ALL "Proceed anyway" buttons
  console.log('\n=== STEP 3: click Proceed anyway ===');
  let n = 0;
  for (let i = 0; i < 10; i++) {
    const clicked = await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      for (const el of walk(document)) {
        if (!['BUTTON','A','MATERIAL-BUTTON'].includes(el.tagName) && el.getAttribute('role') !== 'button') continue;
        const t = (el.innerText || '').trim();
        if (t !== 'Proceed anyway') continue;
        const r = el.getBoundingClientRect();
        if (r.width === 0) continue;
        el.scrollIntoView({ block: 'center' });
        el.click();
        return true;
      }
      return false;
    });
    if (!clicked) break;
    n++;
    console.log(`  proceed #${n}`);
    await sleep(2500);
    // Also click any confirmation "Proceed" or "OK" dialog
    await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      for (const el of walk(document)) {
        if (!['BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) continue;
        const t = (el.innerText || '').trim();
        if (t === 'Proceed' || t === 'OK' || t === 'Confirm') {
          const r = el.getBoundingClientRect();
          if (r.width > 0 && r.width < 200) { el.click(); return; }  // dialog button
        }
      }
    });
    await sleep(2000);
  }
  console.log(`  total proceed clicks: ${n}`);
  await shot(page, 'after-proceed');

  // STEP 4: Save (should now be enabled)
  console.log('\n=== STEP 4: Save ===');
  await click(page, 'Save', { exact: true, afterMs: 8000 });
  await shot(page, 'after-save');
  console.log(`  url: ${page.url()}`);

  // STEP 5: Start rollout
  console.log('\n=== STEP 5: Start rollout ===');
  let rolled = await click(page, 'Start rollout to Production', { exact: true, afterMs: 6000 });
  if (!rolled) rolled = await click(page, 'Start rollout', { exact: true, afterMs: 5000 });
  if (!rolled) {
    // Maybe the Save itself was the rollout. Check publishing.
    console.log('  no rollout button — Save may have done it directly');
  }
  await shot(page, 'after-rollout');

  for (const lbl of ['Rollout', 'Confirm', 'OK']) {
    if (await click(page, lbl, { exact: true, afterMs: 5000 })) break;
  }
  await shot(page, 'after-confirm');

  // STEP 6: Send for review
  console.log('\n=== STEP 6: Send for review ===');
  await page.goto(PUB_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(8000);
  await shot(page, 'publishing');

  const bt = await page.evaluate(() => document.body.innerText || '');
  const has115 = /1\.1\.5|\(12\)/.test(bt);
  const has103 = /1\.0\.3|\(4\)/.test(bt);
  console.log(`  has v1.1.5? ${has115}  has v1.0.3? ${has103}`);

  if (!has115) {
    console.error('❌ v1.1.5 NOT in publishing queue. Aborting send.');
    console.log('  Dumping publishing changes section:');
    const dump = await page.evaluate(() => {
      const t = document.body.innerText || '';
      const m = t.match(/Changes not yet sent for review[\s\S]*?(?=Product updates|$)/);
      return m ? m[0].slice(0, 1500) : null;
    });
    console.log(dump);
    process.exit(6);
  }

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
