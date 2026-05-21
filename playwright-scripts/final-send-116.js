/**
 * final-send-116.js — release 3 (v1.1.6) is saved at /review. The error
 * "advertising ID declaration says Yes but artifact doesn't have AD_ID"
 * has a "Release without permission" button to override. Click it,
 * then Save, then publishing → Send.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const DEVELOPER_ID = '6492542126543167241';
const APP_ID = '4974212910605033903';
const PUB_URL = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/publishing`;
const REL3_REVIEW = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/4697325931803269676/releases/3/review`;

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `final116-${TS}`);
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

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = ctx.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();
  await page.setViewportSize({ width: 1400, height: 1200 });

  // STEP 1: go to release 3 review
  console.log('\n=== STEP 1: open release 3 review ===');
  await page.goto(REL3_REVIEW, { waitUntil:'domcontentloaded', timeout: 90000 });
  await sleep(10000);
  await shot(page, 'review-initial');

  // Expand errors
  await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      const aria = el.getAttribute && el.getAttribute('aria-label');
      if (el.tagName === 'BUTTON' && aria === 'Show more validation details') el.click();
    }
  });
  await sleep(2500);

  // Click "Release without permission" to override AD_ID warning
  console.log('→ click "Release without permission"');
  await clickLargest(page, 'Release without permission', { afterMs: 4000 });
  await shot(page, 'after-release-without');

  // Also handle any "Proceed anyway" if present
  for (let i = 0; i < 5; i++) {
    if (!(await clickLargest(page, 'Proceed anyway', { afterMs: 2500 }))) break;
  }

  // Check errors again
  const errs = await page.evaluate(() => {
    const t = document.body.innerText || '';
    const m = t.match(/Errors,\s*warnings[\s\S]*?(?=Changes to|Release delivery|Release notes|$)/);
    return m ? m[0].slice(0, 1500) : null;
  });
  console.log(`\nERRORS after override:\n${errs || '[none]'}\n`);

  // Click Save (should be enabled now)
  console.log('→ click Save');
  const saveBtn = page.getByRole('button', { name: 'Save' }).first();
  const isDisabled = await saveBtn.isDisabled().catch(() => true);
  console.log(`  Save disabled? ${isDisabled}`);
  if (isDisabled) {
    console.error('❌ Save still disabled');
    process.exit(2);
  }
  await saveBtn.click({ timeout: 8000 });
  await sleep(8000);
  await shot(page, 'after-save');

  // Handle "Go to Publishing overview" dialog
  try {
    await page.getByRole('button', { name: 'Go to overview', exact: true }).click({ timeout: 5000 });
    console.log('  ✓ Go to overview clicked');
  } catch {
    try {
      await page.getByRole('button', { name: 'Not now', exact: true }).click({ timeout: 3000 });
      console.log('  ✓ Not now clicked');
    } catch {}
  }
  await sleep(5000);

  // STEP 2: publishing → Send
  console.log('\n=== STEP 2: publishing → Send ===');
  await page.goto(PUB_URL, { waitUntil:'domcontentloaded', timeout: 90000 });
  await sleep(10000);
  await shot(page, 'publishing');

  const state = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      blocked: /1 issue affects/.test(t),
      hasSendBtn: /Send \d+ changes? for review/.test(t),
      hasV116: /1\.1\.6/.test(t),
      hasV103: /1\.0\.3/.test(t),
      inReview: /Changes in review/.test(t),
    };
  });
  console.log(`state: ${JSON.stringify(state)}`);

  if (state.hasSendBtn && !state.blocked) {
    const sendBtn = page.locator('button').filter({ hasText: /Send \d+ changes? for review/ }).first();
    await sendBtn.click({ timeout: 10000 });
    await sleep(6000);
    try {
      await page.getByRole('button', { name: 'Send changes for review', exact: true }).click({ timeout: 8000 });
      console.log('✓ Send confirmed');
    } catch (e) {
      console.log(`fallback: ${e.message.slice(0, 80)}`);
    }
    await sleep(10000);
    await shot(page, 'sent');
  }

  // Final
  await page.reload({ waitUntil:'domcontentloaded', timeout: 90000 });
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

  if (final.hasInReview && final.hasV116) {
    console.log(`\n✅✅✅ DONE — v1.1.6 in review!`);
    if (final.hasV103) console.log(`  Note: v1.0.3 may still be co-pending`);
  } else {
    console.log(`\n⚠️  state unclear`);
  }
  await browser.close();
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
